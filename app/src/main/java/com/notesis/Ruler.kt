package com.notesis

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class RulerKind { RULER, TRIANGLE, PROTRACTOR }

/**
 * A straight edge or the protractor's arc the pen can be laid against, in the
 * ruler's own frame: x along the ruler, y across it, origin at its middle.
 * Straight edges run (ax, ay)→(bx, by); an arc is centred on the origin.
 */
internal class RulerEdge(
    val ax: Float, val ay: Float, val bx: Float, val by: Float,
    val radius: Float = 0f,
    /** A point inside the body, so "outward" is known for every edge. */
    val insideX: Float = 0f, val insideY: Float = 0f,
) {
    val isArc: Boolean get() = radius > 0f
}

/** The edges of [kind] at [size] (the ruler's length, a triangle's leg, a protractor's radius). */
internal fun rulerEdges(kind: RulerKind, size: Float): List<RulerEdge> {
    val h = size / 2f
    return when (kind) {
        RulerKind.RULER -> {
            val depth = size * RULER_DEPTH
            listOf(RulerEdge(-h, -depth / 2f, h, -depth / 2f), RulerEdge(-h, depth / 2f, h, depth / 2f))
        }
        // Right angle at the bottom left.
        RulerKind.TRIANGLE -> listOf(
            RulerEdge(-h, h, h, h, insideX = -h / 3f, insideY = h / 3f),
            RulerEdge(-h, h, -h, -h, insideX = -h / 3f, insideY = h / 3f),
            RulerEdge(-h, -h, h, h, insideX = -h / 3f, insideY = h / 3f),
        )
        RulerKind.PROTRACTOR -> listOf(
            RulerEdge(-size, 0f, size, 0f, insideY = -size / 2f),
            RulerEdge(0f, 0f, 0f, 0f, radius = size),
        )
    }
}

/**
 * Where the pen goes when laid against [edge], local point (x, y) in, local
 * point out: onto the edge's line, or round the arc, pushed [offset] off the
 * body so the ink sits beside the ruler rather than under it.
 */
internal fun projectOnEdge(edge: RulerEdge, x: Float, y: Float, offset: Float, out: FloatArray, tick: Float = 0f) {
    if (edge.isArc) {
        val angle = atan2(y, x)
        // The protractor is the upper half only.
        val a = if (angle > 0f) (if (x >= 0f) 0f else PI.toFloat()) else angle
        val r = edge.radius + offset
        out[0] = r * cos(a)
        out[1] = r * sin(a)
        return
    }
    val dx = edge.bx - edge.ax
    val dy = edge.by - edge.ay
    val length = hypot(dx, dy)
    val ux = dx / length
    val uy = dy / length
    var t = (x - edge.ax) * ux + (y - edge.ay) * uy
    // Close to a tick, the pen lands on it: lines measured on the ruler come out whole.
    if (tick >= MIN_SNAP_TICK) {
        val nearest = Math.round(t / tick) * tick
        if (kotlin.math.abs(t - nearest) <= tick * TICK_SNAP_FRACTION) t = nearest
    }
    var nx = -uy
    var ny = ux
    // Outward is away from the inside of the body.
    if (nx * (edge.ax - edge.insideX) + ny * (edge.ay - edge.insideY) < 0f) { nx = -nx; ny = -ny }
    out[0] = edge.ax + ux * t + nx * offset
    out[1] = edge.ay + uy * t + ny * offset
}

/** The edge within [reach] of local point (x, y), nearest first, or null. */
internal fun edgeNear(edges: List<RulerEdge>, x: Float, y: Float, reach: Float): RulerEdge? {
    var best: RulerEdge? = null
    var bestDistance = reach
    for (edge in edges) {
        val d = if (edge.isArc) {
            if (y > 0f) continue
            abs(hypot(x, y) - edge.radius)
        } else segmentDistance(edge, x, y)
        if (d <= bestDistance) { bestDistance = d; best = edge }
    }
    return best
}

private fun segmentDistance(edge: RulerEdge, x: Float, y: Float): Float {
    val dx = edge.bx - edge.ax
    val dy = edge.by - edge.ay
    val t = (((x - edge.ax) * dx + (y - edge.ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
    return hypot(x - (edge.ax + dx * t), y - (edge.ay + dy * t))
}

/** The nearest multiple of 45°, in degrees, for a tap on the ruler's middle. */
internal fun snapTo45(degrees: Float): Float = ((degrees / 45f).roundToInt() * 45f).let { ((it % 360f) + 360f) % 360f }

/**
 * The ruler, triangle or protractor laid over the canvas. It lives on the
 * screen, not the page: the page moves under it as it would under a real one.
 * Fingers that land on it move and turn it; the canvas asks it where a pen
 * landing near an edge should be drawn.
 */
@SuppressLint("ViewConstructor")
internal class RulerView(context: Context) : View(context) {
    var kind: RulerKind = RulerKind.RULER
        set(value) { field = value; invalidate() }

    /** Screen pixels per millimetre of page at the current zoom; ticks are real page millimetres. */
    var pxPerMm: Float = 1f
        set(value) { if (field != value) { field = value; invalidate() } }

    var inches: Boolean = false
        set(value) { field = value; invalidate() }

    /** Asked to close: dragged off screen, pinched shut. */
    var onClosed: (() -> Unit)? = null

    var cx = 0f
        private set
    var cy = 0f
        private set
    var degrees = 0f
        private set
    private val density = resources.displayMetrics.density
    private var placed = false

    val size: Float get() = when (kind) {
        RulerKind.RULER -> 560f * density
        RulerKind.TRIANGLE -> 300f * density
        RulerKind.PROTRACTOR -> 180f * density
    }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66B0BEC5; style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC37474F.toInt(); style = Paint.Style.STROKE; strokeWidth = density
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF263238.toInt(); strokeWidth = density * 0.8f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF263238.toInt(); textSize = 11f * density; textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    fun place(x: Float, y: Float) {
        cx = x; cy = y; placed = true; invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!placed) { cx = w / 2f; cy = h / 2f }
    }

    /** Screen point (x, y) into the ruler's frame. */
    fun toLocal(x: Float, y: Float, out: FloatArray) {
        val r = Math.toRadians(-degrees.toDouble())
        val dx = x - cx
        val dy = y - cy
        out[0] = (dx * cos(r) - dy * sin(r)).toFloat()
        out[1] = (dx * sin(r) + dy * cos(r)).toFloat()
    }

    fun toScreen(x: Float, y: Float, out: FloatArray) {
        val r = Math.toRadians(degrees.toDouble())
        out[0] = (cx + x * cos(r) - y * sin(r)).toFloat()
        out[1] = (cy + x * sin(r) + y * cos(r)).toFloat()
    }

    private val local = FloatArray(2)

    /** The edge a pen landing at screen (x, y) is laid against, or null to write freely. */
    fun edgeAt(x: Float, y: Float): RulerEdge? {
        toLocal(x, y, local)
        return edgeNear(rulerEdges(kind, size), local[0], local[1], SNAP_DP * density)
    }

    /** Screen (x, y) moved onto [edge], [offset] screen pixels off the body. */
    fun project(edge: RulerEdge, x: Float, y: Float, offset: Float, out: FloatArray) {
        toLocal(x, y, local)
        projectOnEdge(edge, local[0], local[1], offset, local, if (inches) pxPerMm * 25.4f / 16f else pxPerMm)
        toScreen(local[0], local[1], out)
    }

    private fun contains(x: Float, y: Float): Boolean {
        toLocal(x, y, local)
        val lx = local[0]
        val ly = local[1]
        val s = size / 2f
        return when (kind) {
            RulerKind.RULER -> abs(lx) <= s && abs(ly) <= size * RULER_DEPTH / 2f
            RulerKind.TRIANGLE -> lx >= -s && ly <= s && ly >= lx
            RulerKind.PROTRACTOR -> ly <= 0f && hypot(lx, ly) <= size
        }
    }

    private fun nearMiddle(x: Float, y: Float): Boolean = hypot(x - cx, y - cy) <= MIDDLE_DP * density

    // ---- fingers ------------------------------------------------------------

    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastAngle = 0f
    private var lastSpread = 0f
    private var startSpread = 0f
    private var downTime = 0L
    private var travelled = 0f
    private var fingers = 0

    /** Consumes a finger gesture that starts on the ruler. False leaves it to the page. */
    fun onFinger(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = contains(event.x, event.y)
                if (!dragging) return false
                lastX = event.x; lastY = event.y
                downTime = event.eventTime
                travelled = 0f
                fingers = 1
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!dragging) return false
                fingers = maxOf(fingers, event.pointerCount)
                anchor(event)
                startSpread = lastSpread
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val (x, y) = focus(event)
                travelled += hypot(x - lastX, y - lastY)
                cx += x - lastX
                cy += y - lastY
                lastX = x; lastY = y
                if (event.pointerCount >= 2) {
                    val angle = angleOf(event)
                    var delta = angle - lastAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    degrees = ((degrees + delta) % 360f + 360f) % 360f
                    lastAngle = angle
                    lastSpread = spreadOf(event)
                    travelled += abs(delta)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (!dragging) return false
                val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                lastX = remaining.map { event.getX(it) }.average().toFloat()
                lastY = remaining.map { event.getY(it) }.average().toFloat()
                // Pinched shut: two fingers that ended far closer than they began.
                if (event.pointerCount == 2 && startSpread > 0f && lastSpread < startSpread * PINCH_CLOSE) {
                    dragging = false
                    onClosed?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                val tap = event.eventTime - downTime < TAP_MS && travelled < TAP_SLOP_DP * density
                if (tap && fingers >= 2) {
                    degrees = ((degrees - 90f) % 360f + 360f) % 360f
                } else if (tap && nearMiddle(event.x, event.y)) {
                    degrees = snapTo45(degrees)
                }
                if (cx < -size / 4f || cy < -size / 4f || cx > width + size / 4f || cy > height + size / 4f) {
                    onClosed?.invoke()
                }
                invalidate()
                return true
            }
        }
        return dragging
    }

    private fun anchor(event: MotionEvent) {
        val (x, y) = focus(event)
        lastX = x; lastY = y
        lastAngle = angleOf(event)
        lastSpread = spreadOf(event)
    }

    private fun focus(event: MotionEvent): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (i in 0 until event.pointerCount) { x += event.getX(i); y += event.getY(i) }
        return x / event.pointerCount to y / event.pointerCount
    }

    private fun angleOf(event: MotionEvent): Float =
        if (event.pointerCount < 2) lastAngle
        else Math.toDegrees(atan2((event.getY(1) - event.getY(0)).toDouble(),
            (event.getX(1) - event.getX(0)).toDouble())).toFloat()

    private fun spreadOf(event: MotionEvent): Float =
        if (event.pointerCount < 2) 0f else hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))

    /** Turns the ruler to exactly [value] degrees. */
    fun setDegrees(value: Float) {
        degrees = ((value % 360f) + 360f) % 360f
        invalidate()
    }

    // ---- drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(degrees)
        val s = size / 2f
        path.reset()
        when (kind) {
            RulerKind.RULER -> {
                val d = size * RULER_DEPTH / 2f
                path.addRect(-s, -d, s, d, Path.Direction.CW)
                canvas.drawPath(path, body)
                canvas.drawPath(path, rim)
                ticks(canvas, -s, s, -d, +1f)
            }
            RulerKind.TRIANGLE -> {
                path.moveTo(-s, s); path.lineTo(s, s); path.lineTo(-s, -s); path.close()
                canvas.drawPath(path, body)
                canvas.drawPath(path, rim)
                ticks(canvas, -s, s, s, -1f)
            }
            RulerKind.PROTRACTOR -> {
                val r = size
                path.addArc(-r, -r, r, r, 180f, 180f)
                path.close()
                canvas.drawPath(path, body)
                canvas.drawPath(path, rim)
                for (deg in 0..180) {
                    val a = Math.toRadians(180.0 + deg)
                    val len = if (deg % 10 == 0) 14f * density else if (deg % 5 == 0) 9f * density else 5f * density
                    val ox = (r * cos(a)).toFloat()
                    val oy = (r * sin(a)).toFloat()
                    val ix = ((r - len) * cos(a)).toFloat()
                    val iy = ((r - len) * sin(a)).toFloat()
                    canvas.drawLine(ox, oy, ix, iy, tick)
                    if (deg % 30 == 0) {
                        val tr = r - 26f * density
                        canvas.drawText("$deg", (tr * cos(a)).toFloat(), (tr * sin(a)).toFloat() + 4f * density, label)
                    }
                }
                canvas.drawCircle(0f, 0f, 3f * density, tick)
            }
        }
        canvas.restore()
        // The angle, upright whatever the ruler's turn.
        val shown = if (degrees > 180f) 360f - degrees else degrees
        canvas.drawText("${shown.roundToInt()}°", cx, cy + 4f * density, label)
    }

    /** Millimetre (or sixteenth-inch) ticks along y = [edgeY], pointing in [direction]. */
    private fun ticks(canvas: Canvas, from: Float, to: Float, edgeY: Float, direction: Float) {
        val unit = if (inches) pxPerMm * 25.4f / 16f else pxPerMm
        if (unit < 2f) return
        var i = 0
        var x = from
        while (x <= to) {
            val major = if (inches) i % 16 == 0 else i % 10 == 0
            val mid = if (inches) i % 8 == 0 else i % 5 == 0
            val len = (if (major) 14f else if (mid) 9f else 5f) * density
            canvas.drawLine(x, edgeY, x, edgeY + len * direction, tick)
            if (major && i > 0) {
                canvas.drawText("${if (inches) i / 16 else i / 10}", x, edgeY + (len + 11f * density) * direction +
                    if (direction < 0) 0f else -2f * density, label)
            }
            i++
            x = from + i * unit
        }
    }

    companion object {
        const val SNAP_DP = 28f
        const val MIDDLE_DP = 36f
        const val TAP_MS = 300L
        const val TAP_SLOP_DP = 10f
        const val PINCH_CLOSE = 0.45f
    }
}

/** Ticks closer together than this (screen px) are too fine to snap to. */
internal const val MIN_SNAP_TICK = 4f
internal const val TICK_SNAP_FRACTION = 0.3f

/** How deep a straight ruler is, as a fraction of its length. */
internal const val RULER_DEPTH = 0.16f
