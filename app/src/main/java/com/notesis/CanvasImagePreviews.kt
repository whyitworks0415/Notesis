package com.notesis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * Screen-only images. Sources and export loaders stay full size; preview
 * decoding is bounded and off the UI thread, including custom paper. One
 * worker is shared by all canvases, so opening reference panes adds no pool.
 */
internal class CanvasImagePreviews(
    bytes: Int,
    private val file: (String) -> File,
    private val onReady: () -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private class Preview(val bitmap: Bitmap, val original: Boolean)
    private val cache = object : LruCache<String, Preview>(bytes) {
        override fun sizeOf(key: String, value: Preview) = value.bitmap.allocationByteCount
    }
    private val maxImageBytes = (bytes.toLong() / 2).coerceAtLeast(4)
    private val resources: AsyncResourceCache<String, Preview> = AsyncResourceCache(
        executor = worker,
        cached = cache::get,
        store = { key: String, value: Preview -> cache.put(key, value); Unit },
        clear = cache::evictAll,
        load = ::decode,
        notifyReady = ::postReady,
    )
    private val ready = Runnable { resources.notifyIfOpen(onReady) }

    private fun postReady() { main.removeCallbacks(ready); main.post(ready) }

    fun image(id: String): Bitmap? = resources.request("i:$id")?.bitmap
    fun template(id: String): Bitmap? = resources.request("t:$id")?.bitmap

    /** Selection/capture keeps its original-resolution behavior, outside draw. */
    fun originalImage(id: String): Bitmap? = original("i:$id")
    fun originalTemplate(id: String): Bitmap? = original("t:$id")

    private fun original(key: String): Bitmap? =
        resources.peek(key)?.takeIf { it.original }?.bitmap ?: runCatching {
            BitmapFactory.decodeFile(file(key).path)
        }.getOrNull()

    private fun decode(key: String): Preview? {
        val path = file(key).path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = previewSampleSize(bounds.outWidth, bounds.outHeight, BitmapBudget.MAX_SIDE, maxImageBytes)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(path, options)?.let { Preview(it, options.inSampleSize == 1) }
        } catch (_: OutOfMemoryError) { null }
    }

    override fun close() {
        resources.close()
        main.removeCallbacks(ready)
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "note-images")
        }
    }
}
