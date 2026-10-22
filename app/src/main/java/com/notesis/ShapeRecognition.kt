package com.notesis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a stroke was recognised as. A line, an axis-aligned rectangle and an
 * oval are their [kind] dragged from (fromX, fromY) to (toX, toY), which
 * [shapeOutline] draws. The shapes a box cannot say - a triangle, a tilted or
 * uneven quadrilateral, a bent line, a bent curve - carry their own [outline]
 * (closed ones repeat the first point last), with [corners] the indices in it
 * that stay sharp; the box is then just the outline's bounds.
 */
internal data class RecognizedShape(
    val kind: ShapeKind,
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val outline: List<FloatArray>? = null,
    val corners: List<Int> = emptyList(),
)

/**
 * The shape [points] were meant to be, or null to keep the stroke as written.
 *
 * Unheld - recognition left on for every stroke - only clean, large lines,
 * rectangles, ovals and triangles qualify, so handwriting stays handwriting.
 * A stroke the pen was [held] at the end of was asked to be a shape, so it may
 * also be any quadrilateral, a line bent at sharp corners, or a curve bent at
 * them. Corners are found where they were drawn and the sides between them are
 * fitted, never guessed from the bounding box. [axisSnap] levels a rectangle
 * drawn within a few degrees of level.
 */
internal fun recognizeShape(
    drawn: List<FloatArray>,
    held: Boolean = false,
    axisSnap: Boolean = true,
): RecognizedShape? {
    val points = forRecognition(drawn)
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

    val limits = if (held) HELD else AUTO
    if (diagonal >= limits.minClosedSize && min(width, height) >= 12f) {
        when (val closed = closedShape(points, limits, axisSnap)) {
            is Closed.Shape -> return closed.shape
            Closed.Unfit -> return null
            Closed.Open -> Unit
        }
    }
    // Bent lines and curves are too much like writing to find unasked.
    return if (held) openShape(points, diagonal) else null
}

/** How strict recognition is: unheld strokes are mostly writing, so the bar is high. */
private class Limits(
    val minClosedSize: Float,
    /** The ends may be this far apart, over the stroke's size, and it still counts as closed. */
    val closeGap: Float,
    /** No drawn point further from the fitted polygon than this share of its size... */
    val sideDeviation: Float,
    /** ...and on average no further than this. */
    val sideRms: Float,
    val minCornerDegrees: Float,
    /** Mean distance from the fitted ellipse, as a share of its radius. */
    val radialError: Float,
    /** Whether a quadrilateral that is not a rectangle is kept as drawn. */
    val anyQuad: Boolean,
    /** How much of the stroke, at its two ends together, may be cut off as overshoot. */
    val maxTrim: Float,
)

private val AUTO = Limits(64f, 0.16f, 0.042f, 0.017f, 22f, 0.075f, anyQuad = false, maxTrim = 0.1f)
private val HELD = Limits(24f, 0.22f, 0.06f, 0.024f, 12f, 0.11f, anyQuad = true, maxTrim = 0.25f)

private sealed interface Closed {
    class Shape(val shape: RecognizedShape) : Closed
    /** Closed, but neither a polygon nor an oval: kept as written. */
    data object Unfit : Closed
    /** The ends only looked close: the gap between them is a side of its own, as in a V. */
    data object Open : Closed
}

private const val LOOP_SAMPLES = 96

private fun closedShape(points: List<FloatArray>, limits: Limits, axisSnap: Boolean): Closed {
    val even = resample(points, 128)
    // The closest the stroke's end comes back to its beginning. Ending a
    // little past the start, or a little short of it, are both closing it.
    var bi = 0
    var bj = even.size - 1
    var best = dist(even[bi], even[bj])
    for (i in 0..even.size / 5) for (j in even.size * 4 / 5 until even.size) {
        val d = dist(even[i], even[j])
        if (d < best - 1e-3f) { best = d; bi = i; bj = j }
    }
    // Much more than an overshoot cut off is a loop with a tail, as in a
    // written a, not a closed shape.
    if ((bi + even.size - 1 - bj).toFloat() / (even.size - 1) > limits.maxTrim) return Closed.Open
    val loop = resample(even.subList(bi, bj + 1), LOOP_SAMPLES)
    val box = bounds(loop)
    val size = hypot(box[2] - box[0], box[3] - box[1])
    if (best > size * limits.closeGap) return Closed.Open
    if (best > size * 0.06f && !headsForItsStart(loop)) return Closed.Open
    val ellipse = fitEllipse(loop, box, limits)
    val polygon = fitPolygon(loop, size, min(box[2] - box[0], box[3] - box[1]), limits)
    if (polygon == null || polygon.gapIsSide) {
        return when {
            ellipse != null -> Closed.Shape(ellipse.shape)
            polygon?.gapIsSide == true -> Closed.Open
            else -> Closed.Unfit
        }
    }
    // A rounded square can pass for both; whichever the drawing sits closer to.
    if (ellipse != null && ellipse.rms < polygon.rms) return Closed.Shape(ellipse.shape)
    val shape = polygonShape(polygon.corners, points.first(), limits, axisSnap) ?: return Closed.Unfit
    return Closed.Shape(shape)
}

/**
 * Whether a stroke whose ends stop short of meeting was on its way to closing:
 * one end heads for the other, as the last side of a triangle does for its
 * first corner. The two arms of a V both head away from the gap between them.
 */
private fun headsForItsStart(loop: List<FloatArray>): Boolean {
    val n = loop.size
    val reach = max(2, n / 20)
    val first = loop.first()
    val last = loop.last()
    fun toward(heading: FloatArray, target: FloatArray) = heading[0] * target[0] + heading[1] * target[1] >= HEADING_COS
    return toward(direction(loop[n - 1 - reach], last), direction(last, first)) ||
        toward(direction(loop[reach], first), direction(first, last))
}

/** Within 50 degrees. */
private const val HEADING_COS = 0.64f

private class EllipseFit(val shape: RecognizedShape, val rms: Float)

private fun fitEllipse(loop: List<FloatArray>, box: FloatArray, limits: Limits): EllipseFit? {
    val cx = (box[0] + box[2]) / 2f
    val cy = (box[1] + box[3]) / 2f
    val rx = (box[2] - box[0]) / 2f
    val ry = (box[3] - box[1]) / 2f
    if (rx < 6f || ry < 6f) return null
    val scale = sqrt(rx * ry)
    var radialError = 0f
    var squares = 0f
    var sweep = 0f
    var previousAngle = atan2((loop[0][1] - cy) / ry, (loop[0][0] - cx) / rx)
    for (p in loop) {
        val x = (p[0] - cx) / rx
        val y = (p[1] - cy) / ry
        val off = abs(hypot(x, y) - 1f)
        radialError += off
        squares += (off * scale) * (off * scale)
        val angle = atan2(y, x)
        var step = angle - previousAngle
        while (step > PI) step -= (2 * PI).toFloat()
        while (step < -PI) step += (2 * PI).toFloat()
        sweep += step
        previousAngle = angle
    }
    if (radialError / loop.size > limits.radialError || abs(sweep) < 5.2f) return null
    // Nearly round was meant to be round: a circle, the mean of the two radii.
    val (wx, wy) = if (min(rx, ry) / max(rx, ry) >= 0.85f) (rx + ry) / 2f to (rx + ry) / 2f else rx to ry
    return EllipseFit(
        RecognizedShape(ShapeKind.OVAL, cx - wx, cy - wy, cx + wx, cy + wy),
        sqrt(squares / loop.size),
    )
}

private class PolygonFit(val corners: List<FloatArray>, val rms: Float, val gapIsSide: Boolean)

/**
 * The triangle or quadrilateral [loop] was drawn as, or null. Points are
 * dropped one at a time, always the one whose loss changes the outline least,
 * until three or four remain: the corners are what survive, however noisy the
 * sides were. The fewest corners that fit wins, so a quadrilateral is never
 * read as five corners, and one whose sides do not fit as three or four is
 * nothing at all.
 */
private fun fitPolygon(loop: List<FloatArray>, size: Float, minExtent: Float, limits: Limits): PolygonFit? {
    val n = loop.size
    val prev = IntArray(n) { (it - 1 + n) % n }
    val next = IntArray(n) { (it + 1) % n }
    val alive = BooleanArray(n) { true }
    // What removing a point costs: the furthest any point it stood for lies
    // from the side that would replace it.
    fun cost(i: Int): Float {
        val a = loop[prev[i]]
        val b = loop[next[i]]
        var worst = 0f
        var j = next[prev[i]]
        while (j != next[i]) {
            worst = max(worst, segmentDistance(loop[j], a, b))
            j = next[j]
        }
        return worst
    }
    val costs = FloatArray(n) { cost(it) }
    var count = n
    val snapshots = HashMap<Int, IntArray>()
    while (count > 3) {
        var victim = -1
        for (i in 0 until n) if (alive[i] && (victim < 0 || costs[i] < costs[victim])) victim = i
        alive[victim] = false
        next[prev[victim]] = next[victim]
        prev[next[victim]] = prev[victim]
        costs[prev[victim]] = cost(prev[victim])
        costs[next[victim]] = cost(next[victim])
        count--
        if (count <= 4) snapshots[count] = (0 until n).filter { alive[it] }.toIntArray()
    }
    val maxDeviation = max(3.5f, min(size * limits.sideDeviation, minExtent * 0.3f))
    val maxRms = max(1.8f, min(size * limits.sideRms, minExtent * 0.12f))
    for (k in 3..4) {
        val kept = snapshots[k] ?: continue
        val corners = refineCorners(loop, kept, size)
        var squares = 0f
        var worst = 0f
        for (p in loop) {
            var d = Float.POSITIVE_INFINITY
            for (s in 0 until k) d = min(d, segmentDistance(p, corners[s], corners[(s + 1) % k]))
            squares += d * d
            worst = max(worst, d)
        }
        val rms = sqrt(squares / n)
        if (worst > maxDeviation || rms > maxRms) continue
        if (!wellFormed(corners, limits)) continue
        // Each corner of four must stand well clear of the line between its
        // neighbours: one that barely does is a triangle's noisy side, and a
        // triangle that fails to fit is not made a quadrilateral instead.
        if (k == 4 && (0 until 4).any { s ->
                lineDistance(corners[s], corners[(s + 3) % 4], corners[(s + 1) % 4]) < maxDeviation * 1.6f
            }) continue
        // The side the stroke's two ends meet on. When the gap is most of that
        // side, nothing was drawn there: an open V, not a triangle.
        val wrapSide = dist(corners[k - 1], corners[0])
        val gap = dist(loop.last(), loop.first())
        val gapIsSide = gap > wrapSide * 0.45f
        return PolygonFit(corners, rms, gapIsSide)
    }
    return null
}

/**
 * Each corner where its two sides, fitted to what was drawn of them, meet:
 * hand-drawn corners are rounded, and the point that survived is somewhere on
 * the rounding. Kept as is where the sides are too nearly parallel to say.
 */
private fun refineCorners(loop: List<FloatArray>, kept: IntArray, size: Float): List<FloatArray> {
    val n = loop.size
    val k = kept.size
    val lines = (0 until k).map { s ->
        val a = kept[s]
        val b = kept[(s + 1) % k]
        val span = (b - a + n) % n
        val trim = (span * 0.18f).toInt()
        val side = (trim + 1..span - trim - 1).map { loop[(a + it) % n] }
        fitLine(if (side.size >= 2) side else listOf(loop[a], loop[b]))
    }
    return (0 until k).map { s ->
        val drawn = loop[kept[s]]
        val meet = intersect(lines[(s - 1 + k) % k], lines[s])
        if (meet != null && dist(meet, drawn) <= size * 0.2f) meet else drawn.copyOf()
    }
}

/** Corners sharp enough to be corners, sides long enough to be sides, and no side crossing another. */
private fun wellFormed(corners: List<FloatArray>, limits: Limits): Boolean {
    val k = corners.size
    var perimeter = 0f
    for (s in 0 until k) perimeter += dist(corners[s], corners[(s + 1) % k])
    var sign = 0
    for (s in 0 until k) {
        val a = corners[(s - 1 + k) % k]
        val b = corners[s]
        val c = corners[(s + 1) % k]
        if (dist(b, c) < max(8f, perimeter * 0.03f)) return false
        val angle = interiorDegrees(a, b, c)
        if (angle < limits.minCornerDegrees || angle > 165f) return false
        val turn = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
        val turnSign = if (turn > 0f) 1 else -1
        if (sign == 0) sign = turnSign else if (turnSign != sign && !limits.anyQuad) return false
    }
    if (k == 4 && (segmentsCross(corners[0], corners[1], corners[2], corners[3]) ||
            segmentsCross(corners[1], corners[2], corners[3], corners[0]))) return false
    return true
}

/**
 * The fitted corners as a shape: a quadrilateral whose corners are all square
 * to within 10 degrees is made a true rectangle - level when drawn within a
 * few degrees of it, a square when its sides nearly match - and any other is
 * kept as drawn. The outline starts at the corner nearest where the pen began.
 */
private fun polygonShape(corners: List<FloatArray>, start: FloatArray, limits: Limits, axisSnap: Boolean): RecognizedShape? {
    val k = corners.size
    if (k == 3) return outlineShape(ShapeKind.TRIANGLE, closedFrom(corners, start))
    val square = (0 until 4).all { s ->
        abs(interiorDegrees(corners[(s + 3) % 4], corners[s], corners[(s + 1) % 4]) - 90f) <= 10f
    }
    if (!square) return if (limits.anyQuad) outlineShape(ShapeKind.POLYGON, closedFrom(corners, start)) else null
    // The rectangle's tilt: every side's direction folded into one quarter turn and averaged.
    var sx = 0.0
    var sy = 0.0
    for (s in 0 until 4) {
        val a = corners[s]
        val b = corners[(s + 1) % 4]
        val angle = 4.0 * atan2((b[1] - a[1]).toDouble(), (b[0] - a[0]).toDouble())
        val length = dist(a, b).toDouble()
        sx += cos(angle) * length
        sy += sin(angle) * length
    }
    var tilt = (atan2(sy, sx) / 4.0).toFloat()
    if (axisSnap && abs(tilt) <= AXIS_SNAP_RADIANS) tilt = 0f
    val cx = corners.sumOf { it[0].toDouble() }.toFloat() / 4f
    val cy = corners.sumOf { it[1].toDouble() }.toFloat() / 4f
    val c = cos(tilt)
    val s = sin(tilt)
    val local = corners.map { p ->
        val dx = p[0] - cx
        val dy = p[1] - cy
        floatArrayOf(dx * c + dy * s, -dx * s + dy * c)
    }
    var halfW = local.sumOf { abs(it[0]).toDouble() }.toFloat() / 4f
    var halfH = local.sumOf { abs(it[1]).toDouble() }.toFloat() / 4f
    if (abs(halfW - halfH) <= max(halfW, halfH) * SQUARE_TOLERANCE) {
        halfW = (halfW + halfH) / 2f
        halfH = halfW
    }
    // Each drawn corner becomes the rectangle's corner on its side of the
    // centre, so the outline still runs the way it was drawn.
    val quadrants = local.map { (if (it[0] >= 0f) 1 else 0) + (if (it[1] >= 0f) 2 else 0) }
    if (quadrants.toSet().size != 4) {
        return if (limits.anyQuad) outlineShape(ShapeKind.POLYGON, closedFrom(corners, start)) else null
    }
    if (tilt == 0f) return RecognizedShape(ShapeKind.RECT, cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    val regular = local.map { p ->
        val lx = if (p[0] >= 0f) halfW else -halfW
        val ly = if (p[1] >= 0f) halfH else -halfH
        floatArrayOf(cx + lx * c - ly * s, cy + lx * s + ly * c)
    }
    return outlineShape(ShapeKind.RECT, closedFrom(regular, start))
}

/** [corners] as a closed outline, starting at the one nearest [start]. */
private fun closedFrom(corners: List<FloatArray>, start: FloatArray): List<FloatArray> {
    val first = corners.indices.minBy { dist(corners[it], start) }
    val ordered = corners.indices.map { corners[(first + it) % corners.size] }
    return ordered + ordered[0].copyOf()
}

private fun outlineShape(kind: ShapeKind, outline: List<FloatArray>, corners: List<Int> = (1 until outline.size).toList()): RecognizedShape {
    val box = bounds(outline)
    return RecognizedShape(kind, box[0], box[1], box[2], box[3], outline, corners)
}

private const val OPEN_SAMPLES = 128
/** A bend this sharp, with straight arms either side of it, is a kink even when gentle. */
private const val GENTLE_KINK_RADIANS = 0.35f
private const val KINK_RADIANS = 0.6f
private const val CERTAIN_KINK_RADIANS = 1.31f
private const val MAX_KINKS = 10
private const val MAX_CURVES_PER_SEGMENT = 4

/**
 * A held open stroke as a line bent at its sharp corners, each piece straight,
 * or as a curve bent at them, each piece that was drawn curved fitted with a
 * smooth curve and each that was drawn straight kept straight. Null when a
 * piece is too wobbly for a few curves to follow - that was not a shape.
 */
private fun openShape(points: List<FloatArray>, size: Float): RecognizedShape? {
    var length = 0f
    for (i in 1 until points.size) length += dist(points[i], points[i - 1])
    // Evenly spaced, but never closer than the pen's own jitter is wide.
    val spacing = max(length / (OPEN_SAMPLES - 1), 2.5f)
    val raw = resample(points, (length / spacing).toInt().coerceIn(24, OPEN_SAMPLES))
    val n = raw.size
    val step = length / (n - 1)
    // How far apart the pen's own samples are: a direction is only as sure as
    // the number of real samples it was taken from.
    val gaps = (1 until points.size).map { dist(points[it], points[it - 1]) }.filter { it > 0.01f }.sorted()
    val penSpacing = if (gaps.isEmpty()) step else gaps[gaps.size / 2]
    // Softened over a few page units at least, so a tremor is not a corner; a
    // pass of 1-2-1 spreads a point over a deviation of step * sqrt(1/2).
    val blur = max(max(3f, penSpacing), length * 0.015f)
    val smooth = smoothed(raw, passes = (2f * (blur / step) * (blur / step)).roundToInt().coerceIn(2, 12))
    // Turns are measured over a stretch that is long beside the softening, the
    // pen's sampling and the stroke, so the jitter left in a direction is small;
    // but short enough beside the stroke that its corners stay apart.
    val reach = max(max(length * 0.045f, 18f), max(3f * blur, 5f * penSpacing)).coerceAtMost(length * 0.1f)
    val window = max(3, ceil(reach / step).toInt())
    // Which way the stroke runs over a stretch, fitted to every sample in it
    // so that no one jittery sample can swing it.
    fun heading(from: Int, to: Int): FloatArray {
        val a = from.coerceIn(0, n - 1)
        val b = to.coerceIn(0, n - 1)
        val line = fitLine(smooth.subList(a, b + 1))
        val forward = (smooth[b][0] - smooth[a][0]) * line[2] + (smooth[b][1] - smooth[a][1]) * line[3]
        return if (forward >= 0f) floatArrayOf(line[2], line[3]) else floatArrayOf(-line[2], -line[3])
    }
    fun angle(u: FloatArray, v: FloatArray) = acos((u[0] * v[0] + u[1] * v[1]).coerceIn(-1f, 1f))
    // The turn between the stretches just before and just after a point, and
    // between the stretches beyond those. Either side of a kink the stroke runs
    // straight, so both turn alike; along a curve the outer pair, three times
    // as far apart, turns about three times as much. Jitter turns one and not
    // the other. That tells the three apart.
    val margin = max(window + 1, (n * 0.06f).roundToInt())
    val turns = FloatArray(n) { i ->
        if (i in margin until n - margin) angle(heading(i - window, i), heading(i, i + window)) else 0f
    }
    // The near pair runs into the corner's rounding, so a gentle kink turns a
    // little less there than further out; it is the far pair that says how much.
    // A gentle one is too like a curve with some jitter in it to go by turns
    // alone, so it must also be better drawn as two lines than as one curve.
    val rounding = ceil(blur / step).toInt()
    val candidates = (margin until n - margin).filter { i ->
        val near = turns[i]
        if (near < GENTLE_KINK_RADIANS * 0.7f) return@filter false
        val far = angle(heading(i - 2 * window, i - window), heading(i + window, i + 2 * window))
        near >= CERTAIN_KINK_RADIANS || (far >= near * 0.6f &&
            ((near >= KINK_RADIANS && far <= near * 2f) ||
                (far >= GENTLE_KINK_RADIANS && far <= near * 1.8f && twoLinesBeatACurve(smooth, i, 3 * window, rounding))))
    }.sortedByDescending { turns[it] }
    val kinks = ArrayList<Int>()
    val apart = max(2 * window, (n * 0.06f).roundToInt())
    for (i in candidates) if (kinks.none { abs(it - i) < apart }) kinks += i
    kinks.sort()
    if (kinks.size > MAX_KINKS) return null

    val breaks = listOf(0) + kinks + (n - 1)
    // Straight when it keeps close to the line through it on the whole and
    // nowhere strays far: a single wobble does not make a side a curve. The
    // rounding the softening put into each corner, and the jitter a resting pen
    // leaves at an end, are left out.
    val straight = (0 until breaks.size - 1).map { s ->
        val a = breaks[s]
        val b = breaks[s + 1]
        val inset = min(window, (b - a) / 5)
        val middle = smooth.subList(a + inset, b - inset + 1)
        val line = fitLine(middle)
        var squares = 0f
        var worst = 0f
        for (p in middle) {
            val d = abs((p[0] - line[0]) * line[3] - (p[1] - line[1]) * line[2])
            squares += d * d
            worst = max(worst, d)
        }
        val chord = dist(smooth[a], smooth[b])
        sqrt(squares / middle.size) <= max(1.6f, chord * 0.015f) && worst <= max(5f, chord * 0.05f)
    }
    if (kinks.isEmpty() && straight[0]) {
        val first = points.first()
        val last = points.last()
        return RecognizedShape(ShapeKind.LINE, first[0], first[1], last[0], last[1])
    }
    // Each straight piece's line, fitted to its middle: its ends are rounding.
    val lines = (0 until breaks.size - 1).map { s ->
        if (!straight[s]) null else {
            val a = breaks[s]
            val b = breaks[s + 1]
            val trim = ((b - a) * 0.15f).toInt()
            fitLine(smooth.subList(a + trim, b - trim + 1))
        }
    }
    val joints = breaks.mapIndexed { index, at ->
        val drawn = raw[at]
        when (index) {
            0 -> lines[0]?.let { project(points.first(), it) } ?: points.first().copyOf()
            breaks.size - 1 -> lines[index - 1]?.let { project(points.last(), it) } ?: points.last().copyOf()
            else -> {
                val before = lines[index - 1]
                val after = lines[index]
                val meet = if (before != null && after != null) intersect(before, after) else null
                if (meet != null && dist(meet, drawn) <= size * 0.08f) meet else drawn.copyOf()
            }
        }
    }
    if (straight.all { it }) return outlineShape(ShapeKind.POLYGON, joints)

    val outline = arrayListOf(joints[0])
    val corners = ArrayList<Int>()
    val tolerance = max(2.5f, min(size * 0.015f, 8f))
    for (s in 0 until breaks.size - 1) {
        val a = breaks[s]
        val b = breaks[s + 1]
        if (!straight[s]) {
            val piece = listOf(joints[s]) + smooth.subList(a + 1, b) + listOf(joints[s + 1])
            val reach = min(3, piece.size - 1)
            val curves = fitCubics(
                piece,
                direction(piece[0], piece[reach]),
                direction(piece.last(), piece[piece.size - 1 - reach]),
                tolerance,
            ) ?: return null
            for (curve in curves) outline += sampleCubic(curve)
            // The fit ends exactly on the joint; the copy keeps it so for the next piece too.
            outline[outline.size - 1] = joints[s + 1].copyOf()
        } else {
            outline += joints[s + 1]
        }
        corners += outline.size - 1
    }
    return outlineShape(ShapeKind.CURVE, outline, corners)
}

/**
 * Whether the stroke within [reach] samples of [at] is followed more closely
 * by a line in and a line out of [at] than by one parabola, leaving out the
 * [gap] samples either side of it that softening rounded.
 */
private fun twoLinesBeatACurve(points: List<FloatArray>, at: Int, reach: Int, gap: Int): Boolean {
    val lo = max(0, at - reach)
    val hi = min(points.size - 1, at + reach)
    val before = points.subList(lo, max(lo + 2, at - gap + 1))
    val after = points.subList(min(hi - 1, at + gap), hi + 1)
    if (before.size < 3 || after.size < 3) return false
    fun residual(part: List<FloatArray>): Float {
        val line = fitLine(part)
        return part.sumOf { p ->
            val d = (p[0] - line[0]) * line[3] - (p[1] - line[1]) * line[2]
            (d * d).toDouble()
        }.toFloat()
    }
    val lines = residual(before) + residual(after)
    // The parabola, in the frame of the chord across the whole stretch.
    val origin = points[lo]
    val axis = direction(origin, points[hi])
    var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
    var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
    val local = (before + after).map { p ->
        val dx = p[0] - origin[0]
        val dy = p[1] - origin[1]
        (dx * axis[0] + dy * axis[1]).toDouble() to (dy * axis[0] - dx * axis[1]).toDouble()
    }
    for ((x, y) in local) {
        val xx = x * x
        s0 += 1; s1 += x; s2 += xx; s3 += xx * x; s4 += xx * xx
        t0 += y; t1 += x * y; t2 += xx * y
    }
    // y = a + b x + c x^2 by Cramer's rule on the normal equations.
    fun det(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double, g: Double, h: Double, i: Double) =
        a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    val m = det(s0, s1, s2, s1, s2, s3, s2, s3, s4)
    if (abs(m) < 1e-9) return false
    val a = det(t0, s1, s2, t1, s2, s3, t2, s3, s4) / m
    val b = det(s0, t0, s2, s1, t1, s3, s2, t2, s4) / m
    val c = det(s0, s1, t0, s1, s2, t1, s2, s3, t2) / m
    val curve = local.sumOf { (x, y) -> (y - (a + b * x + c * x * x)).let { it * it } }.toFloat()
    return lines < curve * 0.6f
}

// ---- curve fitting ------------------------------------------------------------

/**
 * Cubic Béziers through [d] from its first point to its last, leaving them
 * along [tangent1] and arriving against [tangent2], each within [tolerance]
 * of every point: least squares, a few Newton passes on the parameters, and a
 * split where the fit is worst when that is not enough (Schneider's method).
 * Null when it takes more than [MAX_CURVES_PER_SEGMENT] curves.
 */
private fun fitCubics(d: List<FloatArray>, tangent1: FloatArray, tangent2: FloatArray, tolerance: Float): List<Array<FloatArray>>? {
    val out = ArrayList<Array<FloatArray>>()
    val limit = tolerance * tolerance
    fun fit(first: Int, last: Int, t1: FloatArray, t2: FloatArray, depth: Int): Boolean {
        val p0 = d[first]
        val p3 = d[last]
        if (last - first == 1) {
            val third = dist(p0, p3) / 3f
            out += arrayOf(p0, add(p0, t1, third), add(p3, t2, third), p3)
            return true
        }
        var u = chordParameters(d, first, last)
        var curve = bezierFor(d, first, last, u, t1, t2)
        var (error, split) = worstFit(d, first, last, curve, u)
        if (error < limit) { out += curve; return true }
        if (error < limit * 4f) {
            repeat(4) {
                u = reparameterized(d, first, last, u, curve)
                curve = bezierFor(d, first, last, u, t1, t2)
                val again = worstFit(d, first, last, curve, u)
                error = again.first
                split = again.second
                if (error < limit) { out += curve; return true }
            }
        }
        if (depth >= 2) return false
        val center = centerTangent(d, split)
        return fit(first, split, t1, center, depth + 1) &&
            fit(split, last, floatArrayOf(-center[0], -center[1]), t2, depth + 1)
    }
    if (!fit(0, d.size - 1, tangent1, tangent2, 0) || out.size > MAX_CURVES_PER_SEGMENT) return null
    return out
}

private fun chordParameters(d: List<FloatArray>, first: Int, last: Int): FloatArray {
    val u = FloatArray(last - first + 1)
    for (i in first + 1..last) u[i - first] = u[i - first - 1] + dist(d[i], d[i - 1])
    val total = u.last()
    if (total > 0f) for (i in u.indices) u[i] /= total
    return u
}

private fun bezierFor(d: List<FloatArray>, first: Int, last: Int, u: FloatArray, t1: FloatArray, t2: FloatArray): Array<FloatArray> {
    val p0 = d[first]
    val p3 = d[last]
    var c00 = 0.0; var c01 = 0.0; var c11 = 0.0; var x0 = 0.0; var x1 = 0.0
    for (i in u.indices) {
        val t = u[i].toDouble()
        val mt = 1 - t
        val b0 = mt * mt * mt
        val b1 = 3 * t * mt * mt
        val b2 = 3 * t * t * mt
        val b3 = t * t * t
        val a1x = t1[0] * b1; val a1y = t1[1] * b1
        val a2x = t2[0] * b2; val a2y = t2[1] * b2
        c00 += a1x * a1x + a1y * a1y
        c01 += a1x * a2x + a1y * a2y
        c11 += a2x * a2x + a2y * a2y
        val p = d[first + i]
        val rx = p[0] - (p0[0] * (b0 + b1) + p3[0] * (b2 + b3))
        val ry = p[1] - (p0[1] * (b0 + b1) + p3[1] * (b2 + b3))
        x0 += a1x * rx + a1y * ry
        x1 += a2x * rx + a2y * ry
    }
    val det = c00 * c11 - c01 * c01
    val span = dist(p0, p3)
    var alpha1 = if (det != 0.0) ((x0 * c11 - x1 * c01) / det).toFloat() else 0f
    var alpha2 = if (det != 0.0) ((c00 * x1 - c01 * x0) / det).toFloat() else 0f
    // A handle pointing backwards, or longer than the whole piece, makes a
    // loop; the plain third-of-the-way handles are the safe fallback.
    if (alpha1 < span * 1e-3f || alpha2 < span * 1e-3f || alpha1 > span * 1.5f || alpha2 > span * 1.5f) {
        alpha1 = span / 3f
        alpha2 = span / 3f
    }
    return arrayOf(p0, add(p0, t1, alpha1), add(p3, t2, alpha2), p3)
}

/** The worst squared miss of [curve] against the points it stands for, and where. */
private fun worstFit(d: List<FloatArray>, first: Int, last: Int, curve: Array<FloatArray>, u: FloatArray): Pair<Float, Int> {
    var worst = 0f
    var at = (first + last) / 2
    for (i in first + 1 until last) {
        val q = cubicAt(curve, u[i - first])
        val dx = q[0] - d[i][0]
        val dy = q[1] - d[i][1]
        val e = dx * dx + dy * dy
        if (e >= worst) { worst = e; at = i }
    }
    return worst to at
}

private fun reparameterized(d: List<FloatArray>, first: Int, last: Int, u: FloatArray, curve: Array<FloatArray>): FloatArray =
    FloatArray(u.size) { i ->
        val t = u[i]
        val p = d[first + i]
        val q = cubicAt(curve, t)
        val q1 = cubicDerivative(curve, t)
        val q2 = cubicSecondDerivative(curve, t)
        val dx = q[0] - p[0]
        val dy = q[1] - p[1]
        val numerator = dx * q1[0] + dy * q1[1]
        val denominator = q1[0] * q1[0] + q1[1] * q1[1] + dx * q2[0] + dy * q2[1]
        if (denominator == 0f) t else (t - numerator / denominator).coerceIn(0f, 1f)
    }

/** The direction back along the points at [at], averaged over a couple either side against noise. */
private fun centerTangent(d: List<FloatArray>, at: Int): FloatArray {
    val reach = min(2, min(at, d.size - 1 - at))
    return direction(d[at + reach], d[at - reach])
}

private fun cubicAt(c: Array<FloatArray>, t: Float): FloatArray {
    val mt = 1 - t
    val b0 = mt * mt * mt
    val b1 = 3 * t * mt * mt
    val b2 = 3 * t * t * mt
    val b3 = t * t * t
    return floatArrayOf(
        c[0][0] * b0 + c[1][0] * b1 + c[2][0] * b2 + c[3][0] * b3,
        c[0][1] * b0 + c[1][1] * b1 + c[2][1] * b2 + c[3][1] * b3,
    )
}

private fun cubicDerivative(c: Array<FloatArray>, t: Float): FloatArray {
    val mt = 1 - t
    return floatArrayOf(
        3 * (mt * mt * (c[1][0] - c[0][0]) + 2 * mt * t * (c[2][0] - c[1][0]) + t * t * (c[3][0] - c[2][0])),
        3 * (mt * mt * (c[1][1] - c[0][1]) + 2 * mt * t * (c[2][1] - c[1][1]) + t * t * (c[3][1] - c[2][1])),
    )
}

private fun cubicSecondDerivative(c: Array<FloatArray>, t: Float): FloatArray {
    val mt = 1 - t
    return floatArrayOf(
        6 * (mt * (c[2][0] - 2 * c[1][0] + c[0][0]) + t * (c[3][0] - 2 * c[2][0] + c[1][0])),
        6 * (mt * (c[2][1] - 2 * c[1][1] + c[0][1]) + t * (c[3][1] - 2 * c[2][1] + c[1][1])),
    )
}

/**
 * Points along [c] after its start, close enough together - in distance and
 * in turn - that the stroke builder reads them as one smooth curve.
 */
private fun sampleCubic(c: Array<FloatArray>): List<FloatArray> {
    val length = dist(c[0], c[1]) + dist(c[1], c[2]) + dist(c[2], c[3])
    var turn = 0f
    for (i in 1..2) if (dist(c[i - 1], c[i]) > 0f && dist(c[i], c[i + 1]) > 0f) turn += turnAngle(c[i - 1], c[i], c[i + 1])
    val steps = max(ceil(length / 6f).toInt(), ceil(turn / 0.14f).toInt()).coerceIn(6, 48)
    return (1..steps).map { cubicAt(c, it.toFloat() / steps) }
}

// ---- small geometry -------------------------------------------------------------

/** [count] points evenly spaced along [points], both ends kept. */
private fun resample(points: List<FloatArray>, count: Int): List<FloatArray> {
    val lengths = FloatArray(points.size)
    for (i in 1 until points.size) lengths[i] = lengths[i - 1] + dist(points[i], points[i - 1])
    val total = lengths.last()
    if (total <= 0f || points.size < 2) return List(count) { points.first().copyOf(2) }
    val out = ArrayList<FloatArray>(count)
    var j = 1
    for (i in 0 until count) {
        val at = total * i / (count - 1)
        while (j < points.size - 1 && lengths[j] < at) j++
        val span = lengths[j] - lengths[j - 1]
        val t = if (span > 0f) ((at - lengths[j - 1]) / span).coerceIn(0f, 1f) else 0f
        val a = points[j - 1]
        val b = points[j]
        out += floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    }
    return out
}

/** [points] softened by a 1-2-1 average, [passes] times, the ends left where they are. */
private fun smoothed(points: List<FloatArray>, passes: Int): List<FloatArray> {
    var current = points
    repeat(passes) {
        current = current.indices.map { i ->
            if (i == 0 || i == current.size - 1) current[i] else floatArrayOf(
                (current[i - 1][0] + 2 * current[i][0] + current[i + 1][0]) / 4f,
                (current[i - 1][1] + 2 * current[i][1] + current[i + 1][1]) / 4f,
            )
        }
    }
    return current
}

private fun bounds(points: List<FloatArray>): FloatArray {
    val box = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    for (p in points) {
        box[0] = min(box[0], p[0]); box[1] = min(box[1], p[1])
        box[2] = max(box[2], p[0]); box[3] = max(box[3], p[1])
    }
    return box
}

private fun dist(a: FloatArray, b: FloatArray): Float = hypot(a[0] - b[0], a[1] - b[1])

private fun add(p: FloatArray, direction: FloatArray, length: Float) =
    floatArrayOf(p[0] + direction[0] * length, p[1] + direction[1] * length)

/** The unit vector from [a] toward [b]. */
private fun direction(a: FloatArray, b: FloatArray): FloatArray {
    val length = dist(a, b)
    return if (length > 0f) floatArrayOf((b[0] - a[0]) / length, (b[1] - a[1]) / length) else floatArrayOf(1f, 0f)
}

private fun segmentDistance(p: FloatArray, a: FloatArray, b: FloatArray): Float {
    val dx = b[0] - a[0]
    val dy = b[1] - a[1]
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared > 0f) (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / lengthSquared).coerceIn(0f, 1f) else 0f
    return hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy)
}

private fun lineDistance(p: FloatArray, a: FloatArray, b: FloatArray): Float {
    val length = dist(a, b)
    if (length == 0f) return dist(p, a)
    return abs((b[0] - a[0]) * (a[1] - p[1]) - (a[0] - p[0]) * (b[1] - a[1])) / length
}

/** How far travel turns at [b], in radians from 0 (straight on) to pi (straight back). */
private fun turnAngle(a: FloatArray, b: FloatArray, c: FloatArray): Float {
    val ux = b[0] - a[0]; val uy = b[1] - a[1]
    val vx = c[0] - b[0]; val vy = c[1] - b[1]
    val lengths = hypot(ux, uy) * hypot(vx, vy)
    if (lengths == 0f) return 0f
    return acos(((ux * vx + uy * vy) / lengths).coerceIn(-1f, 1f))
}

/** The angle inside the corner at [b], in degrees. */
private fun interiorDegrees(a: FloatArray, b: FloatArray, c: FloatArray): Float =
    180f - Math.toDegrees(turnAngle(a, b, c).toDouble()).toFloat()

/** The line through [points] that fits them best: a point on it, then its direction. */
private fun fitLine(points: List<FloatArray>): FloatArray {
    val mx = points.sumOf { it[0].toDouble() } / points.size
    val my = points.sumOf { it[1].toDouble() } / points.size
    var sxx = 0.0; var sxy = 0.0; var syy = 0.0
    for (p in points) {
        val dx = p[0] - mx
        val dy = p[1] - my
        sxx += dx * dx; sxy += dx * dy; syy += dy * dy
    }
    val angle = 0.5 * atan2(2 * sxy, sxx - syy)
    return floatArrayOf(mx.toFloat(), my.toFloat(), cos(angle).toFloat(), sin(angle).toFloat())
}

/** Where two fitted lines cross, or null when they are within 15 degrees of parallel. */
private fun intersect(l1: FloatArray, l2: FloatArray): FloatArray? {
    val cross = l1[2] * l2[3] - l1[3] * l2[2]
    if (abs(cross) < 0.26f) return null
    val t = ((l2[0] - l1[0]) * l2[3] - (l2[1] - l1[1]) * l2[2]) / cross
    return floatArrayOf(l1[0] + l1[2] * t, l1[1] + l1[3] * t)
}

/** The point on fitted line [line] nearest [p]. */
private fun project(p: FloatArray, line: FloatArray): FloatArray {
    val t = (p[0] - line[0]) * line[2] + (p[1] - line[1]) * line[3]
    return floatArrayOf(line[0] + line[2] * t, line[1] + line[3] * t)
}

/** Whether segment a-b properly crosses segment c-d. */
private fun segmentsCross(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray): Boolean {
    fun side(p: FloatArray, q: FloatArray, r: FloatArray) = (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
    val d1 = side(c, d, a)
    val d2 = side(c, d, b)
    val d3 = side(a, b, c)
    val d4 = side(a, b, d)
    return d1 * d2 < 0f && d3 * d4 < 0f
}

private const val AXIS_SNAP_RADIANS = 0.07f

/**
 * The stroke as recognition should see it. Holding the pen still to ask for a
 * shape leaves a few hundred samples jittering on one spot at the end, and the
 * pen settles the same way as it lands; read as they are, those clusters are a
 * tangle of tiny turns, and a straight line came back as a bent one. Each is
 * folded into the one point it rests on, and samples nearer together than a
 * small fraction of the drawing are merged, so a slow 240 Hz trace looks the
 * same as a quick one.
 */
internal fun forRecognition(points: List<FloatArray>): List<FloatArray> {
    if (points.size < 3) return points
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    for (p in points) {
        left = min(left, p[0]); right = max(right, p[0])
        top = min(top, p[1]); bottom = max(bottom, p[1])
    }
    val diagonal = hypot(right - left, bottom - top)
    val rest = max(REST_MIN, diagonal * REST_FRACTION)
    // Measured from the running middle of the cluster, not from one sample:
    // a single jittered sample is as likely as any to be the stray one.
    var end = points.size - 1
    var sumX = points[end][0]
    var sumY = points[end][1]
    while (end > 0) {
        val n = (points.size - end).toFloat()
        val p = points[end - 1]
        if (hypot(p[0] - sumX / n, p[1] - sumY / n) > rest) break
        end--
        sumX += p[0]; sumY += p[1]
    }
    var start = 0
    sumX = points[0][0]
    sumY = points[0][1]
    while (start < end) {
        val n = (start + 1).toFloat()
        val p = points[start + 1]
        if (hypot(p[0] - sumX / n, p[1] - sumY / n) > rest) break
        start++
        sumX += p[0]; sumY += p[1]
    }
    if (end - start < 2) return points
    fun centroid(from: Int, to: Int): FloatArray {
        var x = 0f
        var y = 0f
        for (i in from..to) { x += points[i][0]; y += points[i][1] }
        val n = (to - from + 1).toFloat()
        return floatArrayOf(x / n, y / n)
    }
    val spacing = max(SPACING_MIN, diagonal * SPACING_FRACTION)
    val out = ArrayList<FloatArray>(end - start + 2)
    out += centroid(0, start)
    for (i in start + 1 until end) {
        val p = points[i]
        val previous = out.last()
        if (hypot(p[0] - previous[0], p[1] - previous[1]) >= spacing) out += p
    }
    val landing = centroid(end, points.size - 1)
    val previous = out.last()
    if (out.size > 1 && hypot(landing[0] - previous[0], landing[1] - previous[1]) < spacing) out[out.size - 1] = landing
    else out += landing
    return out
}

/** A rest is the pen within this much of one spot: at least a few units, and more on a big drawing. */
private const val REST_MIN = 6f
private const val REST_FRACTION = 0.02f
private const val SPACING_MIN = 1f
private const val SPACING_FRACTION = 0.004f
