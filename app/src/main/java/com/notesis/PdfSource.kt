package com.notesis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

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
) : AutoCloseable {

    val pageCount: Int = renderer.pageCount

    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = mutableSetOf<Long>()
    private var closed = false

    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    /** A single high-resolution crop, for when the page is zoomed past the cache. */
    private var detail: Detail? = null

    private class Detail(val pageIndex: Int, val source: RectF, val bitmap: Bitmap)

    /** Called on the worker thread once a requested render has landed. */
    var onReady: ((Int) -> Unit)? = null

    /** Page size in world units, or null if the page is out of range. */
    fun pageSize(index: Int): Pair<Float, Float>? = synchronized(lock) {
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
    fun bitmap(index: Int, wantPx: Int): Bitmap? {
        val width = bucketFor(wantPx)
        val key = keyOf(index, width)
        cache.get(key)?.let { return it }
        // A coarser render of the same page is a better thing to show than blank
        // paper while the sharper one is still being made.
        val fallback = coarserThan(index, width)
        request(key) { renderWholePage(index, width) }
        return fallback
    }

    /**
     * A crop of one page rendered at screen resolution. Used past the point
     * where a whole-page bitmap would have to be bigger than [MAX_PAGE_WIDTH],
     * which is where zooming starts to look soft.
     */
    fun detail(index: Int, source: RectF, widthPx: Int): Bitmap? {
        val current = detail
        if (current != null &&
            current.pageIndex == index &&
            current.source.contains(source)
        ) {
            return current.bitmap
        }
        // Render a margin around what is asked for, so small pans reuse the crop
        // instead of re-rendering on every frame.
        val padded = RectF(source).apply {
            inset(-width() * DETAIL_MARGIN, -height() * DETAIL_MARGIN)
        }
        val key = keyOf(index, -widthPx)
        request(key) {
            val bitmap = renderCrop(index, padded, widthPx)
            if (bitmap != null) detail = Detail(index, padded, bitmap)
            bitmap
        }
        return current?.takeIf { it.pageIndex == index }?.bitmap
    }

    /** The region the current detail crop covers, in page-local world units. */
    fun detailSource(index: Int): RectF? =
        detail?.takeIf { it.pageIndex == index }?.let { RectF(it.source) }

    /** Every text run on a page, joined - used to build the search index. */
    fun textOf(index: Int): String = synchronized(lock) {
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
        synchronized(lock) {
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
        synchronized(lock) {
            if (closed || !pending.add(key)) return
        }
        worker.execute {
            val bitmap = runCatching { render() }.getOrNull()
            synchronized(lock) { pending.remove(key) }
            if (bitmap != null) {
                if (key >= 0) cache.put(key, bitmap)
                onReady?.invoke((key shr 32).toInt())
            }
        }
    }

    private fun renderWholePage(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
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
    private fun renderCrop(index: Int, source: RectF, widthPx: Int): Bitmap? =
        synchronized(lock) {
            if (closed) return null
            runCatching {
                renderer.openPage(index).use { page ->
                    val left = source.left / POINTS_TO_WORLD
                    val top = source.top / POINTS_TO_WORLD
                    val cropWidth = (source.width() / POINTS_TO_WORLD).coerceAtLeast(1f)
                    val cropHeight = (source.height() / POINTS_TO_WORLD).coerceAtLeast(1f)
                    val scale = widthPx / cropWidth
                    val height = (cropHeight * scale).toInt().coerceIn(1, MAX_CROP_HEIGHT)
                    val transform = Matrix().apply {
                        setScale(scale, scale)
                        preTranslate(-left, -top)
                    }
                    newBitmap(widthPx, height).also {
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
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.WHITE) }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        worker.shutdown()
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
        cache.evictAll()
        detail = null
    }

    companion object {
        /** PDF points are 1/72"; pages are laid out at 150dpi like a blank page. */
        const val POINTS_TO_WORLD = 150f / 72f

        private const val MIN_PAGE_WIDTH = 1024
        /** 2048 x ~2900 x 4B is about 24MB - past this, crops are cheaper. */
        private const val MAX_PAGE_WIDTH = 2048
        private const val MAX_CROP_HEIGHT = 4096
        private const val DETAIL_MARGIN = 0.25f
        private const val CACHE_BYTES = 96 * 1024 * 1024

        fun open(file: File): PdfSource? {
            if (!file.isFile) return null
            return runCatching {
                val descriptor =
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfSource(descriptor, PdfRenderer(descriptor))
            }.getOrNull()
        }
    }
}
