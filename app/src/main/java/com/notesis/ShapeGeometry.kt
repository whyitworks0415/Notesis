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
}

/** Shapes whose box is worth squaring when the drag is nearly square. */
internal val ShapeKind.squarable: Boolean
    get() = this != ShapeKind.LINE && this != ShapeKind.ARROW && this != ShapeKind.DOUBLE_ARROW &&
        this != ShapeKind.SINE && this != ShapeKind.AXES && this != ShapeKind.BRACE

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
