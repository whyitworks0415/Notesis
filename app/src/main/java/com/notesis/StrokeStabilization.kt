package com.notesis

import kotlin.math.abs

/**
 * Smooths a completed freehand path without moving either endpoint.
 *
 * A centred, triangular window avoids the trailing lag produced by live
 * low-pass filters. The percentage controls both the window size and how much
 * of its average is mixed into the original path; zero is therefore an exact
 * pass-through.
 */
internal fun stabilizedCoordinates(
    x: FloatArray,
    y: FloatArray,
    percent: Int,
): Pair<FloatArray, FloatArray> {
    require(x.size == y.size) { "Coordinate arrays must have the same size" }
    val level = percent.coerceIn(0, 100)
    val resultX = x.copyOf()
    val resultY = y.copyOf()
    if (level == 0 || x.size < 3) return resultX to resultY

    val radius = 1 + (level - 1) * 5 / 99
    val strength = level / 100f * 0.88f
    for (i in 1 until x.lastIndex) {
        val from = (i - radius).coerceAtLeast(0)
        val to = (i + radius).coerceAtMost(x.lastIndex)
        var weightedX = 0f
        var weightedY = 0f
        var weightTotal = 0f
        for (j in from..to) {
            val weight = (radius + 1 - abs(j - i)).toFloat()
            weightedX += x[j] * weight
            weightedY += y[j] * weight
            weightTotal += weight
        }
        resultX[i] = x[i] + (weightedX / weightTotal - x[i]) * strength
        resultY[i] = y[i] + (weightedY / weightTotal - y[i]) * strength
    }
    return resultX to resultY
}
