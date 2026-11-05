package com.notesis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The split panel: "Not split" and each ready-made split, drawn as itself,
 * the opened note's part tinted. The same panel as the pen's settings.
 */
@Composable
internal fun SplitPickerPanel(current: SplitNode?, onPick: (SplitPreset?) -> Unit, onDismiss: () -> Unit) {
    val panel = rememberToolPanelState()
    val shape = current?.shape()
    ToolPanel(
        title = "Split notes",
        state = panel,
        onOutside = { panel.close(onDismiss) },
        maxWidth = 520.dp,
        actions = {
            TextButton(onClick = { panel.close(onDismiss) }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) {
                Text("Done")
            }
        },
    ) {
        Text(
            "This note starts in the tinted part; every other part opens a note of its own, and " +
                "each is written on the same way. Drag the line between two parts to resize them, " +
                "or to an edge to close the part it covers. Tap the handle on the line to swap the " +
                "parts, change a part's note or split it again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        val choices: List<SplitPreset?> = listOf(null) + SplitPreset.entries
        choices.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                row.forEach { preset ->
                    val chosen = if (preset == null) current == null else shape == preset.tree()
                    SplitTile(preset, chosen, Modifier.weight(1f)) { panel.close { onPick(preset) } }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SplitTile(preset: SplitPreset?, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = preset?.label ?: "Not split"
    val shape = RoundedCornerShape(nestedCorner(Ui.Corner.chip))
    Column(
        modifier
            .clip(shape)
            .background(if (chosen) scheme.primaryContainer else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label; selected = chosen }
            .padding(Ui.Space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Ui.Space.xs),
    ) {
        val main = scheme.primary
        val other = scheme.onSurfaceVariant.copy(alpha = 0.35f)
        Canvas(Modifier.size(72.dp, 48.dp)) {
            val gap = 3.dp.toPx()
            val radius = CornerRadius(4.dp.toPx())
            val tree = preset?.tree() ?: SplitNode.Pane(0)
            tree.layout(SplitRect(0f, 0f, size.width, size.height), gap).forEach { (id, r) ->
                drawRoundRect(if (id == 0) main.copy(alpha = 0.55f) else other, Offset(r.left, r.top), Size(r.width, r.height), radius)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * The lines between the parts. Each is thin, with a small handle at its
 * middle: drag it to resize the parts, drag it nearly to an edge and let go
 * to close what it was dragged over, tap the handle for the two sides'
 * choices ([menu]). While held, the line follows the hand and the parts are
 * laid out again once, when it is let go, so no canvas rebuilds its page on
 * every frame of the drag.
 */
@Composable
internal fun SplitSeams(
    tree: SplitNode,
    area: SplitRect,
    gap: Float,
    onMove: (SplitSeam, Float) -> Unit,
    /** The seam was let go close to an edge: close side 0 (before it) or 1 (after it). */
    onClose: (SplitSeam, Int) -> Unit,
    menu: @Composable (SplitSeam, close: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val line = scheme.outlineVariant
    val handle = scheme.onSurfaceVariant.copy(alpha = 0.55f)
    val held = scheme.primary
    val rects = tree.layout(area, gap)
    for (seam in tree.seams(area, gap)) androidx.compose.runtime.key(seam.path, seam.index) {
        var dragged by remember(seam.path, seam.index, seam.sideBySide) { mutableStateOf<Float?>(null) }
        var menuOpen by remember { mutableStateOf(false) }
        val r = seam.rect
        val shift = dragged ?: 0f
        val closing = dragged?.let { if (seam.length > 0f) tree.seamCloses(seam.path, seam.index, it / seam.length) else null }
        // What would close, darkened, while the hand is over the edge.
        if (closing != null) {
            val ids = tree.seamChild(seam.path, seam.index, closing)?.paneIds().orEmpty()
            val covered = ids.mapNotNull { rects[it] }
            if (covered.isNotEmpty()) {
                val left = covered.minOf { it.left }
                val top = covered.minOf { it.top }
                val right = covered.maxOf { it.right }
                val bottom = covered.maxOf { it.bottom }
                Box(
                    Modifier
                        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
                        .background(Color.Black.copy(alpha = 0.38f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Let go to close", color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        // The line itself, as wide as the room between the parts.
        Box(
            Modifier
                .offset { IntOffset((r.left + if (seam.sideBySide) shift else 0f).roundToInt(), (r.top + if (seam.sideBySide) 0f else shift).roundToInt()) }
                .size(with(density) { r.width.coerceAtLeast(1f).toDp() }, with(density) { r.height.coerceAtLeast(1f).toDp() })
                .background(if (dragged != null) held else line),
        )
        // A fingertip's reach, centred on the line, however thin the line is.
        val reach = with(density) { SEAM_TOUCH.toPx() }
        val left = if (seam.sideBySide) (r.left + r.right) / 2f - reach / 2f else r.left
        val top = if (seam.sideBySide) r.top else (r.top + r.bottom) / 2f - reach / 2f
        val width = if (seam.sideBySide) reach else r.width
        val height = if (seam.sideBySide) r.height else reach
        Box(
            Modifier
                .offset { IntOffset((left + if (seam.sideBySide) shift else 0f).roundToInt(), (top + if (seam.sideBySide) 0f else shift).roundToInt()) }
                .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                .pointerInput(seam) {
                    detectDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            val moved = dragged ?: 0f
                            dragged = null
                            if (seam.length <= 0f || moved == 0f) return@detectDragGestures
                            val fraction = moved / seam.length
                            val side = tree.seamCloses(seam.path, seam.index, fraction)
                            if (side != null) onClose(seam, side) else onMove(seam, fraction)
                        },
                        onDragCancel = { dragged = null },
                    ) { change, amount ->
                        change.consume()
                        dragged = (dragged ?: 0f) + if (seam.sideBySide) amount.x else amount.y
                    }
                }
                .semantics { contentDescription = if (seam.sideBySide) "Resize the parts left and right" else "Resize the parts above and below" },
            contentAlignment = Alignment.Center,
        ) {
            Box {
                Box(
                    Modifier
                        .then(if (seam.sideBySide) Modifier.size(SEAM_TOUCH, SEAM_TOUCH * 2) else Modifier.size(SEAM_TOUCH * 2, SEAM_TOUCH))
                        .pointerInput(seam) { detectTapGestures { menuOpen = true } }
                        .semantics { contentDescription = "Split options" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .then(if (seam.sideBySide) Modifier.size(4.dp, 36.dp) else Modifier.size(36.dp, 4.dp))
                            .clip(CircleShape)
                            .background(if (dragged != null) held else handle),
                    )
                }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu(seam) { menuOpen = false }
                }
            }
        }
    }
}

/**
 * A note for one part of the split, found by name: every note but those
 * already open in another part, which would then be two copies saving over
 * each other.
 */
@Composable
internal fun SplitNotePicker(notes: List<NoteMeta>, current: String?, onPick: (NoteMeta) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open a note here") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Find a note") },
                    modifier = Modifier.fillMaxWidth())
                val matches = notes.filter { it.title.contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (matches.isEmpty()) item { Text("No results", color = MaterialTheme.colorScheme.outline) }
                    items(matches, key = { it.id }) { candidate ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(candidate) }.padding(vertical = Ui.Space.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(candidate.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (candidate.id == current) FontWeight.SemiBold else null)
                            if (candidate.id == current) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** How wide a seam's handle is to the finger. */
private val SEAM_TOUCH = 28.dp

/** The room between two parts: a thin line, as a divider between two windows is. */
internal val SPLIT_GAP = 3.dp

/**
 * A split carried over to the next note screen: closing the note the screen
 * was opened on, or opening another one in its part, makes a different note
 * the screen's own, and the other parts stay as they were around it.
 */
internal object SplitCarry {
    data class Held(val tree: SplitNode, val notes: Map<Int, String>, val pages: Map<Int, Int>)

    private var held: Pair<String, Held>? = null

    fun put(noteId: String, split: Held) { held = noteId to split }

    /** The split held for [noteId], once; anything held for another note is dropped. */
    fun take(noteId: String): Held? = held?.takeIf { it.first == noteId }?.second.also { held = null }
}
