package com.notesis

import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.ink.strokes.Stroke
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * One layer of a note. Every page shares the note's layer list; a stroke
 * remembers only the [id] of the layer it was written on. [NoteLayers.list]
 * order is drawing order, bottom first.
 *
 * [locked] and [opacity] are changed through [NoteLayers.setLocked] and
 * [NoteLayers.setOpacity], which keep the per-id tables the canvas reads
 * per stroke in step.
 */
class NoteLayer(
    val id: Int,
    var name: String,
    var visible: Boolean = true,
    locked: Boolean = false,
    opacity: Float = 1f,
) {
    /** Ink on this layer cannot be added, erased, selected, moved or recoloured. */
    var locked: Boolean = locked
        internal set

    /** How strongly the layer's ink is composited, 0..1. A display property: stroke colours are untouched. */
    var opacity: Float = sanitizeOpacity(opacity)
        internal set

    fun copy(id: Int = this.id, name: String = this.name): NoteLayer = NoteLayer(id, name, visible, locked, opacity)
}

private fun sanitizeOpacity(value: Float): Float = if (value.isNaN()) 1f else value.coerceIn(0f, 1f)

/** 0..1 opacity as the 0..255 alpha a canvas layer takes. */
internal fun opacityAlpha(opacity: Float): Int = (sanitizeOpacity(opacity) * 255f + 0.5f).toInt().coerceIn(0, 255)

/**
 * The per-id lock and opacity of a note's layers, as tables a draw or a hit
 * test reads per stroke without scanning the list. Immutable: the layers hand
 * out a new one when either changes, so a worker thread may keep it, and a
 * cache can tell a change by identity.
 */
internal class LayerLook(val alpha: IntArray, val locked: BooleanArray, internal val version: Int) {
    /** Some layer is drawn at less than full strength; when false, drawing takes the plain path. */
    val translucent: Boolean = alpha.any { it < 255 }

    fun alphaOf(layer: Int): Int = if (layer in alpha.indices) alpha[layer] else 255
    fun lockedAt(layer: Int): Boolean = layer in locked.indices && locked[layer]
}

/**
 * Whether ink recorded under [a] still looks right under [b]: the same
 * opacities. A lock toggled on a note with a translucent layer hands out a new
 * look, and must not throw away the recorded tiles for that.
 */
internal fun sameLook(a: LayerLook?, b: LayerLook?): Boolean =
    a === b || (a != null && b != null && a.alpha.contentEquals(b.alpha))

/** The layers of a note, and which one the pen is on. */
class NoteLayers {
    val list: MutableList<NoteLayer> = mutableListOf(NoteLayer(BASE, "Layer 1"))

    /** The layer edits are held to, or [ALL] when every layer can be written and erased. */
    var active: Int = ALL

    /**
     * Where new ink goes while [active] is [ALL]: the layer chosen last, so
     * switching to every layer to tidy up does not move the next stroke.
     */
    var writeTo: Int = BASE

    /**
     * Ids of layers taken out this session. Undo can put one back with its ink,
     * so a new layer must not be handed the same id meanwhile. Memory only:
     * history does not outlive the open note.
     */
    private val retired = HashSet<Int>()

    /** The layer a new stroke is written on. */
    val target: Int get() = if (active == ALL) writeTo.takeIf { id -> list.any { it.id == id } } ?: list.first().id else active

    val hasHidden: Boolean get() = list.any { !it.visible }

    fun layer(id: Int): NoteLayer? = list.firstOrNull { it.id == id }

    fun isVisible(id: Int): Boolean = layer(id)?.visible ?: true

    fun isLocked(id: Int): Boolean = look().lockedAt(id)

    fun opacityOf(id: Int): Float = layer(id)?.opacity ?: 1f

    /** Drawing rank: a layer missing from the list (an older file, a deleted layer) sits at the bottom. */
    fun rank(id: Int): Int = list.indexOfFirst { it.id == id }.coerceAtLeast(0)

    /** Whether edits may touch a stroke on layer [id]: held to it (or to every layer), and not locked. */
    fun editable(id: Int): Boolean = (active == ALL || active == id) && !isLocked(id)

    // ---- lock and opacity tables ------------------------------------------

    private var styleVersion = 0
    @Volatile private var cachedLook: LayerLook? = null

    /** The current lock and opacity tables; the same object until one of them changes. */
    internal fun look(): LayerLook {
        val cached = cachedLook
        if (cached != null && cached.version == styleVersion) return cached
        val alpha = IntArray(MAX_ID + 1) { 255 }
        val locked = BooleanArray(MAX_ID + 1)
        for (layer in list) {
            alpha[layer.id] = opacityAlpha(layer.opacity)
            locked[layer.id] = layer.locked
        }
        return LayerLook(alpha, locked, styleVersion).also { cachedLook = it }
    }

    /** The tables when some layer is translucent, else null: what the draw paths branch on. */
    internal fun translucentLook(): LayerLook? = look().takeIf { it.translucent }

    private fun styleChanged() { styleVersion++ }

    fun setLocked(id: Int, locked: Boolean): Boolean {
        val layer = layer(id) ?: return false
        if (layer.locked == locked) return false
        layer.locked = locked
        styleChanged()
        return true
    }

    fun setOpacity(id: Int, opacity: Float): Boolean {
        val layer = layer(id) ?: return false
        val value = sanitizeOpacity(opacity)
        if (layer.opacity == value) return false
        layer.opacity = value
        styleChanged()
        return true
    }

    // ---- the list -----------------------------------------------------------

    /** An id no layer has and no undo can bring back, or null when all 256 are spoken for. */
    fun freshId(): Int? {
        val highest = maxOf(list.maxOfOrNull { it.id } ?: BASE, retired.maxOrNull() ?: BASE)
        if (highest < MAX_ID) return highest + 1
        return (0..MAX_ID).firstOrNull { id -> list.none { it.id == id } && id !in retired }
    }

    fun canAdd(): Boolean = list.size < MAX_LAYERS && freshId() != null

    /** "Layer n" with the first n no layer is called yet. */
    private fun freshName(): String {
        var n = list.size + 1
        while (list.any { it.name == "Layer $n" }) n++
        return "Layer $n"
    }

    /** A new empty layer on top of the stack, or null when there is no room. */
    fun addOrNull(): NoteLayer? {
        if (!canAdd()) return null
        val layer = NoteLayer(freshId() ?: return null, freshName())
        list += layer
        styleChanged()
        return layer
    }

    /** [addOrNull] for callers that checked [canAdd]; throws when there is no room. */
    fun add(): NoteLayer = checkNotNull(addOrNull()) { "No room for another layer" }

    /**
     * A copy of layer [id]'s settings under a new id, placed directly above it.
     * The copy starts unlocked: duplicating is usually done to change the copy.
     * Its ink is the caller's to copy; see [InkCanvasView.duplicateLayer].
     */
    fun duplicate(id: Int): NoteLayer? {
        val at = list.indexOfFirst { it.id == id }
        if (at < 0 || !canAdd()) return null
        val source = list[at]
        val copy = NoteLayer(freshId() ?: return null, (source.name + " copy").take(MAX_NAME), source.visible, false, source.opacity)
        list.add(at + 1, copy)
        styleChanged()
        return copy
    }

    /**
     * Moves layer [id] to [to] in [list] (bottom first). Only the order changes:
     * the id, and with it every stroke's claim to the layer, stays.
     */
    fun move(id: Int, to: Int): Boolean {
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return false
        val target = to.coerceIn(0, list.lastIndex)
        if (target == from) return false
        list.add(target, list.removeAt(from))
        return true
    }

    /** What [remove] took out, enough for [restore] to put it back exactly. */
    class Removed(val layer: NoteLayer, val at: Int, val active: Int, val writeTo: Int)

    /** Takes layer [id] out of the list; never the last one. Its ink is the caller's. */
    fun remove(id: Int): Removed? {
        if (list.size <= 1) return null
        val at = list.indexOfFirst { it.id == id }
        if (at < 0) return null
        val removed = Removed(list[at], at, active, writeTo)
        list.removeAt(at)
        retired += id
        if (active == id) active = ALL
        if (writeTo == id) writeTo = list[(at - 1).coerceIn(0, list.lastIndex)].id
        styleChanged()
        return removed
    }

    /** Undoes [remove]: the layer back at its place, and the pen where it was. */
    fun restore(removed: Removed) {
        if (list.any { it.id == removed.layer.id }) return
        list.add(removed.at.coerceIn(0, list.size), removed.layer)
        retired -= removed.layer.id
        if (removed.active == ALL || list.any { it.id == removed.active }) active = removed.active
        writeTo = removed.writeTo
        styleChanged()
    }

    /** Puts [layer] in at [at]; how a duplicate is redone. */
    fun insert(layer: NoteLayer, at: Int) {
        if (list.any { it.id == layer.id }) return
        list.add(at.coerceIn(0, list.size), layer)
        retired -= layer.id
        styleChanged()
    }

    fun copyFrom(other: NoteLayers) {
        list.clear()
        other.list.mapTo(list) { it.copy() }
        active = other.active
        writeTo = other.writeTo
        retired.clear()
        retired += other.retired
        styleChanged()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("active", active)
        .put("writeTo", writeTo)
        .put("list", JSONArray().apply {
            list.forEach {
                put(JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("visible", it.visible)
                    .put("locked", it.locked)
                    // Three places are plenty for a slider in whole percent, and
                    // keep 0.7 from being written as 0.699999988079071.
                    .put("opacity", (it.opacity * 1000f).roundToInt() / 1000.0))
            }
        })

    companion object {
        const val ALL = -1
        const val BASE = 0
        /** A layer id is stored in one byte per stroke. */
        const val MAX_ID = 255
        const val MAX_LAYERS = 32
        const val MAX_NAME = 40

        /** Old notes have no "locked" or "opacity": unlocked, fully opaque. */
        fun fromJson(json: JSONObject?): NoteLayers {
            val layers = NoteLayers()
            if (json == null) return layers
            val array = json.optJSONArray("list") ?: return layers
            val read = (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val id = entry.optInt("id", -1).takeIf { it in 0..MAX_ID } ?: return@mapNotNull null
                NoteLayer(
                    id,
                    entry.optString("name", "Layer ${i + 1}"),
                    entry.optBoolean("visible", true),
                    entry.optBoolean("locked", false),
                    entry.optDouble("opacity", 1.0).toFloat(),
                )
            }.distinctBy { it.id }
            if (read.isNotEmpty()) {
                layers.list.clear()
                layers.list += read
            }
            layers.active = json.optInt("active", ALL).takeIf { a -> a == ALL || read.any { it.id == a } } ?: ALL
            layers.writeTo = json.optInt("writeTo", layers.list.first().id)
            layers.styleChanged()
            return layers
        }
    }
}

/**
 * Which layer each stroke is on. Strokes are immutable library objects, so,
 * like [StrokeTags], the layer rides beside them; strokes read from a file all
 * get an entry, which is how a stroke with none is known to be new.
 */
internal object StrokeLayers {
    private val layers = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Stroke, Int>())

    fun of(stroke: Stroke): Int = layers[stroke] ?: NoteLayers.BASE
    fun known(stroke: Stroke): Boolean = layers.containsKey(stroke)
    fun set(stroke: Stroke, layer: Int) { layers[stroke] = layer.coerceIn(0, NoteLayers.MAX_ID) }
    fun carry(from: Stroke, to: Stroke) { if (from !== to) layers[from]?.let { layers[to] = it } }
}

/**
 * Draws [strokes] in order, with each run of a translucent layer's strokes
 * composited once at that layer's opacity: the run is drawn at full strength
 * into an offscreen layer that is then blended at the opacity, so strokes of
 * one layer that overlap do not darken each other and no colour is changed.
 * Stroke lists are kept in layer order (see [settleLayers]), so a layer is
 * normally one run. A layer at 0% is skipped; it is still there to hit.
 *
 * With [look] null (every layer opaque) this is the plain loop it replaces:
 * no layer, no lookup per stroke.
 */
internal inline fun drawLayered(
    canvas: Canvas,
    strokes: Iterable<Stroke>,
    look: LayerLook?,
    bounds: RectF?,
    include: (Stroke) -> Boolean = { true },
    draw: (Stroke) -> Unit,
) {
    if (look == null) {
        for (stroke in strokes) if (include(stroke)) draw(stroke)
        return
    }
    var saved = -1
    var current = -1
    for (stroke in strokes) {
        if (!include(stroke)) continue
        val layer = StrokeLayers.of(stroke)
        val alpha = look.alphaOf(layer)
        if (alpha <= 0) continue
        if (layer != current) {
            if (saved >= 0) {
                canvas.restoreToCount(saved)
                saved = -1
            }
            current = layer
            if (alpha < 255) saved = canvas.saveLayerAlpha(bounds, alpha)
        }
        draw(stroke)
    }
    if (saved >= 0) canvas.restoreToCount(saved)
}

/**
 * [items] split into runs to composite, with each run's alpha: every
 * translucent layer's stretch on its own, the opaque ones between merged into
 * one run at 255. Items on a layer at 0% are left out. For exporters that
 * cannot use [drawLayered]'s canvas layers, such as the vector PDF.
 */
internal fun <T> layerRuns(items: List<T>, layerOf: (T) -> Int, alpha: IntArray): List<Pair<Int, List<T>>> {
    val runs = ArrayList<Pair<Int, List<T>>>()
    var run = ArrayList<T>()
    var runAlpha = -1
    var runLayer = -1
    for (item in items) {
        val layer = layerOf(item)
        val a = if (layer in alpha.indices) alpha[layer] else 255
        if (a <= 0) continue
        val same = if (a >= 255) runAlpha == 255 else runLayer == layer && runAlpha == a
        if (!same) {
            if (run.isNotEmpty()) runs += runAlpha to run
            run = ArrayList()
            runAlpha = if (a >= 255) 255 else a
        }
        runLayer = layer
        run += item
    }
    if (run.isNotEmpty()) runs += runAlpha to run
    return runs
}

/**
 * [items] as a reader of the page sees them: only shown layers, bottom layer
 * first, each layer's own order kept. Pages read straight from disk hold
 * hidden ink and file order, so exports pass their strokes through this.
 */
internal fun <T> NoteLayers.shownInOrder(items: List<T>, layerOf: (T) -> Int): List<T> {
    if (list.size <= 1 && !hasHidden) return items
    val rank = rankTable()
    val shown = BooleanArray(NoteLayers.MAX_ID + 1) { true }
    for (layer in list) shown[layer.id] = layer.visible
    fun at(item: T): Int = layerOf(item).coerceIn(0, NoteLayers.MAX_ID)
    // sortedBy is stable: strokes of one layer stay in the order they were written.
    return items.filter { shown[at(it)] }.sortedBy { rank[at(it)] }
}

/**
 * The colour to draw wet ink in so that it matches the same stroke once
 * committed. Committed ink is composited per layer at the layer's opacity and
 * the page tone's colour matrix is then applied to the finished page. Wet ink
 * lies on a front-buffered surface outside that page, where neither reaches,
 * so both are applied to the stroke's own colour instead: opacity scales its
 * alpha, then the matrix maps its RGB. Source-over is linear in the source and
 * the tone matrices are affine with an identity alpha row, so for a stroke
 * over the page this gives the same pixels the committed stroke will have:
 * M(a·c + (1-a)·p) = a·M(c) + (1-a)·M(p). Display only - the stroke is saved
 * in its own colour.
 */
internal fun wetInkArgb(argb: Int, opacity: Float, tone: FloatArray?): Int {
    var alpha = argb ushr 24
    if (opacity < 1f) alpha = (alpha * sanitizeOpacity(opacity) + 0.5f).toInt().coerceIn(0, 255)
    if (tone == null || tone.size < 15) return (alpha shl 24) or (argb and 0xFFFFFF)
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    fun channel(row: Int): Int =
        (tone[row * 5] * r + tone[row * 5 + 1] * g + tone[row * 5 + 2] * b + tone[row * 5 + 4])
            .roundToInt().coerceIn(0, 255)
    return (alpha shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
}

/**
 * Brings a page's ink in line with the layers: new strokes go on [NoteLayers.target],
 * strokes of hidden layers move aside into [Page.hiddenStrokes] (so nothing
 * draws or hits them) and come back when shown, and the visible ones are kept
 * in layer order. True when the stroke list changed.
 */
internal fun Page.settleLayers(layers: NoteLayers): Boolean {
    // Pen-up is the common case: one stroke appended to a page already
    // settled under the same layers. Only that tail needs looking at; every
    // stroke of a dense page was being checked on every lift.
    val shape = layers.shape()
    val mark = settleMarks[this]
    val from = if (mark != null && mark.shape == shape && mark.hidden == hiddenStrokes.size &&
        strokes.size >= mark.size && (mark.size == 0 || strokes[mark.size - 1] === mark.last)) mark.size else 0
    val changed = if (from == 0) {
        WorkStats.layerSettlesFull.incrementAndGet()
        settleAll(layers)
    } else {
        WorkStats.layerSettlesTail.incrementAndGet()
        settleTail(layers, from)
    }
    settleMarks[this] = SettleMark(strokes.size, strokes.lastOrNull(), hiddenStrokes.size, shape)
    if (changed) {
        // The file's order no longer matches what is in memory: the next save rewrites it.
        savedOnDisk = 0
        revision++
    }
    return changed
}

/** How far a page is known to be settled, and under which layers. */
private class SettleMark(val size: Int, val last: Stroke?, val hidden: Int, val shape: Long)
private val settleMarks = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Page, SettleMark>())

/**
 * The order and visibility of the layers in one number: any change to either
 * changes it. Lock and opacity are left out on purpose - they change neither
 * which strokes a page holds nor their order, so they must not force a resettle.
 */
private fun NoteLayers.shape(): Long {
    var h = 17L
    for (layer in list) h = h * 31 + layer.id * 2 + (if (layer.visible) 1 else 0)
    return h * 31 + list.size
}

/** Drawing rank by layer id, looked up per stroke without a list scan. */
private fun NoteLayers.rankTable(): IntArray {
    val table = IntArray(NoteLayers.MAX_ID + 1)
    list.forEachIndexed { index, layer -> table[layer.id] = index }
    return table
}

/** Strokes from [from] on: the ones added since the page was last settled. */
private fun Page.settleTail(layers: NoteLayers, from: Int): Boolean {
    var changed = false
    for (i in from until strokes.size) {
        val stroke = strokes[i]
        if (!StrokeLayers.known(stroke)) StrokeLayers.set(stroke, layers.target)
    }
    if (layers.hasHidden) {
        val away = (from until strokes.size).map { strokes[it] }.filter { !layers.isVisible(StrokeLayers.of(it)) }
        if (away.isNotEmpty()) {
            val gone = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(away) }
            strokes.removeAll(gone::contains)
            hiddenStrokes += away
            changed = true
        }
    }
    if (layers.list.size > 1) {
        val rank = layers.rankTable()
        var last = if (from > 0 && from - 1 < strokes.size) rank[StrokeLayers.of(strokes[from - 1])] else -1
        for (i in from until strokes.size) {
            val r = rank[StrokeLayers.of(strokes[i])]
            if (r < last) {
                // Written on a lower layer than what is on top: the whole page reorders.
                sortByLayer(rank)
                return true
            }
            last = r
        }
    }
    return changed
}

private fun Page.sortByLayer(rank: IntArray) {
    val ordered = strokes.sortedBy { rank[StrokeLayers.of(it)] }
    strokes.clear()
    strokes += ordered
}

private fun Page.settleAll(layers: NoteLayers): Boolean {
    var changed = false
    for (stroke in strokes) if (!StrokeLayers.known(stroke)) StrokeLayers.set(stroke, layers.target)
    if (hiddenStrokes.isNotEmpty()) {
        val back = hiddenStrokes.filter { layers.isVisible(StrokeLayers.of(it)) }
        if (back.isNotEmpty()) {
            val shown = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(back) }
            hiddenStrokes.removeAll(shown::contains)
            strokes += back
            changed = true
        }
    }
    if (layers.hasHidden) {
        val away = strokes.filter { !layers.isVisible(StrokeLayers.of(it)) }
        if (away.isNotEmpty()) {
            val gone = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(away) }
            strokes.removeAll(gone::contains)
            hiddenStrokes += away
            changed = true
        }
    }
    if (layers.list.size > 1 || changed) {
        val rank = layers.rankTable()
        var last = -1
        val sorted = strokes.all { val r = rank[StrokeLayers.of(it)]; (r >= last).also { last = r } }
        if (!sorted) {
            sortByLayer(rank)
            changed = true
        }
    }
    return changed
}

/** Every stroke the page holds, shown or not, in the order the file keeps them. */
internal val Page.allStrokes: List<Stroke> get() = if (hiddenStrokes.isEmpty()) strokes else hiddenStrokes + strokes

// ---- the layer panel ----------------------------------------------------------

private val ROW_GAP = 6.dp
private val LIST_PADDING = 10.dp
private const val DELETE_UNDO_MS = 8_000L

/** The eye, open: Lucide has no open eye among this app's icons, so both sets use Reicon's. */
private val shownIcon get() = Reicons.Visibility
private val hiddenIcon get() = AppIconStyle.pick(Lucide.EyeOff, Reicons.VisibilityOff)
private val moreIcon get() = AppIconStyle.pick(Lucide.Ellipsis, Reicons.MoreVert)
private val addIcon get() = AppIconStyle.pick(Lucide.Plus, Reicons.Add)
private val gripIcon get() = AppIconStyle.pick(Lucide.Menu, Reicons.Menu)

/**
 * The current write-target layer as a small toolbar chip: its name, and a
 * lock when ink cannot go there. Tapping it opens the layer panel.
 */
@Composable
internal fun LayerTargetChip(view: InkCanvasView?, onClick: () -> Unit) {
    if (view == null) return
    // Read so the chip follows every change made through the canvas.
    view.layersRevision.intValue
    val layers = view.layers
    val target = layers.layer(layers.target) ?: return
    val scheme = MaterialTheme.colorScheme
    val locked = target.locked
    val description = "Layer: ${target.name}" + if (locked) ", locked" else ""
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .widthIn(max = 160.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHighest.copy(alpha = 0.72f))
            .clickable(onClickLabel = "Open layers", onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(AppIcons.layers, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            target.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (locked) {
            Spacer(Modifier.width(4.dp))
            AppIcon(Lucide.Lock.vector, null, Modifier.size(16.dp), tint = scheme.error)
        }
    }
}

/** Drag state for one row's handle: where the handle is, and where the finger was. */
private class HandleTrack {
    var coordinates: LayoutCoordinates? = null
    var lastY = 0f

    /**
     * The finger in root coordinates. The row being dragged is itself moved
     * under the finger, so a position local to it would shrink each delta by
     * however far the row was just moved.
     */
    fun rootY(local: androidx.compose.ui.geometry.Offset): Float {
        val at = coordinates?.takeIf { it.isAttached } ?: return local.y
        return at.localToRoot(local).y
    }
}

/**
 * The note's layers as a side panel: pick where new ink goes, choose between
 * editing one layer and all of them, show, hide, lock, set opacity, rename,
 * duplicate, delete, and drag to reorder. Every change goes through the
 * canvas, which saves it; opacity is previewed while the slider moves and
 * saved when it is let go.
 */
@Composable
internal fun LayerPanel(view: InkCanvasView, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // Read so the panel follows every change made through the canvas, undo included.
    val revision = view.layersRevision.intValue
    val layers = view.layers
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current

    var renaming by remember { mutableStateOf<NoteLayer?>(null) }
    var deleting by remember { mutableStateOf<Pair<NoteLayer, Int>?>(null) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var opacityFor by remember { mutableStateOf<Int?>(null) }
    var deletedName by remember { mutableStateOf<String?>(null) }

    renaming?.let { layer ->
        var text by remember(layer) { mutableStateOf(layer.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename layer") },
            text = { OutlinedTextField(text, { text = it.take(NoteLayers.MAX_NAME) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    view.renameLayer(layer.id, text.trim())
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { (layer, count) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete '${layer.name}'?") },
            text = {
                Text(
                    when (count) {
                        0 -> "The layer has no ink. You can undo this."
                        1 -> "Its 1 stroke is removed from every page. You can undo this."
                        else -> "Its $count strokes are removed from every page. You can undo this."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    if (view.deleteLayer(layer.id)) deletedName = layer.name
                }) { Text("Delete", color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    // The undo offer lasts a while; the toolbar's undo works after it too.
    LaunchedEffect(deletedName) {
        if (deletedName != null) {
            delay(DELETE_UNDO_MS)
            deletedName = null
        }
    }

    // ---- drag to reorder ----
    var dragging by remember { mutableStateOf<Int?>(null) }
    val dragOrder = remember { mutableStateListOf<Int>() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    val gapPx = with(density) { ROW_GAP.toPx() }
    val padPx = with(density) { LIST_PADDING.toPx() }
    val edgePx = with(density) { 56.dp.toPx() }
    val maxStepPx = with(density) { 14.dp.toPx() }

    // Top of the stack first, as layer panels list them.
    val ids: List<Int> = if (dragging != null) dragOrder.toList() else layers.list.asReversed().map { it.id }

    /** Swaps the dragged row past a neighbour once it is more than halfway over it. */
    fun settleDrag() {
        val id = dragging ?: return
        while (true) {
            val at = dragOrder.indexOf(id)
            if (at < 0) return
            if (dragOffset > 0f && at < dragOrder.lastIndex) {
                val step = (heights[dragOrder[at + 1]] ?: 0) + gapPx
                if (dragOffset > step / 2f) {
                    dragOrder.add(at + 1, dragOrder.removeAt(at))
                    dragOffset -= step
                    continue
                }
            } else if (dragOffset < 0f && at > 0) {
                val step = (heights[dragOrder[at - 1]] ?: 0) + gapPx
                if (-dragOffset > step / 2f) {
                    dragOrder.add(at - 1, dragOrder.removeAt(at))
                    dragOffset += step
                    continue
                }
            }
            return
        }
    }

    fun startDrag(id: Int) {
        dragOrder.clear()
        dragOrder.addAll(layers.list.asReversed().map { it.id })
        dragOffset = 0f
        menuFor = null
        dragging = id
    }

    fun dragBy(dy: Float) {
        if (dragging == null) return
        dragOffset += dy
        settleDrag()
    }

    /** One move of the list for the whole drag, so a page is resettled once, not per row passed. */
    fun endDrag() {
        val id = dragging ?: return
        val at = dragOrder.indexOf(id)
        dragging = null
        dragOffset = 0f
        if (at >= 0) view.moveLayer(id, layers.list.lastIndex - at)
    }

    // Near either edge of the list the drag scrolls it, faster the closer it gets.
    LaunchedEffect(dragging) {
        val id = dragging ?: return@LaunchedEffect
        while (dragging == id) {
            withFrameNanos { }
            val at = dragOrder.indexOf(id)
            if (at < 0) break
            var top = padPx
            for (i in 0 until at) top += (heights[dragOrder[i]] ?: 0) + gapPx
            val y = top + dragOffset - scroll.value
            val h = heights[id] ?: 0
            val step = when {
                y < edgePx -> -((edgePx - y) / edgePx).coerceIn(0f, 1f) * maxStepPx
                y + h > viewport - edgePx -> ((y + h - (viewport - edgePx)) / edgePx).coerceIn(0f, 1f) * maxStepPx
                else -> 0f
            }
            if (step != 0f) {
                // The content moved under a finger that did not: the row follows the finger.
                val used = scroll.scrollBy(step)
                if (used != 0f) dragBy(used)
            }
        }
    }

    SkinSurface(
        modifier = modifier
            .windowInsetsPadding(ChromeInsets)
            .padding(12.dp)
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .fillMaxHeight(0.86f),
    ) {
        Column {
            // ---- header: fixed, only the list below scrolls ----
            Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(AppIcons.layers, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    AppIconButton(
                        onClick = {
                            val added = view.addLayer()
                            if (added != null) opacityFor = null
                        },
                        icon = addIcon,
                        contentDescription = "Add layer",
                        enabled = layers.canAdd(),
                    )
                    AppIconButton(onClick = onClose, icon = AppIcons.close, contentDescription = "Close layers")
                }
                Text(
                    "Layers apply to every page of this note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.outline,
                )
                val all = layers.active == NoteLayers.ALL
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable { view.setEditAllLayers(!all) }
                        .padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Edit all layers", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (all) "Erase and select on every unlocked layer. New ink goes to the marked layer."
                            else "Only the highlighted layer can be written, erased or selected.",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.outline,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    SkinSwitch(checked = all, onCheckedChange = { view.setEditAllLayers(it) })
                }
                deletedName?.let { name ->
                    if (view.canUndoLayerDelete()) Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(end = 10.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.inverseSurface)
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Layer '$name' deleted",
                            color = scheme.inverseOnSurface,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            view.undoLayerDelete()
                            deletedName = null
                        }) { Text("Undo", color = scheme.inversePrimary) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 2.dp))

            // ---- the stack ----
            Column(
                Modifier
                    .weight(1f)
                    .onSizeChanged { viewport = it.height }
                    .verticalScroll(scroll)
                    .padding(LIST_PADDING),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            ) {
                for (id in ids) key(id) {
                    val layer = layers.layer(id)
                    if (layer != null) {
                        val listAt = layers.list.indexOf(layer)
                        val isDragged = dragging == id
                        LayerRow(
                            layer = layer,
                            revision = revision,
                            isTarget = layers.target == id,
                            editAll = layers.active == NoteLayers.ALL,
                            dragged = isDragged,
                            offset = { if (dragging == id) dragOffset else 0f },
                            opacityOpen = opacityFor == id,
                            menuOpen = menuFor == id,
                            canMoveUp = listAt < layers.list.lastIndex,
                            canMoveDown = listAt > 0,
                            canDelete = layers.list.size > 1,
                            canDuplicate = layers.canAdd(),
                            onMeasured = { heights[id] = it },
                            onSelect = { view.setWriteLayer(id) },
                            onVisible = { view.setLayerVisible(id, it) },
                            onLocked = { view.setLayerLocked(id, it) },
                            onMenu = { menuFor = if (it) id else null },
                            onOpacityOpen = { opacityFor = if (opacityFor == id) null else id },
                            onOpacity = { view.previewLayerOpacity(id, it) },
                            onOpacityDone = { view.commitLayerOpacity() },
                            onRename = { renaming = layer },
                            onDuplicate = { view.duplicateLayer(id) },
                            onMove = { by -> view.moveLayer(id, listAt + by) },
                            onDelete = { deleting = layer to view.layerInkCount(id) },
                            onDragStart = { startDrag(id) },
                            onDrag = ::dragBy,
                            onDragEnd = ::endDrag,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: NoteLayer,
    // Passed so the row redraws when the layer object changes in place.
    revision: Int,
    isTarget: Boolean,
    editAll: Boolean,
    dragged: Boolean,
    offset: () -> Float,
    opacityOpen: Boolean,
    menuOpen: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    canDuplicate: Boolean,
    onMeasured: (Int) -> Unit,
    onSelect: () -> Unit,
    onVisible: (Boolean) -> Unit,
    onLocked: (Boolean) -> Unit,
    onMenu: (Boolean) -> Unit,
    onOpacityOpen: () -> Unit,
    onOpacity: (Float) -> Unit,
    onOpacityDone: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val track = remember { HandleTrack() }
    val percent = (layer.opacity * 100f).roundToInt()
    val background = when {
        isTarget -> scheme.primaryContainer
        else -> scheme.surfaceContainerHighest.copy(alpha = 0.45f)
    }
    val onBackground = if (isTarget) scheme.onPrimaryContainer else scheme.onSurface
    val status = buildList {
        if (isTarget) add(if (layer.locked) "Target, locked: no new ink" else "New ink goes here")
        else if (!editAll) add("View only")
        if (!layer.visible) add("Hidden")
        else if (layer.locked && !isTarget) add("Locked")
        if (percent < 100) add("$percent%")
    }.joinToString(" · ")

    Column(
        Modifier
            .zIndex(if (dragged) 1f else 0f)
            .graphicsLayer {
                // Read here, in the layer, so a drag moves the row without recomposing the list.
                translationY = offset()
                if (dragged) {
                    shadowElevation = 8f
                    scaleX = 1.02f
                    scaleY = 1.02f
                }
            }
            .onSizeChanged { onMeasured(it.height) }
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background),
    ) {
        Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            // The handle: drag straight away, no long press to discover.
            Box(
                Modifier
                    .size(width = 40.dp, height = 56.dp)
                    .onGloballyPositioned { track.coordinates = it }
                    .pointerInput(layer.id) {
                        detectVerticalDragGestures(
                            onDragStart = { start ->
                                track.lastY = track.rootY(start)
                                onDragStart()
                            },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd,
                        ) { change, _ ->
                            change.consume()
                            val y = track.rootY(change.position)
                            onDrag(y - track.lastY)
                            track.lastY = y
                        }
                    }
                    .semantics { contentDescription = "Drag to reorder ${layer.name}" },
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(gripIcon, null, Modifier.size(18.dp), tint = onBackground.copy(alpha = 0.6f))
            }
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clickable(onClickLabel = "Write on this layer", onClick = onSelect)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    layer.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status.isNotEmpty()) Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = onBackground.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppIconButton(
                onClick = { onVisible(!layer.visible) },
                icon = if (layer.visible) shownIcon else hiddenIcon,
                contentDescription = if (layer.visible) "Hide ${layer.name}" else "Show ${layer.name}",
                tint = onBackground.copy(alpha = if (layer.visible) 0.9f else 0.5f),
            )
            AppIconButton(
                onClick = { onLocked(!layer.locked) },
                icon = Lucide.Lock.vector,
                contentDescription = if (layer.locked) "Unlock ${layer.name}" else "Lock ${layer.name}",
                // No open padlock among the icons: unlocked is the same lock, faint.
                tint = if (layer.locked) scheme.error else onBackground.copy(alpha = 0.3f),
            )
            Box {
                AppIconButton(onClick = { onMenu(true) }, icon = moreIcon, contentDescription = "More for ${layer.name}")
                DropdownMenu(menuOpen, onDismissRequest = { onMenu(false) }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { onMenu(false); onRename() })
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        enabled = canDuplicate,
                        onClick = { onMenu(false); onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (opacityOpen) "Hide opacity" else "Opacity ($percent%)") },
                        onClick = { onMenu(false); onOpacityOpen() },
                    )
                    DropdownMenuItem(text = { Text("Move up") }, enabled = canMoveUp, onClick = { onMenu(false); onMove(1) })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = canMoveDown, onClick = { onMenu(false); onMove(-1) })
                    DropdownMenuItem(
                        text = { Text("Delete", color = if (canDelete) scheme.error else scheme.outline) },
                        enabled = canDelete,
                        onClick = { onMenu(false); onDelete() },
                    )
                }
            }
        }
        if (opacityOpen) OpacityRow(layer.id, layer.opacity, revision, onBackground, onOpacity, onOpacityDone)
    }
}

/**
 * The opacity slider. Moving it only previews (the canvas recomposites, nothing
 * is saved or rebuilt); letting go saves. A pause also saves, for the ways of
 * moving a slider that never lift a finger, such as accessibility actions.
 */
@Composable
private fun OpacityRow(
    id: Int,
    opacity: Float,
    revision: Int,
    color: androidx.compose.ui.graphics.Color,
    onOpacity: (Float) -> Unit,
    onDone: () -> Unit,
) {
    var value by remember(id, revision) { mutableFloatStateOf((opacity * 100f).roundToInt().toFloat()) }
    var pending by remember(id) { mutableStateOf(false) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(pending, value) {
        if (!pending) return@LaunchedEffect
        delay(700)
        pending = false
        done()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Opacity", style = MaterialTheme.typography.labelMedium, color = color)
        Spacer(Modifier.width(8.dp))
        SkinSlider(
            value = value,
            onValueChange = {
                value = it
                pending = true
                onOpacity(it / 100f)
            },
            valueRange = 0f..100f,
            step = 1f,
            modifier = Modifier
                .weight(1f)
                // Watches for the finger leaving without taking the gesture from the slider.
                .pointerInput(id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (pending && event.changes.none { it.pressed }) {
                                pending = false
                                done()
                            }
                        }
                    }
                },
        )
        Text(
            "${value.roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.widthIn(min = 44.dp).padding(start = 8.dp),
        )
    }
}
