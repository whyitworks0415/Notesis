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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

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

    /**
     * Whether this page's strokes have been read off disk yet. Opening a note
     * used to decode every page before anything could be drawn, which makes the
     * wait grow with the note rather than with what is on screen.
     */
    var loaded: Boolean = true

    /** Stroke count from the last save, for pages not loaded this session. */
    var savedStrokeCount: Int = 0

    /**
     * How many of this page's strokes the file already holds. Distinct from
     * [savedStrokeCount], which describes a page that has never been read in.
     */
    var savedOnDisk: Int = 0

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
    /** The note's own image if it has one, else the rendered first page. */
    val thumbnail: File? = null,
    /** Which folder it sits in. Blank is the top level, which is where notes
     *  start and where they go back to if their folder is emptied out. */
    val folder: String = "",
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
    /**
     * One exit save queue for the store. A note screen must be able to disappear
     * immediately without doing file IO on the UI thread, while the write itself
     * still needs to outlive that composable's cancelled coroutine scope.
     */
    private val saveWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Every folder that has a note in it. Folders are not objects with their own
     * files - a folder is the name its notes agree on - so an empty one simply
     * stops existing, which is the behaviour worth having for something created
     * by typing a name.
     */
    fun folders(): List<String> =
        list().map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()

    fun folderOf(id: String): String = runCatching {
        JSONObject(File(root, "$id/meta.json").readText()).optString("folder", "")
    }.getOrDefault("")

    /** Files a note. A blank name puts it back at the top level. */
    fun setFolder(id: String, folder: String) {
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
        // Drawn now, so the card in the list is not blank until the first save.
        writeAutoThumbnail(id, document)
        return NoteMeta(id, title, System.currentTimeMillis(), pages.size, 0)
    }

    /**
     * Notes whose title, extracted PDF text, or recognised handwriting contains
     * [query].
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
        // A picture left behind by an undo, or by a deleted page, is dead weight
        // in the note directory - only what a page still points at survives.
        val liveImages = document.pages.flatMap { page -> page.images.map { "${it.id}.png" } }
            .toSet()
        File(root, "$id/images").listFiles()?.forEach {
            if (it.name !in liveImages) it.delete()
        }
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

    /** Queues a final save without holding the main thread while a note closes. */
    fun saveLater(id: String, title: String, document: Document) {
        saveWorker.execute { save(id, title, document) }
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
                    .put("h", image.height.toDouble()),
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
     * The note as a PDF, with everything on it: the imported page underneath,
     * pictures, ink, and the tape over the top. Rendered at [PDF_EXPORT_SCALE]
     * so the ink is resolved rather than pixelated at page size.
     */
    fun exportPdf(id: String, out: java.io.OutputStream): Boolean = runCatching {
        val document = load(id)
        val pdf = android.graphics.pdf.PdfDocument()
        val source = PdfSource.open(pdfFile(id))
        val renderer = CanvasStrokeRenderer.create()
        try {
            for ((index, page) in document.pages.withIndex()) {
                if (page.width <= 0f || page.height <= 0f) continue
                val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                    page.width.toInt().coerceAtLeast(1),
                    page.height.toInt().coerceAtLeast(1),
                    index + 1,
                ).create()
                val out1 = pdf.startPage(info)
                drawWholePage(id, page, out1.canvas, source, renderer, 1f)
                pdf.finishPage(out1)
            }
            pdf.writeTo(out)
        } finally {
            source?.close()
            pdf.close()
        }
        true
    }.getOrDefault(false)

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
            val renderer = CanvasStrokeRenderer.create()
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
                            epsilon,
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
