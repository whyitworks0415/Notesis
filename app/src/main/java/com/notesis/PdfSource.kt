package com.notesis

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * The imported PDF behind a note's pages, rendered lazily.
 *
 * PdfRenderer allows exactly one open page at a time and is not thread safe, so
 * every call into it is serialised onto one background thread. Rendering a page
 * costs tens of milliseconds, which is a visible stutter if it happens while
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
    private val pending = mutableSetOf<Int>()
    private var closed = false

    // Roughly six full pages. Bitmaps are the biggest thing this app holds, so
    // the cap is in bytes rather than entries.
    private val cache = object : LruCache<Int, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    /** Called on the worker thread once a requested page is cached. */
    var onReady: ((Int) -> Unit)? = null

    /** Page size in world units, or null if the page is out of range. */
    fun pageSize(index: Int): Pair<Float, Float>? = synchronized(lock) {
        if (closed || index !in 0 until pageCount) return null
        renderer.openPage(index).use { page ->
            (page.width * POINTS_TO_WORLD) to (page.height * POINTS_TO_WORLD)
        }
    }

    /** The cached bitmap, kicking off a render when there is not one yet. */
    fun bitmap(index: Int, widthPx: Int): Bitmap? {
        cache.get(index)?.let { return it }
        request(index, widthPx)
        return null
    }

    private fun request(index: Int, widthPx: Int) {
        synchronized(lock) {
            if (closed || index !in 0 until pageCount || !pending.add(index)) return
        }
        worker.execute {
            val bitmap = runCatching { renderPage(index, widthPx) }.getOrNull()
            synchronized(lock) { pending.remove(index) }
            if (bitmap != null) {
                cache.put(index, bitmap)
                onReady?.invoke(index)
            }
        }
    }

    private fun renderPage(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        if (closed) return null
        renderer.openPage(index).use { page ->
            val width = widthPx.coerceIn(64, MAX_BITMAP_WIDTH)
            val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // PDF pages are transparent where nothing is drawn; paper is white.
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

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
    }

    companion object {
        /** PDF points are 1/72"; pages are laid out at 150dpi like a blank page. */
        const val POINTS_TO_WORLD = 150f / 72f

        // ponytail: one bitmap per page at this width, so zooming far in goes
        // soft. Re-rendering visible pages at the current zoom is the fix, and
        // it only matters once someone actually complains about it.
        const val MAX_BITMAP_WIDTH = 1600

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
