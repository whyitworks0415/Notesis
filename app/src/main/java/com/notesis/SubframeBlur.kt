package com.notesis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** What the pen's in-between samples are shown as, ahead of the tip. */
internal const val SUBFRAME_OFF = 0
internal const val SUBFRAME_BLUR = 1
internal const val SUBFRAME_GHOST = 2

/**
 * The S Pen reports four or so positions for every frame a 120 Hz panel shows,
 * so the tip of the ink jumps a frame's travel at a time. From the newest few
 * samples - the last [WINDOW_MS] of them - this is how far the pen moves in
 * [leadMs], in screen pixels: what the panel will not show until the next frame.
 */
internal class SubframeTrack(private val capacity: Int = 16) {
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val ts = LongArray(capacity)
    private var count = 0
    private var head = 0

    /** The answer of [lead], reused: it is asked for on every pen sample. */
    private val out = FloatArray(2)

    fun reset() { count = 0; head = 0 }

    fun add(x: Float, y: Float, time: Long) {
        xs[head] = x; ys[head] = y; ts[head] = time
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    /** (dx, dy) the pen covers in the next [leadMs] at its current velocity, or null when it is not moving. */
    fun lead(leadMs: Float): FloatArray? {
        if (count < 2) return null
        val newest = (head - 1 + capacity) % capacity
        var oldest = newest
        for (k in 1 until count) {
            val i = (newest - k + capacity) % capacity
            val age = ts[newest] - ts[i]
            // The window, but always the sample before the newest unless the pen paused.
            if ((age > WINDOW_MS && k > 1) || age > PAUSE_MS) break
            oldest = i
        }
        val dt = (ts[newest] - ts[oldest]).toFloat()
        if (oldest == newest || dt <= 0f) return null
        val vx = (xs[newest] - xs[oldest]) / dt
        val vy = (ys[newest] - ys[oldest]) / dt
        out[0] = vx * leadMs
        out[1] = vy * leadMs
        return out
    }

    companion object {
        /** About three 480 Hz samples: recent enough to turn with the pen, long enough not to jitter. */
        const val WINDOW_MS = 7L
        const val PAUSE_MS = 24L
    }
}

/**
 * The pixel box, [left, top, right, bottom] into [out], that a mark from
 * (x0, y0) to (x1, y1) drawn [pad] thick on every side covers, kept inside a
 * parent of [maxWidth] x [maxHeight]. False when none of it is inside.
 */
internal fun overlayBounds(
    x0: Float, y0: Float, x1: Float, y1: Float, pad: Float,
    maxWidth: Int, maxHeight: Int, out: IntArray,
): Boolean {
    if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite() || !pad.isFinite()) return false
    val reach = pad.coerceAtLeast(0f)
    val left = floor(minOf(x0, x1) - reach).toInt().coerceAtLeast(0)
    val top = floor(minOf(y0, y1) - reach).toInt().coerceAtLeast(0)
    val right = ceil(maxOf(x0, x1) + reach).toInt().coerceAtMost(maxWidth)
    val bottom = ceil(maxOf(y0, y1) + reach).toInt().coerceAtMost(maxHeight)
    if (right <= left || bottom <= top) return false
    out[0] = left; out[1] = top; out[2] = right; out[3] = bottom
    return true
}

/**
 * Moves an overlay onto the box round what it is about to draw, as a small
 * square shifted there rather than a full-screen view.
 *
 * A View's damage is its whole bounds, so a mark moved about a full-screen
 * overlay on every pen frame redrew - and recomposited - the entire window,
 * page, toolbar and glass included. Kept to the mark, a frame touches only the
 * square where it was and where it is now: the platform damages both when a
 * view's translation changes, which is also what wipes the mark's last
 * position. The square moves by translation alone; changing a view's bounds
 * would make the parent record itself again, and damage all of it. So the size
 * changes only when the mark outgrows it, or when the parent's layout has put
 * the view back to full size. The view draws from its own position, so it is
 * right in either case; [overlayOrigin] is where its drawing starts.
 */
internal fun View.placeOver(box: IntArray) {
    val side = overlaySide(box[2] - box[0], box[3] - box[1])
    if (left != 0 || top != 0 || width < side || height < side || width > side * 2 || height > side * 2) {
        setLeftTopRightBottom(0, 0, side, side)
    }
    val x = box[0].toFloat()
    val y = box[1].toFloat()
    if (translationX != x) translationX = x
    if (translationY != y) translationY = y
}

/** Moves [canvas] so an overlay placed by [placeOver] draws in its parent's coordinates. */
internal fun View.overlayOrigin(canvas: Canvas) {
    canvas.translate(-(left + translationX), -(top + translationY))
}

/**
 * The side of the square an overlay takes for a mark [w] x [h] pixels:
 * rounded up a step, so a mark that grows by a pixel does not resize it.
 */
internal fun overlaySide(w: Int, h: Int): Int =
    (maxOf(w, h, 1) + OVERLAY_STEP_PX - 1) / OVERLAY_STEP_PX * OVERLAY_STEP_PX

private const val OVERLAY_STEP_PX = 64

/**
 * Draws the pen's sub-frame travel just ahead of the wet ink: a streak that
 * fades out along the way (motion blur), or the nib repeated at each sample
 * step and fading (afterimage). Never saved, never part of the stroke.
 */
internal class SubframeBlurView(context: Context) : View(context) {
    var mode: Int = SUBFRAME_OFF
        set(value) { field = value; if (value == SUBFRAME_OFF) clear() }

    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var x = 0f
    private var y = 0f
    private var dx = 0f
    private var dy = 0f
    private var color = Color.BLACK
    private var width = 1f
    private var shown = false
    private var gradient: LinearGradient? = null
    private var gradientColor = 0
    private val gradientMatrix = android.graphics.Matrix()
    private val hide = Runnable { clear() }
    private val box = IntArray(4)

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(tipX: Float, tipY: Float, leadX: Float, leadY: Float, colorArgb: Int, widthPx: Float) {
        if (mode == SUBFRAME_OFF) return
        val length = hypot(leadX, leadY)
        if (!length.isFinite() || length < MIN_LENGTH_PX) { clear(); return }
        // A long streak is a guess, not the pen; keep it to a few nib widths.
        val density = resources.displayMetrics.density
        val cap = maxOf(widthPx * 3f, density * MIN_CAP_DP).coerceAtMost(density * MAX_CAP_DP)
        val scale = if (length > cap) cap / length else 1f
        x = tipX; y = tipY; dx = leadX * scale; dy = leadY * scale
        color = colorArgb
        width = maxOf(1f, widthPx)
        val host = parent as? View
        if (host != null && host.width > 0) {
            // Antialiasing reaches a pixel or two past the nib.
            if (!overlayBounds(x, y, x + dx, y + dy, width / 2f + EDGE_PX, host.width, host.height, box)) {
                clear(); return
            }
            placeOver(box)
        }
        shown = true
        removeCallbacks(hide)
        postDelayed(hide, LIFETIME_MS)
        postInvalidateOnAnimation()
    }

    fun clear() {
        removeCallbacks(hide)
        if (!shown) return
        shown = false
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        // The marks are kept in the parent's coordinates; see [placeOver].
        overlayOrigin(canvas)
        val alpha = Color.alpha(color)
        val rgb = color and 0xFFFFFF
        if (mode == SUBFRAME_BLUR) {
            streak.strokeWidth = width
            // One gradient per ink colour, laid along a unit line and moved onto
            // the streak by its matrix: a new shader on every frame of writing
            // was garbage for nothing.
            if (gradient == null || gradientColor != color) {
                gradient = LinearGradient(0f, 0f, 1f, 0f,
                    (alpha * BLUR_ALPHA).toInt() shl 24 or rgb, rgb, Shader.TileMode.CLAMP)
                gradientColor = color
            }
            gradientMatrix.setScale(hypot(dx, dy).coerceAtLeast(1e-3f), 1f)
            gradientMatrix.postRotate(Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat())
            gradientMatrix.postTranslate(x, y)
            gradient!!.setLocalMatrix(gradientMatrix)
            streak.shader = gradient
            canvas.drawLine(x, y, x + dx, y + dy, streak)
        } else {
            for (step in 1..GHOSTS) {
                val f = step / GHOSTS.toFloat()
                dot.color = (alpha * GHOST_ALPHA * (1f - f + 1f / GHOSTS)).toInt().coerceIn(0, 255) shl 24 or rgb
                canvas.drawCircle(x + dx * f, y + dy * f, width / 2f, dot)
            }
        }
    }

    private companion object {
        const val MIN_LENGTH_PX = 1.5f
        const val EDGE_PX = 2f
        const val MIN_CAP_DP = 6f
        const val MAX_CAP_DP = 18f
        const val LIFETIME_MS = 30L
        const val BLUR_ALPHA = 0.55f
        const val GHOST_ALPHA = 0.42f
        /** One per 480 Hz sample in a 120 Hz frame. */
        const val GHOSTS = 4
    }
}
