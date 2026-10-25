package com.notesis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The handles a floating window is sized by: one in each corner, as a short
 * arc along the window's own rounded corner, and one in the middle of each
 * side and of the bottom, as a short bar along that edge. [top] adds the top
 * edge's, for a window that is not moved by its top bar.
 */
@Composable
internal fun BoxScope.FrameGrips(
    onResize: (horizontal: FrameHandle, vertical: FrameHandle, dx: Float, dy: Float) -> Unit,
    onEnd: () -> Unit,
    top: Boolean = false,
) {
    for (h in listOf(FrameHandle.START, FrameHandle.END)) for (v in listOf(FrameHandle.START, FrameHandle.END)) {
        FrameGrip(h, v, onResize, onEnd, Modifier.align(gripAlignment(h, v)).size(FRAME_CORNER_GRIP))
    }
    FrameGrip(FrameHandle.START, FrameHandle.NONE, onResize, onEnd,
        Modifier.align(Alignment.CenterStart).size(FRAME_EDGE_GRIP_THICK, FRAME_EDGE_GRIP_LONG))
    FrameGrip(FrameHandle.END, FrameHandle.NONE, onResize, onEnd,
        Modifier.align(Alignment.CenterEnd).size(FRAME_EDGE_GRIP_THICK, FRAME_EDGE_GRIP_LONG))
    FrameGrip(FrameHandle.NONE, FrameHandle.END, onResize, onEnd,
        Modifier.align(Alignment.BottomCenter).size(FRAME_EDGE_GRIP_LONG, FRAME_EDGE_GRIP_THICK))
    if (top) FrameGrip(FrameHandle.NONE, FrameHandle.START, onResize, onEnd,
        Modifier.align(Alignment.TopCenter).size(FRAME_EDGE_GRIP_LONG, FRAME_EDGE_GRIP_THICK))
}

private fun gripAlignment(h: FrameHandle, v: FrameHandle): Alignment = when {
    h == FrameHandle.START && v == FrameHandle.START -> Alignment.TopStart
    h == FrameHandle.END && v == FrameHandle.START -> Alignment.TopEnd
    h == FrameHandle.START -> Alignment.BottomStart
    else -> Alignment.BottomEnd
}

@Composable
private fun FrameGrip(
    horizontal: FrameHandle,
    vertical: FrameHandle,
    onResize: (FrameHandle, FrameHandle, Float, Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier,
) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    Canvas(
        modifier
            .semantics { contentDescription = "Resize" }
            .pointerInput(horizontal, vertical) {
                detectDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { change, amount ->
                    change.consume()
                    onResize(
                        horizontal, vertical,
                        if (horizontal == FrameHandle.NONE) 0f else amount.x,
                        if (vertical == FrameHandle.NONE) 0f else amount.y,
                    )
                }
            },
    ) {
        val thick = 3.dp.toPx()
        if (horizontal != FrameHandle.NONE && vertical != FrameHandle.NONE) {
            // Concentric with the window's own corner, a little inside it.
            val corner = Ui.Corner.card.toPx()
            val radius = corner - 6.dp.toPx()
            val left = horizontal == FrameHandle.START
            val upper = vertical == FrameHandle.START
            val cx = if (left) corner else size.width - corner
            val cy = if (upper) corner else size.height - corner
            val start = when {
                left && upper -> 180f
                upper -> 270f
                left -> 90f
                else -> 0f
            }
            drawArc(
                color, startAngle = start, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(cx - radius, cy - radius), size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = thick, cap = StrokeCap.Round),
            )
        } else if (horizontal != FrameHandle.NONE) {
            // A short bar along the side, a little in from it.
            val x = if (horizontal == FrameHandle.START) size.width * 0.35f else size.width * 0.65f
            drawLine(color, Offset(x, size.height * 0.2f), Offset(x, size.height * 0.8f), thick, StrokeCap.Round)
        } else {
            val y = if (vertical == FrameHandle.START) size.height * 0.35f else size.height * 0.65f
            drawLine(color, Offset(size.width * 0.2f, y), Offset(size.width * 0.8f, y), thick, StrokeCap.Round)
        }
    }
}

/** A corner handle: big enough for a fingertip, small enough to leave the page. */
private val FRAME_CORNER_GRIP = 24.dp
/** An edge handle, along the edge and across it. */
private val FRAME_EDGE_GRIP_LONG = 56.dp
private val FRAME_EDGE_GRIP_THICK = 16.dp
