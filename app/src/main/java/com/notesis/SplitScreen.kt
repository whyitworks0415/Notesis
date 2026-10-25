package com.notesis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Text
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
            "This note stays in the tinted part; every other part opens a note of its own. " +
                "Drag the handles between the parts to resize them, and use each part's bar to " +
                "change its note, split it again or close it.",
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
 * The handles on the seams between the parts. Dragging one moves a guide
 * line; the parts are laid out again once, when the hand comes off, so no
 * canvas rebuilds its page on every frame of the drag.
 */
@Composable
internal fun SplitSeams(tree: SplitNode, area: SplitRect, gap: Float, onMove: (SplitSeam, Float) -> Unit) {
    val density = LocalDensity.current
    val handle = MaterialTheme.colorScheme.outline
    val guide = MaterialTheme.colorScheme.primary
    for (seam in tree.seams(area, gap)) androidx.compose.runtime.key(seam.path, seam.index) {
        var dragged by remember(seam.path, seam.index, seam.sideBySide) { mutableStateOf<Float?>(null) }
        val r = seam.rect
        // A fingertip's width, centred on the seam, however thin the seam itself is.
        val reach = with(density) { SEAM_TOUCH.toPx() }
        val left = if (seam.sideBySide) (r.left + r.right) / 2f - reach / 2f else r.left
        val top = if (seam.sideBySide) r.top else (r.top + r.bottom) / 2f - reach / 2f
        val width = if (seam.sideBySide) reach else r.width
        val height = if (seam.sideBySide) r.height else reach
        val shift = dragged ?: 0f
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
                            if (seam.length > 0f && moved != 0f) onMove(seam, moved / seam.length)
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
            // While it is held, a line where the seam will go.
            if (dragged != null) {
                Box(
                    Modifier
                        .then(if (seam.sideBySide) Modifier.size(2.dp, with(density) { height.toDp() })
                            else Modifier.size(with(density) { width.toDp() }, 2.dp))
                        .background(guide),
                )
            }
            Box(
                Modifier
                    .then(if (seam.sideBySide) Modifier.size(5.dp, 56.dp) else Modifier.size(56.dp, 5.dp))
                    .clip(CircleShape)
                    .background(handle.copy(alpha = if (dragged != null) 0.95f else 0.6f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.5f), CircleShape),
            )
        }
    }
}

/** How wide a seam's handle is to the finger. */
private val SEAM_TOUCH = 24.dp

/** The room between two parts: a seam's handle sits in it. */
internal val SPLIT_GAP = 8.dp
