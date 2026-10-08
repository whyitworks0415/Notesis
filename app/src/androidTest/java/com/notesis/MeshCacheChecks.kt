package com.notesis

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.sin

/** Compares cached pixels with the former full-page mesh display list on the GPU. */
internal fun Instrumentation.checkMeshCache(strokeCount: Int = 3000) {
    val report = StringBuilder()
    val page = Page(width = 2048f, height = 3072f)
    fun stroke(x: Float, y: Float, highlight: Boolean = false): Stroke {
        val inputs = MutableStrokeInputBatch().apply {
            repeat(24) { add(InputToolType.STYLUS, x + it * 1.9f,
                y + sin(it * 0.42f) * 6f, it * 4L) }
        }
        return Stroke(Brush.createWithColorIntArgb(
            if (highlight) Tool.HIGHLIGHTER.brushFamily() else Tool.PEN.brushFamily(),
            if (highlight) 0x66FFD740 else Color.rgb(20, 40, 100),
            if (highlight) 22f else 2.5f, 0.025f), inputs.toImmutable())
    }
    repeat(strokeCount) { i ->
        page.strokes += stroke(15f + (i % 32) * 62f, 22f + (i / 32) * 24f)
    }
    // Cross every tile seam, with overlapping translucent ink on both sides.
    page.strokes += stroke(495f, 512f)
    page.strokes += stroke(500f, 514f, highlight = true)
    page.strokes += stroke(515f, 516f, highlight = true)
    page.strokes += stroke(1020f, 1024f)
    page.revision++
    val twins = IdentityHashMap<Stroke, Stroke>()
    fun screen(stroke: Stroke): Stroke = twins.getOrPut(stroke) {
        val family = Tool.meshFamilyOf(stroke.brush.family)
        if (family == null || Color.alpha(stroke.brush.colorIntArgb) != 255) stroke
        else stroke.copy(stroke.brush.copy(family = family))
    }
    val query: (Page, Float, Float, Float, Float) -> List<Stroke> = { p, left, top, right, bottom ->
        p.strokes.filter {
            val box = it.shape.computeBoundingBox()
            box != null && box.xMax >= left && box.xMin <= right && box.yMax >= top && box.yMin <= bottom
        }
    }
    var cache = InkTileCache(screenStroke = ::screen, query = query)
    val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
    val transform = Matrix()
    val multiply = Paint().apply { blendMode = BlendMode.MULTIPLY }
    val reference = RenderNode("former page ink")
    val root = RenderNode("mesh cache check")
    val referenceRoot = RenderNode("mesh reference check")
    val width = 1024
    val height = 768
    root.setPosition(0, 0, width, height)
    referenceRoot.setPosition(0, 0, width, height)
    val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    val hardware = HardwareRenderer().apply { setSurface(reader.surface); setContentRoot(root) }
    val referenceReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    val referenceHardware = HardwareRenderer().apply {
        setSurface(referenceReader.surface); setContentRoot(referenceRoot)
    }
    var scale = 1f
    var panX = 0f
    var panY = 0f
    var above = false
    var interacting = false
    var consolidate = true
    var excluded: Set<Stroke>? = null

    fun recordReference() {
        val w = kotlin.math.ceil(page.width * scale).toInt()
        val h = kotlin.math.ceil(page.height * scale).toInt()
        reference.setPosition(0, 0, w, h)
        val canvas = reference.beginRecording(w, h)
        transform.setScale(scale, scale)
        try {
            canvas.concat(transform)
            val family = Tool.HIGHLIGHTER.brushFamily()
            for (pass in 0..1) {
                val highlight = if (pass == 0) !above else above
                val layer = if (highlight) canvas.saveLayer(0f, 0f, page.width, page.height, multiply) else -1
                for (stroke in page.strokes) {
                    if (excluded?.contains(stroke) == true) continue
                    if ((stroke.brush.family == family) == highlight) renderer.draw(canvas, screen(stroke), transform)
                }
                if (layer >= 0) canvas.restoreToCount(layer)
            }
        } finally { reference.endRecording() }
    }

    fun frame(cached: Boolean, capture: Boolean = false,
        preview: ((Canvas) -> Unit)? = null): Pair<Long, Bitmap?> {
        val start = System.nanoTime()
        val outputRoot = if (cached) root else referenceRoot
        val outputHardware = if (cached) hardware else referenceHardware
        val outputReader = if (cached) reader else referenceReader
        val canvas = outputRoot.beginRecording(width, height)
        try {
            canvas.drawColor(Color.WHITE)
            canvas.translate(-panX * scale, -panY * scale)
            if (preview != null) preview(canvas)
            else if (cached) {
                cache.beginFrame()
                canvas.scale(scale, scale)
                cache.draw(canvas, page, scale, RectF(panX, panY,
                    minOf(page.width, panX + width / scale), minOf(page.height, panY + height / scale)),
                    interacting, consolidate, hasHighlighter = true, highlighterAbove = above,
                    multiply = multiply, excluded = excluded)
                cache.endFrame()
            } else canvas.drawRenderNode(reference)
        } finally { outputRoot.endRecording() }
        outputHardware.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        var rendered = outputReader.acquireNextImage()
        repeat(50) { if (rendered == null) { Thread.sleep(2); rendered = outputReader.acquireNextImage() } }
        val elapsed = System.nanoTime() - start
        val image = rendered ?: error("Mesh cache GPU frame missing")
        val bitmap = image.use { value ->
            if (!capture) null else {
                val buffer = value.hardwareBuffer ?: error("No GPU buffer")
                buffer.use {
                    val wrapped = Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB))!!
                    wrapped.copy(Bitmap.Config.ARGB_8888, false).also { wrapped.recycle() }
                }
            }
        }
        return elapsed to bitmap
    }

    fun compare(label: String) {
        recordReference()
        val expected = frame(false, true).second!!
        val actual = frame(true, true).second!!
        val a = IntArray(width * height)
        val b = IntArray(a.size)
        expected.getPixels(a, 0, width, 0, 0, width, height)
        actual.getPixels(b, 0, width, 0, 0, width, height)
        var different = 0
        var inkPixels = 0
        for (i in a.indices) {
            if (a[i] != Color.WHITE || b[i] != Color.WHITE) inkPixels++
            if (abs(Color.red(a[i]) - Color.red(b[i])) > 20 ||
                abs(Color.green(a[i]) - Color.green(b[i])) > 20 ||
                abs(Color.blue(a[i]) - Color.blue(b[i])) > 20) different++
        }
        report.appendLine("$label: different=$different / ink=$inkPixels ${cache.stats()}")
        if (label == "initial" || different >= maxOf(80, inkPixels / 25)) {
            val name = label.replace(' ', '-')
            File(targetContext.cacheDir, "mesh-cache-$name-reference.png").outputStream().use {
                expected.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(targetContext.cacheDir, "mesh-cache-$name-actual.png").outputStream().use {
                actual.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        expected.recycle(); actual.recycle()
        check(different < maxOf(80, inkPixels / 25)) { "$label changed mesh/highlighter pixels: $different/$inkPixels" }
    }

    try {
        compare("initial")
        val recorded = cache.stats().recordedStrokes
        repeat(6) { panY = it * 3f; frame(true) }
        check(cache.stats().recordedStrokes == recorded) { "Pan replay re-recorded existing strokes" }
        fun benchmark(cached: Boolean): String {
            repeat(5) { frame(cached) }
            val values = LongArray(36) { i -> panY = (i % 6) * 3f; frame(cached).first }.sorted()
            return "p50=${values[17] / 1e6}ms p95=${values[33] / 1e6}ms"
        }
        report.appendLine("$strokeCount strokes, former full-page GPU frames: ${benchmark(false)}")
        report.appendLine("$strokeCount strokes, cached tile GPU frames: ${benchmark(true)}")
        report.appendLine("after benchmark: ${cache.stats()}")
        panY = 0f
        consolidate = false
        page.strokes += stroke(50f, 3000f)
        page.revision++
        val before = cache.stats().recordedStrokes
        frame(true)
        check(cache.stats().recordedStrokes == before) { "Off-screen append rebuilt visible tiles" }
        page.strokes += stroke(470f, 512f)
        page.revision++
        compare("append delta")
        check(cache.stats().recordedStrokes - before <= 4) { "Pen-up re-recorded dense base ink" }
        panX = 600f; panY = 2850f
        frame(true)
        page.strokes += stroke(650f, 2900f)
        page.revision++
        compare("empty tile append")
        panX = 0f; panY = 1000f
        compare("pan away")
        panX = 600f; panY = 2850f
        compare("return empty tile delta")
        panX = 0f; panY = 0f
        consolidate = true
        compare("append consolidated")
        page.strokes.removeAt(0)
        page.revision++
        val beforeErase = cache.stats().recordedStrokes
        compare("erase")
        check(cache.stats().recordedStrokes - beforeErase < strokeCount / 4) { "Local erase rebuilt unrelated tiles" }
        excluded = setOf(page.strokes[0], page.strokes[1], page.strokes[2])
        compare("lasso lifted")
        val beforeLasso = cache.stats().recordedStrokes
        repeat(4) { frame(true) }
        check(cache.stats().recordedStrokes == beforeLasso) { "Lasso movement rebuilt background ink" }
        excluded = null
        compare("lasso restored")
        val middle = page.strokes.size / 2
        page.strokes[middle] = stroke(300f, 450f)
        page.meshRevision++
        compare("mesh replacement")
        above = true
        compare("highlighter above")
        above = false
        scale = 1.37f
        panX = 230f; panY = 170f
        compare("fractional zoom and pan")
        scale = 8f
        panX = 493f; panY = 502f
        compare("deep zoom")
        interacting = true
        consolidate = false
        scale = 1f
        panX = 0f; panY = 0f
        compare("deep zoom out during pinch")
        interacting = false
        consolidate = true
        compare("deep zoom out settled")
        check(cache.stats().layerBytes <= 48 * 1024 * 1024) { "Tile cache exceeded memory budget" }
        repeat(3) { cycle ->
            interacting = false
            scale = 16f
            panX = 493f; panY = 502f
            compare("repeat deep zoom $cycle")
            interacting = true
            scale = 0.5f
            panX = 0f; panY = 0f
            compare("repeat deep zoom out $cycle")
        }
        interacting = false
        scale = 1f
        // Even when visible ink itself exceeds a small cache budget, the GPU
        // must retain every node referenced by this frame, not just the LRU tail.
        cache.clear()
        cache = InkTileCache(byteBudget = 2 * 1024 * 1024, screenStroke = ::screen, query = query)
        compare("visible tiles under memory pressure")
        val budgetFrameRecordings = cache.stats().recordings
        repeat(3) { frame(true) }
        check(cache.stats().recordings == budgetFrameRecordings) { "Visible tiles thrashed under memory pressure" }
        cache.clear()
        check(cache.stats().tiles == 0 && cache.stats().layerBytes == 0)
        for (previewScale in listOf(1f, 8f, 0.5f)) {
            lateinit var previewView: CompatWetInkView
            runOnMainSync {
                previewView = CompatWetInkView(targetContext).apply { layout(0, 0, width, height) }
                val toPage = Matrix().apply {
                    setScale(1f / previewScale, 1f / previewScale)
                    postTranslate(-20f, -30f)
                }
                val brush = Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), Color.BLUE, 2.5f, 0.025f)
                val down = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN,
                    400f, 300f, 0)
                val up = android.view.MotionEvent.obtain(0L, 20L, android.view.MotionEvent.ACTION_UP,
                    450f, 330f, 0)
                try {
                    previewView.start(down, 0, brush, toPage)
                    previewView.finish(up, 0)
                } finally { down.recycle(); up.recycle() }
            }
            val preview = frame(true, true) { canvas -> runOnMainSync { previewView.draw(canvas) } }.second!!
            try {
                val pixel = preview.getPixel(425, 315)
                check(Color.red(pixel) + Color.green(pixel) < 450) {
                    "Compatible wet ink missed screen coordinates at ${previewScale}x: ${Integer.toHexString(pixel)}"
                }
                report.appendLine("compatible wet ink ${previewScale}x: screen-position pixel=${Integer.toHexString(pixel)}")
            } finally {
                preview.recycle()
                runOnMainSync { previewView.clear() }
            }
        }
    } finally {
        cache.clear()
        hardware.destroy()
        referenceHardware.destroy()
        reader.close()
        referenceReader.close()
        reference.discardDisplayList()
        root.discardDisplayList()
        referenceRoot.discardDisplayList()
        File(targetContext.cacheDir, "mesh-cache-performance.txt").writeText(report.toString())
    }
}
