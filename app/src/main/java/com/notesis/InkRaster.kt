package com.notesis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

/** The matrix informs Ink's tessellation; Canvas must apply it separately. */
internal fun rasterizeInk(width: Int, height: Int, scale: Float, strokes: List<Stroke>,
    cancelled: () -> Boolean = { false }): Bitmap? {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        val transform = Matrix().apply { setScale(scale, scale) }
        canvas.concat(transform)
        val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
        for (stroke in strokes) {
            if (cancelled()) { bitmap.recycle(); return null }
            renderer.draw(canvas, stroke, transform)
        }
        return bitmap
    } catch (error: Throwable) {
        bitmap.recycle()
        throw error
    }
}
