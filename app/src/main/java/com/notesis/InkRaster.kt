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

/**
 * Whether [stroke] needs the GPU to be pictured at all: the pencil's graphite
 * and the particle brushes - the dashed lines among them - come out blank on a
 * bitmap's own canvas. The pens, nibs, highlighter and tape do not; anything
 * not known to be one of them is taken to need it.
 */
@OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)
internal fun needsGpuPicture(stroke: Stroke): Boolean {
    val id = stroke.brush.family.clientBrushFamilyId
    // A nib's angle is written after the family's own id; see InkCanvasView's NIB_MARK.
    return CPU_SAFE_FAMILIES.none { id == it || id.startsWith("$it.") || id.startsWith("$it@") }
}

private val CPU_SAFE_FAMILIES = listOf(
    "notesis.pen", "notesis.pressure-pen", "notesis.vanishing", "notesis.fountain", "notesis.calligraphy",
    "notesis.watercolor", "notesis.oil", "notesis.highlighter", "notesis.mask",
)

/**
 * A [width] x [height] picture of what [draw] puts on a canvas, drawn by the
 * GPU as the page on screen is: Ink paints the pencil's graphite and the
 * particle brushes only on a hardware canvas, and on a bitmap's own canvas
 * they came out blank. [draw] gets the canvas and a list to hand bitmaps it
 * drew that may be recycled once the picture is taken - not before: the GPU
 * reads them only then. Null where the GPU could not produce it; the caller
 * then draws on a bitmap of its own.
 */
internal fun renderOnGpu(width: Int, height: Int, draw: (Canvas, MutableList<Bitmap>) -> Unit): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val spent = ArrayList<Bitmap>()
    val reader = runCatching {
        android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 1,
            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    }.getOrNull() ?: return null
    val node = android.graphics.RenderNode("capture")
    val renderer = android.graphics.HardwareRenderer()
    return try {
        node.setPosition(0, 0, width, height)
        val canvas = node.beginRecording(width, height)
        try {
            draw(canvas, spent)
        } finally {
            node.endRecording()
        }
        renderer.setSurface(reader.surface)
        renderer.setContentRoot(node)
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireNextImage()
        try {
            val buffer = image?.hardwareBuffer
            try {
                val shared = buffer?.let {
                    Bitmap.wrapHardwareBuffer(it, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                }
                // Mutable, so a caller can still draw over it - the export's inversion does.
                val copy = shared?.copy(Bitmap.Config.ARGB_8888, true)
                shared?.recycle()
                copy
            } finally {
                buffer?.close()
            }
        } finally {
            image?.close()
        }
    } catch (error: Throwable) {
        null
    } finally {
        renderer.destroy()
        node.discardDisplayList()
        reader.close()
        spent.forEach { it.recycle() }
    }
}
