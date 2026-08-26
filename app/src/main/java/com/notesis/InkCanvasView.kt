package com.notesis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.ExperimentalLatencyDataApi
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.authoring.latency.LatencyData
import androidx.ink.authoring.latency.LatencyDataCallback
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.rendering.android.view.ViewStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor

enum class Tool {
    PEN,
    HIGHLIGHTER,
    ERASER;

    fun brushFamily(): BrushFamily = if (this == HIGHLIGHTER) highlighter else pen

    companion object {
        private val pen by lazy { StockBrushes.pressurePen() }
        private val highlighter by lazy { StockBrushes.highlighter() }

        /** Reverse lookup for reload: an erased stroke was never saved. */
        fun ofBrushFamily(family: BrushFamily): Tool =
            if (family == highlighter) HIGHLIGHTER else PEN
    }
}

/**
 * The note surface: committed ink underneath, wet ink on a front buffer above,
 * one shared world-space transform for pan and zoom.
 *
 * Input rules, which are the whole point of the class:
 *  - S Pen draws. Always, and only.
 *  - Fingers pan and zoom. Never draw, which is also what rejects a palm.
 *  - The S Pen barrel button erases while held, whatever tool is selected.
 */
@OptIn(ExperimentalLatencyDataApi::class)
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), InProgressStrokesFinishedListener {

    var tool: Tool = Tool.PEN
    var colorArgb: Int = 0xFF1A1A1A.toInt()
    var strokeWidth: Float = 5f

    /** Fired whenever committed ink changes, so the host can autosave. */
    var onStrokesChanged: (() -> Unit)? = null

    /** Kept from the P0 spike: the project's own latency instrument. */
    val latency = LatencyStats()

    private val wet = InProgressStrokesView(context)
    private val dry = DryLayer(context)
    private val predictor: MotionEventPredictor

    private val strokes = mutableListOf<Stroke>()
    private val undoStack = mutableListOf<Edit>()

    /** World (document) coordinates -> screen. Its inverse maps touches back. */
    private val worldToScreen = Matrix()
    private val screenToWorld = Matrix()

    private var activeStylusPointer: Int? = null
    private var activeStrokeId: InProgressStrokeId? = null
    private var erasing = false
    private var lastErasePoint: FloatArray? = null
    private val matrixValues = FloatArray(9)
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private sealed interface Edit {
        class Drawn(val stroke: Stroke) : Edit
        class Erased(val strokes: List<Stroke>) : Edit
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                // Clamping the *result* rather than the factor, so a pinch that
                // would overshoot the limit still zooms up to it.
                val factor = (current * detector.scaleFactor)
                    .coerceIn(MIN_SCALE, MAX_SCALE) / current
                worldToScreen.postScale(factor, factor, detector.focusX, detector.focusY)
                onTransformChanged()
                return true
            }
        },
    )

    init {
        // A ViewGroup only gets onTouchEvent once no child has taken the event;
        // being clickable is what keeps the DOWN from being dropped outright.
        isClickable = true
        addView(dry, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wet.eagerInit()
        wet.addFinishedStrokesListener(this)
        wet.setLatencyDataCallback(object : LatencyDataCallback {
            // Off the UI thread, and the instance is pooled and reset once this
            // returns - read the fields, never keep the object.
            override fun onLatencyData(latencyData: LatencyData) {
                if (latencyData.eventAction != LatencyData.EventAction.MOVE) return
                if (!latencyData.isOsDetectsEventSet) return
                latency.add(latencyData.estimatedPixelPresentationTime - latencyData.osDetectsEvent)
            }
        })
        predictor = MotionEventPredictor.newInstance(this)
        onTransformChanged()
    }

    fun setStrokes(loaded: List<Stroke>) {
        strokes.clear()
        strokes += loaded
        undoStack.clear()
        dry.invalidate()
    }

    fun strokes(): List<Stroke> = strokes.toList()

    fun strokeCount(): Int = strokes.size

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun undo() {
        when (val edit = undoStack.removeLastOrNull() ?: return) {
            is Edit.Drawn -> strokes.remove(edit.stroke)
            is Edit.Erased -> strokes += edit.strokes
        }
        dry.invalidate()
        onStrokesChanged?.invoke()
    }

    fun resetZoom() {
        worldToScreen.reset()
        onTransformChanged()
    }

    fun currentScale(): Float {
        worldToScreen.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun onTransformChanged() {
        worldToScreen.invert(screenToWorld)
        dry.invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val stylus = event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS
        return if (stylus || activeStylusPointer != null) onStylus(event) else onFingers(event)
    }

    private fun onStylus(event: MotionEvent): Boolean {
        predictor.record(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeStylusPointer != null) return true
                // Unbuffered dispatch is what gets the S Pen's full sample rate
                // instead of one point per display frame.
                requestUnbufferedDispatch(event)
                val pointerId = event.getPointerId(event.actionIndex)
                activeStylusPointer = pointerId
                erasing = tool == Tool.ERASER || event.isEraserGesture()
                if (erasing) {
                    lastErasePoint = null
                    eraseAlong(event, event.actionIndex)
                } else {
                    activeStrokeId = wet.startStroke(
                        event = event,
                        pointerId = pointerId,
                        brush = currentBrush(),
                        motionEventToWorldTransform = screenToWorld,
                    )
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerId = activeStylusPointer ?: return false
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return false
                if (erasing) {
                    eraseAlong(event, index)
                    return true
                }
                val strokeId = activeStrokeId ?: return false
                latency.addSamples(1 + event.historySize)
                val predicted = predictor.predict()
                try {
                    wet.addToStroke(event, pointerId, strokeId, predicted)
                } finally {
                    predicted?.recycle()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = activeStylusPointer ?: return false
                activeStrokeId?.let { wet.finishStroke(event, pointerId, it) }
                endStylus()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activeStrokeId?.let { wet.cancelStroke(it, event) }
                endStylus()
                return true
            }
        }
        return false
    }

    private fun endStylus() {
        activeStylusPointer = null
        activeStrokeId = null
        erasing = false
        lastErasePoint = null
    }

    private fun onFingers(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                // The centroid jumps when a finger joins or leaves, so re-anchor
                // instead of translating by that jump.
                val focus = focusOf(event, skipPointerIndex = leavingIndex(event))
                lastFocusX = focus[0]
                lastFocusY = focus[1]
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val focus = focusOf(event, skipPointerIndex = -1)
                if (!scaleDetector.isInProgress || event.pointerCount > 1) {
                    worldToScreen.postTranslate(focus[0] - lastFocusX, focus[1] - lastFocusY)
                    onTransformChanged()
                }
                lastFocusX = focus[0]
                lastFocusY = focus[1]
                return true
            }
        }
        return true
    }

    private fun leavingIndex(event: MotionEvent): Int =
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1

    private val focus = FloatArray(2)

    private fun focusOf(event: MotionEvent, skipPointerIndex: Int): FloatArray {
        var x = 0f
        var y = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipPointerIndex) continue
            x += event.getX(i)
            y += event.getY(i)
            n++
        }
        focus[0] = if (n == 0) 0f else x / n
        focus[1] = if (n == 0) 0f else y / n
        return focus
    }

    private fun currentBrush(): Brush {
        val highlighter = tool == Tool.HIGHLIGHTER
        return Brush.createWithColorIntArgb(
            family = tool.brushFamily(),
            colorIntArgb = if (highlighter) (colorArgb and 0x00FFFFFF) or HIGHLIGHT_ALPHA else colorArgb,
            // Sizes are world units, so a stroke keeps its size in the document
            // and zooming magnifies it like everything else on the page.
            size = if (highlighter) strokeWidth * 4f else strokeWidth,
            epsilon = 0.1f,
        )
    }

    /** Erases along the segment travelled since the last event, not just at a point. */
    private fun eraseAlong(event: MotionEvent, pointerIndex: Int) {
        val point = floatArrayOf(event.getX(pointerIndex), event.getY(pointerIndex))
        screenToWorld.mapPoints(point)
        val previous = lastErasePoint
        lastErasePoint = point
        if (previous == null) return

        val segment = ImmutableSegment(
            ImmutableVec(previous[0], previous[1]),
            ImmutableVec(point[0], point[1]),
        )
        // ponytail: whole-stroke eraser. A partial (pixel) eraser means splitting
        // the input batch and rebuilding both halves - worth it only if the
        // whole-stroke behaviour actually gets complained about.
        // intersects() is a member extension on the Intersection object, so it
        // only resolves inside its scope.
        val hit = with(Intersection) {
            strokes.filter { segment.intersects(it.shape, IDENTITY) }
        }
        if (hit.isEmpty()) return
        strokes.removeAll(hit)
        undoStack += Edit.Erased(hit)
        dry.invalidate()
        onStrokesChanged?.invoke()
    }

    override fun onStrokesFinished(finished: Map<InProgressStrokeId, Stroke>) {
        for (stroke in finished.values) {
            strokes += stroke
            undoStack += Edit.Drawn(stroke)
        }
        wet.removeFinishedStrokes(finished.keys)
        dry.invalidate()
        onStrokesChanged?.invoke()
    }

    /** Committed ink. Separate view so wet ink keeps its own front buffer above it. */
    private inner class DryLayer(context: Context) : android.view.View(context) {
        private val renderer = ViewStrokeRenderer(CanvasStrokeRenderer.create(), this)
        private val viewport = FloatArray(4)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // The draw scope caches the canvas matrix when it is obtained, so the
            // world transform has to be applied *before* drawWithStrokes, not
            // inside it.
            canvas.save()
            canvas.concat(worldToScreen)
            visibleWorldBounds(viewport)
            renderer.drawWithStrokes(canvas) { _, scope ->
                for (stroke in strokes) {
                    val box = stroke.shape.computeBoundingBox() ?: continue
                    if (box.xMax < viewport[0] || box.xMin > viewport[2] ||
                        box.yMax < viewport[1] || box.yMin > viewport[3]
                    ) {
                        continue
                    }
                    scope.drawStroke(stroke)
                }
            }
            canvas.restore()
        }

        /** Screen rect mapped back into world space: [xMin, yMin, xMax, yMax]. */
        private fun visibleWorldBounds(out: FloatArray) {
            out[0] = 0f
            out[1] = 0f
            out[2] = width.toFloat()
            out[3] = height.toFloat()
            screenToWorld.mapPoints(out)
            val xMin = minOf(out[0], out[2])
            val xMax = maxOf(out[0], out[2])
            val yMin = minOf(out[1], out[3])
            val yMax = maxOf(out[1], out[3])
            out[0] = xMin
            out[1] = yMin
            out[2] = xMax
            out[3] = yMax
        }
    }

    private companion object {
        const val MIN_SCALE = 0.2f
        const val MAX_SCALE = 8f
        const val HIGHLIGHT_ALPHA = 0x66000000
        val IDENTITY = ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f)
    }
}

/** True while the barrel button is held, or the pen is flipped to its eraser end. */
private fun MotionEvent.isEraserGesture(): Boolean =
    getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER ||
        buttonState and
        (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
