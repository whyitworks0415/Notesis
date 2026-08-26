package com.notesis

import android.content.Context
import androidx.ink.brush.Brush
import androidx.ink.storage.StrokeInputBatchSerialization
import androidx.ink.strokes.Stroke
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** What is printed under the ink. */
enum class PageBackground { BLANK, LINED, GRID, PDF }

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
    /** Index into the note's imported PDF, or -1 when this page has no PDF. */
    var pdfPageIndex: Int = -1,
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

    // Page offsets used to be summed on every call, and every frame asks for
    // them once per visible page - quadratic in page count, which a 200-page
    // PDF turns into real scroll lag. They are cached and rebuilt on change.
    private var tops = FloatArray(0)
    private var widest = Page.A4_WIDTH
    private var laidOutFor = -1

    /** Call after inserting, removing, or resizing a page. */
    fun invalidateLayout() {
        laidOutFor = -1
    }

    private fun layout() {
        if (laidOutFor == pages.size) return
        tops = FloatArray(pages.size)
        var y = 0f
        var maxWidth = 0f
        for (i in pages.indices) {
            tops[i] = y
            y += pages[i].height + PAGE_GAP
            if (pages[i].width > maxWidth) maxWidth = pages[i].width
        }
        widest = if (maxWidth > 0f) maxWidth else Page.A4_WIDTH
        laidOutFor = pages.size
    }

    fun topOf(index: Int): Float {
        layout()
        return tops.getOrElse(index) { 0f }
    }

    fun totalHeight(): Float =
        if (pages.isEmpty()) 0f else topOf(pages.size - 1) + pages.last().height

    fun widestPage(): Float {
        layout()
        return widest
    }

    /** Pages are centred on the document's horizontal axis. */
    fun leftOf(index: Int): Float = (widestPage() - pages[index].width) / 2f

    /** The page under a document-space point, or the nearest one vertically. */
    fun pageAt(documentX: Float, documentY: Float): Int {
        for (i in pages.indices) {
            val top = topOf(i)
            if (documentY < top + pages[i].height + PAGE_GAP / 2f) {
                val left = leftOf(i)
                // Ink outside the page edges is dropped rather than silently
                // landing on a neighbour.
                if (documentX < left || documentX > left + pages[i].width) return -1
                return if (documentY < top) -1 else i
            }
        }
        return -1
    }

    companion object {
        const val PAGE_GAP = 48f
    }
}

/** A note as the list screen needs it, without loading any ink. */
data class NoteMeta(
    val id: String,
    val title: String,
    val modified: Long,
    val pageCount: Int,
    val strokeCount: Int,
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

    private val root = File(context.filesDir, "notes").apply { mkdirs() }

    fun list(): List<NoteMeta> =
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { readMeta(it) }
            ?.sortedByDescending { it.modified }
            ?: emptyList()

    fun create(title: String): NoteMeta {
        val id = UUID.randomUUID().toString()
        File(root, "$id/pages").mkdirs()
        val document = Document(mutableListOf(Page()))
        writeMeta(id, title, document)
        return NoteMeta(id, title, System.currentTimeMillis(), 1, 0)
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
        return NoteMeta(id, title, System.currentTimeMillis(), pages.size, 0)
    }

    /**
     * Notes whose title or extracted PDF text contains [query]. Handwriting is
     * not searchable yet - that needs recognition, which is its own pass.
     */
    fun search(query: String): List<NoteMeta> {
        val needle = query.trim()
        if (needle.isEmpty()) return list()
        return list().filter { meta ->
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
            .listFiles { file -> file.name.endsWith(".txt") }
            ?.any { runCatching { it.readText().contains(needle, true) }.getOrDefault(false) }
            ?: false

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    fun pdfFile(id: String): File = File(root, "$id/doc.pdf")

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
                pdfPageIndex = entry.optInt("pdf", -1),
            )
            page.strokes += readStrokes(File(root, "$id/pages/${page.id}.bin"))
            // Just read from disk, so by definition it matches disk.
            page.dirty = false
            pages += page
        }
        if (pages.isEmpty()) pages += Page()
        return Document(pages)
    }

    fun save(id: String, title: String, document: Document) {
        val dir = File(root, "$id/pages")
        if (!dir.isDirectory && !dir.mkdirs()) return
        for (page in document.pages) {
            if (!page.dirty) continue
            writeStrokes(File(dir, "${page.id}.bin"), page.strokes)
            page.dirty = false
        }
        // A page that was deleted this session leaves its file behind otherwise.
        val live = document.pages.flatMap { listOf("${it.id}.bin", "${it.id}.txt") }.toSet()
        dir.listFiles()?.forEach { if (it.name !in live) it.delete() }
        writeMeta(id, title, document)
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
                    .put("pdf", page.pdfPageIndex),
            )
        }
        val json = JSONObject()
            .put("title", title)
            .put("modified", System.currentTimeMillis())
            .put("strokeCount", document.pages.sumOf { it.strokes.size })
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
            )
        }.getOrNull()
    }

    private fun writeStrokes(file: File, strokes: List<Stroke>) {
        // Write beside the real file and rename over it, so a crash mid-write
        // leaves the previous save intact instead of a truncated page.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(strokes.size)
            for (stroke in strokes) {
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
        }
        tmp.renameTo(file)
    }

    private fun readStrokes(file: File): List<Stroke> {
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
                            STROKE_EPSILON,
                        ),
                        inputs,
                    )
                }
            }
        }
        return strokes
    }

    private companion object {
        const val MAGIC = 0x4E545353 // "NTSS"
        const val VERSION = 1
    }
}
