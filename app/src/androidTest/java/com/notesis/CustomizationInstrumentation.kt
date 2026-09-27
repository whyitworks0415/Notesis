package com.notesis

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.ink.brush.Brush
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import java.io.File
import kotlin.math.sin

/** Native ink and Android text rendering cannot be exercised by plain JVM tests. */
class CustomizationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    override fun onStart() {
        val output = Bundle()
        try {
            val isolated = object : ContextWrapper(targetContext) {
                override fun getFilesDir(): File = File(targetContext.cacheDir, "customization-test").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("customization-test-$name", mode)
            }
            isolated.getSharedPreferences("pens", Context.MODE_PRIVATE).edit().clear().commit()
            val pens = PenStore(isolated)
            pens.saveFavoriteWidths(EditMode.PEN, listOf(0.25f, 1.5f, 3f))
            pens.saveColorTemplates(listOf("수학" to listOf(Color.BLUE, Color.RED)))
            pens.toolbarSize = 2
            pens.homeColor = Color.BLUE
            val reloaded = PenStore(isolated)
            check(reloaded.favoriteWidths(EditMode.PEN) == listOf(0.25f, 1.5f, 3f))
            check(reloaded.colorTemplates().single().second == listOf(Color.BLUE, Color.RED))
            check(reloaded.toolbarSize == 2 && reloaded.homeColor == Color.BLUE)
            val content = TextBoxContent("한글 텍스트\n수식 x² + y² = 1", 32f, Color.BLUE)
            val bitmap = renderTextBox(content)
            check(bitmap.width > 20 && bitmap.height > 20)
            check(bitmap.getPixel(0, 0) == Color.TRANSPARENT)
            val store = NoteStore(isolated)
            val note = store.create("텍스트 회귀 테스트")
            val doc = store.load(note.id)
            val added = store.addImage(note.id, bitmap) ?: error("image save")
            var canvas: InkCanvasView? = null
            onMain {
                canvas = InkCanvasView(targetContext.createDisplayContext(
                    targetContext.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0))).also {
                    it.open(doc, null)
                    it.putTextBox(added.first, bitmap.width, bitmap.height, content)
                    check(doc.pages[0].images.single().textContent == content)
                    it.undo()
                    check(doc.pages[0].images.isEmpty())
                    it.redo()
                    check(doc.pages[0].images.single().textContent == content)
                }
            }
            val before = doc.pages[0].images.single()
            val changed = content.copy(text = "수정한 텍스트", size = 40f, color = Color.RED)
            val changedBitmap = renderTextBox(changed)
            val replacement = store.addImage(note.id, changedBitmap) ?: error("replacement save")
            onMain { canvas!!.putTextBox(replacement.first, changedBitmap.width, changedBitmap.height, changed, before) }
            store.save(note.id, note.title, doc)
            check(store.imageFile(note.id, before.id).isFile) // autosave must preserve undo assets
            check(store.load(note.id).pages[0].images.single().textContent == changed)
            onMain {
                canvas!!.undo()
                check(doc.pages[0].images.single().textContent == content)
                canvas!!.redo()
                check(doc.pages[0].images.single().textContent == changed)
            }
            val exported = File(isolated.cacheDir, "customization-text.pdf")
            check(exported.outputStream().use { store.exportPdf(note.id, it) })
            PdfSource.open(exported)?.use { pdf ->
                val preview = pdf.renderSelection(PdfSelection(0, "錯誤", listOf(RectF(0f, 0f, 1240f, 1754f))))
                check(preview != null && preview.width > 0)
                preview.recycle()
            } ?: error("PDF roundtrip")
            verifyIntermittentContactSpurs()
            verifyStationaryPenStarts()
            verifyPenAntialiasing()
            verifyVectorStrokeOverlap(store, isolated)
            store.delete(note.id)
            bitmap.recycle()
            changedBitmap.recycle()
            output.putString("stream", "PASS preferences, text rendering, insertion/edit/undo/redo, autosave/reload, intermittent pen contact, stationary pen starts, pen antialiasing, PDF export/preview and stroke overlap\n")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private fun verifyIntermittentContactSpurs() {
        val brush = Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), Color.BLACK, 5f, 0.02f)
        fun stroke(points: List<Pair<Float, Float>>, stepMs: Long = 8L): Stroke {
            val inputs = MutableStrokeInputBatch()
            points.forEachIndexed { index, (x, y) ->
                inputs.add(InputToolType.STYLUS, x + 100f, y + 100f, index * stepMs)
            }
            return Stroke(brush, inputs.toImmutable())
        }
        fun times(stroke: Stroke): List<Long> {
            val sample = StrokeInput()
            return (0 until stroke.inputs.size).map { index ->
                stroke.inputs.populate(index, sample).elapsedTimeMillis
            }
        }
        val early = listOf(0f to 0f, 3f to 0f, 0.3f to 0f,
            1.2f to 0f, 2.2f to 0f, 3.2f to 0f, 4.2f to 0f)
        val later = listOf(0f to 0f, 1f to 0f, 2f to 0f, 4.8f to 0f,
            2.2f to 0f, 3.2f to 0f, 4.2f to 0f)
        for (points in listOf(early, later)) {
            val expectedRemoved = if (points === early) 8L else 24L
            for (rotated in listOf(
                points,
                points.map { it.second to it.first },
                points.map { -it.first to it.second },
                points.map { it.second to -it.first },
            )) {
                val original = stroke(rotated)
                val cleaned = withoutContactSpurs(original)
                check(cleaned.inputs.size == original.inputs.size - 1)
                check(expectedRemoved !in times(cleaned))
                check(times(cleaned).first() == 0L && times(cleaned).last() == 48L)
            }
        }
        val atLift = stroke(listOf(0f to 0f, 1f to 0f, 2f to 0f,
            3f to 0f, 4f to 0f, 5f to 0f, 6f to 0f, 9f to 0f, 6.3f to 0f))
        val cleanLift = withoutContactSpurs(atLift)
        check(cleanLift.inputs.size == atLift.inputs.size - 1)
        check(56L !in times(cleanLift))
        val intentionalCorner = stroke(listOf(0f to 0f, 3f to 0f, 3f to 3f,
            3f to 6f, 4f to 8f))
        check(withoutContactSpurs(intentionalCorner) === intentionalCorner)
        val slowReversal = stroke(early, 50L)
        check(withoutContactSpurs(slowReversal) === slowReversal)
    }

    private fun verifyStationaryPenStarts() {
        val brush = Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), Color.BLUE, 1.4f, 0.00625f)
        for (stationaryCount in listOf(12, 28)) {
            val inputs = MutableStrokeInputBatch()
            inputs.add(InputToolType.STYLUS, 100f, 100f, 0L)
            for (index in 1 until stationaryCount) {
                inputs.add(
                    InputToolType.STYLUS,
                    100f + (index % 3 - 1) * 0.015f,
                    100f + (index % 4 - 2) * 0.012f,
                    index * 2L,
                )
            }
            inputs.add(InputToolType.STYLUS, 100.25f, 100f, stationaryCount * 2L)
            inputs.add(InputToolType.STYLUS, 101f, 100.3f, (stationaryCount + 1) * 2L)
            val original = Stroke(brush, inputs.toImmutable())
            val clean = withoutStationaryStart(original)
            check(clean.inputs.size == 3)
            val point = StrokeInput()
            clean.inputs.populate(0, point)
            check(point.x == 100f && point.y == 100f && point.elapsedTimeMillis == 0L)
            clean.inputs.populate(1, point)
            check(point.x == 100.25f && point.elapsedTimeMillis == stationaryCount * 2L)
            check(withoutStationaryStart(clean) === clean)
        }
    }

    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun verifyPenAntialiasing() {
        for (tool in listOf(Tool.PEN, Tool.PRESSURE_PEN)) {
            val family = tool.brushFamily()
            val paints = family.coats.first().paintPreferences
            check(paints.isNotEmpty() && paints.all {
                it.selfOverlap == SelfOverlap.DISCARD
            })
            val brush = Brush.createWithColorIntArgb(family, Color.BLACK, 3f, 0.02f)
            val inputs = MutableStrokeInputBatch().apply {
                add(InputToolType.STYLUS, 15.4f, 20.6f, 0L)
                add(InputToolType.STYLUS, 70.5f, 59.2f, 12L)
                add(InputToolType.STYLUS, 105.3f, 94.7f, 24L)
            }
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            try {
                CanvasStrokeRenderer.create(PencilTextureStore).draw(
                    Canvas(bitmap), Stroke(brush, inputs.toImmutable()), Matrix(),
                )
                var solid = false
                var antialiased = false
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    when (Color.alpha(bitmap.getPixel(x, y))) {
                        255 -> solid = true
                        in 1..254 -> antialiased = true
                    }
                }
                check(solid && antialiased) { "$tool did not produce an antialiased edge" }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun verifyVectorStrokeOverlap(store: NoteStore, context: Context) {
        val note = store.create("벡터 잉크 겹침 회귀 테스트")
        try {
            val document = store.load(note.id)
            val page = document.pages.single()
            page.loaded = true
            page.dirty = true
            fun stroke(tool: Tool, color: Int, y: Float): Stroke {
                val samples = MutableStrokeInputBatch()
                listOf(200f, 400f, 200f, 400f).forEachIndexed { index, x ->
                    samples.add(InputToolType.STYLUS, x, y, index * 8L)
                }
                return Stroke(
                    Brush.createWithColorIntArgb(tool.brushFamily(), color, 20f, 0.02f),
                    samples.toImmutable(),
                )
            }
            page.strokes += stroke(Tool.PEN, Color.BLACK, 200f)
            page.strokes += stroke(Tool.HIGHLIGHTER, 0x99FFCC00.toInt(), 300f)
            page.masks += PageMask(stroke(Tool.MASK, 0xFF8855CC.toInt(), 400f))
            val curve = MutableStrokeInputBatch()
            for (index in 0..16) {
                curve.add(
                    InputToolType.STYLUS,
                    200f + index * 12.5f,
                    600f + 35f * sin(index * Math.PI / 16.0).toFloat(),
                    index * 8L,
                )
            }
            page.strokes += Stroke(
                Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), Color.BLUE, 8f, 0.02f),
                curve.toImmutable(),
            )
            store.save(note.id, note.title, document)
            val output = File(context.cacheDir, "vector-overlap-regression.pdf")
            check(output.outputStream().use { store.exportPdf(note.id, it) })
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    pdf.openPage(0).use { rendered ->
                        val bitmap = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
                        try {
                            rendered.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            listOf(200, 300, 400).forEach { y ->
                                val pixel = bitmap.getPixel(300, y)
                                check(Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) < 680) {
                                    "Exported $y ink overlap is blank: ${Integer.toHexString(pixel)}"
                                }
                            }
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally { store.delete(note.id) }
    }
}
