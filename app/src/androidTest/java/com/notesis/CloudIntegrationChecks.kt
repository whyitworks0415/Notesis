package com.notesis

import android.app.Instrumentation
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import java.io.File

/** Exercises the new editing paths together with the local v2 storage format. */
internal fun Instrumentation.checkCloudIntegration(store: NoteStore, context: Context) {
    fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    val note = store.create("클라우드 통합 회귀 검사")
    try {
        val document = store.load(note.id)
        val page = document.pages.single().apply { loaded = true; dirty = true }
        val inputs = MutableStrokeInputBatch().apply {
            repeat(21) { add(InputToolType.STYLUS, 100f + it * 10f, 200f, it * 8L) }
        }
        val stroke = Stroke(Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), Color.BLACK,
            4f, 0.02f), inputs.toImmutable())
        page.strokes += stroke
        for (tool in listOf(Tool.PEN, Tool.PRESSURE_PEN)) {
            val stored = tool.brushFamily()
            val mesh = Tool.meshFamilyOf(stored) ?: error("Missing mesh family for $tool")
            check(mesh != Tool.HIGHLIGHTER.brushFamily())
            check(Tool.storedFamilyOf(mesh) == stored)
            check(Tool.meshFamilyOf(Tool.HIGHLIGHTER.brushFamily()) == null)
        }

        onMain {
            val display = targetContext.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
            val view = InkCanvasView(targetContext.createDisplayContext(display))
            view.open(document, null)
            view.layout(0, 0, 1200, 1600)
            // Supply a selection independently of touch recognition, then check edit/undo behavior.
            InkCanvasView::class.java.getDeclaredField("lassoPage").apply { isAccessible = true }.setInt(view, 0)
            @Suppress("UNCHECKED_CAST")
            val selection = InkCanvasView::class.java.getDeclaredField("lassoStrokes").apply { isAccessible = true }
                .get(view) as MutableCollection<Stroke>
            selection += stroke
            val bounds = InkCanvasView::class.java.getDeclaredField("lassoBounds").apply { isAccessible = true }
                .get(view) as RectF
            bounds.set(98f, 198f, 302f, 202f)
            view.transformLassoSelection(2f, 0f)
            check(page.strokes.single().brush.size == 8f)
            view.undo()
            check(page.strokes.single() === stroke)
            view.redo()
            check(page.strokes.single().brush.size == 8f)
            // Undo/redo deliberately dismisses selections; select the restored ink again.
            InkCanvasView::class.java.getDeclaredField("lassoPage").apply { isAccessible = true }.setInt(view, 0)
            selection += page.strokes.single()
            bounds.set(-4f, 196f, 404f, 204f)
            view.recolorLassoSelection(Color.RED)
            check(page.strokes.single().brush.colorIntArgb == Color.RED)
            view.duplicateLassoSelection()
            check(page.strokes.size == 2)
            view.undo()
            check(page.strokes.size == 1)
            view.redo()
            check(page.strokes.size == 2)

            view.clearLassoSelection()
            page.strokes.clear(); page.strokes += stroke; page.revision++
            view.refreshSharedInk()
            val worldToScreen = Matrix()
            (InkCanvasView::class.java.getDeclaredField("screenToDocument").apply { isAccessible = true }
                .get(view) as Matrix).invert(worldToScreen)
            val point = floatArrayOf(200f, 200f)
            worldToScreen.mapPoints(point)
            fun tap() {
                val start = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                    val coords = MotionEvent.PointerCoords().apply { x = point[0]; y = point[1]; pressure = 0.5f }
                    val event = MotionEvent.obtain(start, start + if (action == MotionEvent.ACTION_UP) 16 else 0,
                        action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            view.tool = Tool.ERASER; view.eraserWidth = 20f; view.partialEraser = true
            tap()
            check(page.strokes.size == 2) { "Partial eraser did not leave two stroke pieces" }
            view.undo()
            check(page.strokes.single() === stroke) { "Partial erase undo did not restore the original stroke" }
            view.redo()
            check(page.strokes.size == 2)
            view.laserMode = true
            val beforeLaser = page.strokes.toList()
            tap()
            check(page.strokes == beforeLaser) { "Laser pointer changed saved ink" }
            view.laserMode = false
            view.duplicatePage(0)
            check(document.pages.size == 2 && document.pages[0].id != document.pages[1].id)
            check(document.pages[1].strokes.size == 2)
        }

        store.save(note.id, note.title, document)
        val restored = store.load(note.id)
        check(restored.pages.size == 2)
        check(restored.pages.all { store.loadPage(note.id, it, 0.02f).size == 2 })
        val text = File(context.filesDir, "notes/${note.id}/pages/${page.id}.txt")
        text.writeText("통합검색키워드 주변 문장")
        val hit = store.searchWithPages("통합검색키워드").single { it.first.id == note.id }.second.single()
        check(hit.pageIndex == 0 && hit.snippet.contains("통합검색키워드"))
        store.setFavorite(note.id, true)
        check(store.list().single { it.id == note.id }.favorite)
        store.moveToTrash(note.id)
        check(store.list().none { it.id == note.id })
        check(store.trashed().any { it.id == note.id })
        store.restoreFromTrash(note.id)
        check(store.list().any { it.id == note.id && it.favorite })

        val study = MaskStudy(store.studyFile(note.id))
        val key = MaskStudy.keyOf(page.id, stroke)
        study.rename(key, "복습 항목"); study.answer(key, false)
        check(MaskStudy(store.studyFile(note.id)).record(key).let { it.name == "복습 항목" && it.wrong == 1 })
        val audio = File(context.filesDir, "notes/${note.id}/recordings/check.m4a").apply { parentFile?.mkdirs() }
        RecordingTimeline.save(audio, listOf(RecordingTimeline.Event(key, 350L)))
        check(RecordingTimeline.load(audio).single().atMs == 350L)
        check(RecordingTimeline.around(RecordingTimeline.load(audio), 400L, 100L).size == 1)

        val pdfFile = File(context.filesDir, "notes/${note.id}/links.pdf")
        PDDocument().use { pdf ->
            val source = PDPage(PDRectangle(100f, 200f)).apply { rotation = 90 }
            val target = PDPage(PDRectangle(100f, 200f))
            pdf.addPage(source); pdf.addPage(target)
            source.annotations.add(PDAnnotationLink().apply {
                rectangle = PDRectangle(10f, 20f, 20f, 20f)
                action = PDActionGoTo().apply { setDestination(PDPageFitDestination().apply { this.page = target }) }
            })
            source.annotations.add(PDAnnotationLink().apply {
                rectangle = PDRectangle(60f, 100f, 20f, 20f)
                action = PDActionURI().apply { uri = "https://example.com/study" }
            })
            pdf.save(pdfFile)
        }
        PdfSource.open(pdfFile)?.use { pdf ->
            val scale = PdfSource.POINTS_TO_WORLD
            check(pdf.indexedLinkAt(0, 30f * scale, 20f * scale) == PdfLink.Page(1))
            check(pdf.indexedLinkAt(0, 110f * scale, 70f * scale) == PdfLink.Web("https://example.com/study"))
            check(pdf.linkedPageAt(0, 110f * scale, 70f * scale) == null)
        } ?: error("Portable PDF link fixture could not open")
    } finally { store.delete(note.id) }
}
