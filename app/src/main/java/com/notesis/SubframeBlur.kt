package com.notesis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
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
        return floatArrayOf(vx * leadMs, vy * leadMs)
    }

    companion object {
        /** About three 480 Hz samples: recent enough to turn with the pen, long enough not to jitter. */
        const val WINDOW_MS = 7L
        const val PAUSE_MS = 24L
    }
}

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
    private val hide = Runnable { clear() }

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
        val alpha = Color.alpha(color)
        val rgb = color and 0xFFFFFF
        if (mode == SUBFRAME_BLUR) {
            streak.strokeWidth = width
            streak.shader = LinearGradient(x, y, x + dx, y + dy,
                (alpha * BLUR_ALPHA).toInt() shl 24 or rgb, rgb, Shader.TileMode.CLAMP)
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
        const val MIN_CAP_DP = 6f
        const val MAX_CAP_DP = 18f
        const val LIFETIME_MS = 30L
        const val BLUR_ALPHA = 0.55f
        const val GHOST_ALPHA = 0.42f
        /** One per 480 Hz sample in a 120 Hz frame. */
        const val GHOSTS = 4
    }
}
