package com.notesis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/** One rendered piece of a PDF page, with the page-local region it covers. */
class PdfTile(val source: RectF, val bitmap: Bitmap)

/** Text picked out of a PDF page, with its boxes in page-local world units. */
data class PdfSelection(
    val pageIndex: Int,
    val text: String,
    val boxes: List<RectF>,
)

/**
 * The imported PDF behind a note's pages: rendering, text, and text selection.
 *
 * PdfRenderer allows exactly one open page at a time and is not thread safe, so
 * every call into it is serialised onto one background thread. Rendering costs
 * tens of milliseconds, which is a visible stutter if it happens while
 * scrolling - so a miss returns nothing, the page draws blank, and [onReady]
 * fires once the bitmap has landed in the cache.
 */
class PdfSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    cacheBytes: Int,
) : AutoCloseable, MemoryTrimmable {

    val pageCount: Int = renderer.pageCount

    /**
     * Held for the whole of a PdfRenderer call, which takes tens of milliseconds.
     * Only ever taken off the UI thread - the draw path must not be able to
     * block behind a render in progress, which is what made scrolling a PDF
     * stutter.
     */
    private val renderLock = Any()

    /** Cheap bookkeeping only: never held across a render. */
    private val stateLock = Any()

    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    )
    private val pending = mutableMapOf<Long, Int>()

    @Volatile private var viewportGeneration = 0
    @Volatile private var wantedPages: Set<Int> = emptySet()

    @Volatile
    private var closed = false

    // Keep the cheap whole-page floor small and reserve most of the budget for
    // the visible detail tiles. A 2048px A4 bitmap alone is about 24 MiB: with
    // the old 50/50 split it did not fit in the 12 MiB page half on a modest
    // device and was rendered, immediately evicted, then rendered again. The
    // tile half could hold only three 1024px tiles, so a six-tile viewport also
    // evicted itself in a loop. Both loops appeared as text alternating between
    // soft and sharp. 1024px page floors plus 512px tiles keep a complete
    // viewport resident and make each quality promotion stable.
    private val pageCacheBytes = (cacheBytes / 3).coerceIn(MIN_PAGE_CACHE_BYTES, MAX_PAGE_CACHE_BYTES)
    private val tileCacheBytes = (cacheBytes - pageCacheBytes).coerceAtLeast(MIN_TILE_CACHE_BYTES)

    private val cache = object : LruCache<Long, Bitmap>(pageCacheBytes) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    private data class TileKey(
        val pageIndex: Int,
        val density: Float,
        val tileX: Int,
        val tileY: Int,
    )

    private val pendingTiles = mutableMapOf<TileKey, Int>()

    private val tileCache = object : LruCache<TileKey, Bitmap>(tileCacheBytes) {
        override fun sizeOf(key: TileKey, value: Bitmap) = value.allocationByteCount
    }

    init {
        RuntimeMemory.register(this)
    }

    /** Called on the worker thread once a requested render has landed. */
    var onReady: ((Int) -> Unit)? = null

    /**
     * Moves queued rendering priority to the pages around the current viewport.
     * Old FIFO work is allowed to leave the executor, but it exits before
     * opening PdfRenderer, so a fast scroll does not have to render every page
     * passed on the way to the one where the user stopped.
     */
    fun prioritizePages(pageIndices: List<Int>, widthPx: Int) {
        val ordered = pageIndices.filter { it in 0 until pageCount }.distinct()
        if (ordered.isEmpty()) return
        val wanted = ordered.toSet()
        synchronized(stateLock) {
            if (closed) return
            if (wanted != wantedPages) {
                wantedPages = wanted
                viewportGeneration++
                // Work that has not opened PdfRenderer yet is disposable. A
                // queue reset is what makes the stopped-at page genuinely jump
                // ahead instead of merely waiting for old no-op jobs to drain.
                worker.queue.clear()
                pending.clear()
                pendingTiles.clear()
            }
        }
        for (page in ordered) bitmap(page, widthPx, request = true)
    }

    /** Page size in world units, or null if the page is out of range. */
    fun pageSize(index: Int): Pair<Float, Float>? = synchronized(renderLock) {
        if (closed || index !in 0 until pageCount) return null
        runCatching {
            renderer.openPage(index).use { page ->
                (page.width * POINTS_TO_WORLD) to (page.height * POINTS_TO_WORLD)
            }
        }.getOrNull()
    }

    /**
     * The whole page, rendered at the smallest cached step that still covers
     * [wantPx]. Bucketing by powers of two is what stops every pinch frame from
     * kicking off a fresh render of the same page.
     */
    fun bitmap(index: Int, wantPx: Int, request: Boolean = true): Bitmap? {
        val width = bucketFor(wantPx)
        val key = keyOf(index, width)
        cache.get(key)?.let { return it }
        // A coarser render of the same page is a better thing to show than blank
        // paper while the sharper one is still being made.
        val fallback = coarserThan(index, width)
        if (request) request(key) { renderWholePage(index, width) }
        return fallback
    }

    /**
     * The sharp tiles covering [visible] on this page, at the density in use.
     *
     * Tiles are what lets a zoomed-in page stay crisp the way a browser's PDF
     * viewer does: panning reuses the tiles already rendered and only pays for
     * the ones that just came on screen, instead of re-rendering the whole
     * visible area every time it moves. Missing tiles are rendered in the
     * background; the caller keeps the low-resolution whole page underneath, so
     * there is never a hole to look at.
     */
    fun tiles(
        index: Int,
        visible: RectF,
        pageWidth: Float,
        pageHeight: Float,
        pixelsPerUnit: Float,
        /** False while the zoom is still moving: show what is cached, render nothing. */
        request: Boolean = true,
    ): List<PdfTile> {
        if (closed || index !in 0 until pageCount) return emptyList()
        val density = densityFor(pixelsPerUnit)
        val tileUnits = TILE_PX / density
        val firstX = floor(visible.left / tileUnits).toInt().coerceAtLeast(0)
        val lastX = floor((visible.right - 1e-3f) / tileUnits).toInt()
        val firstY = floor(visible.top / tileUnits).toInt().coerceAtLeast(0)
        val lastY = floor((visible.bottom - 1e-3f) / tileUnits).toInt()

        val ready = ArrayList<PdfTile>()
        val missing = ArrayList<TileKey>()
        for (ty in firstY..lastY) {
            for (tx in firstX..lastX) {
                val source = RectF(
                    tx * tileUnits,
                    ty * tileUnits,
                    minOf((tx + 1) * tileUnits, pageWidth),
                    minOf((ty + 1) * tileUnits, pageHeight),
                )
                if (source.width() <= 0f || source.height() <= 0f) continue
                val key = TileKey(index, density, tx, ty)
                val bitmap = tileCache.get(key)
                if (bitmap != null) ready += PdfTile(source, bitmap) else missing += key
            }
        }
        if (request && missing.isNotEmpty()) {
            requestTiles(index, missing, tileUnits, density, pageWidth, pageHeight)
        }
        return ready
    }

    /** All the missing tiles of one page in a single job, so the page opens once. */
    private fun requestTiles(
        index: Int,
        keys: List<TileKey>,
        tileUnits: Float,
        density: Float,
        pageWidth: Float,
        pageHeight: Float,
    ) {
        val generation: Int
        val fresh = synchronized(stateLock) {
            if (closed) return
            generation = viewportGeneration
            keys.filter { key ->
                if (pendingTiles[key] == generation ||
                    pendingTiles.containsKey(key) && index in wantedPages
                ) false
                else { pendingTiles[key] = generation; true }
            }
        }
        if (fresh.isEmpty()) return
        val accepted = runCatching { worker.execute {
            runCatching {
                if (!isRelevant(index, generation)) return@runCatching
                val remaining = fresh.filter { tileCache.get(it) == null }
                if (remaining.isEmpty()) return@runCatching
                synchronized(renderLock) {
                    if (closed) return@synchronized
                    renderer.openPage(index).use { page ->
                        for (key in remaining) {
                            if (closed || Thread.currentThread().isInterrupted) break
                            if (!isRelevant(index, generation)) continue
                            val source = RectF(
                                key.tileX * tileUnits,
                                key.tileY * tileUnits,
                                minOf((key.tileX + 1) * tileUnits, pageWidth),
                                minOf((key.tileY + 1) * tileUnits, pageHeight),
                            )
                            val widthPx = (source.width() * density).toInt()
                            val heightPx = (source.height() * density).toInt()
                            if (widthPx < 1 || heightPx < 1) continue
                            val transform = Matrix().apply {
                                setScale(density * POINTS_TO_WORLD, density * POINTS_TO_WORLD)
                                preTranslate(
                                    -source.left / POINTS_TO_WORLD,
                                    -source.top / POINTS_TO_WORLD,
                                )
                            }
                            val bitmap = newBitmap(widthPx, heightPx)
                            page.render(
                                bitmap,
                                null,
                                transform,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            // close() may have won while PdfRenderer was inside
                            // its non-interruptible render call.
                            synchronized(stateLock) {
                                if (!closed) tileCache.put(key, bitmap)
                            }
                        }
                    }
                }
            }
            synchronized(stateLock) {
                fresh.forEach { key ->
                    if (pendingTiles[key] == generation) pendingTiles.remove(key)
                }
            }
            if (!closed && isRelevant(index, generation)) onReady?.invoke(index)
        } }.isSuccess
        if (!accepted) synchronized(stateLock) {
            fresh.forEach { key -> if (pendingTiles[key] == generation) pendingTiles.remove(key) }
        }
    }

    /**
     * Powers of two, so panning at one zoom keeps hitting the same tiles.
     * A little headroom around each boundary prevents tiny scale rounding
     * changes from swapping 2x and 4x tile sets on adjacent frames.
     */
    private fun densityFor(pixelsPerUnit: Float): Float {
        var density = 1f
        while (density * DENSITY_BUCKET_HEADROOM < pixelsPerUnit && density < MAX_TILE_DENSITY) {
            density *= 2f
        }
        return density
    }

    /** Every text run on a page, joined - used to build the search index. */
    fun textOf(index: Int): String = synchronized(renderLock) {
        if (Build.VERSION.SDK_INT < 35) return ""
        if (closed || index !in 0 until pageCount) return ""
        runCatching {
            renderer.openPage(index).use { page ->
                page.textContents.joinToString("\n") { it.text }
            }
        }.getOrDefault("")
    }

    /**
     * The text between two page-local world points. Passing the same point twice
     * is how the word under the pen gets picked, which is the whole gesture.
     */
    fun select(index: Int, startWorld: RectF, endWorld: RectF): PdfSelection? =
        synchronized(renderLock) {
            if (Build.VERSION.SDK_INT < 35) return null
            if (closed || index !in 0 until pageCount) return null
            runCatching {
                renderer.openPage(index).use { page ->
                    val selection = page.selectContent(
                        SelectionBoundary(toPagePoint(startWorld)),
                        SelectionBoundary(toPagePoint(endWorld)),
                    ) ?: return null
                    val contents = selection.selectedTextContents
                    val text = contents.joinToString("") { it.text }
                    if (text.isBlank()) return null
                    val boxes = contents.flatMap { it.bounds }.map { rect ->
                        RectF(
                            rect.left * POINTS_TO_WORLD,
                            rect.top * POINTS_TO_WORLD,
                            rect.right * POINTS_TO_WORLD,
                            rect.bottom * POINTS_TO_WORLD,
                        )
                    }
                    PdfSelection(index, text, boxes)
                }
            }.getOrNull()
        }

    private fun toPagePoint(world: RectF) = Point(
        (world.left / POINTS_TO_WORLD).toInt(),
        (world.top / POINTS_TO_WORLD).toInt(),
    )

    private fun keyOf(index: Int, width: Int): Long =
        (index.toLong() shl 32) or (width.toLong() and 0xFFFFFFFFL)

    private fun bucketFor(wantPx: Int): Int {
        var width = MIN_PAGE_WIDTH
        while (width < wantPx && width < MAX_PAGE_WIDTH) width *= 2
        return width
    }

    private fun coarserThan(index: Int, width: Int): Bitmap? {
        var candidate = width / 2
        while (candidate >= MIN_PAGE_WIDTH) {
            cache.get(keyOf(index, candidate))?.let { return it }
            candidate /= 2
        }
        return null
    }

    private fun request(key: Long, render: () -> Bitmap?) {
        val generation: Int
        synchronized(stateLock) {
            if (closed) return
            generation = viewportGeneration
            val page = (key shr 32).toInt()
            if (pending[key] == generation || pending.containsKey(key) && page in wantedPages) return
            pending[key] = generation
        }
        val accepted = runCatching { worker.execute {
            val page = (key shr 32).toInt()
            val bitmap = if (cache.get(key) == null && isRelevant(page, generation)) {
                runCatching { render() }.getOrNull()
            } else null
            synchronized(stateLock) {
                if (pending[key] == generation) pending.remove(key)
            }
            if (bitmap != null) {
                val keep = synchronized(stateLock) {
                    if (closed) false else {
                        if (key >= 0) cache.put(key, bitmap)
                        true
                    }
                }
                if (keep) onReady?.invoke((key shr 32).toInt())
            }
        } }.isSuccess
        if (!accepted) synchronized(stateLock) {
            if (pending[key] == generation) pending.remove(key)
        }
    }

    private fun isRelevant(page: Int, generation: Int): Boolean =
        !closed && (generation == viewportGeneration || page in wantedPages)

    /** Blocking render, for callers off the UI thread that need the page now. */
    fun renderNow(index: Int, widthPx: Int): Bitmap? = renderWholePage(index, widthPx)

    /** Preview the printed glyphs, independent of a PDF font's Unicode mapping. */
    fun renderSelection(selection: PdfSelection): Bitmap? = synchronized(renderLock) {
        if (closed || selection.boxes.isEmpty()) return null
        runCatching {
            val bounds = RectF(selection.boxes.first())
            selection.boxes.drop(1).forEach { bounds.union(it) }
            bounds.inset(-4f, -4f)
            val scale = minOf(1200f / bounds.width(), 500f / bounds.height(), 3f)
            if (!scale.isFinite() || scale <= 0f) return null
            val bitmap = Bitmap.createBitmap((bounds.width() * scale).toInt().coerceAtLeast(1),
                (bounds.height() * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(selection.pageIndex).use { page ->
                val transform = Matrix().apply {
                    setScale(scale * POINTS_TO_WORLD, scale * POINTS_TO_WORLD)
                    postTranslate(-bounds.left * scale, -bounds.top * scale)
                }
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            bitmap
        }.getOrNull()
    }

    private fun renderWholePage(index: Int, widthPx: Int): Bitmap? = synchronized(renderLock) {
        if (closed) return null
        runCatching {
            renderer.openPage(index).use { page ->
                val height = (widthPx * page.height.toFloat() / page.width)
                    .toInt().coerceAtLeast(1)
                newBitmap(widthPx, height).also {
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }.getOrNull()
    }

    /** [source] is in page-local world units. */
    @Suppress("unused")
    private fun renderCrop(index: Int, source: RectF, widthPx: Int, heightPx: Int): Bitmap? =
        synchronized(renderLock) {
            if (closed) return null
            runCatching {
                renderer.openPage(index).use { page ->
                    val left = source.left / POINTS_TO_WORLD
                    val top = source.top / POINTS_TO_WORLD
                    val cropWidth = (source.width() / POINTS_TO_WORLD).coerceAtLeast(1f)
                    val cropHeight = (source.height() / POINTS_TO_WORLD).coerceAtLeast(1f)
                    val transform = Matrix().apply {
                        setScale(widthPx / cropWidth, heightPx / cropHeight)
                        preTranslate(-left, -top)
                    }
                    newBitmap(widthPx, heightPx).also {
                        page.render(
                            it,
                            null,
                            transform,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                    }
                }
            }.getOrNull()
        }

    /** PDF pages are transparent where nothing is drawn; paper is white. */
    private fun newBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            // Opaque paper: telling the compositor lets it skip blending a
            // full-screen texture on every frame.
            setHasAlpha(false)
        }

    override fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                cache.evictAll()
                tileCache.evictAll()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                cache.trimToSize(cache.maxSize() / 2)
                tileCache.trimToSize(tileCache.maxSize() / 2)
            }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            pending.clear()
            pendingTiles.clear()
        }
        onReady = null
        RuntimeMemory.unregister(this)
        // PdfRenderer.render() cannot be interrupted. Do not make navigation
        // wait on its lock; discard queued tiles and finish native cleanup on a
        // single process-wide closer once the current render returns.
        worker.shutdownNow()
        cache.evictAll()
        tileCache.evictAll()
        closeWorker.execute {
            synchronized(renderLock) {
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
            }
        }
    }

    companion object {
        /** PDF points are 1/72"; pages are laid out at 150dpi like a blank page. */
        const val POINTS_TO_WORLD = 150f / 72f

        private const val MIN_PAGE_WIDTH = 1024
        // The whole-page image is only a stable floor. Detail comes from tiles;
        // keeping this at 1024 avoids a single A4 bitmap consuming the cache.
        private const val MAX_PAGE_WIDTH = 1024
        private const val MAX_CROP_PX = 4096
        /** Small enough for a full tablet viewport to remain in the tile LRU. */
        private const val TILE_PX = 512f
        private const val DENSITY_BUCKET_HEADROOM = 1.12f
        private const val MIN_PAGE_CACHE_BYTES = 8 * 1024 * 1024
        private const val MAX_PAGE_CACHE_BYTES = 32 * 1024 * 1024
        private const val MIN_TILE_CACHE_BYTES = 8 * 1024 * 1024
        // The canvas can reach 16 screen pixels per page unit. Matching that
        // ceiling keeps glyph edges one source pixel per display pixel even at
        // maximum zoom instead of magnifying the last tile bucket twofold.
        private const val MAX_TILE_DENSITY = MAX_CANVAS_SCALE
        private const val DETAIL_MARGIN = 0.25f
        private val closeWorker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "notesis-pdf-close").apply { isDaemon = true }
        }

        /**
         * A page at [MAX_PAGE_WIDTH] is roughly 24MB, so a cache that only holds
         * three or four of them evicts on every scroll and pays to render and
         * re-upload the same pages over and over. Size it off what the device
         * actually has instead of a flat number.
         */
        /** Beyond this many pixels across, a page needs tiles to stay sharp. */
        fun baseWidthLimit(): Int = MAX_PAGE_WIDTH

        fun cacheBytesFor(context: android.content.Context): Int {
            return RuntimeMemory.pdfCacheBytes(context)
        }

        fun open(file: File, cacheBytes: Int = 32 * 1024 * 1024): PdfSource? {
            if (!file.isFile) return null
            return runCatching {
                val descriptor =
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfSource(descriptor, PdfRenderer(descriptor), cacheBytes)
            }.getOrNull()
        }
    }
}
