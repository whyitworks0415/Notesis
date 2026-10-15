package com.notesis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Trace
import android.util.LruCache
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
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
 * scrolling. A small independent page preview remains underneath sharp images
 * and tiles, and [onReady] fires when a better image lands in the cache.
 */
/** Where a link in a PDF leads. */
sealed interface PdfLink {
    data class Web(val url: String) : PdfLink

    /** A zero-based page of the same PDF. */
    data class Page(val pdfPageIndex: Int) : PdfLink
}

class PdfSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** The whole memory budget this source was opened with. */
    val cacheBytes: Int,
) : AutoCloseable {

    /** The file this was opened from, for [PdfHold] to know it again. */
    var file: File? = null
        private set

    /**
     * The PDF's own link annotations, by page. Empty until they are known:
     * read from the note's small link cache when there is one, otherwise found
     * by a full PDFBox parse on a background thread after the note is already
     * on screen - that parse used to hold the whole note closed until it ended.
     */
    @Volatile private var pageLinks: Map<Int, List<PdfPageLink>> = emptyMap()

    val pageCount: Int = renderer.pageCount

    /** Internal destination under a finger, if this PDF declares one there. */
    fun linkedPageAt(pageIndex: Int, x: Float, y: Float, tolerance: Float = 0f): Int? =
        (indexedLinkAt(pageIndex, x, y, tolerance) as? PdfLink.Page)?.pdfPageIndex

    /** Pre-indexed links do not wait for the PDF render worker or require Android 15. */
    fun indexedLinkAt(pageIndex: Int, x: Float, y: Float, tolerance: Float = 0f): PdfLink? {
        if (closed || pageIndex !in 0 until pageCount) return null
        val link = pageLinks[pageIndex]
            ?.asReversed()
            ?.firstOrNull { it.contains(x, y, tolerance) }
            ?: return null
        return link.webUrl?.let { PdfLink.Web(it) } ?: PdfLink.Page(link.targetPageIndex)
    }

    /** Set by the active canvas; null for exports, thumbnails, and normal use. */
    @Volatile var diagnostics: LatencyStats? = null

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

    val isClosed: Boolean get() = closed

    // [cacheBytes] remains one total budget. Detail space fits a 2048px A4
    // page at the minimum budget and keeps a screenful of sharp tiles.
    // A separate low-resolution floor survives sharp page/tile cache churn.
    private val previewBytes = (cacheBytes / 8).coerceAtLeast(1)
    private val previewCache = object : LruCache<Int, Bitmap>(previewBytes) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }
    private val detailBytes = (cacheBytes - previewBytes).coerceAtLeast(2)
    private val cache = object : LruCache<Long, Bitmap>((detailBytes / 2).coerceAtLeast(1)) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    private data class TileKey(
        val pageIndex: Int,
        val density: Float,
        val tileX: Int,
        val tileY: Int,
    )

    private val pendingTiles = mutableMapOf<TileKey, Int>()

    private val tileCache = object : LruCache<TileKey, Bitmap>((detailBytes - detailBytes / 2).coerceAtLeast(1)) {
        override fun sizeOf(key: TileKey, value: Bitmap) = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: TileKey, oldValue: Bitmap, newValue: Bitmap?) {
            tilesHeld.remove(key, oldValue)
        }
    }

    /**
     * What [tileCache] holds, readable without counting as a use. A stand-in
     * tile read through the cache itself became its newest entry and pushed
     * out the right tiles as they rendered - the very churn the density
     * budget is there to prevent.
     */
    private val tilesHeld = java.util.concurrent.ConcurrentHashMap<TileKey, Bitmap>()

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
        // Nearest first, and only as many at full size as the page cache
        // holds alongside each other; the next few at the smallest step; the
        // rest only as previews. Seven pages at 2048px were asked for at
        // once, and as the far ones landed the LRU pushed out the near ones -
        // the very pages the next short scroll shows - to be rendered again.
        var budget = cache.maxSize().toLong() * PREFETCH_BUDGET_PERCENT / 100
        val full = bucketFor(widthPx)
        for (page in ordered) {
            val fullBytes = pageBytes(full, A4_ASPECT)
            val smallBytes = pageBytes(MIN_PAGE_WIDTH, A4_ASPECT)
            when {
                fullBytes <= budget -> {
                    budget -= fullBytes
                    bitmap(page, full, request = true)
                }
                smallBytes <= budget -> {
                    budget -= smallBytes
                    bitmap(page, MIN_PAGE_WIDTH, request = true)
                }
                else -> bitmap(page, MIN_PAGE_WIDTH, request = false)
            }
        }
    }

    /**
     * Gives memory back when the system asks: tiles and sharp pages are only
     * ever copies of the PDF and come back on demand. [severe] is the app
     * being in the background or memory running out; then only previews stay.
     */
    fun trimMemory(severe: Boolean) {
        if (closed) return
        tileCache.evictAll()
        if (severe) cache.evictAll() else cache.trimToSize(cache.maxSize() / 2)
    }

    /** Page size in world units, or null if the page is out of range. */
    private fun loadLinks(file: File) {
        val cache = File(file.parentFile, LINKS_CACHE)
        readPdfLinksCache(cache, file)?.let {
            pageLinks = it
            return
        }
        linkWorker.execute {
            if (closed) return@execute
            val found = loadPdfPageLinks(file)
            writePdfLinksCache(cache, file, found)
            if (!closed) pageLinks = found
        }
    }

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
    fun bitmap(
        index: Int,
        wantPx: Int,
        request: Boolean = true,
        /** How many PDF pages are on screen together, all wanting this size. */
        sharing: Int = 1,
        /** Height over width, for what one page at a size costs. */
        aspect: Float = A4_ASPECT,
    ): Bitmap? {
        val width = fittingWidth(bucketFor(wantPx), sharing, aspect)
        if (closed || index !in 0 until pageCount) return null
        val preview = previewCache.get(index)
        if (preview == null) request(keyOf(index, PREVIEW_WIDTH)) {
            renderWholePage(index, PREVIEW_WIDTH)
        }
        val key = keyOf(index, width)
        cache.get(key)?.let { return it }
        // Reuse a sharper cached page when zooming out. Looking only for a
        // coarser page made an already visible PDF go white until the smaller
        // bucket finished rendering.
        // Downsampling a sharper page is already the best result for this
        // zoom, and avoids evicting it just to render a smaller duplicate.
        finerThan(index, width)?.let { return it }
        val fallback = coarserThan(index, width)
        if (request) request(key) { renderWholePage(index, width) }
        return fallback ?: preview
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
        val density = densityFor(pixelsPerUnit, visible.width(), visible.height())
        val tileUnits = TILE_PX / density
        val firstX = floor(visible.left / tileUnits).toInt().coerceAtLeast(0)
        val lastX = floor((visible.right - 1e-3f) / tileUnits).toInt()
        val firstY = floor(visible.top / tileUnits).toInt().coerceAtLeast(0)
        val lastY = floor((visible.bottom - 1e-3f) / tileUnits).toInt()

        val ready = ArrayList<PdfTile>()
        val missing = ArrayList<TileKey>()
        val fallback = ArrayList<PdfTile>()
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
                if (bitmap != null) ready += PdfTile(source, bitmap) else {
                    missing += key
                    fallbackTiles(index, density, tx, ty, pageWidth, pageHeight, fallback)
                }
            }
        }
        if (request && missing.isNotEmpty()) {
            requestTiles(index, missing, tileUnits, density, pageWidth, pageHeight)
        }
        if (fallback.isEmpty()) return ready
        // Stand-ins first, so the right tiles are drawn over them.
        fallback += ready
        return fallback
    }

    /**
     * While the tile at [density] is on its way, the same place from the
     * zoom just left: the coarser tile that contains it, or the finer ones
     * inside it. Right after a zoom step this is a nearly sharp page instead
     * of the soft whole-page bitmap underneath.
     */
    private fun fallbackTiles(
        index: Int,
        density: Float,
        tx: Int,
        ty: Int,
        pageWidth: Float,
        pageHeight: Float,
        out: MutableList<PdfTile>,
    ) {
        val coarser = density / 2f
        if (coarser >= 1f) {
            val units = TILE_PX / coarser
            val key = TileKey(index, coarser, tx / 2, ty / 2)
            val bitmap = tilesHeld[key]
            if (bitmap != null) {
                val source = tileRect(key.tileX, key.tileY, units, pageWidth, pageHeight)
                if (out.none { it.bitmap === bitmap }) out += PdfTile(source, bitmap)
                return
            }
        }
        val finer = density * 2f
        if (finer > MAX_TILE_DENSITY) return
        val units = TILE_PX / finer
        for (dy in 0..1) for (dx in 0..1) {
            val key = TileKey(index, finer, tx * 2 + dx, ty * 2 + dy)
            val bitmap = tilesHeld[key] ?: continue
            val source = tileRect(key.tileX, key.tileY, units, pageWidth, pageHeight)
            if (source.width() > 0f && source.height() > 0f) out += PdfTile(source, bitmap)
        }
    }

    private fun tileRect(tx: Int, ty: Int, units: Float, pageWidth: Float, pageHeight: Float) = RectF(
        tx * units,
        ty * units,
        minOf((tx + 1) * units, pageWidth),
        minOf((ty + 1) * units, pageHeight),
    )

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
                if (!shouldEnqueueRender(pendingTiles[key], generation)) false
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
                            measuredRender(widthPx.toLong() * heightPx) {
                                page.render(
                                    bitmap,
                                    null,
                                    transform,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                            }
                            // close() may have won while PdfRenderer was inside
                            // its non-interruptible render call.
                            val keep = synchronized(stateLock) {
                                if (shouldPublishRender(generation, viewportGeneration, closed)) {
                                    tileCache.put(key, bitmap)
                                    tilesHeld[key] = bitmap
                                    true
                                } else false
                            }
                            if (!keep) bitmap.recycle()
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
     * Powers of two, so panning at one zoom keeps hitting the same tiles - and
     * never so dense that the tiles on screen outgrow the tile cache.
     *
     * Just past each power of two a tile covers barely more than half a
     * screen-tile, so a screenful needed about four times as many: some 30
     * tiles of 4MB on a 2560px tablet, more than the cache holds. Rendering
     * them evicted the ones already drawn, every finished render asked for the
     * evicted ones again, and the text went sharp and blurry by turns for as
     * long as the page sat still. One density lower is all but as sharp there
     * (the step just crossed was a few percent), and the set fits.
     */
    private fun densityFor(pixelsPerUnit: Float, visibleWidth: Float, visibleHeight: Float): Float {
        var density = 1f
        while (density < pixelsPerUnit && density < MAX_TILE_DENSITY) density *= 2f
        val budget = tileCache.maxSize().toLong() * TILE_BUDGET_PERCENT / 100
        while (density > 1f && tileBytesAtMost(visibleWidth, visibleHeight, density) > budget) density /= 2f
        return density
    }

    /**
     * The most a visible area this size can need at [density], wherever it
     * sits against the tile grid - so the choice does not flip back and forth
     * as the page is panned a little.
     */
    private fun tileBytesAtMost(visibleWidth: Float, visibleHeight: Float, density: Float): Long {
        val units = TILE_PX / density
        val cols = ceil(visibleWidth.coerceAtLeast(0f) / units).toLong() + 1
        val rows = ceil(visibleHeight.coerceAtLeast(0f) / units).toLong() + 1
        return cols * rows * (TILE_PX * TILE_PX * 4).toLong()
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

    /**
     * The link under a page-local world point: a web address, or another page
     * of the same PDF (a table of contents, a footnote, a "see page 12").
     * Prefer the portable index, with native Android 15 links as a fallback.
     */
    fun linkAt(index: Int, worldX: Float, worldY: Float): PdfLink? {
        indexedLinkAt(index, worldX, worldY)?.let { return it }
        if (Build.VERSION.SDK_INT < 35) return null
        if (closed || index !in 0 until pageCount) return null
        return synchronized(renderLock) { runCatching {
            renderer.openPage(index).use { page ->
                val x = worldX / POINTS_TO_WORLD
                val y = worldY / POINTS_TO_WORLD
                page.linkContents.firstOrNull { link -> link.bounds.any { it.contains(x, y) } }
                    ?.let { return PdfLink.Web(it.uri.toString()) }
                page.gotoLinks.firstOrNull { link -> link.bounds.any { it.contains(x, y) } }
                    ?.let { return PdfLink.Page(it.destination.pageNumber) }
                null
            }
        }.getOrNull() }
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

    /**
     * [width], or the largest step below it at which [sharing] pages fit in
     * the page cache together. Two pages on screen at 2048px are about 48MB;
     * where the cache held only one, each render evicted the other, which went
     * soft, asked again and evicted the first - both flickering for as long
     * as they sat there. A steady step down is far better than that.
     */
    private fun fittingWidth(width: Int, sharing: Int, aspect: Float): Int {
        var fitted = width
        val room = cache.maxSize().toLong()
        while (fitted > MIN_PAGE_WIDTH && sharing.coerceAtLeast(1) * pageBytes(fitted, aspect) > room) fitted /= 2
        return fitted
    }

    private fun pageBytes(width: Int, aspect: Float): Long =
        width.toLong() * (width * aspect.coerceIn(0.2f, 5f)).toLong() * 4L

    private fun finerThan(index: Int, width: Int): Bitmap? {
        var candidate = width * 2
        while (candidate <= MAX_PAGE_WIDTH) {
            cache.get(keyOf(index, candidate))?.let { return it }
            candidate *= 2
        }
        return null
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
            if (!shouldEnqueueRender(pending[key], generation)) return
            pending[key] = generation
        }
        val accepted = runCatching { worker.execute {
            val page = (key shr 32).toInt()
            val alreadyCached = if (key.toInt() == PREVIEW_WIDTH) previewCache.get(page) != null else cache.get(key) != null
            val bitmap = if (!alreadyCached && isRelevant(page, generation)) {
                runCatching { render() }.getOrNull()
            } else null
            synchronized(stateLock) {
                if (pending[key] == generation) pending.remove(key)
            }
            if (bitmap != null) {
                val keep = synchronized(stateLock) {
                    // Whole-page images are immutable and useful as fallback
                    // even if zoom/page priority changed during native render.
                    val page = (key shr 32).toInt()
                    val preview = key.toInt() == PREVIEW_WIDTH
                    if (!closed && (preview || generation == viewportGeneration || page in wantedPages)) {
                        if (preview) previewCache.put(page, bitmap) else cache.put(key, bitmap)
                        true
                    } else false
                }
                if (keep) onReady?.invoke((key shr 32).toInt()) else bitmap.recycle()
            }
        } }.isSuccess
        if (!accepted) synchronized(stateLock) {
            if (pending[key] == generation) pending.remove(key)
        }
    }

    private fun isRelevant(page: Int, generation: Int): Boolean =
        page in 0 until pageCount &&
            shouldPublishRender(generation, viewportGeneration, closed)

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
                measuredRender(bitmap.width.toLong() * bitmap.height) {
                    page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
            bitmap
        }.getOrNull()
    }

    private fun renderWholePage(index: Int, widthPx: Int): Bitmap? = synchronized(renderLock) {
        if (closed) return null
        runCatching {
            renderer.openPage(index).use { page ->
                val renderWidth = if (widthPx == PREVIEW_WIDTH) minOf(widthPx,
                    (PREVIEW_MAX_HEIGHT * page.width.toFloat() / page.height).toInt().coerceAtLeast(1)) else widthPx
                val height = (renderWidth * page.height.toFloat() / page.width)
                    .toInt().coerceAtLeast(1)
                newBitmap(renderWidth, height).also {
                    measuredRender(it.width.toLong() * it.height) {
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
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
                        measuredRender(it.width.toLong() * it.height) {
                            page.render(
                                it,
                                null,
                                transform,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                        }
                    }
                }
            }.getOrNull()
        }

    /** The lambda is inline, so diagnostics add no allocation to normal renders. */
    private inline fun measuredRender(pixelCount: Long, render: () -> Unit) {
        val stats = diagnostics
        val started = if (stats?.enabled == true) System.nanoTime() else 0L
        if (started != 0L) Trace.beginSection(TRACE_PDF_RENDER)
        try {
            render()
        } finally {
            if (started != 0L) {
                val elapsed = System.nanoTime() - started
                Trace.endSection()
                stats?.addPdfRender(elapsed, pixelCount)
            }
        }
    }

    /** PDF pages are transparent where nothing is drawn; paper is white. */
    private fun newBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            // Opaque paper: telling the compositor lets it skip blending a
            // full-screen texture on every frame.
            setHasAlpha(false)
        }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            pending.clear()
            pendingTiles.clear()
        }
        onReady = null
        // PdfRenderer.render() cannot be interrupted. Do not make navigation
        // wait on its lock; discard queued tiles and finish native cleanup on a
        // single process-wide closer once the current render returns.
        worker.shutdownNow()
        cache.evictAll()
        previewCache.evictAll()
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

        private const val PREVIEW_WIDTH = 384
        private const val PREVIEW_MAX_HEIGHT = 1024
        private const val MIN_PAGE_WIDTH = 1024
        /** 2048 x ~2900 x 4B is about 24MB - past this, crops are cheaper. */
        private const val MAX_PAGE_WIDTH = 2048
        private const val MAX_CROP_PX = 4096
        /** Tile edge in pixels. Big enough that a screen needs only a handful. */
        private const val TILE_PX = 1024f
        /** Of the tile cache, what one page's visible tiles may take; the rest is for a neighbour. */
        private const val TILE_BUDGET_PERCENT = 85
        // The canvas can reach 16 screen pixels per page unit. Matching that
        // ceiling keeps glyph edges one source pixel per display pixel even at
        // maximum zoom instead of magnifying the last tile bucket twofold.
        private const val MAX_TILE_DENSITY = MAX_CANVAS_SCALE
        private const val DETAIL_MARGIN = 0.25f
        private const val MIN_CACHE_BYTES = 64 * 1024 * 1024
        private const val MAX_CACHE_BYTES = 256 * 1024 * 1024
        private const val MB = 1024L * 1024L
        /** A4, height over width: what a page is assumed to be where its size is not to hand. */
        private const val A4_ASPECT = 1.414f
        /** Of the page cache, what a prefetch may fill; the rest keeps what is on screen. */
        private const val PREFETCH_BUDGET_PERCENT = 85
        private const val TRACE_PDF_RENDER = "Notesis PDF render"
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

        /**
         * The budget for one open PDF. Bitmaps live outside the Java heap, so
         * the heap class alone undersold a tablet with plenty of RAM; the
         * device's total memory sets the ceiling instead, and a tablet with
         * more of it keeps more of a long PDF sharp when scrolling back.
         * A [share] above 1 is for a second view of a PDF, such as the
         * reference popup, which should not claim as much as the note itself.
         */
        fun cacheBytesFor(context: android.content.Context, share: Int = 1): Int {
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            val heap = (manager?.largeMemoryClass ?: 128).toLong() * MB / 2
            val info = android.app.ActivityManager.MemoryInfo()
            val total = runCatching { manager?.getMemoryInfo(info); info.totalMem }.getOrDefault(0L)
            val ceiling = pdfCacheCeilingFor(total)
            val bytes = maxOf(heap, total / 32).coerceIn(MIN_CACHE_BYTES.toLong(), ceiling)
            return (bytes / share.coerceAtLeast(1)).coerceAtLeast(MIN_CACHE_BYTES.toLong()).toInt()
        }

        /**
         * Opens [file] for drawing. With [links], the PDF's link annotations
         * are made available too - only the note being read needs them, so
         * thumbnails, exports and indexing never pay for finding them.
         */
        fun open(file: File, cacheBytes: Int = MIN_CACHE_BYTES, links: Boolean = false): PdfSource? {
            if (!file.isFile) return null
            val source = runCatching {
                val descriptor =
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfSource(descriptor, PdfRenderer(descriptor), cacheBytes)
            }.getOrNull() ?: return null
            source.file = file
            if (links) source.loadLinks(file)
            return source
        }

        private val linkWorker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "notesis-pdf-links").apply {
                isDaemon = true
                // Behind rendering and ink loading: a link is only needed when tapped.
                priority = Thread.MIN_PRIORITY
            }
        }

        /** Beside the note's PDF; see [readPdfLinksCache]. */
        private const val LINKS_CACHE = "pdf-links.json"
    }
}

/** One PDF's cache ceiling: under 3GB of RAM 128MB, under 7GB 256MB as before, more RAM up to 384MB. */
internal fun pdfCacheCeilingFor(totalMem: Long): Long {
    val mb = 1024L * 1024L
    val gb = 1024L * mb
    return when {
        totalMem <= 0L -> 256L * mb
        totalMem < 3L * gb -> 128L * mb
        totalMem < 7L * gb -> 256L * mb
        else -> 384L * mb
    }
}
