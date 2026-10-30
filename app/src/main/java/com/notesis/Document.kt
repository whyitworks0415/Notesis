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
import java.lang.ref.WeakReference
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
    /** Left alone by the lasso and the eraser unless they are told otherwise. */
    var locked: Boolean = false,
    /** Objects sharing a nonzero group are selected together. See [StrokeTags]. */
    var group: Int = 0,
    var opacity: Float = 1f,
    /** Degrees clockwise about the picture's middle. */
    var rotation: Float = 0f,
    /** [BOX_NONE], or a container ([BOX_STICKY], [BOX_TABLE]) that carries what lies on it. */
    var box: Int = BOX_NONE,
    /** A folded sticky note: drawn as a small tab, its full size kept for unfolding. */
    var collapsed: Boolean = false,
    var expandedWidth: Float = 0f,
    var expandedHeight: Float = 0f,
    /** A table's grid, so it can be redrawn with more rows or columns. */
    var rows: Int = 0,
    var cols: Int = 0,
    /** A [BOX_CHECK] that has been ticked. */
    val checked: Boolean = false,
    /** A [BOX_CHECK]'s ink colour; 0 for the default dark grey. */
    val color: Int = 0,
) {
    companion object {
        const val BOX_NONE = 0
        const val BOX_STICKY = 1
        const val BOX_TABLE = 2
        /** A box to tick, drawn rather than pictured: there is no image file behind it. */
        const val BOX_CHECK = 3
        /** The side of a folded sticky note, in page units. */
        const val FOLDED_SIZE = 56f
    }

    /** Whether [other] holds exactly what [copy] would carry over: every field, by value. */
    internal fun sameState(other: PageImage): Boolean =
        id == other.id && x == other.x && y == other.y && width == other.width && height == other.height &&
            textContent == other.textContent && locked == other.locked && group == other.group &&
            opacity == other.opacity && rotation == other.rotation && box == other.box &&
            collapsed == other.collapsed && expandedWidth == other.expandedWidth &&
            expandedHeight == other.expandedHeight && rows == other.rows && cols == other.cols &&
            checked == other.checked && color == other.color

    /** Everything carried over, with whatever is named changed. */
    fun copy(
        id: String = this.id,
        x: Float = this.x,
        y: Float = this.y,
        width: Float = this.width,
        height: Float = this.height,
        textContent: TextBoxContent? = this.textContent,
        checked: Boolean = this.checked,
    ) = PageImage(id, x, y, width, height, textContent, locked, group, opacity, rotation,
        box, collapsed, expandedWidth, expandedHeight, rows, cols, checked, color)
}

/**
 * Lock and group for a stroke. A Stroke is the ink library's immutable object
 * and has nowhere to put either, so they live beside it, keyed by identity
 * (Stroke does not override equals), and ride in the high bits of the tool
 * field when a page is written. Bit 0 is locked; the rest is the group.
 */
internal object StrokeTags {
    private val tags = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Stroke, Int>())

    fun of(stroke: Stroke): Int = tags[stroke] ?: 0
    fun set(stroke: Stroke, tag: Int) { if (tag == 0) tags.remove(stroke) else tags[stroke] = tag }
    fun locked(stroke: Stroke): Boolean = of(stroke) and 1 != 0
    fun group(stroke: Stroke): Int = of(stroke) ushr 1
    fun tag(locked: Boolean, group: Int): Int = ((group and MAX_GROUP) shl 1) or (if (locked) 1 else 0)

    /** The tool field as written: the ordinal in the low byte, the tag above it. */
    fun pack(ordinal: Int, tag: Int): Int = (ordinal and 0xFF) or (tag shl 8)
    fun ordinalOf(packed: Int): Int = packed and 0xFF
    fun tagOf(packed: Int): Int = packed ushr 8

    /** A stroke rebuilt from another (moved, turned, re-meshed) is still the same object to the user. */
    fun carry(from: Stroke, to: Stroke) {
        if (from !== to) of(from).let { if (it != 0) set(to, it) }
        StrokeLayers.carry(from, to)
    }

    // ponytail: random ids, collisions are one in four million per pair of groups on a page.
    fun newGroup(): Int = 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(MAX_GROUP)

    const val MAX_GROUP = 0x3FFFFF
}

/**
 * Masking tape: a stroke drawn like any other, in an opaque colour, whose job is
 * to cover what is under it. Tapping one turns it into its own outline, so what
 * it covers can be read without taking the tape off.
 */
class PageMask(stroke: Stroke) {
    /** Replaced only when the whole page is turned. */
    var stroke: Stroke = stroke
        internal set

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

/**
 * What is printed under the ink. [INFINITE] is a blank page that runs on
 * downwards without end; [INFINITE_ALL] one that runs on every way. Saved by
 * name, so a constant is never renamed.
 */
enum class PageBackground { BLANK, LINED, GRID, PDF, DOT, NARROW_LINED, CORNELL, CUSTOM, INFINITE, INFINITE_ALL }

/** A page with no end, which grows as it is written and moved on: see InkCanvasView.growEndlessPages. */
val PageBackground.isEndless: Boolean
    get() = this == PageBackground.INFINITE || this == PageBackground.INFINITE_ALL

/**
 * The size an endless page starts at, from a page [width] wide. Down only, it
 * keeps its width and starts twelve pages tall. Every way, it starts as a
 * sheet [ENDLESS_ALL_START] pages each way, and is opened in its middle, so
 * there is as much room above and to the left as there is below and to the
 * right; it then grows further right and down as it is reached.
 */
fun endlessStartSize(background: PageBackground, width: Float): Pair<Float, Float> = when (background) {
    PageBackground.INFINITE_ALL -> Page.A4_WIDTH * ENDLESS_ALL_START to Page.A4_HEIGHT * ENDLESS_ALL_START
    else -> width to Page.A4_HEIGHT * 12f
}

const val ENDLESS_ALL_START = 20f

data class UserPageTemplate(val id: String, val name: String)

enum class ExportPageSize { ORIGINAL, A4, LETTER }

data class PageExportOptions(
    val first: Int,
    val last: Int,
    val size: ExportPageSize = ExportPageSize.ORIGINAL,
    val rotation: Int = 0,
    /** Every page flattened to a picture, for viewers that mishandle vector ink. */
    val raster: Boolean = false,
    /** Dark paper, light ink: every colour turned to its opposite. */
    val invert: Boolean = false,
)

enum class PageLayoutMode {
    VERTICAL, HORIZONTAL, SPREAD_2X1, GRID_2X2,

    /** Two by two like [SPREAD_2X1], but the first page alone on the right, as a book's cover is. */
    SPREAD_COVER,
}

/**
 * Something kept with a page but apart from what is written on it, shown as a
 * tab on the page's edge: a comment, which may carry a link, or another page
 * to work on beside this one - a practice sheet, or any page of any note.
 */
data class PageAttachment(
    val kind: Kind,
    /** A comment's words; for a page, what to call it. */
    val text: String = "",
    /** A link the comment carries, or blank. */
    val url: String = "",
    /** The note the attached page is in, and which of its pages. */
    val noteId: String = "",
    val pageIndex: Int = 0,
    /**
     * Where on the page a comment points, in page units, as a memo in the
     * margin is drawn back to what it is about; negative for nowhere in
     * particular, when it hangs from the page's edge.
     */
    val x: Float = -1f,
    val y: Float = -1f,
) {
    /** Whether the comment points at a place on the page. */
    val anchored: Boolean get() = x >= 0f && y >= 0f

    enum class Kind { COMMENT, PAGE }

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("text", text)
        .put("url", url)
        .put("note", noteId)
        .put("page", pageIndex)
        .put("x", x.toDouble())
        .put("y", y.toDouble())

    companion object {
        fun fromJson(json: JSONObject): PageAttachment? {
            val kind = runCatching { Kind.valueOf(json.optString("kind")) }.getOrNull() ?: return null
            val attachment = PageAttachment(
                kind = kind,
                text = json.optString("text"),
                url = json.optString("url"),
                noteId = json.optString("note"),
                pageIndex = json.optInt("page", 0).coerceAtLeast(0),
                x = json.optDouble("x", -1.0).toFloat().takeIf { it.isFinite() } ?: -1f,
                y = json.optDouble("y", -1.0).toFloat().takeIf { it.isFinite() } ?: -1f,
            )
            // A page with no note, or a comment with nothing in it, is nothing to show.
            return attachment.takeIf {
                if (kind == Kind.PAGE) it.noteId.isNotBlank() else it.text.isNotBlank() || it.url.isNotBlank()
            }
        }
    }
}

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
    /** Optional table-of-contents entry attached to this page's stable ID. */
    var tocTitle: String? = null,
    var tocHighlighted: Boolean = false,
    /** How deep the outline entry sits: 0 at the top, each step one level in. */
    var tocLevel: Int = 0,
    var bookmarked: Boolean = false,
    /**
     * Links on this page's objects: the group the linked objects share, and
     * where it goes - an https URL, "page:<pageId>" or "note:<noteId>#<pageIndex>".
     */
    val links: MutableMap<Int, String> = mutableMapOf(),
    /** Comments and pages kept with this page, as tabs on its edge. */
    val attachments: MutableList<PageAttachment> = mutableListOf(),
    /** The tabs put away: the attachments are kept, only not shown on the page. */
    var attachmentsHidden: Boolean = false,
    /**
     * The page this one is a practice sheet for, by id: laid beside it, as
     * the other half of a spread, rather than below. Null for a page of the
     * note's own run.
     */
    var besideOf: String? = null,
    /** Pictures, drawn over the background and under the ink. */
    val images: MutableList<PageImage> = mutableListOf(),
    /** Masking tape, drawn over everything, because covering is the job. */
    val masks: MutableList<PageMask> = mutableListOf(),
    val strokes: MutableList<Stroke> = mutableListOf(),
    /** Ink on hidden layers: kept and saved, but neither drawn nor hit. See [settleLayers]. */
    val hiddenStrokes: MutableList<Stroke> = mutableListOf(),
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
    /** The note's layers; saved in meta.json. */
    val layers = NoteLayers()

    /** Runtime ownership only; simultaneous canvases share edited page objects. */
    var liveCanvasCount: Int = 0
        private set
    private val canvasListeners = mutableSetOf<() -> Unit>()
    internal fun attachCanvas(onChanged: (() -> Unit)? = null) {
        liveCanvasCount++
        onChanged?.let(canvasListeners::add)
    }
    internal fun detachCanvas(onChanged: (() -> Unit)? = null) {
        liveCanvasCount = (liveCanvasCount - 1).coerceAtLeast(0)
        onChanged?.let(canvasListeners::remove)
    }
    internal fun notifyOtherCanvases(source: () -> Unit) {
        canvasListeners.forEach { if (it !== source) it() }
    }

    /** Runtime-only generation for save coalescing; never written to disk. */
    internal val saveSessionId: Long = nextSaveSession.getAndIncrement()
    internal var editRevision: Long = 0L
        private set

    internal fun markEdited() {
        editRevision++
    }

    /** The open canvas's diagnostics, for the autosave snapshot's timing; runtime only. */
    @Volatile internal var latency: LatencyStats? = null

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

    private var memoLefts = FloatArray(0)
    private var shown = BooleanArray(0)

    private fun layout() {
        val signature = pages.size * 10 + layoutMode.ordinal
        if (laidOutFor == signature) return
        tops = FloatArray(pages.size)
        lefts = FloatArray(pages.size)
        memoLefts = FloatArray(pages.size) { pages[it].width + PAGE_GAP }
        shown = BooleanArray(pages.size) { true }
        val cellWidth = pages.maxOfOrNull { it.width } ?: Page.A4_WIDTH
        val cellHeight = pages.maxOfOrNull { it.height } ?: Page.A4_HEIGHT
        if (layoutMode == PageLayoutMode.VERTICAL) {
            widest = layoutRows(cellWidth, cellHeight)
            total = pages.indices.maxOfOrNull { tops[it] + pages[it].height } ?: Page.A4_HEIGHT
            laidOutFor = signature
            return
        }
        for (i in pages.indices) when (layoutMode) {
            PageLayoutMode.VERTICAL -> Unit
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
            PageLayoutMode.SPREAD_COVER -> {
                // One place ahead: the cover takes the right half of the first spread.
                val place = i + 1
                lefts[i] = (place / 2) * (cellWidth * 2f + PAGE_GAP * 2f) +
                    (place % 2) * (cellWidth + PAGE_GAP)
                tops[i] = 0f
            }
        }
        widest = pages.indices.maxOfOrNull { lefts[it] + pages[it].width } ?: Page.A4_WIDTH
        total = pages.indices.maxOfOrNull { tops[it] + pages[it].height } ?: Page.A4_HEIGHT
        laidOutFor = signature
    }

    /**
     * The single column, in rows: a page, the practice pages laid beside it,
     * and a margin for its comments when it has some, each row centred on the
     * widest. A note with neither is the column it always was. Returns the
     * column's width.
     */
    private fun layoutRows(cellWidth: Float, cellHeight: Float): Float {
        val rows = ArrayList<IntArray>()
        var row = ArrayList<Int>()
        var hostId: String? = null
        for (i in pages.indices) {
            val page = pages[i]
            if (page.besideOf != null && page.besideOf == hostId && row.isNotEmpty()) {
                row += i
            } else {
                if (row.isNotEmpty()) rows += row.toIntArray()
                row = arrayListOf(i)
                hostId = page.id
            }
        }
        if (row.isNotEmpty()) rows += row.toIntArray()
        // Put away with the host's other attachments, practice pages and comments leave no room.
        fun open(r: IntArray) = !pages[r[0]].attachmentsHidden
        fun memos(r: IntArray) = open(r) && r.any { i -> pages[i].attachments.any { it.kind == PageAttachment.Kind.COMMENT } }
        val widths = rows.map { r ->
            var w = pages[r[0]].width
            if (open(r)) for (k in 1 until r.size) w += PAGE_GAP + pages[r[k]].width
            if (memos(r)) w += PAGE_GAP + MEMO_COLUMN
            w
        }
        val width = maxOf(cellWidth, widths.maxOrNull() ?: cellWidth)
        rows.forEachIndexed { at, r ->
            val top = at * (cellHeight + PAGE_GAP)
            var x = (width - widths[at]) / 2f
            for (k in r.indices) {
                val i = r[k]
                tops[i] = top
                if (k > 0 && !open(r)) {
                    shown[i] = false
                    lefts[i] = lefts[r[0]]
                    continue
                }
                lefts[i] = x
                x += pages[i].width + PAGE_GAP
            }
            // The margin starts past the last page of the row, for every page in it.
            for (i in r) if (shown[i]) memoLefts[i] = x - lefts[i]
        }
        return width
    }

    /** The page a practice page is laid beside, or [page] itself. */
    fun hostOf(page: Page): Page = page.besideOf?.let { id -> pages.firstOrNull { it.id == id } } ?: page

    /** Whether page [index] has anything to put away: comments, tabs, or practice pages beside it. */
    fun hasAttachments(index: Int): Boolean {
        val page = pages.getOrNull(index) ?: return false
        val host = hostOf(page)
        return host.attachments.isNotEmpty() || page.attachments.isNotEmpty() || pages.any { it.besideOf == host.id }
    }

    /** Whether page [index] is laid out to be seen: a practice page put away with its host's attachments is not. */
    fun isShown(index: Int): Boolean { layout(); return shown.getOrElse(index) { true } }

    /** Where page [index]'s comments start, page-local: past the last page of its row. */
    fun memoLeftOf(index: Int): Float {
        layout()
        return memoLefts.getOrElse(index) { (pages.getOrNull(index)?.width ?: Page.A4_WIDTH) + PAGE_GAP }
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
        // A sheet that runs every way is as wide as it has grown; its 100% is a page's width.
        val cell = pages.maxOfOrNull { if (it.background == PageBackground.INFINITE_ALL) Page.A4_WIDTH else it.width }
            ?: Page.A4_WIDTH
        return when (layoutMode) {
            PageLayoutMode.HORIZONTAL -> cell
            PageLayoutMode.SPREAD_2X1, PageLayoutMode.SPREAD_COVER -> cell * 2f + PAGE_GAP
            else -> if (pages.any { it.background == PageBackground.INFINITE_ALL }) cell else widestPage()
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
        // A practice page shares its host's top; the row is the host's.
        var at = high.coerceIn(0, pages.lastIndex)
        while (at > 0 && pages[at].besideOf != null && tops[at] == tops[at - 1]) at--
        return at
    }

    /** The few pages that can intersect a vertical viewport, without scanning all pages. */
    fun pagesIntersecting(top: Float, bottom: Float): IntRange {
        if (pages.isEmpty() || bottom < top) return IntRange.EMPTY
        if (layoutMode != PageLayoutMode.VERTICAL) {
            // Asked several times a frame: find the ends without building a list.
            var first = -1
            var last = -1
            for (i in pages.indices) {
                if (topOf(i) <= bottom && topOf(i) + pages[i].height >= top) {
                    if (first < 0) first = i
                    last = i
                }
            }
            return if (first < 0) IntRange.EMPTY else first..last
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
        // Only the pages in this band can hold the point; every move of a
        // pinch on a long note asked all of them.
        return pagesIntersecting(documentY, documentY).firstOrNull { index ->
            val page = pages[index]
            isShown(index) && documentX in leftOf(index)..(leftOf(index) + page.width) &&
                documentY in topOf(index)..(topOf(index) + page.height)
        } ?: -1
    }

    fun nearestPage(documentX: Float, documentY: Float): Int = nearbyCandidates(documentY).filter(::isShown).minByOrNull { index ->
        val page = pages[index]
        val dx = documentX - (leftOf(index) + page.width / 2f)
        val dy = documentY - (topOf(index) + page.height / 2f)
        dx * dx + dy * dy
    } ?: -1

    /**
     * Pages worth comparing for the one nearest a point: in a single column the
     * one at that height and its neighbours, since the nearest centre can only
     * be one of those; otherwise all of them.
     */
    private fun nearbyCandidates(documentY: Float): IntRange {
        if (layoutMode != PageLayoutMode.VERTICAL || pages.isEmpty()) return pages.indices
        val at = pageIndexAt(documentY)
        return (at - 1).coerceAtLeast(0)..(at + 1).coerceAtMost(pages.lastIndex)
    }

    companion object {
        const val PAGE_GAP = 48f
        /** The margin a row keeps for its comments, in page units: about a third of an A4 page. */
        const val MEMO_COLUMN = 400f
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
    /** Pinned to the front of the list. */
    val favorite: Boolean = false,
    /** When it went into the trash, or 0 for a note that is not in it. */
    val trashedAt: Long = 0L,
    val tags: List<String> = emptyList(),
    /** A colour to tell it apart in the list, or 0 for none. */
    val label: Int = 0,
    /** Behind the master password. See [NoteLock]. */
    val locked: Boolean = false,
    /** Where it sits among the favourites; pinned later sits later. */
    val favoriteOrder: Long = 0L,
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
     * What the last save wrote for each page's tape, by note and then page id.
     * Only [save] touches it, under that method's lock. The strokes are held
     * weakly: a mask taken off and collected can no longer compare equal to
     * anything on the page, so forgetting it costs a rewrite, never a skip.
     */
    private val maskWrites = recentNotes<HashMap<String, MaskWrite>>()

    /** The typed-text index last written per page, by note and then page id. Guarded by itself. */
    private val typedWrites = recentNotes<HashMap<String, TypedWrite>>()

    /** The tape a save wrote, and the file it left. */
    private class MaskWrite(val strokes: List<WeakReference<Stroke>>, val file: WrittenFile)

    /** The typed index a save wrote, and the file it left. */
    private class TypedWrite(val text: String, val file: WrittenFile)

    /**
     * A file as a write left it. Remembering what was written is only safe
     * while nothing else has replaced the file - a restore, an import, a
     * deleted note folder - so a cached write is trusted only while the file
     * still has the size and time it was left with.
     */
    private class WrittenFile(val length: Long, val modified: Long) {
        fun matches(file: File): Boolean =
            file.isFile && file.length() == length && file.lastModified() == modified

        companion object {
            fun of(file: File) = WrittenFile(file.length(), file.lastModified())
        }
    }

    /**
     * Only the notes used most recently are remembered: the caches are a
     * shortcut for the note being written in, not a record of the library.
     */
    private fun <V> recentNotes(): MutableMap<String, V> =
        object : LinkedHashMap<String, V>(REMEMBERED_NOTES * 2, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean =
                size > REMEMBERED_NOTES
        }

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

    /**
     * Deleting a folder sends its notes to the trash, still filed under it, so
     * restoring one brings the folder back with it. [keepNotes] moves them to
     * the top level instead.
     */
    fun deleteFolder(name: String, keepNotes: Boolean = false) {
        list().filter { it.folder == name }.forEach {
            if (keepNotes) setFolder(it.id, "") else moveToTrash(it.id)
        }
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
        val bitmap = decodeSampled(input, MAX_TEMPLATE_PX) ?: error("Can't read the image")
        val item = UserPageTemplate(UUID.randomUUID().toString(),
            name.trim().ifBlank { "My template" })
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

    fun list(): List<NoteMeta> {
        purgeTrashOnce()
        return root.listFiles { f -> f.isDirectory && !File(f, TRASHED).isFile }
            ?.mapNotNull { readMeta(it) }
            ?.sortedByDescending { it.modified }
            ?: emptyList()
    }

    // ---- trash and favourites ------------------------------------------------
    // Kept as marker files beside meta.json rather than in it: every save
    // rewrites meta.json from the document and would drop keys it does not know.

    /** Deleting goes here first; [TRASH_DAYS] later it is gone for good. */
    fun moveToTrash(id: String) {
        runCatching { File(root, "$id/$TRASHED").writeText(System.currentTimeMillis().toString()) }
    }

    fun restoreFromTrash(id: String) {
        File(root, "$id/$TRASHED").delete()
    }

    fun trashed(): List<NoteMeta> =
        root.listFiles { f -> f.isDirectory && File(f, TRASHED).isFile }
            ?.mapNotNull { readMeta(it) }
            ?.sortedByDescending { it.trashedAt }
            ?: emptyList()

    fun emptyTrash() {
        trashed().forEach { delete(it.id) }
    }

    // ---- reusable library ------------------------------------------------------
    // Objects kept for reuse across pages and notes, one directory each:
    // strokes.bin in the page format, the pictures' PNGs, preview.png and item.json.

    private val libraryRoot = File(appContext.filesDir, "library").apply { mkdirs() }

    data class LibraryItem(val id: String, val tags: List<String>, val preview: File, val created: Long)

    fun library(): List<LibraryItem> =
        libraryRoot.listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
            val json = runCatching { JSONObject(File(dir, "item.json").readText()) }.getOrNull() ?: return@mapNotNull null
            val tags = json.optJSONArray("tags")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            LibraryItem(dir.name, tags, File(dir, "preview.png"), json.optLong("created"))
        }?.sortedByDescending { it.created }.orEmpty()

    /** Keeps [clip] for good, with a picture of it for the list. Returns its id. */
    internal fun addToLibrary(clip: InkClipboard.Clip, preview: Bitmap?): String? = runCatching {
        val id = UUID.randomUUID().toString()
        val dir = File(libraryRoot, id).apply { mkdirs() }
        writeStrokes(File(dir, "strokes.bin"), clip.strokes)
        val images = JSONArray()
        clip.images.forEachIndexed { i, (image, bitmap) ->
            bitmap?.let { b -> File(dir, "$i.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            images.put(JSONObject().put("x", image.x.toDouble()).put("y", image.y.toDouble())
                .put("w", image.width.toDouble()).put("h", image.height.toDouble())
                .put("rotation", image.rotation.toDouble()).put("opacity", image.opacity.toDouble()))
        }
        preview?.let { b -> File(dir, "preview.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } }
        File(dir, "item.json").writeText(JSONObject()
            .put("created", System.currentTimeMillis())
            .put("tags", JSONArray())
            .put("bounds", JSONArray(listOf(clip.bounds.left, clip.bounds.top, clip.bounds.right, clip.bounds.bottom)
                .map { it.toDouble() }))
            .put("images", images).toString())
        id
    }.getOrNull()

    /** A kept item as a clip, ready to paste anywhere. */
    internal fun libraryClip(id: String): InkClipboard.Clip? = runCatching {
        val dir = File(libraryRoot, id)
        val json = JSONObject(File(dir, "item.json").readText())
        val b = json.getJSONArray("bounds")
        val images = json.optJSONArray("images") ?: JSONArray()
        InkClipboard.Clip(
            readStrokes(File(dir, "strokes.bin")),
            android.graphics.RectF(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(),
                b.getDouble(2).toFloat(), b.getDouble(3).toFloat()),
            (0 until images.length()).map { i ->
                val item = images.getJSONObject(i)
                PageImage(x = item.optDouble("x").toFloat(), y = item.optDouble("y").toFloat(),
                    width = item.optDouble("w").toFloat(), height = item.optDouble("h").toFloat(),
                    opacity = item.optDouble("opacity", 1.0).toFloat(), rotation = item.optDouble("rotation", 0.0).toFloat()) to
                    BitmapFactory.decodeFile(File(dir, "$i.png").path)
            },
        )
    }.getOrNull()

    fun setLibraryTags(id: String, tags: List<String>) {
        val file = File(libraryRoot, "$id/item.json")
        runCatching {
            val json = JSONObject(file.readText())
            json.put("tags", JSONArray(tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()))
            file.writeText(json.toString())
        }
    }

    fun deleteLibraryItem(id: String) {
        File(libraryRoot, id).deleteRecursively()
    }

    fun setTags(id: String, tags: List<String>) {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val file = File(root, "$id/$TAGS")
        if (clean.isEmpty()) file.delete() else runCatching { file.writeText(JSONArray(clean).toString()) }
    }

    /** Every tag on a note that is not in the trash, for the filter row. */
    fun allTags(): List<String> = list().flatMap { it.tags }.distinct().sorted()

    fun setLabel(id: String, color: Int) {
        val file = File(root, "$id/$LABEL")
        if (color == 0) file.delete() else runCatching { file.writeText(color.toString()) }
    }

    fun setLocked(id: String, locked: Boolean) {
        val marker = File(root, "$id/$LOCKED")
        if (locked) runCatching { marker.writeText("1") } else marker.delete()
    }

    fun isLocked(id: String): Boolean = File(root, "$id/$LOCKED").isFile

    fun setFavorite(id: String, favorite: Boolean) {
        val marker = File(root, "$id/$FAVORITE")
        // The marker holds the pinning time, which is the favourite's place in line.
        if (favorite) runCatching { marker.writeText(System.currentTimeMillis().toString()) } else marker.delete()
    }

    /** Renumbers the favourites in [ids]' order. */
    fun setFavoriteOrder(ids: List<String>) {
        ids.forEachIndexed { i, id ->
            val marker = File(root, "$id/$FAVORITE")
            if (marker.isFile) runCatching { marker.writeText((i + 1).toString()) }
        }
    }

    @Volatile private var trashPurged = false

    private fun purgeTrashOnce() {
        if (trashPurged) return
        trashPurged = true
        val cutoff = System.currentTimeMillis() - TRASH_DAYS * 24L * 60L * 60L * 1000L
        trashed().filter { it.trashedAt in 1 until cutoff }.forEach { delete(it.id) }
    }

    fun create(
        title: String, background: PageBackground = PageBackground.BLANK,
        templateId: String? = null,
    ): NoteMeta {
        val id = UUID.randomUUID().toString()
        File(root, "$id/pages").mkdirs()
        val (width, height) = if (background.isEndless) endlessStartSize(background, Page.A4_WIDTH)
            else Page.A4_WIDTH to Page.A4_HEIGHT
        val document = Document(mutableListOf(Page(
            width = width, height = height,
            background = background, templateId = templateId,
        )))
        writeMeta(id, title, document)
        return NoteMeta(id, title, System.currentTimeMillis(), 1, 0)
    }

    /**
     * A note with one picture per page, in [streams]' order: each page is A4
     * wide and as tall as its picture needs.
     */
    fun createFromImages(title: String, streams: List<() -> java.io.InputStream?>): NoteMeta? {
        val id = UUID.randomUUID().toString()
        File(root, "$id/pages").mkdirs()
        val pages = streams.mapNotNull { open ->
            val added = runCatching { open()?.use { addImage(id, it) } }.getOrNull() ?: return@mapNotNull null
            val height = (Page.A4_WIDTH / added.second.coerceAtLeast(0.05f)).coerceIn(100f, Page.A4_HEIGHT * 6f)
            Page(width = Page.A4_WIDTH, height = height).also {
                it.images += PageImage(added.first, 0f, 0f, Page.A4_WIDTH, height)
            }
        }
        if (pages.isEmpty()) {
            File(root, id).deleteRecursively()
            return null
        }
        val document = Document(pages.toMutableList())
        writeMeta(id, title, document)
        writeAutoThumbnail(id, document)
        return readMeta(File(root, id))
    }

    /**
     * A note whose pages [build] lays out itself, given the new note's id to
     * keep pictures under - the guide note's text boxes, for one. Nothing is
     * left behind when it builds no pages or fails part-way.
     */
    fun createFromPages(title: String, build: (String) -> List<Page>): NoteMeta? {
        val id = UUID.randomUUID().toString()
        File(root, "$id/pages").mkdirs()
        val pages = runCatching { build(id) }.getOrNull()
        if (pages.isNullOrEmpty()) {
            File(root, id).deleteRecursively()
            return null
        }
        val document = Document(pages.toMutableList())
        writeMeta(id, title, document)
        writeAutoThumbnail(id, document)
        return readMeta(File(root, id))
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
            return readMeta(dir) ?: error("Couldn't create the note")
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
        // Extract the text now, while the PDF is already open - doing it at
        // search time would mean re-opening every PDF on every keystroke - but
        // behind the import rather than in front of it: a long PDF read page
        // by page held the new note closed until its last page was read.
        val textPages = document.pages.filter { it.pdfPageIndex >= 0 }.map { it.id to it.pdfPageIndex }
        textWorker.execute {
            try {
                for ((pageId, pdfIndex) in textPages) {
                    runCatching { File(root, "$id/pages/$pageId.txt").writeText(source.textOf(pdfIndex)) }
                }
            } finally {
                source.close()
            }
        }
        writeMeta(id, title, document)
        // Drawn now, so the card in the list is not blank until the first save.
        writeAutoThumbnail(id, document)
        return NoteMeta(id, title, System.currentTimeMillis(), pages.size, 0)
    }

    /**
     * Adds [pages] - in-memory pages of note [sourceId], ink loaded - to the
     * end of note [targetId] on disk: strokes, tape, pictures and any PDF page
     * under them. The target must not be open.
     */
    fun appendPages(targetId: String, sourceId: String, pages: List<Page>): Boolean = runCatching {
        PDFBoxResourceLoader.init(appContext)
        val target = load(targetId)
        val title = readMeta(File(root, targetId))?.title ?: "Note"
        val memory = { MemoryUsageSetting.setupTempFileOnly().setTempDir(appContext.cacheDir) }
        val sourcePdf = pdfFile(sourceId).takeIf { it.isFile && pages.any { p -> p.pdfPageIndex >= 0 } }
            ?.let { PDDocument.load(it, memory()) }
        val targetPdfFile = pdfFile(targetId)
        val targetPdf = if (targetPdfFile.isFile) PDDocument.load(targetPdfFile, memory()) else PDDocument(memory())
        val pagesDir = File(root, "$targetId/pages").apply { mkdirs() }
        var pdfChanged = false
        try {
            for (old in pages) {
                val pageId = UUID.randomUUID().toString()
                val pdfIndex = if (old.pdfPageIndex >= 0 && sourcePdf != null &&
                    old.pdfPageIndex < sourcePdf.numberOfPages) {
                    targetPdf.importPage(sourcePdf.getPage(old.pdfPageIndex))
                    pdfChanged = true
                    targetPdf.numberOfPages - 1
                } else -1
                val images = old.images.map { image ->
                    val imageId = UUID.randomUUID().toString()
                    val file = imageFile(sourceId, image.id)
                    if (file.isFile) file.copyTo(imageFile(targetId, imageId).also { it.parentFile?.mkdirs() })
                    image.copy(id = imageId)
                }.toMutableList()
                writeStrokes(File(pagesDir, "$pageId.bin"), old.allStrokes)
                if (old.masks.isNotEmpty()) writeStrokes(File(pagesDir, "$pageId.mask"), old.masks.map { it.stroke })
                target.pages += Page(
                    id = pageId, width = old.width, height = old.height,
                    background = if (pdfIndex < 0 && old.background == PageBackground.PDF) PageBackground.BLANK
                        else old.background,
                    templateId = old.templateId, pdfPageIndex = pdfIndex, tocTitle = old.tocTitle,
                    tocHighlighted = old.tocHighlighted, tocLevel = old.tocLevel, bookmarked = old.bookmarked,
                    links = old.links.toMutableMap(), attachments = old.attachments.toMutableList(),
                    attachmentsHidden = old.attachmentsHidden, images = images,
                ).also {
                    it.loaded = false
                    it.dirty = false
                    it.savedStrokeCount = old.allStrokes.size
                    it.savedOnDisk = old.allStrokes.size
                }
            }
            if (pdfChanged) {
                // Beside and renamed over: the loaded document still reads from the old file.
                val tmp = File(targetPdfFile.parentFile, "doc.pdf.tmp")
                targetPdf.save(tmp)
                targetPdf.close()
                tmp.renameTo(targetPdfFile)
            }
            writeMeta(targetId, title, target)
        } finally {
            sourcePdf?.close()
            runCatching { targetPdf.close() }
        }
    }.isSuccess

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
                            image.copy(id = imageId)
                        }.toMutableList()
                        val page = Page(
                            id = pageId, width = old.width, height = old.height,
                            background = old.background, templateId = old.templateId,
                            pdfPageIndex = pdfIndex, tocTitle = old.tocTitle,
                            tocHighlighted = old.tocHighlighted, tocLevel = old.tocLevel,
                            bookmarked = old.bookmarked, images = images,
                            links = old.links.toMutableMap(), attachments = old.attachments.toMutableList(),
                            attachmentsHidden = old.attachmentsHidden,
                        ).also {
                            it.loaded = false
                            it.dirty = false
                            it.savedStrokeCount = old.savedStrokeCount
                            it.savedOnDisk = old.savedStrokeCount
                        }
                        for (suffix in listOf(".bin", ".mask", ".txt", INK_INDEX, BOX_INDEX)) {
                            val source = File(root, "$id/pages/${old.id}$suffix")
                            if (source.isFile) source.copyTo(File(pagesDir, "$pageId$suffix"))
                        }
                        pages += page
                    }
                } finally { oldPdf?.close() }
            }
            if (pdf.numberOfPages > 0) pdf.save(pdfFile(newId))
            val merged = Document(pages)
            writeMeta(newId, title.trim().ifBlank { "Merged note" }, merged)
            writeAutoThumbnail(newId, merged)
            readMeta(target) ?: error("Can't read the merged note")
        } catch (error: Throwable) {
            target.deleteRecursively()
            throw error
        } finally { pdf.close() }
    }.getOrNull()

    /**
     * Notes whose title, extracted PDF text, or recognised handwriting contains
     * [query].
     */
    fun search(query: String): List<NoteMeta> = searchWithPages(query).map { it.first }

    private val searchIndex by lazy { SearchIndex(appContext) }

    /**
     * Notes matching [query], each with the pages its text was found on, in
     * page order. Page text comes from [SearchIndex], which only rereads files
     * that changed since the last search.
     */
    fun searchWithPages(query: String): List<Pair<NoteMeta, List<PageHit>>> {
        val needle = query.trim()
        if (needle.isEmpty()) return list().map { it to emptyList() }
        val notes = list()
        for (meta in notes) {
            if (meta.kind == NoteKind.MARKDOWN) continue
            ensureTextIndex(meta.id)
            runCatching { searchIndex.sync(meta.id, File(root, "${meta.id}/pages")) }
        }
        val found = runCatching { searchIndex.find(needle) }.getOrDefault(emptyMap())
        return notes.mapNotNull { meta ->
            if (meta.kind == NoteKind.MARKDOWN) {
                val match = meta.title.contains(needle, true) ||
                    runCatching { loadMarkdown(meta.id).contains(needle, true) }.getOrDefault(false)
                return@mapNotNull if (match) meta to emptyList() else null
            }
            val files = found[meta.id].orEmpty()
            if (files.isEmpty() && !meta.title.contains(needle, ignoreCase = true)) return@mapNotNull null
            val order = pageOrder(meta.id)
            val hits = files
                .map { (file, snippet) ->
                    val pageId = SearchIndex.pageIdOf(file)
                    PageHit(pageId, order[pageId] ?: -1, snippet)
                }
                .filter { it.pageIndex >= 0 }
                .distinctBy { it.pageId }
                .sortedBy { it.pageIndex }
            meta to hits
        }
    }

    /** Page id to position, read from meta.json without decoding any strokes. */
    private fun pageOrder(id: String): Map<String, Int> {
        val json = runCatching { JSONObject(File(root, "$id/meta.json").readText()) }.getOrNull()
            ?: return emptyMap()
        val array = json.optJSONArray("pages") ?: return emptyMap()
        val order = HashMap<String, Int>(array.length())
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { order[it] = i }
        }
        return order
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
        runCatching { searchIndex.removeNote(id) }
    }

    fun pdfFile(id: String): File = File(root, "$id/doc.pdf")

    /** One entry of the outline a PDF carries inside it. */
    data class PdfOutlineEntry(val title: String, val pdfPage: Int, val level: Int)

    /**
     * The PDF's own table of contents, flattened in reading order. Read from
     * the file each time it is asked for; it is the document's, not the note's,
     * and is never edited here.
     */
    fun pdfOutline(id: String): List<PdfOutlineEntry> {
        val file = pdfFile(id)
        if (!file.isFile) return emptyList()
        PDFBoxResourceLoader.init(appContext)
        return runCatching {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(appContext.cacheDir)).use { doc ->
                val out = mutableListOf<PdfOutlineEntry>()
                fun walk(node: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode, level: Int) {
                    var child = node.firstChild
                    var guard = 0
                    while (child != null && guard++ < MAX_OUTLINE_ENTRIES) {
                        val page = runCatching { child.findDestinationPage(doc) }.getOrNull()
                        val index = page?.let { doc.pages.indexOf(it) } ?: -1
                        if (index >= 0) out += PdfOutlineEntry(child.title.orEmpty().trim(), index, level)
                        if (level < MAX_OUTLINE_DEPTH) walk(child, level + 1)
                        child = child.nextSibling
                    }
                }
                doc.documentCatalog.documentOutline?.let { walk(it, 0) }
                out
            }
        }.getOrDefault(emptyList())
    }

    /** Study progress on the note's tape; see [MaskStudy]. */
    fun studyFile(id: String): File = File(root, "$id/study.json")

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
                tocTitle = entry.optString("tocTitle", "").ifBlank { null },
                tocHighlighted = entry.optBoolean("tocHighlighted", false),
                tocLevel = entry.optInt("tocLevel", 0),
                bookmarked = entry.optBoolean("bookmark", false),
                links = linksFrom(entry),
                attachments = attachmentsFrom(entry),
                attachmentsHidden = entry.optBoolean("attachmentsHidden", false),
                besideOf = entry.optString("beside", "").ifBlank { null },
            )
            page.images.addAll(imagesFrom(entry))
            // Strokes are left on disk until the page is actually needed.
            page.loaded = false
            page.savedStrokeCount = entry.optInt("strokes", 0)
            page.dirty = false
            pages += page
        }
        if (pages.isEmpty()) pages += Page()
        return Document(pages).also { it.layers.copyFrom(NoteLayers.fromJson(json.optJSONObject("layers"))) }
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
            // The vanishing pen's strokes live only in memory: leave the note and they are gone.
            val kept = page.allStrokes.filterNot { Tool.isVanishing(it.brush.family) }
            appendOrWriteStrokes(File(dir, "${page.id}.bin"), kept, page.savedOnDisk)
            page.savedOnDisk = kept.size
            // A page is dirty for any ink edit, but its tape rarely changes with
            // it. The canvas edits page.masks in too many places to flag them,
            // so the tape is compared instead: the same Stroke objects in the
            // same order as the last write, and that file untouched since, means
            // the file already holds exactly this. Turning a page replaces each
            // mask's stroke, so it is caught as a change like any other.
            val maskFile = File(dir, "${page.id}.mask")
            val tape = page.masks.map { it.stroke }
            val written = maskWrites.getOrPut(id) { HashMap() }
            val last = written[page.id]
            if (last == null || !sameReferences(last.strokes, tape) || !last.file.matches(maskFile)) {
                written.remove(page.id)
                if (writeStrokes(maskFile, tape)) {
                    written[page.id] = MaskWrite(tape.map { WeakReference(it) }, WrittenFile.of(maskFile))
                }
            }
            page.dirty = false
            if (index == 0) firstPageChanged = true
        }
        // Image versions may still belong to undo/redo history. Keep their files:
        // autosave must not delete the previous version of an editable text box.
        // A page that was deleted this session leaves its file behind otherwise.
        val live = document.pages
            .flatMap {
                listOf("${it.id}.bin", "${it.id}.mask", "${it.id}.txt", "${it.id}$INK_INDEX", "${it.id}$BOX_INDEX")
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
        val started = System.nanoTime()
        val captured = document.pages.map { live ->
            // Clean pages need only their metadata in this generation. Copying
            // every immutable Stroke reference on every autosave doubled the
            // largest live list, and queued saves multiplied that peak again.
            val copyInk = live.loaded && live.dirty
            // A clean page whose metadata is as it was last time hands over
            // that same copy: nothing touches a copy once it is made, so two
            // queued saves can share it. A long PDF note otherwise built a
            // new page, its lists and its pictures for every page on every
            // autosave, to write out the one being written on.
            if (!copyInk) synchronized(cleanCopies) { cleanCopies[live] }?.let { cached ->
                if (sameCleanCopy(live, cached)) {
                    WorkStats.savePagesReused.incrementAndGet()
                    return@map SavePage(live, cached, live.revision)
                }
            }
            val copy = Page(
                id = live.id,
                width = live.width,
                height = live.height,
                background = live.background,
                templateId = live.templateId,
                pdfPageIndex = live.pdfPageIndex,
                tocTitle = live.tocTitle,
                tocHighlighted = live.tocHighlighted,
                tocLevel = live.tocLevel,
                bookmarked = live.bookmarked,
                links = live.links.toMutableMap(),
                attachments = live.attachments.toMutableList(),
                attachmentsHidden = live.attachmentsHidden,
                besideOf = live.besideOf,
                images = live.images.map { image ->
                    image.copy()
                }.toMutableList(),
                // Built only for pages that are written: mapping first and
                // discarding afterwards copied every page's tape on every autosave.
                masks = if (copyInk) {
                    live.masks.mapTo(ArrayList<PageMask>(live.masks.size)) { mask ->
                        PageMask(mask.stroke).also { it.revealed = mask.revealed }
                    }
                } else mutableListOf(),
                strokes = if (copyInk) live.strokes.toMutableList() else mutableListOf(),
                hiddenStrokes = if (copyInk) live.hiddenStrokes.toMutableList() else mutableListOf(),
            ).also {
                it.dirty = live.dirty
                it.tessellatedFor = live.tessellatedFor
                // A metadata-only copy behaves like an unloaded page so save()
                // neither writes its empty lists nor reports an empty count.
                it.loaded = copyInk
                it.savedStrokeCount = strokeCountOf(live)
                it.savedOnDisk = live.savedOnDisk
                it.revision = live.revision
            }
            if (!copyInk) synchronized(cleanCopies) { cleanCopies[live] = copy }
            SavePage(live, copy, live.revision)
        }
        val copy = Document(captured.map { it.copy }.toMutableList()).also { it.layers.copyFrom(document.layers) }
        document.latency?.record(PerformanceMetric.SAVE_SNAPSHOT, System.nanoTime() - started)
        return SaveSnapshot(copy, captured)
    }

    /**
     * The metadata-only copy last made of each clean page, keyed weakly by the
     * live page. Read and written on the UI thread that snapshots; the lock is
     * for a flush arriving from elsewhere.
     */
    private val cleanCopies = java.util.WeakHashMap<Page, Page>()

    /**
     * Whether [copy] still says everything a save writes for [live], which
     * has no ink to copy. Every field the copy was built from is compared,
     * the pictures field by field, since pictures move in place without an
     * edit being recorded.
     */
    private fun sameCleanCopy(live: Page, copy: Page): Boolean {
        if (copy.loaded || copy.dirty != live.dirty || copy.revision != live.revision ||
            copy.width != live.width || copy.height != live.height || copy.background != live.background ||
            copy.templateId != live.templateId || copy.pdfPageIndex != live.pdfPageIndex ||
            copy.tocTitle != live.tocTitle || copy.tocHighlighted != live.tocHighlighted ||
            copy.tocLevel != live.tocLevel || copy.bookmarked != live.bookmarked ||
            copy.tessellatedFor != live.tessellatedFor || copy.savedOnDisk != live.savedOnDisk ||
            copy.savedStrokeCount != strokeCountOf(live) || copy.links != live.links ||
            copy.attachments != live.attachments || copy.attachmentsHidden != live.attachmentsHidden ||
            copy.besideOf != live.besideOf ||
            copy.images.size != live.images.size
        ) return false
        for (i in live.images.indices) if (!copy.images[i].sameState(live.images[i])) return false
        return true
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
                        .put("value", content.text).put("size", content.size.toDouble()).put("color", content.color)
                        .put("html", content.html ?: "").put("font", content.font).put("bg", content.background)
                        .put("corner", content.corner.toDouble()).put("pad", content.padding.toDouble())
                        .put("min", content.minSize.toDouble()) })
                    .put("locked", image.locked)
                    .put("group", image.group)
                    .put("opacity", image.opacity.toDouble())
                    .put("rotation", image.rotation.toDouble())
                    .put("box", image.box)
                    .put("collapsed", image.collapsed)
                    .put("ew", image.expandedWidth.toDouble())
                    .put("eh", image.expandedHeight.toDouble())
                    .put("rows", image.rows)
                    .put("cols", image.cols)
                    .put("checked", image.checked)
                    .put("color", image.color),
            )
        }
        return array
    }

    private fun attachmentsFrom(entry: JSONObject): MutableList<PageAttachment> {
        val array = entry.optJSONArray("attachments") ?: return mutableListOf()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(PageAttachment::fromJson)
        }.toMutableList()
    }

    private fun linksFrom(entry: JSONObject): MutableMap<Int, String> {
        val json = entry.optJSONObject("links") ?: return mutableMapOf()
        return json.keys().asSequence().mapNotNull { key ->
            key.toIntOrNull()?.let { it to json.optString(key) }
        }.filter { it.second.isNotBlank() }.toMap().toMutableMap()
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
                        content.optInt("color", 0xFF000000.toInt()),
                        html = content.optString("html", "").ifBlank { null },
                        font = content.optString("font", ""),
                        background = content.optInt("bg", 0),
                        corner = content.optDouble("corner", 0.0).toFloat(),
                        padding = content.optDouble("pad", TextBoxContent.DEFAULT_PADDING.toDouble()).toFloat(),
                        minSize = content.optDouble("min", 0.0).toFloat())
                },
                locked = item.optBoolean("locked", false),
                group = item.optInt("group", 0),
                opacity = item.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                rotation = item.optDouble("rotation", 0.0).toFloat(),
                box = item.optInt("box", PageImage.BOX_NONE),
                collapsed = item.optBoolean("collapsed", false),
                expandedWidth = item.optDouble("ew", 0.0).toFloat(),
                expandedHeight = item.optDouble("eh", 0.0).toFloat(),
                rows = item.optInt("rows", 0),
                cols = item.optInt("cols", 0),
                checked = item.optBoolean("checked", false),
                color = item.optInt("color", 0),
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
        val bitmap = runCatching { decodeSampled(input, MAX_IMAGE_PX) }.getOrNull() ?: return null
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
        // Sampled by the longer side; four times the card width leaves the
        // shorter side wide enough even for a tall picture.
        val source = decodeSampled(input, THUMB_WIDTH * 4) ?: return false
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
        if (options != null && (options.size != ExportPageSize.ORIGINAL || options.raster || options.invert)) {
            return exportSizedPdf(id, selected, out, options, document.layers)
        }
        // The file holds hidden layers' ink too, in file order: export what is seen.
        val look = document.layers.translucentLook()
        val pages = selected.map { page ->
            VectorPdfPage(
                page = page,
                strokes = document.layers.shownInOrder(
                    readStrokes(File(root, "$id/pages/${page.id}.bin"), PDF_EXPORT_EPSILON), StrokeLayers::of),
                masks = readStrokes(File(root, "$id/pages/${page.id}.mask"), PDF_EXPORT_EPSILON),
                look = look,
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
        id: String, pages: List<Page>, out: java.io.OutputStream, options: PageExportOptions, layers: NoteLayers,
    ): Boolean = runCatching {
        val pdf = android.graphics.pdf.PdfDocument()
        val source = PdfSource.open(pdfFile(id))
        val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
        try {
            for ((index, page) in pages.withIndex()) {
                val bitmap = renderExportBitmap(id, page, layers, source, renderer,
                    options.size, options.rotation, options.invert)
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
    /** One PNG per page, each handed to [write] with its page number. */
    fun exportPng(
        id: String, options: PageExportOptions, write: (page: Int, png: Bitmap) -> Boolean,
    ): Boolean = runCatching {
        val document = load(id)
        require(options.first >= 1 && options.last >= options.first &&
            options.last <= document.pages.size)
        val source = PdfSource.open(pdfFile(id))
        val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
        try {
            for (number in options.first..options.last) {
                val bitmap = renderExportBitmap(id, document.pages[number - 1], document.layers,
                    source, renderer, options.size, options.rotation, options.invert)
                try { check(write(number, bitmap)) } finally { bitmap.recycle() }
            }
        } finally { source?.close() }
        true
    }.getOrDefault(false)

    /** Turns every colour in [bitmap] to its opposite, alpha untouched. */
    private fun invertColours(bitmap: Bitmap) {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ))
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
        }
        Canvas(bitmap).drawBitmap(copy, 0f, 0f, paint)
        copy.recycle()
    }

    private fun renderExportBitmap(
        id: String, page: Page, layers: NoteLayers, source: PdfSource?, renderer: CanvasStrokeRenderer,
        size: ExportPageSize, rotation: Int, invert: Boolean = false,
    ): Bitmap {
        val (width, height) = when (size) {
            ExportPageSize.A4 -> 1240 to 1754
            ExportPageSize.LETTER -> 1275 to 1650
            ExportPageSize.ORIGINAL -> {
                val fit = (BitmapBudget.MAX_SIDE / maxOf(page.width, page.height)).coerceAtMost(1f)
                val w = (page.width * fit).toInt().coerceAtLeast(1)
                val h = (page.height * fit).toInt().coerceAtLeast(1)
                val budget = BitmapBudget.scaleFor(w, h)
                (w * budget).toInt().coerceAtLeast(1) to (h * budget).toInt().coerceAtLeast(1)
            }
        }
        val scale = minOf(width / page.width, height / page.height)
        val paint = { canvas: Canvas, spent: MutableList<Bitmap> ->
            canvas.drawColor(Color.WHITE)
            canvas.save()
            canvas.translate((width - page.width * scale) / 2f,
                (height - page.height * scale) / 2f)
            drawWholePage(id, page, layers, canvas, source, renderer, scale, spent)
            canvas.restore()
        }
        // Through the GPU, as the page is seen: see [renderOnGpu].
        val bitmap = renderOnGpu(width, height, paint) ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            val spent = ArrayList<Bitmap>()
            paint(Canvas(it), spent)
            spent.forEach(Bitmap::recycle)
        }
        if (invert) invertColours(bitmap)
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
        layers: NoteLayers,
        canvas: Canvas,
        source: PdfSource?,
        renderer: CanvasStrokeRenderer,
        scale: Float,
        /** Bitmaps drawn here, to be recycled once the canvas has been rendered. */
        spent: MutableList<Bitmap>,
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
                spent += bitmap
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
            if (image.box == PageImage.BOX_CHECK) {
                drawCheckbox(canvas, android.graphics.RectF(image.x * scale, image.y * scale,
                    (image.x + image.width) * scale, (image.y + image.height) * scale), image.color, image.checked)
                continue
            }
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
            spent += bitmap
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
        // Shown layers only, in layer order, each translucent one composited at
        // its opacity - the page as it is seen, not as the file lists it.
        drawLayered(canvas, layers.shownInOrder(strokes, StrokeLayers::of), layers.translucentLook(), null) {
            renderer.draw(canvas, it, transform)
        }
        for (stroke in masks) renderer.draw(canvas, stroke, transform)
    }

    private fun writeAutoThumbnail(id: String, document: Document) {
        val page = document.pages.firstOrNull() ?: return
        if (page.width <= 0f || page.height <= 0f) return
        runCatching {
            val width = THUMB_WIDTH
            val height = (width * page.height / page.width).toInt().coerceAtLeast(1)
            val paint = { canvas: Canvas, _: MutableList<Bitmap> ->
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
                val layers = document.layers
                drawLayered(canvas, layers.shownInOrder(strokes, StrokeLayers::of), layers.translucentLook(), null) {
                    renderer.draw(canvas, it, transform)
                }
                for (stroke in masks) renderer.draw(canvas, stroke, transform)
            }
            // Through the GPU, as the page is seen: see [renderOnGpu].
            val bitmap = renderOnGpu(width, height, paint)
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { paint(Canvas(it), ArrayList()) }
            val tmp = File(root, "$id/$AUTO_THUMB.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            tmp.renameTo(File(root, "$id/$AUTO_THUMB"))
        }
    }

    /**
     * What was typed on each page - text boxes, sticky notes - kept beside the
     * page for search, rewritten only when it changes.
     */
    private fun writeTypedIndex(id: String, document: Document) = synchronized(typedWrites) {
        val dir = File(root, "$id/pages")
        val written = typedWrites.getOrPut(id) { HashMap() }
        for (page in document.pages) {
            val typed = page.images.mapNotNull { it.textContent?.text?.takeIf(String::isNotBlank) }.joinToString("\n")
            val file = File(dir, page.id + BOX_INDEX)
            if (typed.isEmpty()) {
                written.remove(page.id)
                if (file.isFile) file.delete()
                continue
            }
            // Autosave writes the meta every second or so while writing, and
            // reading every page's index back only to find it unchanged was
            // most of what this cost. What was written last is remembered, and
            // the disk is read only when that memory cannot vouch for the file.
            val last = written[page.id]
            if (last != null && last.text == typed && last.file.matches(file)) continue
            if (file.isFile && runCatching { file.readText() }.getOrNull() == typed) {
                written[page.id] = TypedWrite(typed, WrittenFile.of(file))
                continue
            }
            written.remove(page.id)
            dir.mkdirs()
            runCatching { file.writeText(typed) }
                .onSuccess { written[page.id] = TypedWrite(typed, WrittenFile.of(file)) }
        }
    }

    private fun writeMeta(id: String, title: String, document: Document) {
        writeTypedIndex(id, document)
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
                    .put("tocTitle", page.tocTitle ?: "")
                    .put("tocHighlighted", page.tocHighlighted)
                    .put("tocLevel", page.tocLevel)
                    .put("bookmark", page.bookmarked)
                    .put("links", JSONObject(page.links.mapKeys { it.key.toString() }))
                    .put("attachments", JSONArray(page.attachments.map { it.toJson() }))
                    .put("attachmentsHidden", page.attachmentsHidden)
                    .put("beside", page.besideOf ?: "")
                    .put("strokes", strokeCountOf(page))
                    .put("images", imagesToJson(page)),
            )
        }
        val json = JSONObject()
            .put("title", title)
            // Read back rather than passed in: saving a note happens on a timer
            // and knows nothing about where the note was filed.
            .put("folder", folderOf(id))
            .put("modified", System.currentTimeMillis())
            .put("strokeCount", document.pages.sumOf { strokeCountOf(it) })
            .put("pages", pages)
            .put("layers", document.layers.toJson())
        File(root, "$id/meta.json").writeText(json.toString())
    }

    /** Counted, not concatenated: allStrokes builds hidden + visible just to be measured. */
    private fun strokeCountOf(page: Page): Int =
        if (page.loaded) page.hiddenStrokes.size + page.strokes.size else page.savedStrokeCount

    private fun readMeta(dir: File): NoteMeta? {
        val file = File(dir, "meta.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            NoteMeta(
                id = dir.name,
                title = json.optString("title", "Untitled"),
                modified = json.optLong("modified"),
                pageCount = json.optJSONArray("pages")?.length() ?: 1,
                strokeCount = json.optInt("strokeCount"),
                thumbnail = thumbnailOf(dir),
                folder = json.optString("folder", ""),
                kind = if (json.optString("kind") == NoteKind.MARKDOWN.name) NoteKind.MARKDOWN else NoteKind.INK,
                favorite = File(dir, FAVORITE).isFile,
                trashedAt = File(dir, TRASHED).takeIf { it.isFile }
                    ?.let { runCatching { it.readText().trim().toLong() }.getOrDefault(it.lastModified()) }
                    ?: 0L,
                tags = File(dir, TAGS).takeIf { it.isFile }?.let { file ->
                    runCatching { JSONArray(file.readText()).let { a -> (0 until a.length()).map(a::getString) } }
                        .getOrNull()
                }.orEmpty(),
                label = File(dir, LABEL).takeIf { it.isFile }
                    ?.let { runCatching { it.readText().trim().toInt() }.getOrNull() } ?: 0,
                locked = File(dir, LOCKED).isFile,
                favoriteOrder = File(dir, FAVORITE).takeIf { it.isFile }
                    ?.let { runCatching { it.readText().trim().toLong() }.getOrNull() } ?: 0L,
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
        // The ordinal never needs more than the low byte; the tags ride above it.
        out.writeInt(StrokeTags.pack(Tool.ofBrushFamily(brush.family).ordinal, StrokeTags.of(stroke)))
        out.writeInt(brush.colorIntArgb)
        out.writeFloat(brush.size)
        out.writeFloat(brush.epsilon)
        // One scratch buffer per thread, written straight through: a fresh
        // buffer and its toByteArray() copy were two allocations per stroke,
        // thousands per dense page. The bytes on disk are the same.
        val inputs = strokeScratch.get()?.apply { reset() }
            ?: ByteArrayOutputStream().also(strokeScratch::set)
        StrokeInputBatchSerialization.encode(stroke.inputs, inputs)
        out.writeInt(inputs.size())
        inputs.writeTo(out)
        // One very long stroke must not pin its buffer to the thread for good.
        if (inputs.size() > MAX_SCRATCH_BYTES) strokeScratch.remove()
        // Version 3: the layer the stroke is on, after everything version 2 had.
        out.writeByte(StrokeLayers.of(stroke))
        // Version 4: the angle a fountain or calligraphy nib was turned to.
        out.writeByte(Tool.nibAngleOf(brush.family) ?: Tool.NO_NIB)
    }

    /** False when the rename failed and [file] still holds what it held before. */
    private fun writeStrokes(file: File, strokes: List<Stroke>): Boolean {
        // Write beside the real file and rename over it, so a crash mid-write
        // leaves the previous save intact instead of a truncated page.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(strokes.size)
            for (stroke in strokes) writeStroke(out, stroke)
        }
        return tmp.renameTo(file)
    }

    private fun readStrokes(file: File, epsilon: Float = STROKE_EPSILON): List<Stroke> {
        if (!file.isFile) return emptyList()
        // Reading the file is quick; rebuilding each stroke's geometry from its
        // inputs is nearly all of the cost. So read every record first, then
        // rebuild them across the cores at once instead of one after another.
        val records = mutableListOf<StoredStroke>()
        // One Brush per tool, colour and size: a page of the same pen
        // otherwise made a native brush for every stroke.
        val brushes = HashMap<BrushKey, Brush>()
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return emptyList()
                val storedVersion = input.readInt()
                if (storedVersion !in 1..VERSION) return emptyList()
                repeat(input.readInt()) {
                    val rawTool = input.readInt()
                    val storedTool = Tool.entries[StrokeTags.ordinalOf(rawTool)]
                    val color = input.readInt()
                    // Earlier identical pen/highlighter families wrote opaque
                    // normal pens with the highlighter ordinal. Restore their
                    // source-over rendering; translucent highlights stay intact.
                    val tool = if (storedVersion == 1 && storedTool == Tool.HIGHLIGHTER && color ushr 24 == 255)
                        Tool.PEN else storedTool
                    val size = input.readFloat()
                    // The stored epsilon is read but not used: geometry is rebuilt
                    // from the raw inputs on every load, so a note written when the
                    // app used a coarser mesh gets the current fidelity for free.
                    input.readFloat()
                    val bytes = ByteArray(input.readInt()).also { input.readFully(it) }
                    val layer = if (storedVersion >= 3) input.readUnsignedByte() else NoteLayers.BASE
                    val nib = if (storedVersion >= 4) input.readByte().toInt() else Tool.NO_NIB
                    val brush = brushes.getOrPut(BrushKey(tool.ordinal, color, size, 0f, 0, nib)) {
                        val family = tool.brushFamily(nib.takeIf { it != Tool.NO_NIB })
                        Brush.createWithColorIntArgb(family, color, size, inkEpsilon(family, size, epsilon))
                    }
                    records += StoredStroke(tool, rawTool, brush, bytes, layer)
                }
            }
        }
        return StrokeBuilder.buildAll(records)
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
        /** A page template is a whole page of background; this keeps it sharp at 150dpi A4 and then some. */
        const val MAX_TEMPLATE_PX = 4096
        const val INK_INDEX = ".ink"
        const val TRASHED = "trashed"
        const val FAVORITE = "favorite"
        const val TRASH_DAYS = 14
        /** Typed text on a page, for search. */
        const val BOX_INDEX = ".box"
        private const val MAX_OUTLINE_ENTRIES = 2000
        private const val MAX_OUTLINE_DEPTH = 6
        const val TAGS = "tags.json"
        const val LABEL = "label"
        const val LOCKED = "locked"
        const val ARCHIVE_MARK = "notesis.json"
        const val ARCHIVE_VERSION = 1
        const val THUMB_INTERVAL_MS = 20_000L
        /** Finer outlines than the live viewport, where PDF zoom has no fixed limit. */
        private const val PDF_EXPORT_EPSILON = 0.02f
        private const val SAVE_ATTEMPTS = 3
        /** How many notes' last writes are remembered; see [recentNotes]. */
        private const val REMEMBERED_NOTES = 4
        /** A per-thread stroke buffer that grew past this is dropped after use. */
        private const val MAX_SCRATCH_BYTES = 1 shl 20
        private val strokeScratch = ThreadLocal<ByteArrayOutputStream>()
        /**
         * 3 added a layer byte to each stroke; 1 and 2 still read, as layer 0.
         * 4 added the nib's angle after it; 3 reads as every nib at its default.
         */
        const val VERSION = 4
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

/**
 * Whether [current] is exactly the objects [written] remembers, in the same
 * order. Identity, not equality: a page's tape is skipped on save on the
 * strength of this, and only the very same immutable strokes are certain to
 * write the very same bytes. A collected reference never matches.
 */
internal fun <T : Any> sameReferences(written: List<WeakReference<T>>, current: List<T>): Boolean {
    if (written.size != current.size) return false
    for (i in current.indices) if (written[i].get() !== current[i]) return false
    return true
}

/** Below this many strokes, handing work to other threads costs more than it saves. */
internal const val INK_PARALLEL_FROM = 48

/** Slices, and so threads, one load may use while the pen is down or the device is hot. */
internal const val INK_THROTTLED_SLICES = 2

/**
 * How many slices [StrokeBuilder] cuts a load of [records] strokes into, for
 * a pool of [threads]. One means build on the calling thread. Unthrottled,
 * twice as many slices as threads lets a fast thread take another while a
 * slow one finishes; [throttled], one slice per thread is what keeps the load
 * to its share of the cores.
 */
internal fun inkBuildSlices(threads: Int, records: Int, throttled: Boolean): Int {
    val width = if (throttled) threads.coerceAtMost(INK_THROTTLED_SLICES) else threads
    if (records < INK_PARALLEL_FROM || width <= 1) return 1
    return (if (throttled) width else width * 2).coerceAtMost(records)
}

/** One stroke as it sits in a page file, before its geometry is rebuilt. */
internal class StoredStroke(val tool: Tool, val rawTool: Int, val brush: Brush, val inputs: ByteArray, val layer: Int = NoteLayers.BASE)

/**
 * Rebuilds strokes from their saved inputs on every spare core.
 *
 * Only raw inputs are stored and the mesh is rebuilt on load - that is what
 * keeps saved notes readable across ink versions - but it made opening a
 * dense page wait on one core while the others sat idle. Strokes are
 * independent, so they are built in contiguous slices in parallel and put
 * back in their saved order, which is also their drawing order.
 */
internal object StrokeBuilder {
    private val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(threads) { runnable ->
        Thread(runnable, "notesis-ink-build").apply { isDaemon = true }
    }

    /**
     * Set by the canvas while the pen is down. A page loading in the halo
     * beside the one being written on used to take every core for its
     * meshes, and the live stroke's frames waited on them; while this is set
     * a load is built on at most [INK_THROTTLED_SLICES] threads instead.
     */
    @Volatile var penActive: Boolean = false

    /**
     * Set while the device reports thermal pressure. Building on every core
     * is exactly the burst that makes a hot tablet clock down further, so
     * it is capped the same way as [penActive].
     */
    @Volatile var thermalThrottle: Boolean = false

    /** Set around a read of a page the screen is waiting on; see [forScreen]. */
    private val onScreen = ThreadLocal<Boolean>()

    /**
     * Runs [read] as the load of a page that is on screen: it keeps a couple
     * of threads for its meshes even while the page moves or a pen writes,
     * where a prefetch gets only what [InteractionGovernor.budget] allows.
     */
    fun <T> forScreen(read: () -> T): T {
        val was = onScreen.get()
        onScreen.set(true)
        try {
            return read()
        } finally {
            if (was == null) onScreen.remove() else onScreen.set(was)
        }
    }

    fun buildAll(records: List<StoredStroke>): List<Stroke> {
        // The governor knows what the person is doing and whether the device
        // is saving power or hot; one core while a pen writes or a page moves,
        // two in battery saver, the lot when nothing is going on.
        val width = inkBuildWidth(threads, InteractionGovernor.budget(threads), onScreen.get() == true, INK_THROTTLED_SLICES)
        val slices = inkBuildSlices(width, records.size, penActive || thermalThrottle || width < threads)
        if (slices <= 1) return records.mapNotNull(::build)
        val per = (records.size + slices - 1) / slices
        val futures = records.chunked(per).map { slice ->
            pool.submit(java.util.concurrent.Callable { slice.mapNotNull(::build) })
        }
        val built = ArrayList<Stroke>(records.size)
        for (future in futures) {
            // A cancelled load (the page scrolled away) gives up waiting; what
            // was built so far is returned like a file that ends early.
            val part = try {
                future.get()
            } catch (_: InterruptedException) {
                // Keep the flag for the caller, which checks it to drop the load.
                Thread.currentThread().interrupt()
                futures.forEach { it.cancel(false) }
                break
            } catch (_: Exception) {
                break
            }
            built += part
        }
        return built
    }

    private fun build(record: StoredStroke): Stroke? = runCatching {
        val inputs = StrokeInputBatchSerialization.decode(ByteArrayInputStream(record.inputs))
        // Tidied on the way in too, so notes written before a fix draw clean -
        // on the inputs, so the stroke is still built only once.
        val loaded = Stroke(record.brush,
            if (record.tool == Tool.PEN) tidiedPenInputs(inputs, record.brush.size) else inputs)
        StrokeTags.set(loaded, StrokeTags.tagOf(record.rawTool))
        StrokeLayers.set(loaded, record.layer)
        loaded
    }.getOrNull()
}

/** Extracts imported PDFs' text for search, one at a time, out of the way. */
private val textWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "notesis-pdf-text").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }
}

/**
 * Decodes a picture no larger than it has to be: at least [targetPx] along
 * its longer side where it has that many, decoded at a power-of-two step of
 * its full size. A 48MP photo decoded whole took about 190MB for a moment,
 * just to be shrunk to [NoteStore.MAX_IMAGE_PX] straight afterwards - enough
 * to run a tablet out of memory mid-import. Callers still scale to the exact
 * size they keep.
 */
internal fun decodeSampled(input: java.io.InputStream, targetPx: Int): Bitmap? {
    val bytes = input.readBytes()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** The largest power of two that still leaves the longer side at [targetPx] or more. */
internal fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= targetPx.coerceAtLeast(1)) sample *= 2
    return sample
}
