@file:Suppress("RestrictedApi")
package com.notesis

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import java.util.concurrent.Executors

internal object InkRenderingRegression {
    fun run(instrumentation: Instrumentation) {
        fun onMain(action: () -> Unit) {
            var error: Throwable? = null
            instrumentation.runOnMainSync { try { action() } catch (failure: Throwable) { error = failure } }
            error?.let { throw it }
        }
        fun stroke(y: Float, highlighter: Boolean): Stroke {
            val inputs = MutableStrokeInputBatch()
            inputs.add(InputToolType.STYLUS, 20f, y, 0L, pressure = 1f)
            inputs.add(InputToolType.STYLUS, 1000f, y, 100L, pressure = 1f)
            return Stroke(Brush.createWithColorIntArgb(
                (if (highlighter) Tool.HIGHLIGHTER else Tool.PEN).brushFamily(),
                if (highlighter) 0x66FFFF00 else Color.BLACK, 24f, .1f), inputs.toImmutable())
        }
        val page = Page(width = 1024f, height = 1024f, strokes = mutableListOf(stroke(128f, true), stroke(256f, false)))
        val executor = Executors.newSingleThreadExecutor()
        lateinit var renderer: InkTileRenderer
        onMain { renderer = InkTileRenderer(executor, 16 * 1024 * 1024) {} }
        fun render(minimumEdit: Long): Bitmap {
            var bitmap: Bitmap? = null
            val deadline = System.nanoTime() + 30_000_000_000L
            while (bitmap == null && System.nanoTime() < deadline) {
                onMain {
                    renderer.beginFrame()
                    val plan = renderer.plan(page, InkRect(0f, 0f, 1024f, 1024f), 1f, true, true)
                    renderer.finishFrame(listOf(plan), !plan.covered)
                    if (plan.covered && plan.sources.all { it.source.edit >= minimumEdit }) {
                        bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap!!); canvas.drawColor(Color.WHITE)
                        renderer.draw(canvas, plan, InkLayer.HIGHLIGHTER)
                        canvas.drawRect(400f, 100f, 600f, 300f, Paint().apply { color = Color.RED })
                        renderer.draw(canvas, plan, InkLayer.PEN)
                    }
                }
                if (bitmap == null) Thread.sleep(20)
            }
            return checkNotNull(bitmap) { "Ink coverage timed out" }
        }
        try {
            val initial = render(0)
            check(initial.getPixel(500, 128) == Color.RED) { "Highlighter must remain below image/text" }
            check(Color.red(initial.getPixel(500, 256)) < 50) { "Pen must remain above image/text" }
            check(initial.getPixel(510, 128) == initial.getPixel(514, 128)) { "Tile boundary seam" }
            val before = initial.getPixel(300, 128)
            onMain { page.strokes += stroke(512f, true); renderer.changed(page) }
            val added = render(1)
            check(added.getPixel(300, 128) == before) { "Existing highlighter alpha changed" }
            check(added.getPixel(300, 512) != Color.WHITE) { "Append disappeared" }
            onMain { page.strokes.removeAt(0); renderer.changed(page) }
            val removed = render(2)
            check(removed.getPixel(300, 128) == Color.WHITE) { "Deletion left stale ink" }
            check(removed.getPixel(300, 512) == added.getPixel(300, 512)) { "Deletion damaged overlapping layer coverage" }
            initial.recycle(); added.recycle(); removed.recycle()
        } finally { onMain { renderer.close() }; executor.shutdownNow() }
    }
}
