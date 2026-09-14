package com.notesis

import kotlin.math.abs

private const val AXIS_SNAP_RATIO = 0.176327f // tan(10 degrees)

/** Snaps a line endpoint when it is within ten degrees of horizontal or vertical. */
internal fun snappedLineEnd(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    enabled: Boolean,
): FloatArray {
    if (!enabled) return floatArrayOf(endX, endY)
    val dx = endX - startX
    val dy = endY - startY
    val ax = abs(dx)
    val ay = abs(dy)
    return when {
        ax == 0f && ay == 0f -> floatArrayOf(endX, endY)
        ay <= ax * AXIS_SNAP_RATIO -> floatArrayOf(endX, startY)
        ax <= ay * AXIS_SNAP_RATIO -> floatArrayOf(startX, endY)
        else -> floatArrayOf(endX, endY)
    }
}
