package com.notesis

/** A floating panel's place and size, in the coordinates of what it floats over. */
internal data class PanelBounds(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * [start] after one of its corners has been dragged by ([dragX], [dragY])
 * since the hand went down. The corner held follows the hand and the one
 * opposite stays put, each axis on its own; [fromLeft] and [fromTop] say
 * which corner it is. Never smaller than [minSize] and never past the
 * container's edges. The drag is the total since the start, not this
 * frame's step, so a corner pushed past a limit and brought back is picked
 * up again exactly where the finger is.
 */
internal fun cornerResize(
    start: PanelBounds,
    fromLeft: Boolean,
    fromTop: Boolean,
    dragX: Float,
    dragY: Float,
    minSize: Float,
    containerWidth: Float,
    containerHeight: Float,
): PanelBounds = frameResize(
    start, if (fromLeft) FrameHandle.START else FrameHandle.END, if (fromTop) FrameHandle.START else FrameHandle.END,
    dragX, dragY, minSize, containerWidth, containerHeight,
)

/** Which edge of a frame, along one axis, a resize handle moves. */
internal enum class FrameHandle { START, NONE, END }

/**
 * [start] after a handle has been dragged by ([dragX], [dragY]) since the
 * hand went down: [horizontal] and [vertical] say which edge it moves along
 * each axis - a corner moves one of each, an edge's handle one and [NONE] of
 * the other, which then stays exactly as it was. Limits as [cornerResize].
 */
internal fun frameResize(
    start: PanelBounds,
    horizontal: FrameHandle,
    vertical: FrameHandle,
    dragX: Float,
    dragY: Float,
    minSize: Float,
    containerWidth: Float,
    containerHeight: Float,
): PanelBounds {
    fun axis(near: Float, far: Float, drag: Float, handle: FrameHandle, limit: Float): Pair<Float, Float> = when (handle) {
        FrameHandle.NONE -> near to far - near
        FrameHandle.START -> {
            // The near edge moves; the far one is the anchor.
            val lowest = minOf(0f, far - minSize)
            val edge = (near + drag).coerceIn(lowest, far - minSize)
            edge to far - edge
        }
        FrameHandle.END -> {
            val reach = maxOf(minSize, limit - near)
            near to (far - near + drag).coerceIn(minSize, reach)
        }
    }
    val (left, width) = axis(start.left, start.right, dragX, horizontal, containerWidth)
    val (top, height) = axis(start.top, start.bottom, dragY, vertical, containerHeight)
    return PanelBounds(left, top, width, height)
}

/**
 * Where a panel of [width] x [height] lands when its top bar is dragged by
 * ([dx], [dy]) from [left], [top]: inside the container, so its top bar can
 * always be reached again.
 */
internal fun movePanel(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    dx: Float,
    dy: Float,
    containerWidth: Float,
    containerHeight: Float,
): Pair<Float, Float> = Pair(
    (left + dx).coerceIn(0f, (containerWidth - width).coerceAtLeast(0f)),
    (top + dy).coerceIn(0f, (containerHeight - height).coerceAtLeast(0f)),
)

/**
 * [bounds] made to fit a container of [width] x [height]: no smaller than
 * [minSize] (or the container, if that is smaller still), no larger than the
 * container, and moved back inside it. For a window kept from before the
 * screen turned or the window was resized.
 */
internal fun clampPanel(bounds: PanelBounds, width: Float, height: Float, minSize: Float): PanelBounds {
    val w = bounds.width.coerceIn(minOf(minSize, width), width.coerceAtLeast(0f))
    val h = bounds.height.coerceIn(minOf(minSize, height), height.coerceAtLeast(0f))
    return PanelBounds(
        bounds.left.coerceIn(0f, (width - w).coerceAtLeast(0f)),
        bounds.top.coerceIn(0f, (height - h).coerceAtLeast(0f)),
        w, h,
    )
}
