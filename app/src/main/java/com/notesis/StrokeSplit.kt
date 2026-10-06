package com.notesis

import kotlin.math.hypot
import kotlin.math.min

/**
 * The partial eraser's geometry, on plain coordinates so it runs on the JVM.
 *
 * A stroke is its inputs, so erasing part of one means keeping the runs of
 * inputs the eraser did not reach and turning each run into its own stroke.
 * The eraser sweeps the segment a→b with half-width [reach]. An input inside
 * that band is dropped; two neighbouring inputs both outside it are still cut
 * apart when the line between them crosses the band, so a fast stroke with
 * widely spaced samples is split where it was actually touched.
 *
 * Returns the kept runs as [start, endExclusive) index pairs, runs of a single
 * input dropped (they would draw as a stray dot). Returns null when nothing was
 * cut, so the caller can leave the original stroke alone.
 */
internal fun keptRuns(
    xs: FloatArray,
    ys: FloatArray,
    count: Int,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    reach: Float,
): List<IntRange>? {
    if (count <= 0) return null
    val runs = ArrayList<IntRange>()
    var runStart = -1
    var cut = false
    for (i in 0 until count) {
        val inside = pointSegmentDistance(xs[i], ys[i], ax, ay, bx, by) <= reach
        if (inside) {
            cut = true
            if (runStart >= 0) {
                if (i - runStart >= 2) runs += runStart until i
                runStart = -1
            }
            continue
        }
        if (runStart >= 0 && i > 0 &&
            segmentSegmentDistance(xs[i - 1], ys[i - 1], xs[i], ys[i], ax, ay, bx, by) <= reach
        ) {
            cut = true
            if (i - runStart >= 2) runs += runStart until i
            runStart = i
            continue
        }
        if (runStart < 0) runStart = i
    }
    if (runStart >= 0 && count - runStart >= 2) runs += runStart until count
    return if (cut) runs else null
}

internal fun pointSegmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 1e-12f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

internal fun segmentSegmentDistance(
    p1x: Float, p1y: Float, p2x: Float, p2y: Float,
    q1x: Float, q1y: Float, q2x: Float, q2y: Float,
): Float {
    if (segmentsIntersect(p1x, p1y, p2x, p2y, q1x, q1y, q2x, q2y)) return 0f
    return min(
        min(
            pointSegmentDistance(p1x, p1y, q1x, q1y, q2x, q2y),
            pointSegmentDistance(p2x, p2y, q1x, q1y, q2x, q2y),
        ),
        min(
            pointSegmentDistance(q1x, q1y, p1x, p1y, p2x, p2y),
            pointSegmentDistance(q2x, q2y, p1x, p1y, p2x, p2y),
        ),
    )
}

private fun segmentsIntersect(
    p1x: Float, p1y: Float, p2x: Float, p2y: Float,
    q1x: Float, q1y: Float, q2x: Float, q2y: Float,
): Boolean {
    fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
    val d1 = cross(q1x, q1y, q2x, q2y, p1x, p1y)
    val d2 = cross(q1x, q1y, q2x, q2y, p2x, p2y)
    val d3 = cross(p1x, p1y, p2x, p2y, q1x, q1y)
    val d4 = cross(p1x, p1y, p2x, p2y, q2x, q2y)
    if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
        ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    ) return true
    // Collinear touching is covered by the endpoint distances.
    return false
}

