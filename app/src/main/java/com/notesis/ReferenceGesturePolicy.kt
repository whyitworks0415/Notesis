package com.notesis

import kotlin.math.abs

/** Once a deliberate dismiss starts, grip noise must not turn it into a resize. */
internal class ReferenceDismissGesture(private val slop: Float) {
    private var tracking = false

    fun reset() { tracking = false }

    fun tracks(dx: Float, dy: Float, spreadFactor: Float): Boolean {
        if (!tracking && dy > slop && dy > abs(dx) * 1.15f && abs(spreadFactor - 1f) < 0.12f) {
            tracking = true
        }
        return tracking
    }
}

/**
 * A panel of [width] x [height] after a resize handle has been dragged by
 * ([dragX], [dragY]) since the hand went down: each axis on its own, so the
 * shape follows the hand rather than keeping its ratio. The drag is the total
 * since the start, not this frame's step, so pushing past a limit and coming
 * back picks the edge up again exactly where the finger is.
 */
internal fun freeResize(
    width: Float,
    height: Float,
    dragX: Float,
    dragY: Float,
    minSize: Float,
    maxWidth: Float,
    maxHeight: Float,
): Pair<Float, Float> = Pair(
    (width + dragX).coerceIn(minSize, maxOf(minSize, maxWidth)),
    (height + dragY).coerceIn(minSize, maxOf(minSize, maxHeight)),
)

/**
 * Where a dragged panel's top lands, clamped to [0, maxTop], and how far the
 * hand has carried it past the bottom edge. [overshoot] is what was already
 * past it, so the panel comes back up exactly with the hand.
 */
internal fun panelTopWithOvershoot(top: Float, overshoot: Float, dy: Float, maxTop: Float): Pair<Float, Float> {
    val wanted = top + overshoot + dy
    val limit = maxTop.coerceAtLeast(0f)
    return Pair(wanted.coerceIn(0f, limit), (wanted - limit).coerceAtLeast(0f))
}
