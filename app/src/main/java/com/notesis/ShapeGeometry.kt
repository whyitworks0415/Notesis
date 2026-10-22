package com.notesis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * The shapes the pen can be made to draw instead of following the hand. Each is
 * one polyline from the drag's start to its end, retracing itself where a shape
 * has parts (an arrow's barbs, a cube's back edges), so it stays one stroke and
 * one undo. Appended only: nothing saves an ordinal, but the menu keeps this order.
 */
enum class ShapeKind {
    LINE, ARROW, RECT, OVAL,
    TRIANGLE, RIGHT_TRIANGLE, DIAMOND, PARALLELOGRAM, TRAPEZOID, PENTAGON, HEXAGON, STAR, HEART,
    ARC, SECTOR, SINE, AXES, BRACE, BUBBLE, CUBE, CYLINDER, DOUBLE_ARROW,
    /** A gentle bow from start to end. */
    CURVE,
    /** Tapped out a corner at a time; closes on the first corner, ends on a double tap. */
    POLYGON,
    /** A box to tick: a tap places one, a drag sizes it. Not drawn as ink - see [drawCheckbox]. */
    CHECKBOX,
}

/** Shapes whose box is worth squaring when the drag is nearly square. */
internal val ShapeKind.squarable: Boolean
    get() = this != ShapeKind.LINE && this != ShapeKind.ARROW && this != ShapeKind.DOUBLE_ARROW &&
        this != ShapeKind.SINE && this != ShapeKind.AXES && this != ShapeKind.BRACE &&
        this != ShapeKind.CURVE && this != ShapeKind.POLYGON

/**
 * Where the drag ends once a nearly square box is made exactly square, or the
 * end unchanged. Within [tolerance] of equal sides counts as meant to be equal.
 */
internal fun squaredEnd(x0: Float, y0: Float, x1: Float, y1: Float, tolerance: Float = SQUARE_TOLERANCE): FloatArray? {
    val w = abs(x1 - x0)
    val h = abs(y1 - y0)
    if (w < 8f || h < 8f) return null
    if (abs(w - h) > max(w, h) * tolerance || w == h) return if (w == h) floatArrayOf(x1, y1) else null
    val side = (w + h) / 2f
    return floatArrayOf(x0 + side * sign(x1 - x0), y0 + side * sign(y1 - y0))
}

/** The outline of [kind] dragged from (x0, y0) to (x1, y1), sampled densely enough that the brush follows it. */
internal fun shapeOutline(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float, axisSnap: Boolean): List<FloatArray> {
    val left = min(x0, x1)
    val right = max(x0, x1)
    val top = min(y0, y1)
    val bottom = max(y0, y1)
    val w = right - left
    val h = bottom - top
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f
    fun p(x: Float, y: Float) = floatArrayOf(x, y)
    fun closed(vararg points: FloatArray) = points.toList() + points[0]
    fun ellipse(from: Double, to: Double, rx: Float, ry: Float, steps: Int = OVAL_STEPS) =
        (0..steps).map {
            val t = from + (to - from) * it / steps
            p(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat())
        }
    fun barbs(tipX: Float, tipY: Float, angle: Float, length: Float): List<FloatArray> {
        val barb = (length * 0.22f).coerceIn(12f, 90f)
        return listOf(
            p(tipX - barb * cos(angle + ARROW_SPREAD), tipY - barb * sin(angle + ARROW_SPREAD)),
            p(tipX, tipY),
            p(tipX - barb * cos(angle - ARROW_SPREAD), tipY - barb * sin(angle - ARROW_SPREAD)),
        )
    }
    return when (kind) {
        ShapeKind.LINE -> {
            val dx = x1 - x0
            val dy = y1 - y0
            val length = hypot(dx, dy)
            val snap = axisSnap && length > 20f && min(abs(dx), abs(dy)) / length < 0.14f
            listOf(p(x0, y0), p(if (snap && abs(dx) < abs(dy)) x0 else x1, if (snap && abs(dy) < abs(dx)) y0 else y1))
        }
        ShapeKind.ARROW -> {
            val angle = atan2(y1 - y0, x1 - x0)
            listOf(p(x0, y0), p(x1, y1)) + barbs(x1, y1, angle, hypot(x1 - x0, y1 - y0))
        }
        ShapeKind.DOUBLE_ARROW -> {
            val angle = atan2(y1 - y0, x1 - x0)
            val length = hypot(x1 - x0, y1 - y0)
            barbs(x0, y0, angle + PI.toFloat(), length) + listOf(p(x1, y1)) + barbs(x1, y1, angle, length)
        }
        ShapeKind.RECT -> closed(p(x0, y0), p(x1, y0), p(x1, y1), p(x0, y1))
        ShapeKind.OVAL -> ellipse(0.0, 2 * PI, w / 2f, h / 2f)
        ShapeKind.TRIANGLE -> closed(p(cx, top), p(right, bottom), p(left, bottom))
        ShapeKind.RIGHT_TRIANGLE -> closed(p(left, top), p(right, bottom), p(left, bottom))
        ShapeKind.DIAMOND -> closed(p(cx, top), p(right, cy), p(cx, bottom), p(left, cy))
        ShapeKind.PARALLELOGRAM -> closed(p(left + w * 0.25f, top), p(right, top), p(right - w * 0.25f, bottom), p(left, bottom))
        ShapeKind.TRAPEZOID -> closed(p(left + w * 0.25f, top), p(right - w * 0.25f, top), p(right, bottom), p(left, bottom))
        ShapeKind.PENTAGON -> polygon(cx, cy, w / 2f, h / 2f, 5, 1f)
        ShapeKind.HEXAGON -> polygon(cx, cy, w / 2f, h / 2f, 6, 1f)
        ShapeKind.STAR -> polygon(cx, cy, w / 2f, h / 2f, 10, STAR_INNER)
        ShapeKind.HEART -> (0..OVAL_STEPS).map {
            // The classic parametric heart, x in [-16, 16] and y in [-17, 12], fitted to the box.
            val t = it * 2.0 * PI / OVAL_STEPS
            val hx = 16 * sin(t).let { s -> s * s * s }
            val hy = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
            p(cx + (hx / 32.0 * w).toFloat(), top + ((12.0 - hy) / 29.0 * h).toFloat())
        }
        // The upper half of the ellipse, the way a protractor's arc is drawn.
        ShapeKind.ARC -> ellipse(PI, 2 * PI, w / 2f, h)
            .map { p(it[0], it[1] + h / 2f) }
        ShapeKind.SECTOR -> listOf(p(cx, bottom)) + ellipse(PI * 1.25, PI * 1.75, w / 2f / sin(PI / 4).toFloat(), h)
            .map { p(it[0], it[1] + h / 2f) } + p(cx, bottom)
        ShapeKind.SINE -> (0..OVAL_STEPS).map {
            val t = it.toFloat() / OVAL_STEPS
            p(x0 + (x1 - x0) * t, cy - h / 2f * sin(t * 2 * PI).toFloat())
        }
        ShapeKind.AXES -> {
            // Both axes cross at the box's centre, each ending in an arrowhead.
            val xTip = barbs(right, cy, 0f, w)
            val yTip = barbs(cx, top, (-PI / 2).toFloat(), h)
            listOf(p(left, cy), p(right, cy)) + xTip + listOf(p(right, cy), p(cx, cy), p(cx, bottom), p(cx, top)) + yTip
        }
        ShapeKind.BRACE -> {
            // A curly brace opening right, standing as tall as the drag.
            val q = h / 4f
            val half = w / 2f
            listOf(p(right, top)) + curve(right, top, left + half, top, left + half, top + q) +
                curve(left + half, top + q, left + half, cy, left, cy) +
                curve(left, cy, left + half, cy, left + half, cy + q) +
                curve(left + half, cy + q, left + half, bottom, right, bottom)
        }
        ShapeKind.BUBBLE -> {
            // A rounded box with a tail from its lower left.
            val r = min(w, h) * 0.18f
            val bodyBottom = top + h * 0.8f
            listOf(p(left + r, top), p(right - r, top)) +
                curve(right - r, top, right, top, right, top + r) + listOf(p(right, bodyBottom - r)) +
                curve(right, bodyBottom - r, right, bodyBottom, right - r, bodyBottom) +
                listOf(p(left + w * 0.35f, bodyBottom), p(left + w * 0.15f, bottom), p(left + w * 0.2f, bodyBottom),
                    p(left + r, bodyBottom)) +
                curve(left + r, bodyBottom, left, bodyBottom, left, bodyBottom - r) + listOf(p(left, top + r)) +
                curve(left, top + r, left, top, left + r, top)
        }
        ShapeKind.CUBE -> {
            val d = min(w, h) * 0.3f
            val fl = left
            val ft = top + d
            val fr = right - d
            val fb = bottom
            // Front face, then each back edge out and along, retracing to stay one line.
            listOf(p(fl, ft), p(fr, ft), p(fr, fb), p(fl, fb), p(fl, ft), p(fl + d, top), p(right, top),
                p(fr, ft), p(right, top), p(right, bottom - d), p(fr, fb))
        }
        ShapeKind.CURVE -> {
            // Bowed to the left of the drag by a quarter of its length.
            val mx = (x0 + x1) / 2f - (y1 - y0) * 0.25f
            val my = (y0 + y1) / 2f + (x1 - x0) * 0.25f
            listOf(p(x0, y0)) + curve(x0, y0, mx, my, x1, y1)
        }
        // Built corner by corner on the canvas; a drag alone is a line.
        ShapeKind.POLYGON -> listOf(p(x0, y0), p(x1, y1))
        // Only ever previewed: the square the checkbox will fill.
        ShapeKind.CHECKBOX -> {
            val side = max(w, h)
            val l = if (x1 >= x0) x0 else x0 - side
            val t = if (y1 >= y0) y0 else y0 - side
            listOf(p(l, t), p(l + side, t), p(l + side, t + side), p(l, t + side), p(l, t))
        }
        ShapeKind.CYLINDER -> {
            val ry = min(h * 0.15f, w * 0.3f)
            val rx = w / 2f
            val topY = top + ry
            val bottomY = bottom - ry
            fun ring(y: Float, from: Double, to: Double) = (0..OVAL_STEPS / 2).map {
                val t = from + (to - from) * it / (OVAL_STEPS / 2)
                p(cx + rx * cos(t).toFloat(), y + ry * sin(t).toFloat())
            }
            ring(topY, 0.0, 2 * PI) + listOf(p(right, bottomY)) + ring(bottomY, 0.0, PI) + listOf(p(left, topY))
        }
    }
}

/** The polygon shapes whose corners can be rounded. */
internal val ShapeKind.cornered: Boolean
    get() = this in setOf(ShapeKind.RECT, ShapeKind.TRIANGLE, ShapeKind.RIGHT_TRIANGLE, ShapeKind.DIAMOND,
        ShapeKind.PARALLELOGRAM, ShapeKind.TRAPEZOID, ShapeKind.PENTAGON, ShapeKind.HEXAGON, ShapeKind.STAR)

/**
 * A closed outline (first point repeated last) with every corner replaced by
 * a curve starting [radius] before it and ending [radius] after, each cut no
 * deeper than half of either side it joins.
 */
internal fun roundCorners(points: List<FloatArray>, radius: Float): List<FloatArray> {
    if (radius <= 0f || points.size < 4) return points
    val corners = points.dropLast(1)
    val n = corners.size
    val out = ArrayList<FloatArray>()
    for (i in 0 until n) {
        val prev = corners[(i - 1 + n) % n]
        val at = corners[i]
        val next = corners[(i + 1) % n]
        val inLength = hypot(at[0] - prev[0], at[1] - prev[1])
        val outLength = hypot(next[0] - at[0], next[1] - at[1])
        if (inLength == 0f || outLength == 0f) continue
        val r = minOf(radius, inLength / 2f, outLength / 2f)
        val ax = at[0] + (prev[0] - at[0]) * r / inLength
        val ay = at[1] + (prev[1] - at[1]) * r / inLength
        val bx = at[0] + (next[0] - at[0]) * r / outLength
        val by = at[1] + (next[1] - at[1]) * r / outLength
        out += floatArrayOf(ax, ay)
        out += curve(ax, ay, at[0], at[1], bx, by)
    }
    if (out.isNotEmpty()) out += out.first()
    return out
}

/** One input of a generated shape stroke: where it is, and when, in milliseconds from the stroke's start. */
internal class ShapeInput(val x: Float, val y: Float, val timeMs: Long)

/**
 * The inputs that make the brush trace [points] faithfully. Ink does not draw
 * a stroke through its inputs: its input modeler pulls a spring-like pen
 * after them, which trails some 25 milliseconds of travel behind and needs
 * about a tenth of a second to settle once they stop. Inputs a few milliseconds apart -
 * the way a whole rectangle used to be written in 32ms - leave it no time to
 * reach any corner, so the shape came out as a lumpy loop with whatever corners
 * the lag happened to make. So the outline is "drawn" at a steady hand speed,
 * slower where it bends, and the pen rests on every sharp corner (by repeating
 * that corner's input at later times) until the model has caught up with it;
 * the next side then sets off from the corner itself and runs straight.
 *
 * [corners] are indices that must stay sharp, on top of any point where the
 * outline turns by [CORNER_TURN] or more; the last point always rests, so the
 * stroke ends where it should. A corner's repeats sit at its exact coordinates,
 * so dragging that corner on a selected shape moves them all together. They
 * are kept to what fits in [budget] inputs when the outline itself does, so a
 * polygon stays editable corner by corner.
 */
internal fun shapeInputs(
    points: List<FloatArray>,
    corners: Collection<Int>? = null,
    budget: Int = SHAPE_EDITABLE_INPUTS,
): List<ShapeInput> {
    if (points.isEmpty()) return emptyList()
    // Repeated points add nothing to an outline and would read as a turn of
    // zero length; each index is remembered as the point it repeats.
    val kept = ArrayList<FloatArray>(points.size)
    val keptIndex = IntArray(points.size)
    for ((i, p) in points.withIndex()) {
        val last = kept.lastOrNull()
        if (last == null || last[0] != p[0] || last[1] != p[1]) kept += p
        keptIndex[i] = kept.size - 1
    }
    if (kept.size == 1) {
        // A dot: still two inputs, the way a tap is.
        val p = kept[0]
        return listOf(ShapeInput(p[0], p[1], 0L), ShapeInput(p[0], p[1], CORNER_DWELL_MS.toLong()))
    }
    val n = kept.size
    val turns = FloatArray(n)
    for (i in 1 until n - 1) turns[i] = turnBetween(kept[i - 1], kept[i], kept[i + 1])
    val sharp = BooleanArray(n)
    for (i in 1 until n) if (turns[i] >= CORNER_TURN) sharp[i] = true
    corners?.forEach { index -> if (index in points.indices && keptIndex[index] > 0) sharp[keptIndex[index]] = true }
    sharp[n - 1] = true
    val restCount = sharp.count { it }
    // Two repeats settle the model a touch closer than one; either is far
    // better than none, so one is what an outline with many corners gets.
    val repeats = if (n + 2 * restCount <= budget || n > budget) 2 else 1
    val out = ArrayList<ShapeInput>(n + repeats * restCount)
    var time = 0.0
    var lastMs = 0L
    fun emit(p: FloatArray) {
        // Ink wants time to move on between inputs, and to the millisecond.
        val ms = max(time.toLong(), if (out.isEmpty()) 0L else lastMs + 1)
        out += ShapeInput(p[0], p[1], ms)
        lastMs = ms
        time = max(time, ms.toDouble())
    }
    emit(kept[0])
    for (i in 1 until n) {
        val from = kept[i - 1]
        val to = kept[i]
        val length = hypot(to[0] - from[0], to[1] - from[1])
        var step = length / SHAPE_SPEED
        // Through a smooth bend, slow enough that the model keeps to the curve
        // instead of cutting inside it.
        if (!sharp[i - 1]) step = max(step, turns[i - 1] / SHAPE_TURN_RATE)
        // A side from corner to corner takes long enough that the model sees
        // it as one movement, not as a jump.
        if ((i == 1 || sharp[i - 1]) && sharp[i]) step = max(step, MIN_SIDE_MS)
        // Above this the model takes coarser steps, which can ring; a long side
        // is drawn a little faster instead.
        time += step.coerceIn(1f, MAX_STEP_MS)
        emit(to)
        if (sharp[i]) repeat(repeats) {
            time += CORNER_DWELL_MS / repeats
            emit(to)
        }
    }
    return out
}

/** How far the direction of travel turns at [b], from a-to-b to b-to-c, in radians (0 to pi). */
private fun turnBetween(a: FloatArray, b: FloatArray, c: FloatArray): Float {
    val h1 = atan2(b[1] - a[1], b[0] - a[0])
    val h2 = atan2(c[1] - b[1], c[0] - b[0])
    var d = abs(h2 - h1)
    if (d > PI.toFloat()) d = 2 * PI.toFloat() - d
    return d
}

/** A regular polygon of [corners] points; every other point pulled in to [inner] makes a star. */
private fun polygon(cx: Float, cy: Float, rx: Float, ry: Float, corners: Int, inner: Float): List<FloatArray> =
    (0..corners).map {
        val t = -PI / 2 + it * 2 * PI / corners
        val r = if (it % 2 == 1) inner else 1f
        floatArrayOf(cx + rx * r * cos(t).toFloat(), cy + ry * r * sin(t).toFloat())
    }

/** A quadratic curve from (ax, ay) bending toward (bx, by) and ending at (cx, cy), start excluded. */
private fun curve(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): List<FloatArray> =
    (1..CURVE_STEPS).map {
        val t = it.toFloat() / CURVE_STEPS
        val u = 1 - t
        floatArrayOf(u * u * ax + 2 * u * t * bx + t * t * cx, u * u * ay + 2 * u * t * by + t * t * cy)
    }

private const val OVAL_STEPS = 64
private const val CURVE_STEPS = 10
private const val STAR_INNER = 0.42f
private const val ARROW_SPREAD = 0.5f
internal const val SQUARE_TOLERANCE = 0.08f

/** At most this many inputs and a selected shape's corners can be dragged one by one. */
internal const val SHAPE_EDITABLE_INPUTS = 24
/** A turn of this much, in radians, is a corner to rest on rather than part of a curve. */
private const val CORNER_TURN = 0.52f
/** A steady hand: page units per millisecond along a generated outline. */
private const val SHAPE_SPEED = 1.2f
/** How quickly a generated outline may turn while it curves, in radians per millisecond. */
private const val SHAPE_TURN_RATE = 0.008f
/** How long the pen rests on a corner, which the input model needs to settle on it. */
private const val CORNER_DWELL_MS = 120f
private const val MIN_SIDE_MS = 40f
private const val MAX_STEP_MS = 300f
