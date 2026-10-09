package com.notesis

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View

/**
 * Picks a colour off whatever is on screen - ink, a picture, a PDF - by
 * copying the pixel under the finger or pen out of the window. The window and
 * not the canvas, because the page is drawn by RenderNodes and a SurfaceView
 * that a software canvas cannot read back.
 */
internal class EyedropperView(context: Context) : View(context) {
    /** Non-null while picking; called once with the colour where the touch lifted. */
    var onPicked: ((Int) -> Unit)? = null
        set(value) {
            field = value
            visibility = if (value == null) GONE else VISIBLE
            showing = false
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private var x = 0f
    private var y = 0f
    private var showing = false
    private var color = 0xFF000000.toInt()
    private val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val handler = Handler(Looper.getMainLooper())
    private val location = IntArray(2)
    private var copying = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init { visibility = GONE }

    /** True while picking: the touch is the picker's, not the page's. */
    fun onTouch(event: MotionEvent): Boolean {
        val picked = onPicked ?: return false
        x = event.x
        y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                showing = true
                sample()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                onPicked = null
                picked(color)
            }
            MotionEvent.ACTION_CANCEL -> onPicked = null
        }
        return true
    }

    private fun sample() {
        val window = activity()?.window ?: return
        if (copying) return
        getLocationInWindow(location)
        val sx = (location[0] + x).toInt()
        val sy = (location[1] + y).toInt()
        copying = true
        runCatching {
            PixelCopy.request(window, Rect(sx, sy, sx + 1, sy + 1), pixel, { result ->
                copying = false
                if (result == PixelCopy.SUCCESS) {
                    color = pixel.getPixel(0, 0) or 0xFF000000.toInt()
                    invalidate()
                }
            }, handler)
        }.onFailure { copying = false }
    }

    private fun activity(): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        if (!showing) return
        // The loupe sits above the finger, which would otherwise cover what it picks.
        val r = LOUPE_DP * density
        val lx = x
        val ly = (y - r * 1.6f).coerceAtLeast(r)
        fill.color = color
        canvas.drawCircle(lx, ly, r, fill)
        ring.strokeWidth = 4f * density
        ring.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(lx, ly, r, ring)
        ring.strokeWidth = 1f * density
        ring.color = 0xFF424242.toInt()
        canvas.drawCircle(lx, ly, r + 2f * density, ring)
        canvas.drawCircle(x, y, 3f * density, ring)
    }

    companion object {
        const val LOUPE_DP = 34f
    }
}
