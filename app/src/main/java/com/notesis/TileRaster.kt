package com.notesis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import java.util.IdentityHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Paints ink tiles on worker threads, so a page coming on screen costs the
 * render thread a picture per tile instead of every stroke in it.
 *
 * A tile recorded for the GPU is a list of meshes the render thread paints
 * into the tile's layer in the frame that first shows it: a dense page brought
 * a few thousand meshes into one frame, and the screen ran at a few frames a
 * second until every tile had been painted once. Here each tile is painted on
 * a bitmap's own canvas, several at a time on spare cores, moved into graphics
 * memory on the same worker, and handed back finished; the render thread only
 * draws it. Strokes being written are still drawn as they are, over the
 * picture (InkTileCache's delta), so the pen waits on none of this.
 */
internal object TileRaster {
    /** One tile's picture: its strokes, their on-screen twins, and where the tile sits. */
    class Job(
        val strokes: List<Stroke>,
        val twins: IdentityHashMap<Stroke, Stroke>,
        val look: LayerLook?,
        val scale: Float,
        val dx: Float,
        val dy: Float,
        val size: Int,
    ) {
        /** Set on the UI thread when the tile goes or changes; the worker stops at the next stroke. */
        @Volatile var cancelled = false
    }

    // A third of the cores at most: the page's meshes are read in on others,
    // and the UI and render threads must keep theirs.
    private val threads = (Runtime.getRuntime().availableProcessors() / 3).coerceIn(1, 3)
    private val pool = ThreadPoolExecutor(threads, threads, 10L, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
        Thread(runnable, "notesis-tile-raster").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val renderers = ThreadLocal.withInitial { CanvasStrokeRenderer.create(PencilTextureStore) }
    /** While the pen writes, one tile at a time. */
    private val writingGate = Any()
    /** Stands for a tile with no ink: nothing to draw, nothing to hold. */
    private val empty: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    /** Paints [job] on a worker; [done] gets the picture, or null if it could not be made. Not called once cancelled. */
    fun paint(job: Job, done: (Bitmap?) -> Unit) {
        pool.execute {
            if (job.cancelled) return@execute
            if (job.strokes.isEmpty()) { done(empty); return@execute }
            val picture = if (InteractionGovernor.writing) synchronized(writingGate) { paintNow(job) } else paintNow(job)
            if (!job.cancelled) done(picture)
        }
    }

    private fun paintNow(job: Job): Bitmap? {
        if (job.cancelled) return null
        val started = System.nanoTime()
        val bitmap = runCatching { Bitmap.createBitmap(job.size, job.size, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        try {
            val canvas = Canvas(bitmap)
            val transform = Matrix().apply {
                setScale(job.scale, job.scale)
                postTranslate(job.dx, job.dy)
            }
            canvas.concat(transform)
            val renderer = renderers.get()!!
            drawLayered(canvas, job.strokes, job.look, null) { stroke ->
                if (!job.cancelled) renderer.draw(canvas, job.twins[stroke] ?: stroke, transform)
            }
        } catch (_: Throwable) {
            bitmap.recycle()
            return null
        }
        if (job.cancelled) { bitmap.recycle(); return null }
        // Into graphics memory here, on this thread: drawn as a plain bitmap, the
        // upload would be the render thread's, in the frame that first shows it.
        val shown = runCatching { bitmap.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
        val result = if (shown != null) { bitmap.recycle(); shown } else bitmap.also { it.prepareToDraw() }
        WorkStats.tileRasterNanos.addAndGet(System.nanoTime() - started)
        return result
    }
}
