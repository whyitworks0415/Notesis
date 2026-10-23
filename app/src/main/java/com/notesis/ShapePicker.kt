package com.notesis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** What the shape tool offers, in the order the picker shows them: lines first, then closed shapes. */
internal val OFFERED_SHAPES = listOf(
    ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW, ShapeKind.CURVE, ShapeKind.POLYGON,
    ShapeKind.RECT, ShapeKind.OVAL, ShapeKind.TRIANGLE, ShapeKind.RIGHT_TRIANGLE,
    ShapeKind.DIAMOND, ShapeKind.PARALLELOGRAM, ShapeKind.TRAPEZOID,
    ShapeKind.PENTAGON, ShapeKind.HEXAGON, ShapeKind.STAR, ShapeKind.CHECKBOX,
)

/** Shapes dragged from one end to the other rather than corner to corner of a box. */
private val ShapeKind.drawnEndToEnd: Boolean
    get() = this == ShapeKind.LINE || this == ShapeKind.ARROW || this == ShapeKind.DOUBLE_ARROW ||
        this == ShapeKind.CURVE

/**
 * The shape tool's picker, opened by tapping the tool again: every shape it
 * draws, each shown as itself, and the two settings that decide how shapes
 * come out - hold-to-shape for the pen, and how round the corners are. The
 * same panel as the pen's own settings, out of the tool that was tapped.
 */
@Composable
internal fun ShapePickerDialog(
    current: ShapeKind,
    holdToShape: Boolean,
    onHoldToShape: (Boolean) -> Unit,
    cornerRadius: Float,
    onCornerRadius: (Float) -> Unit,
    onPick: (ShapeKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val panel = rememberToolPanelState()
    ToolPanel(
        title = "Shapes",
        state = panel,
        onOutside = { panel.close(onDismiss) },
        actions = {
            TextButton(onClick = { panel.close(onDismiss) }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) {
                Text("Done")
            }
        },
    ) {
        PanelSectionHeader("Shape")
        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            OFFERED_SHAPES.chunked(SHAPES_PER_ROW).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                    row.forEach { kind ->
                        ShapeTile(kind, kind == current, Modifier.weight(1f)) { panel.close { onPick(kind) } }
                    }
                    repeat(SHAPES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        PanelSectionHeader("Pen")
        PanelSwitchRow(
            "Hold the pen to make a shape",
            holdToShape,
            hint = "Draw with the pen and keep it still for half a second before lifting. A ring says it " +
                "took: the stroke becomes a line, triangle, rectangle or other four-sided shape, circle, " +
                "bent line or bent curve.",
            onChange = onHoldToShape,
        )
        PanelSectionHeader("Corners")
        Text(
            if (cornerRadius <= 0f) "Sharp" else "Rounded ${cornerRadius.roundToInt()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        SkinSlider(cornerRadius, onCornerRadius, 0f..120f, step = 1f)
    }
}

@Composable
private fun ShapeTile(kind: ShapeKind, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (chosen) scheme.onPrimaryContainer else scheme.onSurface
    // Concentric with the panel it sits in, as every tile in a panel is.
    val shape = RoundedCornerShape(nestedCorner(Ui.Corner.chip))
    Column(
        modifier
            .clip(shape)
            .background(if (chosen) scheme.primaryContainer else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = shapeLabel(kind); selected = chosen }
            .heightIn(min = 72.dp)
            .padding(vertical = Ui.Space.s, horizontal = Ui.Space.hair),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(40.dp)) {
            val pad = size.minDimension * 0.12f
            val far = size.minDimension - pad
            val points = when {
                // A polyline is tapped out on the page; show one with two bends.
                kind == ShapeKind.POLYGON -> listOf(
                    floatArrayOf(pad, far), floatArrayOf(size.width * 0.38f, pad),
                    floatArrayOf(size.width * 0.62f, far), floatArrayOf(far, pad),
                )
                kind.drawnEndToEnd -> shapeOutline(kind, pad, far, far, pad, axisSnap = false)
                else -> shapeOutline(kind, pad, pad, far, far, axisSnap = false)
            }
            if (points.size >= 2) {
                val path = Path().apply {
                    moveTo(points[0][0], points[0][1])
                    for (i in 1 until points.size) lineTo(points[i][0], points[i][1])
                }
                drawPath(path, ink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(
            shapeLabel(kind).substringBefore(" ("),
            style = MaterialTheme.typography.labelSmall,
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SHAPES_PER_ROW = 4
