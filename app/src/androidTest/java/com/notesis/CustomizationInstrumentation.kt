package com.notesis

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import java.io.File

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
            store.delete(note.id)
            bitmap.recycle()
            changedBitmap.recycle()
            output.putString("stream", "PASS preferences, text rendering, insertion/edit/undo/redo, autosave/reload, PDF export/preview\n")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
