package com.notesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.ink.authoring.ExperimentalLatencyDataApi
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
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
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** Deepest zoom the canvas allows, in screen pixels per page unit. */
const val MAX_CANVAS_SCALE = 8f

/**
 * Mesh fidelity for new strokes, in page units.
 *
 * Ink tessellates a stroke once, when it is created, and epsilon is what fixes
 * how finely - so it has to be chosen for the deepest zoom the canvas allows,
 * not for the zoom the stroke happened to be drawn at. Anything coarser and a
 * zoomed-in stroke shows its facets and its antialiasing band spreads over
 * several pixels, which reads as broken and blurry at the same time.
 *
 * Ink's guidance is 0.1 physical pixels; this is that, at maximum zoom.
 */
const val STROKE_EPSILON = 0.1f / MAX_CANVAS_SCALE

enum class Tool {
    PEN,
    HIGHLIGHTER,
    ERASER;

    fun brushFamily(): BrushFamily = if (this == HIGHLIGHTER) highlighter else pen

    companion object {
        private val pen by lazy { StockBrushes.marker() }
        private val highlighter by lazy { StockBrushes.highlighter() }

        /** Reverse lookup for reload: an erased stroke was never saved. */
        fun ofBrushFamily(family: BrushFamily): Tool =
            if (family == highlighter) HIGHLIGHTER else PEN
    }
}

/**
 * The note surface: pages stacked down a document, committed ink drawn beneath a
 * front-buffered wet-ink layer, one transform mapping document space to screen.
 *
 * Input rules, which are the whole point of the class:
 *  - S Pen draws. Always, and only.
 *  - Fingers scroll and zoom. Never draw, which is also what rejects a palm.
 *  - The S Pen barrel button erases while held, whatever tool is selected.
 *
 * Strokes live in page-local coordinates, so reordering or deleting a page never
 * has to touch the ink on it.
 */
@OptIn(ExperimentalLatencyDataApi::class)
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), InProgressStrokesFinishedListener {

    var tool: Tool = Tool.PEN
    var colorArgb: Int = 0xFF000000.toInt()
    var strokeWidth: Float = 5f

    /** Fired whenever committed ink changes, so the host can autosave. */
    var onStrokesChanged: (() -> Unit)? = null

    /** Fired when the page under the middle of the screen changes. */
    var onCurrentPageChanged: ((Int) -> Unit)? = null

    /** Fired when PDF text gets selected or cleared, so the host can offer actions. */
    var onSelectionChanged: ((PdfSelection?) -> Unit)? = null

    /** Kept from the P0 spike: the project's own latency instrument. */
    val latency = LatencyStats()

    var document: Document = Document(mutableListOf(Page()))
        private set

    private var pdf: PdfSource? = null

    private val wet = InProgressStrokesView(context)
    private val dry = DryLayer(context)
    private val predictor: MotionEventPredictor

    private val undoStack = mutableListOf<Edit>()
    private val redoStack = mutableListOf<Edit>()

    /** Document coordinates -> screen. Its inverse maps touches back. */
    private val documentToScreen = Matrix()
    private val screenToDocument = Matrix()
    private val strokeTransform = Matrix()
    private val matrixValues = FloatArray(9)

    private var activeStylusPointer: Int? = null
    private var activeStrokeId: InProgressStrokeId? = null
    private var activePage: Page? = null
    private var erasing = false
    private var lastErasePoint: FloatArray? = null
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var currentPage = 0
    private var fitted = false

    private var velocityTracker: VelocityTracker? = null
    private var flingVx = 0f
    private var flingVy = 0f
    private var flingLastNanos = 0L
    private var flinging = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var selection: PdfSelection? = null
    private var selectingPage = -1
    private var selectionAnchor: RectF? = null
    private var selectingText = false
    private var pressX = 0f
    private var pressY = 0f
    private val longPress = Runnable { beginTextSelection() }

    private sealed interface Edit {
        val page: Page

        class Drawn(override val page: Page, val stroke: Stroke) : Edit
        class Erased(override val page: Page, val strokes: List<Stroke>) : Edit
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
                documentToScreen.postScale(factor, factor, detector.focusX, detector.focusY)
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

    fun open(document: Document, pdf: PdfSource?) {
        this.pdf?.close()
        this.document = document
        document.invalidateLayout()
        this.pdf = pdf
        // Has to be the layer that draws the pages: invalidating this ViewGroup
        // leaves the child's cached display list alone, so nothing repaints.
        pdf?.onReady = { dry.postInvalidate() }
        undoStack.clear()
        redoStack.clear()
        fitted = false
        requestLayout()
        dry.invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted && w > 0) fitWidth()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pdf?.close()
        pdf = null
    }

    /** Scales the widest page to the view, which is where every note starts. */
    fun fitWidth() {
        if (width == 0) return
        fitted = true
        val scale = width * FIT_MARGIN / document.widestPage()
        documentToScreen.reset()
        documentToScreen.postScale(scale, scale)
        documentToScreen.postTranslate((width - document.widestPage() * scale) / 2f, 0f)
        onTransformChanged()
    }

    fun scrollToPage(index: Int) {
        if (index !in document.pages.indices) return
        val scale = currentScale()
        documentToScreen.getValues(matrixValues)
        matrixValues[Matrix.MTRANS_Y] = -document.topOf(index) * scale + PAGE_TOP_MARGIN_PX
        documentToScreen.setValues(matrixValues)
        onTransformChanged()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        val edit = undoStack.removeLastOrNull() ?: return
        applyInverse(edit)
        redoStack += edit
        afterEdit(edit.page)
    }

    fun redo() {
        val edit = redoStack.removeLastOrNull() ?: return
        when (edit) {
            is Edit.Drawn -> edit.page.strokes += edit.stroke
            is Edit.Erased -> edit.page.strokes.removeAll(edit.strokes)
        }
        undoStack += edit
        afterEdit(edit.page)
    }

    private fun applyInverse(edit: Edit) {
        when (edit) {
            is Edit.Drawn -> edit.page.strokes.remove(edit.stroke)
            is Edit.Erased -> edit.page.strokes += edit.strokes
        }
    }

    private fun afterEdit(vararg changed: Page) {
        for (page in changed) page.dirty = true
        dry.invalidate()
        onStrokesChanged?.invoke()
    }

    fun addPage(after: Int) {
        val template = document.pages.getOrNull(after)
        val page = Page(
            width = template?.width ?: Page.A4_WIDTH,
            height = template?.height ?: Page.A4_HEIGHT,
            // A new page is paper even in a PDF note; it is an insertion, not a
            // second copy of a PDF page.
            background = template?.background?.takeIf { it != PageBackground.PDF }
                ?: PageBackground.BLANK,
        )
        document.pages.add((after + 1).coerceIn(0, document.pages.size), page)
        document.invalidateLayout()
        afterEdit(page)
    }

    fun deletePage(index: Int) {
        if (document.pages.size <= 1 || index !in document.pages.indices) return
        val removed = document.pages.removeAt(index)
        // Undo cannot bring the page back, so drop any history that points at it
        // rather than leaving edits that would resurrect strokes onto nothing.
        undoStack.removeAll { it.page === removed }
        redoStack.removeAll { it.page === removed }
        document.invalidateLayout()
        afterEdit()
    }

    fun setBackground(index: Int, background: PageBackground) {
        val page = document.pages.getOrNull(index) ?: return
        if (page.background == PageBackground.PDF) return
        page.background = background
        afterEdit()
    }

    fun currentPageIndex(): Int = currentPage

    fun strokeCount(): Int = document.pages.sumOf { it.strokes.size }

    fun currentScale(): Float {
        documentToScreen.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun onTransformChanged() {
        clampTransform()
        documentToScreen.invert(screenToDocument)
        dry.invalidate()
        updateCurrentPage()
    }

    /** Keeps the document from being flung off into empty space. */
    private fun clampTransform() {
        if (width == 0 || height == 0) return
        val scale = currentScale()
        val docWidth = document.widestPage() * scale
        val docHeight = document.totalHeight() * scale
        documentToScreen.getValues(matrixValues)
        var x = matrixValues[Matrix.MTRANS_X]
        var y = matrixValues[Matrix.MTRANS_Y]

        x = if (docWidth <= width) {
            (width - docWidth) / 2f
        } else {
            x.coerceIn(width - docWidth, 0f)
        }
        y = if (docHeight <= height) {
            (height - docHeight) / 2f
        } else {
            // Half a screen of overscroll at each end, so the last page is
            // reachable without fighting the edge.
            y.coerceIn(height - docHeight - height / 2f, height / 2f)
        }

        matrixValues[Matrix.MTRANS_X] = x
        matrixValues[Matrix.MTRANS_Y] = y
        documentToScreen.setValues(matrixValues)
    }

    private fun updateCurrentPage() {
        val middle = floatArrayOf(width / 2f, height / 2f)
        screenToDocument.mapPoints(middle)
        var index = 0
        for (i in document.pages.indices) {
            if (middle[1] >= document.topOf(i)) index = i
        }
        if (index != currentPage) {
            currentPage = index
            onCurrentPageChanged?.invoke(index)
        }
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
                val index = pageUnder(event, event.actionIndex)
                if (index < 0) return true
                val pointerId = event.getPointerId(event.actionIndex)
                activeStylusPointer = pointerId
                activePage = document.pages[index]
                erasing = tool == Tool.ERASER || event.isEraserGesture()
                if (erasing) {
                    lastErasePoint = null
                    eraseAlong(event, event.actionIndex)
                } else {
                    activeStrokeId = wet.startStroke(
                        event = event,
                        pointerId = pointerId,
                        brush = currentBrush(),
                        // "World" here is the page, so the finished stroke comes
                        // back in page-local coordinates and stays with its page.
                        motionEventToWorldTransform = screenToPage(index),
                    )
                    // Writing always moves the pen straight away, so a pen that
                    // stays put is asking for the text underneath, not for ink.
                    // The stroke starts anyway and is cancelled if the hold wins,
                    // because waiting first would cost the latency this is about.
                    if (document.pages[index].background == PageBackground.PDF) {
                        pressX = event.x
                        pressY = event.y
                        selectingPage = index
                        postDelayed(longPress, LONG_PRESS_MS)
                    }
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
                if (selectingText) {
                    extendTextSelection(event, index)
                    return true
                }
                if (hypot(event.getX(index) - pressX, event.getY(index) - pressY) > touchSlop) {
                    removeCallbacks(longPress)
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
                if (selectingText) {
                    endStylus()
                    return true
                }
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
        removeCallbacks(longPress)
        selectingText = false
        activeStylusPointer = null
        activeStrokeId = null
        erasing = false
        lastErasePoint = null
    }

    private fun onFingers(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        trackVelocity(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                stopFling()
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
                    documentToScreen.postTranslate(focus[0] - lastFocusX, focus[1] - lastFocusY)
                    onTransformChanged()
                }
                lastFocusX = focus[0]
                lastFocusY = focus[1]
                return true
            }

            MotionEvent.ACTION_UP -> {
                startFling()
                releaseVelocity()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseVelocity()
                return true
            }
        }
        return true
    }

    // ---- inertia ------------------------------------------------------------

    private fun trackVelocity(event: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)
    }

    private fun releaseVelocity() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun startFling() {
        val tracker = velocityTracker ?: return
        // Pinching ends with the fingers moving in opposite directions, which
        // averages into a velocity that has nothing to do with a flick.
        if (scaleDetector.isInProgress) return
        tracker.computeCurrentVelocity(1000, MAX_FLING_VELOCITY)
        val vx = tracker.xVelocity
        val vy = tracker.yVelocity
        if (hypot(vx, vy) < MIN_FLING_VELOCITY) return
        flingVx = vx
        flingVy = vy
        flingLastNanos = System.nanoTime()
        if (!flinging) {
            flinging = true
            postOnAnimation(flingStep)
        }
    }

    private fun stopFling() {
        flinging = false
        removeCallbacks(flingStep)
    }

    private val flingStep = object : Runnable {
        override fun run() {
            if (!flinging) return
            val now = System.nanoTime()
            val dt = ((now - flingLastNanos) / 1e9f).coerceIn(0f, 0.05f)
            flingLastNanos = now

            documentToScreen.getValues(matrixValues)
            val beforeX = matrixValues[Matrix.MTRANS_X]
            val beforeY = matrixValues[Matrix.MTRANS_Y]
            documentToScreen.postTranslate(flingVx * dt, flingVy * dt)
            onTransformChanged()

            // Exponential decay rather than a fixed per-frame factor, so the
            // glide feels the same whether the display is at 60Hz or 120Hz.
            val decay = exp(-FLING_FRICTION * dt)
            flingVx *= decay
            flingVy *= decay

            documentToScreen.getValues(matrixValues)
            val movedX = abs(matrixValues[Matrix.MTRANS_X] - beforeX)
            val movedY = abs(matrixValues[Matrix.MTRANS_Y] - beforeY)
            // Clamping pins the document at the ends; carrying on would just
            // burn frames going nowhere.
            if (hypot(flingVx, flingVy) < MIN_FLING_VELOCITY || movedX + movedY < 0.05f) {
                stopFling()
                return
            }
            postOnAnimation(this)
        }
    }

    private fun leavingIndex(event: MotionEvent): Int =
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1

    private val focus = FloatArray(2)
    private val scratch = FloatArray(2)

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

    /** Index of the page under a pointer, or -1 in the gap between pages. */
    private fun pageUnder(event: MotionEvent, pointerIndex: Int): Int {
        scratch[0] = event.getX(pointerIndex)
        scratch[1] = event.getY(pointerIndex)
        screenToDocument.mapPoints(scratch)
        return document.pageAt(scratch[0], scratch[1])
    }

    private fun screenToPage(index: Int): Matrix {
        strokeTransform.set(screenToDocument)
        strokeTransform.postTranslate(-document.leftOf(index), -document.topOf(index))
        return strokeTransform
    }

    private fun currentBrush(): Brush {
        val highlighter = tool == Tool.HIGHLIGHTER
        return Brush.createWithColorIntArgb(
            family = tool.brushFamily(),
            colorIntArgb = if (highlighter) (colorArgb and 0x00FFFFFF) or HIGHLIGHT_ALPHA else colorArgb,
            // Sizes are page units, so a stroke keeps its size on the page and
            // zooming magnifies it like everything else on the paper.
            size = if (highlighter) strokeWidth * 4f else strokeWidth,
            epsilon = STROKE_EPSILON,
        )
    }

    // ---- PDF text selection -------------------------------------------------

    /**
     * The hold won: throw away the stroke it started and select the word under
     * the pen instead.
     */
    private fun beginTextSelection() {
        val index = selectingPage
        val source = pdf ?: return
        if (index !in document.pages.indices) return
        activeStrokeId?.let { wet.cancelStroke(it) }
        activeStrokeId = null
        selectingText = true

        val point = pageLocal(pressX, pressY, index)
        selectionAnchor = point
        val found = source.select(document.pages[index].pdfPageIndex, point, point) ?: return
        setSelection(found)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /** Dragging after the hold runs the selection from the anchor to the pen. */
    private fun extendTextSelection(event: MotionEvent, pointerIndex: Int) {
        val source = pdf ?: return
        val index = selectingPage
        val anchor = selectionAnchor ?: return
        if (index !in document.pages.indices) return
        val point = pageLocal(event.getX(pointerIndex), event.getY(pointerIndex), index)
        val found = source.select(document.pages[index].pdfPageIndex, anchor, point) ?: return
        setSelection(found)
    }

    private fun setSelection(found: PdfSelection?) {
        selection = found
        dry.invalidate()
        onSelectionChanged?.invoke(found)
    }

    fun clearSelection() {
        if (selection == null) return
        selectionAnchor = null
        setSelection(null)
    }

    /**
     * Turns the selected text boxes into real highlighter strokes, so a
     * highlight is ink on the page like any other and needs no new file format.
     */
    fun highlightSelection() {
        val found = selection ?: return
        val page = document.pages.getOrNull(selectingPage) ?: return
        val brush = Brush.createWithColorIntArgb(
            family = Tool.HIGHLIGHTER.brushFamily(),
            colorIntArgb = (colorArgb and 0x00FFFFFF) or HIGHLIGHT_ALPHA,
            size = 1f,
            epsilon = STROKE_EPSILON,
        )
        for (box in found.boxes) {
            if (box.width() <= 0f || box.height() <= 0f) continue
            val middle = box.centerY()
            val inputs = MutableStrokeInputBatch()
            // Two points is a stroke; the brush size covers the line height.
            inputs.add(InputToolType.STYLUS, box.left, middle, 0L)
            inputs.add(InputToolType.STYLUS, box.right, middle, 16L)
            val sized = brush.copy(size = box.height() * HIGHLIGHT_HEIGHT)
            val stroke = Stroke(sized, inputs.toImmutable())
            page.strokes += stroke
            undoStack += Edit.Drawn(page, stroke)
        }
        redoStack.clear()
        clearSelection()
        afterEdit(page)
    }

    fun selectedText(): String? = selection?.text

    /** Screen point -> page-local world units, as a degenerate rect. */
    private fun pageLocal(x: Float, y: Float, index: Int): RectF {
        val point = floatArrayOf(x, y)
        screenToPage(index).mapPoints(point)
        return RectF(point[0], point[1], point[0], point[1])
    }

    // ---- erasing ------------------------------------------------------------

    /** Erases along the segment travelled since the last event, not just at a point. */
    private fun eraseAlong(event: MotionEvent, pointerIndex: Int) {
        val page = activePage ?: return
        val index = document.pages.indexOf(page)
        if (index < 0) return
        val point = floatArrayOf(event.getX(pointerIndex), event.getY(pointerIndex))
        screenToPage(index).mapPoints(point)
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
        //
        // intersects() is a member extension on the Intersection object, so it
        // only resolves inside its scope.
        val hit = with(Intersection) {
            page.strokes.filter { segment.intersects(it.shape, IDENTITY) }
        }
        if (hit.isEmpty()) return
        page.strokes.removeAll(hit)
        undoStack += Edit.Erased(page, hit)
        redoStack.clear()
        afterEdit(page)
    }

    override fun onStrokesFinished(finished: Map<InProgressStrokeId, Stroke>) {
        val page = activePage
        if (page != null) {
            for (stroke in finished.values) {
                page.strokes += stroke
                undoStack += Edit.Drawn(page, stroke)
            }
            redoStack.clear()
        }
        wet.removeFinishedStrokes(finished.keys)
        if (page != null) afterEdit(page) else afterEdit()
    }

    /** Committed ink and paper. Wet ink keeps its own front buffer above this. */
    private inner class DryLayer(context: Context) : android.view.View(context) {
        private val renderer = ViewStrokeRenderer(CanvasStrokeRenderer.create(), this)
        private val viewport = FloatArray(4)
        private val pageRect = RectF()
        private val paper = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        private val shadow = Paint().apply { color = 0x22000000; isAntiAlias = true }
        private val rule = Paint().apply {
            color = 0xFFD8E2EC.toInt()
            strokeWidth = 2f
            isAntiAlias = true
        }
        private val bitmapPaint = Paint().apply {
            // Filtering matters (the bitmap is scaled); antialiasing does not,
            // since the destination is an axis-aligned rect, and asking for it
            // pushes the draw onto a slower path.
            isFilterBitmap = true
        }
        private val selectionPaint = Paint().apply {
            isAntiAlias = true
            color = 0x553B7DDD
        }
        private val crop = RectF()
        private val destination = RectF()

        // Strokes are immutable, so a bounding box is worth computing once.
        // Weak keys let an erased stroke's entry go with the stroke.
        private val boxes = java.util.WeakHashMap<Stroke, RectF>()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            visibleDocumentBounds(viewport)
            for (i in document.pages.indices) {
                val page = document.pages[i]
                val top = document.topOf(i)
                if (top > viewport[3] || top + page.height < viewport[1]) continue

                canvas.save()
                canvas.concat(documentToScreen)
                canvas.translate(document.leftOf(i), top)
                drawPaper(canvas, page, i)
                // The draw scope caches the canvas matrix when it is obtained, so
                // the page transform has to be in place before this call, not
                // applied inside it.
                // Page-level culling alone still redraws every stroke on a page
                // that is only half on screen. A zoomed-in page of dense notes is
                // exactly when frames are tightest.
                val cullLeft = viewport[0] - document.leftOf(i)
                val cullTop = viewport[1] - top
                val cullRight = viewport[2] - document.leftOf(i)
                val cullBottom = viewport[3] - top
                renderer.drawWithStrokes(canvas) { _, scope ->
                    for (stroke in page.strokes) {
                        val box = boundsOf(stroke) ?: continue
                        if (box.right < cullLeft || box.left > cullRight ||
                            box.bottom < cullTop || box.top > cullBottom
                        ) {
                            continue
                        }
                        scope.drawStroke(stroke)
                    }
                }
                if (i == selectingPage) {
                    selection?.boxes?.forEach { canvas.drawRect(it, selectionPaint) }
                }
                canvas.restore()
            }
        }

        private fun boundsOf(stroke: Stroke): RectF? = boxes.getOrPut(stroke) {
            val box = stroke.shape.computeBoundingBox() ?: return null
            RectF(box.xMin, box.yMin, box.xMax, box.yMax)
        }

        private fun drawPaper(canvas: Canvas, page: Page, index: Int) {
            pageRect.set(0f, 0f, page.width, page.height)
            // Two slivers down the right and along the bottom, rather than a
            // full-page rect the page then covers. That rect was a whole extra
            // screen of fill per page per frame, for four pixels of edge.
            canvas.drawRect(
                page.width, SHADOW, page.width + SHADOW, page.height + SHADOW, shadow,
            )
            canvas.drawRect(SHADOW, page.height, page.width, page.height + SHADOW, shadow)

            if (page.background == PageBackground.PDF) {
                // The rendered page is opaque and covers the paper exactly, so
                // filling underneath it is another wasted screen of fill. Only
                // fall back to blank paper when the bitmap is not ready yet.
                if (drawPdf(canvas, page, index)) return
                canvas.drawRect(pageRect, paper)
                return
            }
            canvas.drawRect(pageRect, paper)
            when (page.background) {
                PageBackground.BLANK -> Unit
                PageBackground.LINED -> {
                    var y = RULE_SPACING
                    while (y < page.height) {
                        canvas.drawLine(0f, y, page.width, y, rule)
                        y += RULE_SPACING
                    }
                }

                PageBackground.GRID -> {
                    var y = RULE_SPACING
                    while (y < page.height) {
                        canvas.drawLine(0f, y, page.width, y, rule)
                        y += RULE_SPACING
                    }
                    var x = RULE_SPACING
                    while (x < page.width) {
                        canvas.drawLine(x, 0f, x, page.height, rule)
                        x += RULE_SPACING
                    }
                }

                PageBackground.PDF -> Unit
            }
        }

        /** Returns true when the page was actually painted. */
        private fun drawPdf(canvas: Canvas, page: Page, index: Int): Boolean {
            val source = pdf ?: return false
            // Ask for roughly the pixels the page occupies right now, so a page
            // viewed small is not rendered at full size - and, once zoomed past
            // what a whole-page bitmap can carry, for a crop of just what is on
            // screen at screen resolution. That last part is what stops zoomed-in
            // text from going soft and stair-stepped.
            val wantPx = (page.width * currentScale()).toInt().coerceAtLeast(320)
            if (wantPx > DETAIL_THRESHOLD_PX) {
                visibleCropOf(page, index, crop)
                val detail = source.detail(
                    page.pdfPageIndex,
                    crop,
                    page.width,
                    page.height,
                    width,
                )
                if (detail != null) {
                    // The crop the renderer actually produced is padded, so it is
                    // asked for again here rather than assumed to match.
                    source.detailSource(page.pdfPageIndex)?.let { region ->
                        destination.set(region)
                        // A crop covers only part of the page, so the paper still
                        // has to be there underneath it.
                        canvas.drawRect(pageRect, paper)
                        canvas.drawBitmap(detail, null, destination, bitmapPaint)
                        return true
                    }
                }
            }
            val bitmap: Bitmap = source.bitmap(page.pdfPageIndex, wantPx) ?: return false
            canvas.drawBitmap(bitmap, null, pageRect, bitmapPaint)
            return true
        }

        /** The part of this page currently on screen, in page-local units. */
        private fun visibleCropOf(page: Page, index: Int, out: RectF) {
            val left = document.leftOf(index)
            val top = document.topOf(index)
            out.set(
                viewport[0] - left,
                viewport[1] - top,
                viewport[2] - left,
                viewport[3] - top,
            )
            if (out.left < 0f) out.left = 0f
            if (out.top < 0f) out.top = 0f
            if (out.right > page.width) out.right = page.width
            if (out.bottom > page.height) out.bottom = page.height
        }

        /** Screen rect mapped back into document space: [xMin, yMin, xMax, yMax]. */
        private fun visibleDocumentBounds(out: FloatArray) {
            out[0] = 0f
            out[1] = 0f
            out[2] = width.toFloat()
            out[3] = height.toFloat()
            screenToDocument.mapPoints(out)
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
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = MAX_CANVAS_SCALE
        const val FIT_MARGIN = 0.94f
        const val HIGHLIGHT_ALPHA = 0x66000000
        const val RULE_SPACING = 60f
        const val PAGE_TOP_MARGIN_PX = 24f
        const val LONG_PRESS_MS = 350L
        const val HIGHLIGHT_HEIGHT = 0.85f
        const val MIN_FLING_VELOCITY = 80f
        const val MAX_FLING_VELOCITY = 12000f
        /** Higher is stickier; this lands close to the platform list fling. */
        const val FLING_FRICTION = 3.2f
        const val DETAIL_THRESHOLD_PX = 2048
        const val SHADOW = 4f
        val IDENTITY = ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f)
    }
}

/** True while the barrel button is held, or the pen is flipped to its eraser end. */
private fun MotionEvent.isEraserGesture(): Boolean =
    getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER ||
        buttonState and
        (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
