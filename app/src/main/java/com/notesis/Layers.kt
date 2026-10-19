package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.ink.strokes.Stroke
import org.json.JSONArray
import org.json.JSONObject

/**
 * One layer of a note. Every page shares the note's layer list; a stroke
 * remembers only the [id] of the layer it was written on. [NoteLayers.list]
 * order is drawing order, bottom first.
 */
class NoteLayer(val id: Int, var name: String, var visible: Boolean = true)

/** The layers of a note, and which one the pen is on. */
class NoteLayers {
    val list: MutableList<NoteLayer> = mutableListOf(NoteLayer(BASE, "레이어 1"))

    /** The layer edits are held to, or [ALL] when every layer can be written and erased. */
    var active: Int = ALL

    /**
     * Where new ink goes while [active] is [ALL]: the layer chosen last, so
     * switching to every layer to tidy up does not move the next stroke.
     */
    var writeTo: Int = BASE

    /** The layer a new stroke is written on. */
    val target: Int get() = if (active == ALL) writeTo.takeIf { id -> list.any { it.id == id } } ?: list.first().id else active

    val hasHidden: Boolean get() = list.any { !it.visible }

    fun isVisible(id: Int): Boolean = list.firstOrNull { it.id == id }?.visible ?: true

    /** Drawing rank: a layer missing from the list (an older file, a deleted layer) sits at the bottom. */
    fun rank(id: Int): Int = list.indexOfFirst { it.id == id }.coerceAtLeast(0)

    /** Whether edits may touch a stroke on layer [id]. */
    fun editable(id: Int): Boolean = active == ALL || active == id

    fun add(): NoteLayer {
        val id = (list.maxOfOrNull { it.id } ?: BASE) + 1
        val layer = NoteLayer(id.coerceAtMost(MAX_ID), "레이어 ${list.size + 1}")
        list += layer
        return layer
    }

    fun copyFrom(other: NoteLayers) {
        list.clear()
        other.list.mapTo(list) { NoteLayer(it.id, it.name, it.visible) }
        active = other.active
        writeTo = other.writeTo
    }

    fun toJson(): JSONObject = JSONObject()
        .put("active", active)
        .put("writeTo", writeTo)
        .put("list", JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("visible", it.visible)) }
        })

    companion object {
        const val ALL = -1
        const val BASE = 0
        /** A layer id is stored in one byte per stroke. */
        const val MAX_ID = 255
        const val MAX_LAYERS = 32

        fun fromJson(json: JSONObject?): NoteLayers {
            val layers = NoteLayers()
            if (json == null) return layers
            val array = json.optJSONArray("list") ?: return layers
            val read = (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val id = entry.optInt("id", -1).takeIf { it in 0..MAX_ID } ?: return@mapNotNull null
                NoteLayer(id, entry.optString("name", "레이어 ${i + 1}"), entry.optBoolean("visible", true))
            }.distinctBy { it.id }
            if (read.isNotEmpty()) {
                layers.list.clear()
                layers.list += read
            }
            layers.active = json.optInt("active", ALL).takeIf { a -> a == ALL || read.any { it.id == a } } ?: ALL
            layers.writeTo = json.optInt("writeTo", layers.list.first().id)
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
 * Brings a page's ink in line with the layers: new strokes go on [NoteLayers.target],
 * strokes of hidden layers move aside into [Page.hiddenStrokes] (so nothing
 * draws or hits them) and come back when shown, and the visible ones are kept
 * in layer order. True when the stroke list changed.
 */
internal fun Page.settleLayers(layers: NoteLayers): Boolean {
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
        var last = -1
        val sorted = strokes.all { val r = layers.rank(StrokeLayers.of(it)); (r >= last).also { last = r } }
        if (!sorted) {
            val ordered = strokes.sortedBy { layers.rank(StrokeLayers.of(it)) }
            strokes.clear()
            strokes += ordered
            changed = true
        }
    }
    if (changed) {
        // The file's order no longer matches what is in memory: the next save rewrites it.
        savedOnDisk = 0
        revision++
    }
    return changed
}

/** Every stroke the page holds, shown or not, in the order the file keeps them. */
internal val Page.allStrokes: List<Stroke> get() = if (hiddenStrokes.isEmpty()) strokes else hiddenStrokes + strokes

/**
 * The layer list: pick the one to write on (or every layer), show or hide,
 * rename, add, delete, and move up or down.
 */
@Composable
internal fun LayersDialog(
    layers: NoteLayers,
    onChanged: () -> Unit,
    onDelete: (NoteLayer) -> Unit,
    onDismiss: () -> Unit,
) {
    var revision by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<NoteLayer?>(null) }
    var deleting by remember { mutableStateOf<NoteLayer?>(null) }
    fun changed() { revision++; onChanged() }

    renaming?.let { layer ->
        var text by remember(layer) { mutableStateOf(layer.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("레이어 이름") },
            text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { layer.name = text.trim(); renaming = null; changed() }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("취소") } },
        )
    }
    deleting?.let { layer ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("'${layer.name}' 삭제") },
            text = { Text("이 레이어와 그 위의 필기가 모든 페이지에서 지워집니다. 실행 취소로 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(layer); changed() }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("레이어") },
        text = {
            key(revision) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "고른 레이어의 필기만 쓰고 지우고 고를 수 있습니다. 전체 레이어에서는 모두 편집할 수 있고, 새 필기는 마지막에 고른 레이어로 갑니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    LayerRow(
                        name = "전체 레이어",
                        selected = layers.active == NoteLayers.ALL,
                        onSelect = { layers.active = NoteLayers.ALL; changed() },
                    )
                    HorizontalDivider()
                    // Top of the stack first, as layer panels list them.
                    layers.list.asReversed().toList().forEach { layer ->
                        val at = layers.list.indexOf(layer)
                        LayerRow(
                            name = layer.name,
                            selected = layers.active == layer.id,
                            writeTarget = layers.active == NoteLayers.ALL && layers.target == layer.id,
                            // Picking a layer to write on shows it: ink vanishing as it is drawn helps no one.
                            onSelect = { layers.active = layer.id; layers.writeTo = layer.id; layer.visible = true; changed() },
                        ) {
                            TextButton(onClick = { layer.visible = !layer.visible; changed() }, contentPadding = PaddingValues(6.dp)) {
                                Text(if (layer.visible) "보임" else "숨김")
                            }
                            TextButton(enabled = at < layers.list.lastIndex, contentPadding = PaddingValues(6.dp), onClick = {
                                layers.list.add(at + 1, layers.list.removeAt(at)); changed()
                            }) { Text("▲") }
                            TextButton(enabled = at > 0, contentPadding = PaddingValues(6.dp), onClick = {
                                layers.list.add(at - 1, layers.list.removeAt(at)); changed()
                            }) { Text("▼") }
                            TextButton(onClick = { renaming = layer }, contentPadding = PaddingValues(6.dp)) { Text("이름") }
                            TextButton(enabled = layers.list.size > 1, contentPadding = PaddingValues(6.dp),
                                onClick = { deleting = layer }) { Text("삭제") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = {
            TextButton(enabled = layers.list.size < NoteLayers.MAX_LAYERS && (layers.list.maxOfOrNull { it.id } ?: 0) < NoteLayers.MAX_ID,
                onClick = {
                    val layer = layers.add()
                    layers.active = layer.id
                    layers.writeTo = layer.id
                    changed()
                }) { Text("+ 레이어 추가") }
        },
    )
}

@Composable
private fun LayerRow(
    name: String,
    selected: Boolean,
    writeTarget: Boolean = false,
    onSelect: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface)
            if (writeTarget) Text("새 필기가 들어가는 레이어", style = MaterialTheme.typography.labelSmall, color = scheme.outline)
        }
        actions()
    }
}
