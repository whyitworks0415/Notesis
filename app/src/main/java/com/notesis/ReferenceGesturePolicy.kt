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
