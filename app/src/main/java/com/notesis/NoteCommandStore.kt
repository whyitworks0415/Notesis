package com.notesis

import android.content.Context
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * The note commands' workspace over the notes on disk (NoteCommands.kt).
 * [isOpenNote] says which notes are on screen now, so they are left alone.
 *
 * Undo copies of a note are its meta.json and page files, kept in the cache
 * for as long as this lives; a new one starts with none.
 */
internal class NoteStoreWorkspace(
    private val context: Context,
    private val store: NoteStore,
    /** The notes on screen now, for `status`. */
    private val openNotes: () -> Set<String> = { emptySet() },
    /** Whether a note is on screen now (see [OpenNotes]), so it is read but not changed. Last, so a call can end with it as a lambda. */
    private val isOpenNote: (String) -> Boolean,
) : NoteWorkspace {
    private val kept = File(context.cacheDir, "command-undo").apply { deleteRecursively(); mkdirs() }

    override fun notes(): List<NoteMeta> = store.list()
    override fun trashed(): List<NoteMeta> = store.trashed()
    override fun isOpen(id: String): Boolean = isOpenNote(id)
    // The screen writes what it shows; what it wrote is on disk before anything is copied for undo or changed.
    override fun hold(id: String): Boolean = LiveNotes.hold(id).also { if (it) store.settled() }
    override fun letGo(id: String, changed: Boolean) {
        // Which pages' ink this batch rewrote, so the screen reads only those again; not known after an undo.
        val pages = if (id in inkUnknown) null else inkTouched[id].orEmpty()
        inkTouched.remove(id)
        inkUnknown.remove(id)
        LiveNotes.letGo(id, changed, pages)
    }

    private val inkTouched = HashMap<String, MutableSet<String>>()
    private val inkUnknown = HashSet<String>()
    // A note closed a moment ago may still be on its way to disk; what is read is what it was left as.
    override fun load(id: String): Document { fresh(id); return store.load(id) }

    /** A note on screen is read as it is on screen: its screen writes what is new first (LiveNotes). */
    private fun fresh(id: String) {
        if (isOpenNote(id)) LiveNotes.flush(id)
        store.settled()
    }
    override fun save(id: String, title: String, document: Document) {
        val touched = inkTouched.getOrPut(id) { HashSet() }
        for (page in document.pages) if (page.loaded && page.dirty) touched += page.id
        if (document.board.dirty) touched += Document.BOARD_PAGE_ID
        store.save(id, title, document)
    }
    override fun markdown(id: String): String? = runCatching { fresh(id); store.loadMarkdown(id) }.getOrNull()
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
        // The ink and tape off the pages live beside them, not among them.
        for (name in BOARD_FILES) File(note, name).takeIf { it.isFile }?.copyTo(File(copy, name))
        return copy
    }

    override fun release(kept: Any) { (kept as? File)?.deleteRecursively() }

    override fun bring(id: String, kept: Any) {
        inkUnknown += id
        val copy = kept as File
        val note = store.noteDir(id)
        File(copy, "meta.json").takeIf { it.isFile }?.copyTo(File(note, "meta.json"), overwrite = true)
        val pages = File(note, "pages")
        pages.deleteRecursively()
        File(copy, "pages").takeIf { it.isDirectory }?.copyRecursively(pages, overwrite = true) ?: pages.mkdirs()
        for (name in BOARD_FILES) {
            val kept = File(copy, name)
            if (kept.isFile) kept.copyTo(File(note, name), overwrite = true) else File(note, name).delete()
        }
    }

    // ---- for the agent commands (Todo.md Q1-Q5) ----

    override fun pdfOutline(id: String): List<PdfMark> = store.pdfOutline(id).map { PdfMark(it.title, it.pdfPage, it.level) }

    /**
     * The text of each PDF page: from the page's search text when it has some,
     * else read out of the PDF with PDFBox and kept there for next time (and for
     * search). Android itself gives PDF text only from Android 15, so on older
     * tablets the search text of a PDF page is empty and this is where it comes from.
     */
    override fun pdfText(id: String, pages: List<Page>): List<String?> {
        var document: PDDocument? = null
        try {
            return pages.map { page ->
                if (page.pdfPageIndex < 0) return@map null
                val file = File(store.noteDir(id), "pages/${page.id}.txt")
                file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }?.let { return@map it }
                val pdf = document ?: run {
                    val source = store.pdfFile(id).takeIf { it.isFile } ?: return@map null
                    PDFBoxResourceLoader.init(context)
                    PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).also { document = it }
                }
                if (page.pdfPageIndex >= pdf.numberOfPages) return@map null
                val text = runCatching {
                    PDFTextStripper().apply { startPage = page.pdfPageIndex + 1; endPage = page.pdfPageIndex + 1 }.getText(pdf)
                }.getOrNull().orEmpty()
                if (text.isNotBlank()) runCatching { file.parentFile?.mkdirs(); file.writeText(text) }
                text
            }
        } catch (error: Exception) {
            throw CommandError("The PDF could not be read: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { document?.close() }
        }
    }

    override fun pageImage(id: String, pageIndex: Int, width: Int): ByteArray? {
        var out: ByteArray? = null
        val written = store.exportPng(id, PageExportOptions(pageIndex + 1, pageIndex + 1)) { _, picture ->
            val height = (picture.height * width.toFloat() / picture.width).toInt().coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(picture, width, height, true)
            try {
                val bytes = ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
                out = bytes.toByteArray()
            } finally {
                if (scaled !== picture) scaled.recycle()
            }
            true
        }
        return out.takeIf { written }
    }

    override fun search(query: String, limit: Int): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        for ((meta, hits) in store.searchWithPages(query)) {
            if (hits.isEmpty()) out += SearchHit(meta.id, meta.title, -1, "")
            else hits.forEach { out += SearchHit(meta.id, meta.title, it.pageIndex, it.snippet) }
            if (out.size >= limit) break
        }
        return out.take(limit)
    }

    override fun saveMarkdown(id: String, title: String, text: String) = store.saveMarkdown(id, title, text)

    override fun createNote(title: String, folder: String, background: PageBackground, pages: Int): String? = runCatching {
        val meta = store.create(title, background)
        if (pages > 1) {
            val document = store.load(meta.id)
            val first = document.pages.first()
            repeat(pages - 1) { document.pages += Page(width = first.width, height = first.height, background = background) }
            store.save(meta.id, title, document)
        }
        if (folder.isNotEmpty()) store.setFolder(meta.id, folder)
        meta.id
    }.getOrNull()

    override fun createMarkdown(title: String, folder: String, text: String): String? =
        runCatching { store.createMarkdown(title, text, folder).id }.getOrNull()

    /** A copy of the note's folder under a new id: its pages, pictures and PDF, but not its place among the favourites. */
    override fun duplicateNote(id: String, title: String): String? = runCatching {
        store.settled()
        val newId = UUID.randomUUID().toString()
        val target = store.noteDir(newId)
        check(store.noteDir(id).copyRecursively(target))
        File(target, FAVORITE_MARKER).delete()
        if (!store.renameNote(newId, title)) { target.deleteRecursively(); error("rename") }
        newId
    }.getOrNull()

    override fun mergeNotes(ids: List<String>, title: String): String? { store.settled(); return store.mergeNotes(ids, title).getOrNull()?.id }
    override fun setFavorite(id: String, on: Boolean) = store.setFavorite(id, on)
    override fun setLabel(id: String, color: Int) = store.setLabel(id, color)
    override fun folders(): List<String> = store.folders()
    override fun createFolder(path: String): Boolean = store.createFolder(path)
    override fun removeEmptyFolder(path: String): Boolean {
        if (store.list().any { isInFolder(it.folder, path) }) return false
        store.deleteFolder(path, keepNotes = true)
        return true
    }
    override fun renameFolder(from: String, to: String): Boolean = store.renameFolder(from, to)
    override fun openNotes(): Set<String> = openNotes.invoke()
    override fun appVersion(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    override fun copyInk(id: String, document: Document, from: Page, to: Page) {
        loaded(id, from)
        if (from.strokes.isEmpty()) return
        // A stroke cannot be changed, so the copy may share it; its layer and lock ride along with it.
        to.strokes.addAll(from.strokes)
        to.loaded = true
        to.dirty = true
    }

    override fun copyImage(id: String, imageId: String): String? {
        val source = store.imageFile(id, imageId).takeIf { it.isFile } ?: return null
        val newId = UUID.randomUUID().toString()
        return runCatching { source.copyTo(store.imageFile(id, newId)); newId }.getOrNull()
    }

    override fun recognize(id: String, document: Document, page: Page): String? {
        loaded(id, page)
        val visible = page.strokes.filter { document.layers.isVisible(StrokeLayers.of(it)) }
        if (visible.isEmpty()) return null
        val reader = InkIndexer(context)
        try {
            return reader.textOf(visible, firstReading = true)
                ?: throw CommandError("Handwriting cannot be read yet: the reading models are not downloaded. " +
                    "Connect to the internet and try again.", code = "NOT_READY")
        } finally {
            reader.close()
        }
    }

    /** Where each shared file went, so taking it away again finds it. */
    private val shared = java.util.concurrent.ConcurrentHashMap<String, android.net.Uri>()

    /**
     * Copies a file the commands made into Download/Notesis, through the media
     * store: no storage permission is needed for it, and Termux (after
     * termux-setup-storage) and every other app can read it there.
     */
    override fun shareFile(path: String): String? = runCatching {
        val file = File(path)
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Notesis")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        // The name it was given: the store adds " (1)" when the name is taken.
        val name = resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: file.name
        val out = "/storage/emulated/0/${android.os.Environment.DIRECTORY_DOWNLOADS}/Notesis/$name"
        shared[out] = uri
        out
    }.getOrNull()

    override fun unshareFile(path: String) {
        shared.remove(path)?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
    }

    private companion object {
        val BOARD_FILES = listOf(Document.BOARD_FILE, Document.BOARD_MASK_FILE)
        const val JPEG_QUALITY = 82
        /** The favourite marker's file name in a note's folder; see NoteStore.setFavorite. */
        const val FAVORITE_MARKER = "favorite"
    }
}
