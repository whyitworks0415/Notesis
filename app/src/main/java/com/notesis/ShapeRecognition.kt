package com.notesis

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class RecognizedShape(
    val kind: ShapeKind,
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
)

/** Conservative fitting: an uncertain gesture remains the stroke the user drew. */
internal fun recognizeShape(points: List<FloatArray>): RecognizedShape? {
    if (points.size < 6) return null
    val first = points.first()
    val last = points.last()
    val chord = hypot(last[0] - first[0], last[1] - first[1])
    var path = 0f
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    for (i in points.indices) {
        val p = points[i]
        left = min(left, p[0]); right = max(right, p[0])
        top = min(top, p[1]); bottom = max(bottom, p[1])
        if (i > 0) path += hypot(p[0] - points[i - 1][0], p[1] - points[i - 1][1])
    }
    val width = right - left
    val height = bottom - top
    val diagonal = hypot(width, height)
    if (diagonal < 24f || path < 30f) return null

    if (chord > 24f && path / chord < 1.18f) {
        val tolerance = max(7f, chord * 0.035f)
        val deviation = points.maxOf { p ->
            abs((last[0] - first[0]) * (first[1] - p[1]) -
                (first[0] - p[0]) * (last[1] - first[1])) / chord
        }
        if (deviation < tolerance) {
            return RecognizedShape(ShapeKind.LINE, first[0], first[1], last[0], last[1])
        }
    }

    if (width < 24f || height < 24f || chord > diagonal * 0.16f) return null
    val edgeTolerance = max(6f, min(width, height) * 0.12f)
    val nearEdges = points.count { p ->
        min(min(abs(p[0] - left), abs(p[0] - right)),
            min(abs(p[1] - top), abs(p[1] - bottom))) <= edgeTolerance
    }.toFloat() / points.size
    val perimeter = 2f * (width + height)
    if (nearEdges > 0.88f && path in perimeter * 0.75f..perimeter * 1.35f) {
        return RecognizedShape(ShapeKind.RECT, left, top, right, bottom)
    }

    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f
    val rx = width / 2f
    val ry = height / 2f
    var radialError = 0f
    var sweep = 0f
    var previousAngle = atan2((first[1] - cy) / ry, (first[0] - cx) / rx)
    for (p in points) {
        val x = (p[0] - cx) / rx
        val y = (p[1] - cy) / ry
        radialError += abs(hypot(x, y) - 1f)
        val angle = atan2(y, x)
        var step = angle - previousAngle
        while (step > Math.PI) step -= (2 * Math.PI).toFloat()
        while (step < -Math.PI) step += (2 * Math.PI).toFloat()
        sweep += step
        previousAngle = angle
    }
    if (radialError / points.size < 0.18f && abs(sweep) > 4.7f) {
        return RecognizedShape(ShapeKind.OVAL, left, top, right, bottom)
    }
    return null
}
