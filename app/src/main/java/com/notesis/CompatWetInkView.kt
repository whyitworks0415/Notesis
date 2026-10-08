package com.notesis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput

/**
 * Galaxy Tab S6 Lite models. Ink's front-buffer layer accepts every update on
 * them but the panel only shows it once HWUI draws again - at pen-up - so the
 * stroke appears all at once when the pen lifts. androidx.ink itself carries a
 * workaround for this tablet (b/365131024), which is not enough here.
 */
internal fun frontBufferInkUnreliable(model: String = Build.MODEL ?: ""): Boolean =
    Regex("^SM-P6(1[0-9]|2[0-9])[A-Z]*$").matches(model.trim().uppercase())

/**
 * Wet ink drawn by an ordinary View, for tablets where [frontBufferInkUnreliable].
 *
 * It mirrors the stroke that InProgressStrokesView is building from the same
 * events - same brush, same page transform, same timing - so what shows while
 * writing is what lands at pen-up. InProgressStrokesView still produces the
 * committed stroke; this only makes it visible on the way. It runs a frame or
 * two behind the front buffer, which is the price of a panel that shows it.
 */
internal class CompatWetInkView(context: Context) : View(context) {
    private val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
    private val stroke = InProgressStroke()
    private val real = MutableStrokeInputBatch()
    private val predicted = MutableStrokeInputBatch()
    private val screenToPage = Matrix()
    private val pageToScreen = Matrix()
    private val point = FloatArray(2)
    private var startTime = 0L
    private var lastTime = 0L
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var active = false
    private var visibleStroke = false

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun start(event: MotionEvent, pointerId: Int, brush: Brush, transform: Matrix) {
        val ok = runCatching {
            stroke.start(brush)
            screenToPage.set(transform)
            if (!screenToPage.invert(pageToScreen)) pageToScreen.reset()
            startTime = event.eventTime
            lastTime = startTime
            lastX = Float.NaN
            lastY = Float.NaN
            active = true
            visibleStroke = true
            enqueue(event, pointerId, null)
        }.isSuccess
        if (!ok) clear()
    }

    fun add(event: MotionEvent, pointerId: Int, prediction: MotionEvent?) {
        if (!active) return
        runCatching { enqueue(event, pointerId, prediction) }
    }

    fun finish(event: MotionEvent, pointerId: Int) {
        if (!active) return
        runCatching {
            enqueue(event, pointerId, null)
            stroke.finishInput()
            stroke.updateShape(Long.MAX_VALUE)
        }
        // Stay on screen until the committed stroke is drawn underneath.
        active = false
        postInvalidateOnAnimation()
    }

    /** Hides the preview: the stroke was cancelled, or has landed in the page. */
    fun clear() {
        active = false
        if (!visibleStroke) return
        visibleStroke = false
        postInvalidateOnAnimation()
    }

    private fun enqueue(event: MotionEvent, pointerId: Int, prediction: MotionEvent?) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        real.clear()
        predicted.clear()
        for (h in 0 until event.historySize) {
            addSample(real, event.getHistoricalX(index, h), event.getHistoricalY(index, h),
                event.getHistoricalEventTime(h), event.getHistoricalPressure(index, h),
                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h), track = true)
        }
        addSample(real, event.getX(index), event.getY(index), event.eventTime,
            event.getPressure(index), event.getAxisValue(MotionEvent.AXIS_TILT, index), track = true)
        if (prediction != null) {
            val p = prediction.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
            if (p < prediction.pointerCount) {
                addSample(predicted, prediction.getX(p), prediction.getY(p),
                    maxOf(prediction.eventTime, lastTime), prediction.getPressure(p),
                    prediction.getAxisValue(MotionEvent.AXIS_TILT, p), track = false)
            }
        }
        stroke.enqueueInputs(real, predicted)
        stroke.updateShape(lastTime - startTime)
        postInvalidateOnAnimation()
    }

    private fun addSample(
        batch: MutableStrokeInputBatch,
        x: Float,
        y: Float,
        time: Long,
        pressure: Float,
        tilt: Float,
        track: Boolean,
    ) {
        if (time < lastTime) return
        point[0] = x
        point[1] = y
        screenToPage.mapPoints(point)
        if (point[0] == lastX && point[1] == lastY) return
        if (!point[0].isFinite() || !point[1].isFinite()) return
        runCatching {
            batch.add(
                type = InputToolType.STYLUS,
                x = point[0],
                y = point[1],
                elapsedTimeMillis = time - startTime,
                pressure = pressure.coerceIn(0f, 1f),
                tiltRadians = if (tilt.isFinite()) tilt.coerceIn(0f, HALF_PI) else StrokeInput.NO_TILT,
            )
        }.onSuccess {
            if (track) {
                lastTime = time
                lastX = point[0]
                lastY = point[1]
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!visibleStroke) return
        runCatching { renderer.draw(canvas, stroke, pageToScreen) }
    }

    private companion object {
        const val HALF_PI = (Math.PI / 2).toFloat()
    }
}
