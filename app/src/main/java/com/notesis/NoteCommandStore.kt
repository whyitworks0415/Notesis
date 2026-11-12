package com.notesis

import android.content.Context
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import java.io.File
import java.util.UUID

/**
 * The note commands' workspace over the notes on disk (NoteCommands.kt).
 * [openNote] says which note is on screen now, so it is left alone.
 *
 * Undo copies of a note are its meta.json and page files, kept in the cache
 * for as long as this lives; a new one starts with none.
 */
internal class NoteStoreWorkspace(
    private val context: Context,
    private val store: NoteStore,
    private val openNote: () -> String?,
) : NoteWorkspace {
    private val kept = File(context.cacheDir, "command-undo").apply { deleteRecursively(); mkdirs() }

    override fun notes(): List<NoteMeta> = store.list()
    override fun trashed(): List<NoteMeta> = store.trashed()
    override fun isOpen(id: String): Boolean = openNote() == id
    override fun load(id: String): Document = store.load(id)
    override fun save(id: String, title: String, document: Document) = store.save(id, title, document)
    override fun markdown(id: String): String? = runCatching { store.loadMarkdown(id) }.getOrNull()
    override fun rename(id: String, title: String): Boolean = store.renameNote(id, title)
    override fun setFolder(id: String, folder: String) = store.setFolder(id, folder)
    override fun setTags(id: String, tags: List<String>) = store.setTags(id, tags)
    override fun trash(id: String) = store.moveToTrash(id)
    override fun restore(id: String) = store.restoreFromTrash(id)

    override fun handwriting(id: String, page: Page): String? =
        store.inkIndexFile(id, page.id).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    override fun inkCount(id: String, page: Page): Int = if (page.loaded) page.allStrokes.size else page.savedStrokeCount

    override fun textBox(id: String, content: TextBoxContent): PageImage? {
        val bitmap = renderTextBox(content)
        return try {
            // Text boxes are drawn at twice their page size; see renderTextBox.
            store.addImage(id, bitmap)?.let { PageImage(it.first, 0f, 0f, bitmap.width / 2f, bitmap.height / 2f, content) }
        } finally {
            bitmap.recycle()
        }
    }

    /** Reads the page's strokes in, all of them in file order, so a save writes them back as they were. */
    private fun loaded(id: String, page: Page) {
        if (page.loaded) return
        val strokes = store.loadPage(id, page, STROKE_EPSILON)
        page.strokes.addAll(strokes)
        page.savedOnDisk = strokes.size
        page.loaded = true
    }

    override fun addInk(id: String, document: Document, page: Page, lines: List<List<FloatArray>>, color: Int, width: Float): Int {
        loaded(id, page)
        val brush = Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), color, width, STROKE_EPSILON)
        val layer = document.layers.target
        var made = 0
        for (line in lines) {
            if (line.isEmpty()) continue
            val inputs = MutableStrokeInputBatch()
            // Drawn at a steady hand speed, resting on corners, as shapes are; see shapeInputs.
            for (input in shapeInputs(line, budget = line.size * 3 + 8)) inputs.add(InputToolType.STYLUS, input.x, input.y, input.timeMs)
            val stroke = Stroke(brush, inputs.toImmutable())
            StrokeLayers.set(stroke, layer)
            page.strokes += stroke
            made++
        }
        if (made > 0) page.dirty = true
        return made
    }

    override fun eraseInk(id: String, document: Document, page: Page, area: FloatArray?): Int {
        loaded(id, page)
        // Ink on a hidden layer is not seen, so it is not what was meant.
        val gone = page.strokes.filter { stroke ->
            document.layers.isVisible(StrokeLayers.of(stroke)) && (area == null || stroke.shape.computeBoundingBox()?.let { box ->
                box.xMax >= area[0] && box.xMin <= area[2] && box.yMax >= area[1] && box.yMin <= area[3]
            } == true)
        }.toSet()
        if (gone.isEmpty()) return 0
        page.strokes.removeAll { it in gone }
        page.dirty = true
        return gone.size
    }

    override fun export(id: String, title: String, format: ExportKind, pages: List<Int>, withInk: Boolean): String? = runCatching {
        val dir = File(context.cacheDir, "shared/commands").apply { mkdirs() }
        when (format) {
            ExportKind.MD -> {
                val file = File(dir, chatFileName(title, "", "md"))
                val written = file.outputStream().use { out ->
                    if (store.list().firstOrNull { it.id == id }?.kind == NoteKind.MARKDOWN) store.exportMarkdown(id, out)
                    else store.exportInkAsMarkdown(id, title, out)
                }
                file.takeIf { written }
            }
            ExportKind.PDF -> {
                val document = store.load(id)
                val look = document.layers.translucentLook()
                val chosen = pages.map { document.pages[it] }
                val file = File(dir, chatFileName(title, if (pages.size == document.pages.size) "" else "${pages.size} pages", "pdf"))
                val written = file.outputStream().use { out ->
                    writeVectorPdf(context, store.pdfFile(id), chosen.map { page ->
                        if (!withInk) VectorPdfPage(page, emptyList(), emptyList())
                        else VectorPdfPage(page, document.layers.shownInOrder(store.loadPage(id, page, STROKE_EPSILON), StrokeLayers::of),
                            store.loadMasks(id, page, STROKE_EPSILON).map { it.stroke }, look)
                    }, { imageId -> store.imageFile(id, imageId) }, out)
                }
                file.takeIf { written }
            }
            ExportKind.PNG -> {
                val first = pages.first() + 1
                val last = pages.last() + 1
                var firstFile: File? = null
                val written = store.exportPng(id, PageExportOptions(first, last)) { number, png ->
                    val file = File(dir, chatFileName(title, "page $number", "png"))
                    if (firstFile == null) firstFile = file
                    file.outputStream().use { png.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                firstFile.takeIf { written }
            }
        }?.absolutePath
    }.getOrNull()

    override fun keep(id: String): Any {
        val copy = File(kept, UUID.randomUUID().toString()).apply { mkdirs() }
        val note = store.noteDir(id)
        File(note, "meta.json").takeIf { it.isFile }?.copyTo(File(copy, "meta.json"))
        File(note, "pages").takeIf { it.isDirectory }?.copyRecursively(File(copy, "pages"))
        return copy
    }

    override fun bring(id: String, kept: Any) {
        val copy = kept as File
        val note = store.noteDir(id)
        File(copy, "meta.json").takeIf { it.isFile }?.copyTo(File(note, "meta.json"), overwrite = true)
        val pages = File(note, "pages")
        pages.deleteRecursively()
        File(copy, "pages").takeIf { it.isDirectory }?.copyRecursively(pages, overwrite = true) ?: pages.mkdirs()
    }
}
