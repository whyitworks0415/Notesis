package com.notesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.ink.brush.Brush
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.storage.StrokeInputBatchSerialization
import androidx.ink.strokes.Stroke
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * A picture placed on a page, in page-local units like everything else on it.
 * The bytes live in the note's own directory; this is only where it sits.
 */
class PageImage(
    val id: String = UUID.randomUUID().toString(),
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 0f,
    var height: Float = 0f,
    val textContent: TextBoxContent? = null,
)

/**
 * Masking tape: a stroke drawn like any other, in an opaque colour, whose job is
 * to cover what is under it. Tapping one turns it into its own outline, so what
 * it covers can be read without taking the tape off.
 */
class PageMask(val stroke: Stroke) {
    /**
     * Lifted to look at what is underneath. Deliberately not saved: reopening a
     * note should put every strip back down, which is the point of covering
     * something up in the first place.
     */
    var revealed: Boolean = false

    companion object {
        const val DEFAULT_MASK_COLOR = 0xFFB8A6E8.toInt()
    }
}

/** What is printed under the ink. */
enum class PageBackground { BLANK, LINED, GRID, PDF, DOT, NARROW_LINED, CORNELL, CUSTOM, INFINITE }

data class UserPageTemplate(val id: String, val name: String)

enum class ExportPageSize { ORIGINAL, A4, LETTER }

data class PageExportOptions(
    val first: Int,
    val last: Int,
    val size: ExportPageSize = ExportPageSize.ORIGINAL,
    val rotation: Int = 0,
)

enum class PageLayoutMode { VERTICAL, HORIZONTAL, SPREAD_2X1, GRID_2X2 }

/**
 * One page of a note. Strokes are kept in page-local coordinates, so a page can
 * be reordered or deleted without touching a single stroke, and a PDF page maps
 * onto its own coordinates directly.
 */
class Page(
    val id: String = UUID.randomUUID().toString(),
    var width: Float = A4_WIDTH,
    var height: Float = A4_HEIGHT,
    var background: PageBackground = PageBackground.BLANK,
    var templateId: String? = null,
    /** Index into the note's imported PDF, or -1 when this page has no PDF. */
    var pdfPageIndex: Int = -1,
    /** Pictures, drawn over the background and under the ink. */
    val images: MutableList<PageImage> = mutableListOf(),
    /** Masking tape, drawn over everything, because covering is the job. */
    val masks: MutableList<PageMask> = mutableListOf(),
    val strokes: MutableList<Stroke> = mutableListOf(),
) {
    /**
     * Whether this page's strokes differ from what is on disk. Autosave fires
     * on a timer while writing, and rewriting every page of a long note each
     * time is a hitch you can feel.
     */
    var dirty: Boolean = true

    /**
     * The zoom level this page's stroke meshes were built for, in screen pixels
     * per page unit. Zero means "not tessellated for any particular zoom yet".
     *
     * Ink bakes a stroke's outline, antialiasing band and all, when the stroke is
     * made. Magnifying that mesh magnifies the band with it, which is what makes
     * a zoomed-in stroke look soft. The geometry is rebuilt from the stored
     * inputs when the zoom moves far enough from this value.
     */
    var tessellatedFor: Float = 0f

    /** Changes when stroke meshes are rebuilt without changing saved ink. */
    var meshRevision: Long = 0L

    /** Generated geometry only; this is deliberately absent from the saved format. */
    @Volatile var renderState: RenderState = RenderState.Complete

    /**
     * Whether this page's strokes have been read off disk yet. Opening a note
     * used to decode every page before anything could be drawn, which makes the
     * wait grow with the note rather than with what is on screen.
     */
    var loaded: Boolean = true
        set(value) {
            field = value
            if (!value) renderState = RenderState.Dirty
        }

    /** Stroke count from the last save, for pages not loaded this session. */
    var savedStrokeCount: Int = 0

    /**
     * How many of this page's strokes the file already holds. Distinct from
     * [savedStrokeCount], which describes a page that has never been read in.
     */
    var savedOnDisk: Int = 0

    /**
     * Main-thread content generation used to reconcile an asynchronous save.
     * It is not persisted: it only answers whether this exact page changed
     * after the save worker received its immutable snapshot.
     */
    var revision: Long = 0L

    companion object {
        // A4 at 150dpi. Any consistent unit works; this one makes an imported
        // PDF and a blank page land at comparable sizes.
        const val A4_WIDTH = 1240f
        const val A4_HEIGHT = 1754f
    }
}

/**
 * A note: pages stacked down the document, which is the layout every one of
 * these apps uses. Document space is shared by all pages; each page owns a
 * band of it, and [topOf] is the only thing that decides where.
 */
class Document(val pages: MutableList<Page>) {

    /** Runtime-only generation for save coalescing; never written to disk. */
    internal val saveSessionId: Long = nextSaveSession.getAndIncrement()
    internal var editRevision: Long = 0L
        private set

    internal fun markEdited() {
        editRevision++
    }

    // Page offsets used to be summed on every call, and every frame asks for
    // them once per visible page - quadratic in page count, which a 200-page
    // PDF turns into real scroll lag. They are cached and rebuilt on change.
    private var tops = FloatArray(0)
    private var lefts = FloatArray(0)
    private var widest = Page.A4_WIDTH
    private var total = Page.A4_HEIGHT
    private var laidOutFor = -1
    var layoutMode: PageLayoutMode = PageLayoutMode.VERTICAL
        set(value) {
            if (field != value) { field = value; invalidateLayout() }
        }

    /** Call after inserting, removing, or resizing a page. */
    fun invalidateLayout() {
        laidOutFor = -1
    }

    private fun layout() {
        val signature = pages.size * 10 + layoutMode.ordinal
        if (laidOutFor == signature) return
        tops = FloatArray(pages.size)
        lefts = FloatArray(pages.size)
        val cellWidth = pages.maxOfOrNull { it.width } ?: Page.A4_WIDTH
        val cellHeight = pages.maxOfOrNull { it.height } ?: Page.A4_HEIGHT
        for (i in pages.indices) when (layoutMode) {
            PageLayoutMode.VERTICAL -> {
                tops[i] = i * (cellHeight + PAGE_GAP)
                lefts[i] = (cellWidth - pages[i].width) / 2f
            }
            PageLayoutMode.HORIZONTAL -> {
                lefts[i] = i * (cellWidth + PAGE_GAP)
                tops[i] = 0f
            }
            PageLayoutMode.SPREAD_2X1 -> {
                val spread = i / 2
                lefts[i] = spread * (cellWidth * 2f + PAGE_GAP * 2f) +
                    (i % 2) * (cellWidth + PAGE_GAP)
                tops[i] = 0f
            }
            PageLayoutMode.GRID_2X2 -> {
                lefts[i] = (i % 2) * (cellWidth + PAGE_GAP)
                tops[i] = (i / 2) * (cellHeight + PAGE_GAP)
            }
        }
        widest = pages.indices.maxOfOrNull { lefts[it] + pages[it].width } ?: Page.A4_WIDTH
        total = pages.indices.maxOfOrNull { tops[it] + pages[it].height } ?: Page.A4_HEIGHT
        laidOutFor = signature
    }

    fun topOf(index: Int): Float {
        layout()
        return tops.getOrElse(index) { 0f }
    }

    fun totalHeight(): Float { layout(); return if (pages.isEmpty()) 0f else total }

    fun widestPage(): Float {
        layout()
        return widest
    }

    fun fitWidth(): Float {
        val cell = pages.maxOfOrNull { it.width } ?: Page.A4_WIDTH
        return when (layoutMode) {
            PageLayoutMode.HORIZONTAL -> cell
            PageLayoutMode.SPREAD_2X1 -> cell * 2f + PAGE_GAP
            else -> widestPage()
        }
    }

    /** Pages are centred on the document's horizontal axis. */
    fun leftOf(index: Int): Float { layout(); return lefts.getOrElse(index) { 0f } }

    /** Greatest page top at or above which [documentY] lies, found in O(log n). */
    fun pageIndexAt(documentY: Float): Int {
        layout()
        if (pages.isEmpty()) return -1
        var low = 0
        var high = tops.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (tops[middle] <= documentY) low = middle + 1 else high = middle - 1
        }
        return high.coerceIn(0, pages.lastIndex)
    }

    /** The few pages that can intersect a vertical viewport, without scanning all pages. */
    fun pagesIntersecting(top: Float, bottom: Float): IntRange {
        if (pages.isEmpty() || bottom < top) return IntRange.EMPTY
        if (layoutMode != PageLayoutMode.VERTICAL) {
            val hit = pages.indices.filter { i ->
                topOf(i) <= bottom && topOf(i) + pages[i].height >= top
            }
            return if (hit.isEmpty()) IntRange.EMPTY else hit.first()..hit.last()
        }
        val first = pageIndexAt(top).coerceAtLeast(0)
        var low = first
        var high = pages.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (topOf(middle) <= bottom) low = middle + 1 else high = middle - 1
        }
        return first..high.coerceAtLeast(first)
    }

    /** The page under a document-space point, or the nearest one vertically. */
    fun pageAt(documentX: Float, documentY: Float): Int {
        if (pages.isEmpty() || documentY < 0f || documentX < 0f) return -1
        return pages.indices.firstOrNull { index ->
            val page = pages[index]
            documentX in leftOf(index)..(leftOf(index) + page.width) &&
                documentY in topOf(index)..(topOf(index) + page.height)
        } ?: -1
    }

    fun nearestPage(documentX: Float, documentY: Float): Int = pages.indices.minByOrNull { index ->
        val page = pages[index]
        val dx = documentX - (leftOf(index) + page.width / 2f)
        val dy = documentY - (topOf(index) + page.height / 2f)
        dx * dx + dy * dy
    } ?: -1

    companion object {
        const val PAGE_GAP = 48f
        private val nextSaveSession = AtomicLong(1L)
    }
}

internal data class SaveGeneration(
    val sessionId: Long,
    val editRevision: Long,
    val title: String,
)

/** Tracks generations before a potentially large immutable snapshot is made. */
internal class SaveRequestLedger {
    private val pending = mutableMapOf<String, SaveGeneration>()
    private val active = mutableMapOf<String, SaveGeneration>()
    private val completed = mutableMapOf<String, SaveGeneration>()

    fun contains(id: String, generation: SaveGeneration): Boolean =
        pending[id] == generation || active[id] == generation || completed[id] == generation

    fun queued(id: String, generation: SaveGeneration) {
        pending[id] = generation
    }

    fun started(id: String, generation: SaveGeneration) {
        if (pending[id] == generation) pending.remove(id)
        active[id] = generation
    }

    fun finished(id: String, generation: SaveGeneration, successful: Boolean) {
        if (active[id] == generation) active.remove(id)
        if (successful) completed[id] = generation
    }
}

/** A note as the list screen needs it, without loading any ink. */
enum class NoteKind { INK, MARKDOWN }

data class NoteMeta(
    val id: String,
    val title: String,
    val modified: Long,
    val pageCount: Int,
    val strokeCount: Int,
    /** The note's own image if it has one, else the rendered first page. */
    val thumbnail: File? = null,
    /** Which folder it sits in. Blank is the top level, which is where notes
     *  start and where they go back to if their folder is emptied out. */
    val folder: String = "",
    val kind: NoteKind = NoteKind.INK,
)

/**
 * Notes on disk, one directory each:
 *
 *     <id>/meta.json          title, and the page list in order
 *     <id>/pages/<pageId>.bin one file per page, raw stroke inputs
 *     <id>/doc.pdf            the imported PDF, if there is one
 *
 * No database - the file tree is the source of truth. A page is its own file so
 * that editing one page never rewrites the rest of the note.
 *
 * ponytail: lives in filesDir, so notes are backed up with the app but invisible
 * to file managers. A later pass moves the root to a SAF-picked folder and lets
 * the user's own cloud client sync it; this layout does not change.
 */
class NoteStore(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(context.filesDir, "notes").apply { mkdirs() }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /**
     * One exit save queue for the store. A note screen must be able to disappear
     * immediately without doing file IO on the UI thread, while the write itself
     * still needs to outlive that composable's cancelled coroutine scope.
     */
    private val saveWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val saveQueueLock = Any()
    private val pendingInkSaves = mutableMapOf<String, PendingSave>()
    private val drainingInkSaves = mutableSetOf<String>()
    private val saveLedger = SaveRequestLedger()

    /**
     * Every folder that has a note in it. Folders are not objects with their own
     * files - a folder is the name its notes agree on - so an empty one simply
     * stops existing, which is the behaviour worth having for something created
     * by typing a name.
     */
    fun folders(): List<String> =
        (savedFolders() + list().map { it.folder }).filter { it.isNotBlank() }.distinct().sorted()

    private fun savedFolders(): List<String> = runCatching {
        val array = JSONArray(File(root, "folders.json").readText())
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeFolders(names: Collection<String>) =
        atomicText(File(root, "folders.json"), JSONArray(names.map { it.trim() }
            .filter { it.isNotBlank() }.distinct().sorted()).toString())

    fun createFolder(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/')) return false
        if (clean in folders()) return true
        writeFolders(savedFolders() + clean)
        return true
    }

    fun renameFolder(from: String, to: String): Boolean {
        val clean = to.trim()
        if (from.isBlank() || clean.isBlank() || clean.contains('/') ||
            (clean != from && clean in folders())) return false
        list().filter { it.folder == from }.forEach { setFolder(it.id, clean) }
        writeFolders(savedFolders().filterNot { it == from } + clean)
        return true
    }

    /** Deleting a folder keeps its notes at the top level. */
    fun deleteFolder(name: String) {
        list().filter { it.folder == name }.forEach { setFolder(it.id, "") }
        writeFolders(savedFolders().filterNot { it == name })
    }

    fun pageTemplates(): List<UserPageTemplate> = runCatching {
        val array = JSONArray(File(root, "templates.json").readText())
        (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            UserPageTemplate(entry.getString("id"), entry.getString("name"))
        }.filter { templateFile(it.id).isFile }
    }.getOrDefault(emptyList())

    fun templateFile(id: String): File = File(root, "templates/$id.png")

    fun addPageTemplate(name: String, input: java.io.InputStream): UserPageTemplate? = runCatching {
        val bitmap = BitmapFactory.decodeStream(input) ?: error("이미지를 읽을 수 없습니다")
        val item = UserPageTemplate(UUID.randomUUID().toString(),
            name.trim().ifBlank { "내 템플릿" })
        val file = templateFile(item.id)
        file.parentFile?.mkdirs()
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val entries = pageTemplates() + item
            atomicText(File(root, "templates.json"), JSONArray(entries.map {
                JSONObject().put("id", it.id).put("name", it.name)
            }).toString())
            item
        } finally { bitmap.recycle() }
    }.getOrNull()

    fun folderOf(id: String): String = runCatching {
        JSONObject(File(root, "$id/meta.json").readText()).optString("folder", "")
    }.getOrDefault("")

    /** Files a note. A blank name puts it back at the top level. */
    fun setFolder(id: String, folder: String) {
        if (folder.isNotBlank()) createFolder(folder)
        val file = File(root, "$id/meta.json")
        runCatching {
            val json = JSONObject(file.readText())
            json.put("folder", folder.trim())
            file.writeText(json.toString())
        }
    }

    fun list(): List<NoteMeta> =
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { readMeta(it) }
            ?.sortedByDescending { it.modified }
            ?: emptyList()

    fun create(
        title: String, background: PageBackground = PageBackground.BLANK,
        templateId: String? = null,
    ): NoteMeta {
        val id = UUID.randomUUID().toString()
        File(root, "$id/pages").mkdirs()
        val document = Document(mutableListOf(Page(
            height = if (background == PageBackground.INFINITE) Page.A4_HEIGHT * 12f else Page.A4_HEIGHT,
            background = background, templateId = templateId,
        )))
        writeMeta(id, title, document)
        return NoteMeta(id, title, System.currentTimeMillis(), 1, 0)
    }

    fun createMarkdown(title: String, text: String = "", folder: String = ""): NoteMeta {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        try {
            atomicText(File(dir, "note.md"), text)
            atomicText(File(dir, "meta.json"), JSONObject()
                .put("title", title).put("kind", NoteKind.MARKDOWN.name)
                .put("modified", System.currentTimeMillis()).put("folder", folder)
                .put("pages", JSONArray()).toString())
            return readMeta(dir) ?: error("노트를 만들지 못했습니다")
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun loadMarkdown(id: String): String = File(root, "$id/note.md").readText(Charsets.UTF_8)

    fun exportMarkdown(id: String, out: java.io.OutputStream): Boolean = runCatching {
        out.bufferedWriter(Charsets.UTF_8).use { it.write(loadMarkdown(id)) }
        true
    }.getOrDefault(false)

    /** Loads and writes use the same queue, so reopening never races an exit save. */
    fun loadMarkdownLater(id: String, done: (Result<String>) -> Unit) {
        saveWorker.execute {
            val result = runCatching { loadMarkdown(id) }
            mainHandler.post { done(result) }
        }
    }

    fun saveMarkdownLater(id: String, title: String, text: String, done: (Result<Unit>) -> Unit = {}) {
        saveWorker.execute {
            val result = runCatching { saveMarkdown(id, title, text) }
            mainHandler.post { done(result) }
        }
    }

    internal fun saveMarkdown(id: String, title: String, text: String) {
        val metaFile = File(root, "$id/meta.json")
        val meta = JSONObject(metaFile.readText())
        check(meta.optString("kind") == NoteKind.MARKDOWN.name)
        atomicText(File(root, "$id/note.md"), text)
        meta.put("title", title).put("modified", System.currentTimeMillis())
        atomicText(metaFile, meta.toString())
    }

    private fun atomicText(file: File, text: String) {
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    /**
     * Copies the PDF into the note - the picked Uri is a loan, and a note that
     * stops rendering because the original moved is not a note.
     */
    fun createFromPdf(title: String, input: java.io.InputStream): NoteMeta? {
        val id = UUID.randomUUID().toString()
        File(root, "$id/pages").mkdirs()
        runCatching { pdfFile(id).outputStream().use { input.copyTo(it) } }
            .onFailure { File(root, id).deleteRecursively(); return null }

        val source = PdfSource.open(pdfFile(id))
        if (source == null || source.pageCount == 0) {
            source?.close()
            File(root, id).deleteRecursively()
            return null
        }
        val pages = (0 until source.pageCount).map { index ->
            val size = source.pageSize(index)
            Page(
                width = size?.first ?: Page.A4_WIDTH,
                height = size?.second ?: Page.A4_HEIGHT,
                background = PageBackground.PDF,
                pdfPageIndex = index,
            )
        }
        val document = Document(pages.toMutableList())
        // Extract the text now, while the PDF is already open. Doing it at search
        // time would mean re-opening every PDF the user owns on every keystroke.
        for (page in document.pages) {
            if (page.pdfPageIndex < 0) continue
            runCatching {
                File(root, "$id/pages/${page.id}.txt")
                    .writeText(source.textOf(page.pdfPageIndex))
            }
        }
        source.close()
        writeMeta(id, title, document)
        // Drawn now, so the card in the list is not blank until the first save.
        writeAutoThumbnail(id, document)
        return NoteMeta(id, title, System.currentTimeMillis(), pages.size, 0)
    }

    /** Copies editable pages, ink, pictures and PDF backgrounds into one new note. */
    fun mergeNotes(ids: List<String>, title: String): NoteMeta? = runCatching {
        val sources = ids.distinct().mapNotNull { id ->
            readMeta(File(root, id))?.takeIf { it.kind == NoteKind.INK }?.let { id to load(id) }
        }
        require(sources.size >= 2)
        PDFBoxResourceLoader.init(appContext)
        val newId = UUID.randomUUID().toString()
        val target = File(root, newId).apply { mkdirs() }
        val pagesDir = File(target, "pages").apply { mkdirs() }
        val pdf = PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(appContext.cacheDir))
        val pages = mutableListOf<Page>()
        try {
            for ((id, document) in sources) {
                val oldPdf = pdfFile(id).takeIf { it.isFile }?.let {
                    PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly().setTempDir(appContext.cacheDir))
                }
                try {
                    for (old in document.pages) {
                        val pageId = UUID.randomUUID().toString()
                        val pdfIndex = if (old.background == PageBackground.PDF) {
                            require(oldPdf != null && old.pdfPageIndex in 0 until oldPdf.numberOfPages)
                            val index = pdf.numberOfPages
                            pdf.importPage(oldPdf.getPage(old.pdfPageIndex))
                            index
                        } else -1
                        val images = old.images.map { image ->
                            val imageId = UUID.randomUUID().toString()
                            val source = imageFile(id, image.id)
                            if (source.isFile) {
                                imageFile(newId, imageId).also { it.parentFile?.mkdirs() }
                                    .let { source.copyTo(it) }
                            }
                            PageImage(imageId, image.x, image.y, image.width, image.height,
                                image.textContent)
                        }.toMutableList()
                        val page = Page(
                            id = pageId, width = old.width, height = old.height,
                            background = old.background, templateId = old.templateId,
                            pdfPageIndex = pdfIndex, images = images,
                        ).also {
                            it.loaded = false
                            it.dirty = false
                            it.savedStrokeCount = old.savedStrokeCount
                            it.savedOnDisk = old.savedStrokeCount
                        }
                        for (suffix in listOf(".bin", ".mask", ".txt", INK_INDEX)) {
                            val source = File(root, "$id/pages/${old.id}$suffix")
                            if (source.isFile) source.copyTo(File(pagesDir, "$pageId$suffix"))
                        }
                        pages += page
                    }
                } finally { oldPdf?.close() }
            }
            if (pdf.numberOfPages > 0) pdf.save(pdfFile(newId))
            val merged = Document(pages)
            writeMeta(newId, title.trim().ifBlank { "합친 노트" }, merged)
            writeAutoThumbnail(newId, merged)
            readMeta(target) ?: error("합친 노트를 읽을 수 없습니다")
        } catch (error: Throwable) {
            target.deleteRecursively()
            throw error
        } finally { pdf.close() }
    }.getOrNull()

    /**
     * Notes whose title, extracted PDF text, or recognised handwriting contains
     * [query].
     */
    fun search(query: String): List<NoteMeta> {
        val needle = query.trim()
        if (needle.isEmpty()) return list()
        return list().filter { meta ->
            if (meta.kind == NoteKind.MARKDOWN) return@filter meta.title.contains(needle, true) ||
                runCatching { loadMarkdown(meta.id).contains(needle, true) }.getOrDefault(false)
            ensureTextIndex(meta.id)
            meta.title.contains(needle, ignoreCase = true) || textContains(meta.id, needle)
        }
    }

    /**
     * Builds the text index for a PDF note that has not got one - a note
     * imported before indexing existed would otherwise be quietly unsearchable
     * forever.
     */
    private fun ensureTextIndex(id: String) {
        val dir = File(root, "$id/pages")
        if (!pdfFile(id).isFile) return
        if (dir.listFiles { file -> file.name.endsWith(".txt") }?.isNotEmpty() == true) return
        val source = PdfSource.open(pdfFile(id)) ?: return
        // Read the page list out of meta.json rather than load(), which would
        // decode every stroke in the note just to learn the page ids.
        val meta = runCatching { JSONObject(File(root, "$id/meta.json").readText()) }.getOrNull()
        val array = meta?.optJSONArray("pages") ?: JSONArray()
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val pdfIndex = entry.optInt("pdf", -1)
            if (pdfIndex < 0) continue
            val pageId = entry.optString("id")
            runCatching { File(dir, "$pageId.txt").writeText(source.textOf(pdfIndex)) }
        }
        source.close()
    }

    private fun textContains(id: String, needle: String): Boolean =
        File(root, "$id/pages")
            .listFiles { file -> file.name.endsWith(".txt") || file.name.endsWith(INK_INDEX) }
            ?.any { runCatching { it.readText().contains(needle, true) }.getOrDefault(false) }
            ?: false

    /** Where a page's recognised handwriting is kept, beside its strokes. */
    fun inkIndexFile(id: String, pageId: String): File =
        File(root, "$id/pages/$pageId$INK_INDEX")

    /**
     * Records what a page's handwriting says. Blank text still gets written -
     * an erased page has to stop matching what it used to say.
     */
    fun writeInkIndex(id: String, pageId: String, text: String) {
        val file = inkIndexFile(id, pageId)
        file.parentFile?.mkdirs()
        runCatching { file.writeText(text) }
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    fun pdfFile(id: String): File = File(root, "$id/doc.pdf")

    fun recordings(id: String): List<File> =
        File(root, "$id/recordings").listFiles { file -> file.isFile && file.extension == "m4a" }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun newRecordingFile(id: String): File =
        File(root, "$id/recordings/${System.currentTimeMillis()}.m4a")
            .also { it.parentFile?.mkdirs() }

    fun load(id: String): Document {
        val meta = File(root, "$id/meta.json")
        if (!meta.isFile) return Document(mutableListOf(Page()))
        val json = runCatching { JSONObject(meta.readText()) }.getOrNull()
            ?: return Document(mutableListOf(Page()))
        val array = json.optJSONArray("pages") ?: JSONArray()
        val pages = mutableListOf<Page>()
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val page = Page(
                id = entry.optString("id", UUID.randomUUID().toString()),
                width = entry.optDouble("w", Page.A4_WIDTH.toDouble()).toFloat(),
                height = entry.optDouble("h", Page.A4_HEIGHT.toDouble()).toFloat(),
                background = runCatching {
                    PageBackground.valueOf(entry.optString("bg", "BLANK"))
                }.getOrDefault(PageBackground.BLANK),
                templateId = entry.optString("template", "").ifBlank { null },
                pdfPageIndex = entry.optInt("pdf", -1),
            )
            page.images.addAll(imagesFrom(entry))
            // Strokes are left on disk until the page is actually needed.
            page.loaded = false
            page.savedStrokeCount = entry.optInt("strokes", 0)
            page.dirty = false
            pages += page
        }
        if (pages.isEmpty()) pages += Page()
        return Document(pages)
    }

    /**
     * Reads one page's strokes, generating their geometry at [epsilon] - the
     * fidelity the zoom in use calls for, so the meshes are built once instead
     * of built coarse and immediately rebuilt.
     */
    fun loadPage(id: String, page: Page, epsilon: Float): List<Stroke> =
        readStrokes(File(root, "$id/pages/${page.id}.bin"), epsilon)

    /**
     * Masking tape is strokes too, kept in its own file so that a page's ink and
     * what covers it stay separable - erasing tape must not touch the note.
     */
    fun loadMasks(id: String, page: Page, epsilon: Float): List<PageMask> =
        readStrokes(File(root, "$id/pages/${page.id}.mask"), epsilon).map { PageMask(it) }

    @Synchronized
    fun save(id: String, title: String, document: Document) {
        val dir = File(root, "$id/pages")
        if (!dir.isDirectory && !dir.mkdirs()) return
        var firstPageChanged = false
        for ((index, page) in document.pages.withIndex()) {
            // A page never loaded cannot have changed, and its file must stay.
            if (!page.loaded || !page.dirty) continue
            appendOrWriteStrokes(File(dir, "${page.id}.bin"), page.strokes, page.savedOnDisk)
            page.savedOnDisk = page.strokes.size
            writeStrokes(File(dir, "${page.id}.mask"), page.masks.map { it.stroke })
            page.dirty = false
            if (index == 0) firstPageChanged = true
        }
        // Image versions may still belong to undo/redo history. Keep their files:
        // autosave must not delete the previous version of an editable text box.
        // A page that was deleted this session leaves its file behind otherwise.
        val live = document.pages
            .flatMap {
                listOf("${it.id}.bin", "${it.id}.mask", "${it.id}.txt", "${it.id}$INK_INDEX")
            }
            .toSet()
        dir.listFiles()?.forEach { if (it.name !in live) it.delete() }
        writeMeta(id, title, document)

        // ponytail: rendering the first page costs a PDF decode, and autosave
        // runs every second or so while writing. The picture only has to be
        // roughly current, so it is refreshed at most once a THUMB_INTERVAL_MS.
        val auto = File(root, "$id/$AUTO_THUMB")
        val stale = System.currentTimeMillis() - auto.lastModified() > THUMB_INTERVAL_MS
        if (!auto.isFile || (firstPageChanged && stale)) writeAutoThumbnail(id, document)
    }

    private data class SavePage(
        val live: Page,
        val copy: Page,
        val revision: Long,
    )

    private data class SaveSnapshot(
        val document: Document,
        val pages: List<SavePage>,
    )

    private data class PendingSave(
        val title: String,
        val snapshot: SaveSnapshot,
        val generation: SaveGeneration,
    )

    /**
     * Copies every mutable collection before it crosses onto the save worker.
     * Stroke objects are immutable, but their page lists are not: walking a live
     * list while the main thread commits a pen stroke used to be the recurring
     * ConcurrentModificationException recorded in crash.log.
     */
    private fun snapshotForSave(document: Document): SaveSnapshot {
        val captured = document.pages.map { live ->
            // Clean pages need only their metadata in this generation. Copying
            // every immutable Stroke reference on every autosave doubled the
            // largest live list, and queued saves multiplied that peak again.
            val copyInk = live.loaded && live.dirty
            val copy = Page(
                id = live.id,
                width = live.width,
                height = live.height,
                background = live.background,
                pdfPageIndex = live.pdfPageIndex,
                images = live.images.map { image ->
                    PageImage(image.id, image.x, image.y, image.width, image.height, image.textContent)
                }.toMutableList(),
                masks = live.masks.map { mask ->
                    PageMask(mask.stroke).also { it.revealed = mask.revealed }
                }.takeIf { copyInk }?.toMutableList() ?: mutableListOf(),
                strokes = if (copyInk) live.strokes.toMutableList() else mutableListOf(),
            ).also {
                it.dirty = live.dirty
                it.tessellatedFor = live.tessellatedFor
                // A metadata-only copy behaves like an unloaded page so save()
                // neither writes its empty lists nor reports an empty count.
                it.loaded = copyInk
                it.savedStrokeCount = if (live.loaded) live.strokes.size else live.savedStrokeCount
                it.savedOnDisk = live.savedOnDisk
                it.revision = live.revision
            }
            SavePage(live, copy, live.revision)
        }
        return SaveSnapshot(Document(captured.map { it.copy }.toMutableList()), captured)
    }

    /**
     * Queues only the newest snapshot for one note. If disk is slower than the
     * pen, intermediate generations are obsolete; retaining all of them was a
     * large, avoidable heap spike and made leaving a dense note crash-prone.
     */
    fun saveLater(id: String, title: String, document: Document) {
        enqueueSave(id, title, document)
    }

    /**
     * Bypasses the UI debounce when a note closes or the app enters the
     * background. Duplicate lifecycle events are rejected before snapshotting.
     */
    fun flushSave(id: String, title: String, document: Document) {
        enqueueSave(id, title, document)
    }

    private fun enqueueSave(id: String, title: String, document: Document) {
        val generation = SaveGeneration(document.saveSessionId, document.editRevision, title)
        if (synchronized(saveQueueLock) { saveLedger.contains(id, generation) }) return

        // The snapshot is the only potentially sizeable main-thread allocation.
        // Checking on both sides keeps ON_STOP + dispose duplicates cheap.
        val snapshot = snapshotForSave(document)
        val startDrain = synchronized(saveQueueLock) {
            if (saveLedger.contains(id, generation)) return@synchronized false
            pendingInkSaves[id] = PendingSave(title, snapshot, generation)
            saveLedger.queued(id, generation)
            drainingInkSaves.add(id)
        }
        if (startDrain) saveWorker.execute { drainInkSaves(id) }
    }

    private fun drainInkSaves(id: String) {
        while (true) {
            val pending = synchronized(saveQueueLock) {
                val next = pendingInkSaves.remove(id) ?: run {
                    drainingInkSaves.remove(id)
                    return
                }
                saveLedger.started(id, next.generation)
                next
            }
            val snapshot = pending.snapshot
            var saved = false
            var attempt = 0
            while (!saved && attempt < SAVE_ATTEMPTS) {
                attempt++
                saved = runCatching { save(id, pending.title, snapshot.document) }.isSuccess
            }
            synchronized(saveQueueLock) {
                saveLedger.finished(id, pending.generation, saved)
            }
            if (!saved) {
                android.util.Log.e("NoteStore", "Failed to save note $id after $attempt attempts")
            }
            if (!saved) continue
            mainHandler.post {
                // Do not mark newer work clean. A page only adopts the worker's
                // disk count when no edit happened since this snapshot was made.
                for (page in snapshot.pages) {
                    if (page.live.revision != page.revision || !page.copy.loaded) continue
                    page.live.savedOnDisk = page.copy.savedOnDisk
                    page.live.savedStrokeCount = page.copy.strokes.size
                    page.live.dirty = false
                }
            }
        }
    }

    private fun imagesToJson(page: Page): JSONArray {
        val array = JSONArray()
        for (image in page.images) {
            array.put(
                JSONObject()
                    .put("id", image.id)
                    .put("x", image.x.toDouble())
                    .put("y", image.y.toDouble())
                    .put("w", image.width.toDouble())
                    .put("h", image.height.toDouble())
                    .put("text", image.textContent?.let { content -> JSONObject()
                        .put("value", content.text).put("size", content.size.toDouble()).put("color", content.color) }),
            )
        }
        return array
    }

    private fun imagesFrom(entry: JSONObject): MutableList<PageImage> {
        val array = entry.optJSONArray("images") ?: return mutableListOf()
        val images = mutableListOf<PageImage>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            images += PageImage(
                id = item.optString("id"),
                x = item.optDouble("x").toFloat(),
                y = item.optDouble("y").toFloat(),
                width = item.optDouble("w").toFloat(),
                height = item.optDouble("h").toFloat(),
                textContent = item.optJSONObject("text")?.let { content ->
                    TextBoxContent(content.optString("value"), content.optDouble("size", 32.0).toFloat(),
                        content.optInt("color", 0xFF000000.toInt()))
                },
            )
        }
        return images
    }

    fun imageFile(id: String, imageId: String): File =
        File(root, "$id/images/$imageId.png")

    /**
     * Copies a picture into the note and returns its id with the aspect ratio
     * the caller needs to place it, or null if it could not be read.
     */
    fun addImage(id: String, input: java.io.InputStream): Pair<String, Float>? {
        val bitmap = runCatching { BitmapFactory.decodeStream(input) }.getOrNull() ?: return null
        return addImage(id, bitmap)
    }

    fun addImage(id: String, source: Bitmap): Pair<String, Float>? {
        if (source.width <= 0 || source.height <= 0) return null
        // A phone photo is far more pixels than a page can show, and every one
        // of them would be decoded again on every redraw.
        val longest = maxOf(source.width, source.height)
        val bitmap = if (longest > MAX_IMAGE_PX) {
            val scale = MAX_IMAGE_PX.toFloat() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }
        val imageId = UUID.randomUUID().toString()
        val file = imageFile(id, imageId)
        file.parentFile?.mkdirs()
        val written = runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }.isSuccess
        if (!written) return null
        return imageId to bitmap.width.toFloat() / bitmap.height
    }

    /** The custom image if the note has one, else the rendered first page. */
    private fun thumbnailOf(dir: File): File? =
        File(dir, CUSTOM_THUMB).takeIf { it.isFile } ?: File(dir, AUTO_THUMB).takeIf { it.isFile }

    /**
     * Stores the user's own picture as the note's thumbnail. Downscaled on the
     * way in, because a phone photo is many megabytes and this is a card.
     */
    fun setThumbnail(id: String, input: java.io.InputStream): Boolean = runCatching {
        val source = BitmapFactory.decodeStream(input) ?: return false
        val scale = (THUMB_WIDTH.toFloat() / source.width).coerceAtMost(1f)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }
        File(root, "$id/$CUSTOM_THUMB").outputStream().use {
            scaled.compress(Bitmap.CompressFormat.PNG, 90, it)
        }
        true
    }.getOrDefault(false)

    /** Drops the custom picture, so the note falls back to its first page. */
    fun clearThumbnail(id: String) {
        File(root, "$id/$CUSTOM_THUMB").delete()
    }

    /**
     * Draws the first page - its PDF background and its ink - into a small PNG.
     * Runs off the UI thread: it opens the PDF and builds stroke geometry.
     */
    // ---- backup and export --------------------------------------------------

    /**
     * One note, or every note, written into a zip. This is the whole of the
     * note's storage - metadata, page strokes, masking, pictures, thumbnails -
     * so restoring it needs nothing this app does not already know how to read.
     */
    fun exportArchive(ids: List<String>, out: java.io.OutputStream): Boolean = runCatching {
        java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(ARCHIVE_MARK))
            zip.write(
                JSONObject()
                    .put("version", ARCHIVE_VERSION)
                    .put("exported", System.currentTimeMillis())
                    .put("notes", JSONArray(ids))
                    .toString()
                    .toByteArray(),
            )
            zip.closeEntry()
            for (id in ids) {
                val dir = File(root, id)
                if (!dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val name = "$id/" + file.relativeTo(dir).invariantSeparatorsPath
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        true
    }.getOrDefault(false)

    /**
     * Reads an archive back. Every note comes in under a fresh id, so importing
     * a backup on top of a live library adds to it rather than overwriting work
     * that happens to share an id. Returns how many notes arrived.
     */
    fun importArchive(input: java.io.InputStream): Int = runCatching {
        val remapped = mutableMapOf<String, String>()
        var seen = false
        java.util.zip.ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (entry.name == ARCHIVE_MARK) {
                    seen = true
                    zip.closeEntry()
                    continue
                }
                val cut = entry.name.indexOf('/')
                if (cut <= 0) {
                    zip.closeEntry()
                    continue
                }
                val original = entry.name.substring(0, cut)
                val rest = entry.name.substring(cut + 1)
                // Names inside an archive are not to be trusted with the file
                // system: an entry that climbs out of the root is dropped.
                if (rest.contains("..") || rest.startsWith("/")) {
                    zip.closeEntry()
                    continue
                }
                val id = remapped.getOrPut(original) { UUID.randomUUID().toString() }
                val target = File(root, "$id/$rest")
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
                zip.closeEntry()
            }
        }
        if (!seen) 0 else remapped.size
    }.getOrDefault(0)

    /**
     * The note as a PDF, retaining imported content and ink as vector paths.
     * A PDF exported here can therefore be imported into another note without
     * turning text or pen edges into a fixed-resolution page bitmap.
     */
    fun exportPdf(
        id: String, out: java.io.OutputStream, options: PageExportOptions? = null,
    ): Boolean {
        val document = load(id)
        val first = options?.first ?: 1
        val last = options?.last ?: document.pages.size
        if (first < 1 || last < first || last > document.pages.size) return false
        val selected = document.pages.subList(first - 1, last)
        if (selected.isEmpty()) return false
        if (options?.size != null && options.size != ExportPageSize.ORIGINAL) {
            return exportSizedPdf(id, selected, out, options)
        }
        val pages = selected.map { page ->
            VectorPdfPage(
                page = page,
                strokes = readStrokes(File(root, "$id/pages/${page.id}.bin")),
                masks = readStrokes(File(root, "$id/pages/${page.id}.mask")),
            )
        }
        return writeVectorPdf(
            context = appContext,
            sourceFile = pdfFile(id),
            pages = pages,
            imageFile = { imageId ->
                if (imageId.startsWith("template:")) templateFile(imageId.removePrefix("template:"))
                else imageFile(id, imageId)
            },
            out = out,
            rotation = options?.rotation ?: 0,
        )
    }

    private fun exportSizedPdf(
        id: String, pages: List<Page>, out: java.io.OutputStream, options: PageExportOptions,
    ): Boolean = runCatching {
        val pdf = android.graphics.pdf.PdfDocument()
        val source = PdfSource.open(pdfFile(id))
        val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
        try {
            for ((index, page) in pages.withIndex()) {
                val bitmap = renderExportBitmap(id, page, source, renderer,
                    options.size, options.rotation)
                try {
                    val pdfPage = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(
                        (bitmap.width / 2).coerceAtLeast(1),
                        (bitmap.height / 2).coerceAtLeast(1), index + 1,
                    ).create())
                    pdfPage.canvas.drawBitmap(bitmap, null, android.graphics.RectF(
                        0f, 0f, pdfPage.canvas.width.toFloat(), pdfPage.canvas.height.toFloat(),
                    ), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    pdf.finishPage(pdfPage)
                } finally { bitmap.recycle() }
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
            source?.close()
        }
        true
    }.getOrDefault(false)

    /** Exports one page as PNG, or a numbered PNG ZIP for a range. */
    fun exportPng(
        id: String, out: java.io.OutputStream, options: PageExportOptions,
    ): Boolean = runCatching {
        val document = load(id)
        require(options.first >= 1 && options.last >= options.first &&
            options.last <= document.pages.size)
        val source = PdfSource.open(pdfFile(id))
        val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
        try {
            if (options.first == options.last) {
                val bitmap = renderExportBitmap(id, document.pages[options.first - 1],
                    source, renderer, options.size, options.rotation)
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) }
                finally { bitmap.recycle() }
            } else {
                java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
                    for (number in options.first..options.last) {
                        val bitmap = renderExportBitmap(id, document.pages[number - 1],
                            source, renderer, options.size, options.rotation)
                        try {
                            zip.putNextEntry(java.util.zip.ZipEntry(
                                "page-${number.toString().padStart(3, '0')}.png"))
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                            zip.closeEntry()
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally { source?.close() }
        true
    }.getOrDefault(false)

    private fun renderExportBitmap(
        id: String, page: Page, source: PdfSource?, renderer: CanvasStrokeRenderer,
        size: ExportPageSize, rotation: Int,
    ): Bitmap {
        val (width, height) = when (size) {
            ExportPageSize.A4 -> 1240 to 1754
            ExportPageSize.LETTER -> 1275 to 1650
            ExportPageSize.ORIGINAL -> {
                val fit = (4096f / maxOf(page.width, page.height)).coerceAtMost(1f)
                (page.width * fit).toInt().coerceAtLeast(1) to
                    (page.height * fit).toInt().coerceAtLeast(1)
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val scale = minOf(width / page.width, height / page.height)
        canvas.save()
        canvas.translate((width - page.width * scale) / 2f,
            (height - page.height * scale) / 2f)
        drawWholePage(id, page, canvas, source, renderer, scale)
        canvas.restore()
        val degrees = ((rotation % 360) + 360) % 360
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        } finally { bitmap.recycle() }
    }

    /**
     * One page onto one canvas, in the order it is seen: paper, imported page,
     * pictures, ink, tape. Shared by the PDF export and the thumbnail, because
     * a thumbnail that disagrees with the export is a bug waiting to be filed.
     */
    private fun drawWholePage(
        id: String,
        page: Page,
        canvas: Canvas,
        source: PdfSource?,
        renderer: CanvasStrokeRenderer,
        scale: Float,
    ) {
        canvas.drawColor(Color.WHITE)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        if (page.background == PageBackground.PDF && page.pdfPageIndex >= 0) {
            source?.renderNow(page.pdfPageIndex, width)?.let {
                canvas.drawBitmap(it, null, android.graphics.Rect(0, 0, width, height), null)
            }
        }
        if (page.background == PageBackground.CUSTOM) {
            page.templateId?.let { BitmapFactory.decodeFile(templateFile(it).path) }?.let { bitmap ->
                canvas.drawBitmap(bitmap, null,
                    android.graphics.Rect(0, 0, width, height), null)
                bitmap.recycle()
            }
        }
        if (page.background !in listOf(PageBackground.BLANK, PageBackground.PDF,
                PageBackground.CUSTOM)) {
            val rule = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFD8E2EC.toInt()
                strokeWidth = 2f
            }
            canvas.save()
            canvas.scale(scale, scale)
            val spacing = if (page.background == PageBackground.NARROW_LINED) 30f else 60f
            if (page.background == PageBackground.DOT) {
                var y = spacing
                while (y < page.height) {
                    var x = spacing
                    while (x < page.width) {
                        canvas.drawCircle(x, y, 2.5f, rule)
                        x += spacing
                    }
                    y += spacing
                }
            } else {
                var y = spacing
                val end = if (page.background == PageBackground.CORNELL)
                    page.height * 0.82f else page.height
                while (y < end) {
                    canvas.drawLine(0f, y, page.width, y, rule)
                    y += spacing
                }
                if (page.background == PageBackground.GRID) {
                    var x = spacing
                    while (x < page.width) {
                        canvas.drawLine(x, 0f, x, page.height, rule)
                        x += spacing
                    }
                }
                if (page.background == PageBackground.CORNELL) {
                    canvas.drawLine(page.width * 0.30f, 0f, page.width * 0.30f,
                        page.height * 0.82f, rule)
                    canvas.drawLine(0f, page.height * 0.82f, page.width,
                        page.height * 0.82f, rule)
                }
            }
            canvas.restore()
        }
        val transform = Matrix().apply { setScale(scale, scale) }
        for (image in page.images) {
            val bitmap = runCatching {
                BitmapFactory.decodeFile(imageFile(id, image.id).path)
            }.getOrNull() ?: continue
            val target = android.graphics.RectF(
                image.x * scale,
                image.y * scale,
                (image.x + image.width) * scale,
                (image.y + image.height) * scale,
            )
            canvas.drawBitmap(bitmap, null, target, null)
        }
        val strokes = if (page.loaded) {
            page.strokes
        } else {
            readStrokes(File(root, "$id/pages/${page.id}.bin"))
        }
        val masks = if (page.loaded) {
            page.masks.map { it.stroke }
        } else {
            readStrokes(File(root, "$id/pages/${page.id}.mask"))
        }
        for (stroke in strokes) renderer.draw(canvas, stroke, transform)
        for (stroke in masks) renderer.draw(canvas, stroke, transform)
    }

    private fun writeAutoThumbnail(id: String, document: Document) {
        val page = document.pages.firstOrNull() ?: return
        if (page.width <= 0f || page.height <= 0f) return
        runCatching {
            val width = THUMB_WIDTH
            val height = (width * page.height / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            if (page.background == PageBackground.PDF && page.pdfPageIndex >= 0) {
                val source = PdfSource.open(pdfFile(id))
                source?.renderNow(page.pdfPageIndex, width)?.let {
                    canvas.drawBitmap(it, null, android.graphics.Rect(0, 0, width, height), null)
                }
                source?.close()
            }
            // A page not loaded this session still has its strokes on disk, and
            // an unopened note is exactly the case this picture is drawn for.
            val strokes = if (page.loaded) {
                page.strokes
            } else {
                readStrokes(File(root, "$id/pages/${page.id}.bin"))
            }
            val scale = width / page.width
            val transform = Matrix().apply { setScale(scale, scale) }
            // Masking tape covers the page for a reader, so it covers the
            // thumbnail too - a picture of what is under the tape is a picture
            // of a page that does not exist.
            val masks = if (page.loaded) {
                page.masks.map { it.stroke }
            } else {
                readStrokes(File(root, "$id/pages/${page.id}.mask"))
            }
            val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
            for (stroke in strokes) renderer.draw(canvas, stroke, transform)
            for (stroke in masks) renderer.draw(canvas, stroke, transform)
            val tmp = File(root, "$id/$AUTO_THUMB.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            tmp.renameTo(File(root, "$id/$AUTO_THUMB"))
        }
    }

    private fun writeMeta(id: String, title: String, document: Document) {
        val pages = JSONArray()
        for (page in document.pages) {
            pages.put(
                JSONObject()
                    .put("id", page.id)
                    .put("w", page.width)
                    .put("h", page.height)
                    .put("bg", page.background.name)
                    .put("template", page.templateId ?: "")
                    .put("pdf", page.pdfPageIndex)
                    .put("strokes", if (page.loaded) page.strokes.size else page.savedStrokeCount)
                    .put("images", imagesToJson(page)),
            )
        }
        val json = JSONObject()
            .put("title", title)
            // Read back rather than passed in: saving a note happens on a timer
            // and knows nothing about where the note was filed.
            .put("folder", folderOf(id))
            .put("modified", System.currentTimeMillis())
            .put(
                "strokeCount",
                document.pages.sumOf { if (it.loaded) it.strokes.size else it.savedStrokeCount },
            )
            .put("pages", pages)
        File(root, "$id/meta.json").writeText(json.toString())
    }

    private fun readMeta(dir: File): NoteMeta? {
        val file = File(dir, "meta.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            NoteMeta(
                id = dir.name,
                title = json.optString("title", "제목 없음"),
                modified = json.optLong("modified"),
                pageCount = json.optJSONArray("pages")?.length() ?: 1,
                strokeCount = json.optInt("strokeCount"),
                thumbnail = thumbnailOf(dir),
                folder = json.optString("folder", ""),
                kind = if (json.optString("kind") == NoteKind.MARKDOWN.name) NoteKind.MARKDOWN else NoteKind.INK,
            )
        }.getOrNull()
    }

    /**
     * Writing a page used to mean writing all of it, so adding one stroke to a
     * dense page rewrote every stroke on it, once per autosave. Ink is almost
     * always appended, so when the file already holds a prefix of what is in
     * memory the new strokes are added on the end and only the count at the
     * head is rewritten. Anything else - an erase, an undo, a move - falls back
     * to the full rewrite.
     *
     * The count goes last on purpose: a crash midway leaves a file whose header
     * still claims the old number, and the reader takes exactly that many.
     */
    private fun appendOrWriteStrokes(file: File, strokes: List<Stroke>, onDisk: Int) {
        if (!canAppendStrokes(onDisk, strokes.size, file.isFile)) {
            writeStrokes(file, strokes)
            return
        }
        val appended = runCatching {
            java.io.RandomAccessFile(file, "rw").use { raw ->
                if (raw.length() < HEADER_BYTES) return@use false
                raw.seek(0)
                if (raw.readInt() != MAGIC || raw.readInt() != VERSION) return@use false
                if (raw.readInt() != onDisk) return@use false
                raw.seek(raw.length())
                DataOutputStream(java.io.BufferedOutputStream(FileOutputStreamAt(raw))).use { out ->
                    for (i in onDisk until strokes.size) writeStroke(out, strokes[i])
                }
                raw.seek(COUNT_OFFSET)
                raw.writeInt(strokes.size)
                true
            }
        }.getOrDefault(false)
        if (!appended) writeStrokes(file, strokes)
    }

    private fun writeStroke(out: DataOutputStream, stroke: Stroke) {
        val brush = stroke.brush
        out.writeInt(Tool.ofBrushFamily(brush.family).ordinal)
        out.writeInt(brush.colorIntArgb)
        out.writeFloat(brush.size)
        out.writeFloat(brush.epsilon)
        val inputs = ByteArrayOutputStream().also {
            StrokeInputBatchSerialization.encode(stroke.inputs, it)
        }.toByteArray()
        out.writeInt(inputs.size)
        out.write(inputs)
    }

    private fun writeStrokes(file: File, strokes: List<Stroke>) {
        // Write beside the real file and rename over it, so a crash mid-write
        // leaves the previous save intact instead of a truncated page.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(strokes.size)
            for (stroke in strokes) writeStroke(out, stroke)
        }
        tmp.renameTo(file)
    }

    private fun readStrokes(file: File, epsilon: Float = STROKE_EPSILON): List<Stroke> {
        if (!file.isFile) return emptyList()
        val strokes = mutableListOf<Stroke>()
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return emptyList()
                repeat(input.readInt()) {
                    val tool = Tool.entries[input.readInt()]
                    val color = input.readInt()
                    val size = input.readFloat()
                    // The stored epsilon is read but not used: geometry is rebuilt
                    // from the raw inputs on every load, so a note written when the
                    // app used a coarser mesh gets the current fidelity for free.
                    input.readFloat()
                    val bytes = ByteArray(input.readInt()).also { input.readFully(it) }
                    // Only raw inputs are stored; the mesh is rebuilt here. That
                    // is what keeps saved notes readable across ink versions.
                    val inputs = StrokeInputBatchSerialization.decode(ByteArrayInputStream(bytes))
                    strokes += Stroke(
                        Brush.createWithColorIntArgb(
                            tool.brushFamily(),
                            color,
                            size,
                            strokeEpsilon(size, epsilon),
                        ),
                        inputs,
                    )
                }
            }
        }
        return strokes
    }

    companion object {
        const val MAGIC = 0x4E545353 // "NTSS"
        /** magic, version, count. */
        const val HEADER_BYTES = 12L
        const val COUNT_OFFSET = 8L
        const val CUSTOM_THUMB = "thumb.png"
        const val AUTO_THUMB = "auto.png"
        const val THUMB_WIDTH = 480
        const val MAX_IMAGE_PX = 2048
        const val INK_INDEX = ".ink"
        const val ARCHIVE_MARK = "notesis.json"
        const val ARCHIVE_VERSION = 1
        const val THUMB_INTERVAL_MS = 20_000L
        private const val SAVE_ATTEMPTS = 3
        const val VERSION = 1
    }
}

/**
 * Writes into an already-positioned RandomAccessFile. Only so the same stroke
 * writer can serve both the full rewrite and the append.
 */
private class FileOutputStreamAt(
    private val raw: java.io.RandomAccessFile,
) : java.io.OutputStream() {
    override fun write(b: Int) = raw.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = raw.write(b, off, len)
}

/**
 * Whether a page's file can be extended rather than rewritten. Getting this
 * wrong writes strokes twice or drops them, so it is a plain function with a
 * test rather than a condition buried in the writer.
 */
internal fun canAppendStrokes(onDisk: Int, total: Int, fileExists: Boolean): Boolean =
    fileExists && onDisk > 0 && total > onDisk
