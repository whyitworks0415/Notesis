package com.notesis

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How long a bar down the side is: one cell per tool, each a fingertip tall, and its inset at both ends. */
internal fun verticalBarLength(count: Int, compact: Boolean, padding: Dp, scale: Float = 1f): Dp =
    (if (compact) VERTICAL_CELL_COMPACT else VERTICAL_CELL) * scale * count + padding * 2

private val VERTICAL_CELL = 52.dp
private val VERTICAL_CELL_COMPACT = 44.dp

/**
 * The selection pill's radius in a tool strip [barHeight] tall whose own
 * corner is [corner], its cells [inset] in: see the float [toolPillRadius].
 */
internal fun toolPillRadius(corner: Dp, barHeight: Dp, inset: Dp): Dp =
    toolPillRadius(corner.value, barHeight.value, inset.value, Ui.Corner.nestedMin.value).dp

/**
 * Equal cells along a bar, each placed to the fraction of a pixel. A row of
 * weights rounds every cell to whole pixels and hands the spare pixels to
 * the first cells; while the bar's width springs, which cells get them
 * changes from frame to frame, and the icons shook back and forth by a pixel
 * as the bar grew. Here each cell's middle is exactly where it belongs.
 */
@Composable
internal fun EvenCells(vertical: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val along = (if (vertical) height else width).toFloat()
        val across = if (vertical) width else height
        val cell = along / measurables.size.coerceAtLeast(1)
        val size = kotlin.math.ceil(cell).toInt().coerceAtLeast(0)
        val placeables = measurables.map {
            it.measure(if (vertical) androidx.compose.ui.unit.Constraints.fixed(across, size)
                else androidx.compose.ui.unit.Constraints.fixed(size, across))
        }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val start = evenCellStart(index, cell, size)
                val whole = kotlin.math.floor(start).toInt()
                val part = start - whole
                if (vertical) placeable.placeWithLayer(0, whole) { translationY = part }
                else placeable.placeRelativeWithLayer(whole, 0) { translationX = part }
            }
        }
    }
}

/** Where cell [index] of cells [cell] long starts, drawn [size] long about the same middle. */
internal fun evenCellStart(index: Int, cell: Float, size: Int): Float = index * cell + (cell - size) / 2f
