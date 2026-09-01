package com.notesis

import android.os.Build
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
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
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.Intersection
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.rendering.android.view.ViewStrokeRenderer
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.atan2

/** Deepest zoom the canvas allows, in screen pixels per page unit. */
const val MAX_CANVAS_SCALE = 8f

/** Ink's recommended mesh fidelity, in physical pixels. */
const val TESSELLATION_TARGET_PX = 0.1f

/**
 * Mesh fidelity for a stroke about to be shown at [scale] screen pixels per page
 * unit, expressed in page units.
 *
 * Ink bakes a stroke's outline - antialiasing band included - when the stroke is
 * created, so magnifying the mesh magnifies the softness with it. Pinning
 * epsilon at the deepest zoom would keep every stroke sharp, but it also makes
 * every stroke carry that vertex count forever, which costs memory on a long
 * note and time on every stroke drawn. Choosing it from the zoom in use, and
 * rebuilding when the zoom moves, gets the same picture for what the picture
 * actually needs.
 */
fun epsilonFor(scale: Float): Float =
    TESSELLATION_TARGET_PX / scale.coerceIn(1f, MAX_CANVAS_SCALE)

/**
 * Powers of two, so a pinch settles onto one of a handful of fidelities instead
 * of asking for a slightly different mesh at every zoom level along the way.
 */
fun tessellationBucket(scale: Float): Float {
    var bucket = 1f
    while (bucket < scale && bucket < MAX_CANVAS_SCALE) bucket *= 2f
    return bucket.coerceAtMost(MAX_CANVAS_SCALE)
}

/** Fidelity for a stroke that has no zoom context yet, such as one just loaded. */
const val STROKE_EPSILON = TESSELLATION_TARGET_PX

/** The shapes the pen can be made to draw instead of following the hand. */
enum class ShapeKind { LINE, ARROW, RECT, OVAL }

enum class Tool {
    PEN,
    HIGHLIGHTER,
    ERASER;

    fun brushFamily(): BrushFamily = if (this == HIGHLIGHTER) highlighter else pen

    companion object {
        private val pen by lazy { StockBrushes.marker() }

        /**
         * The stock highlighter has a chisel tip, which draws a slanted flat end.
         * Only the tip is replaced with a circle - the rest of the family is what
         * keeps overlapping passes from stacking up into a darker blob.
         */
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val highlighter by lazy {
            val stock = StockBrushes.highlighter()
            val coat = stock.coats.first()
            stock.copy(
                coat = coat.copy(
                    tip = coat.tip.copy(
                        scaleX = 1f,
                        scaleY = 1f,
                        cornerRounding = 1f,
                        slantDegrees = 0f,
                        pinch = 0f,
                        rotationDegrees = 0f,
                    ),
                ),
            )
        }

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

    /** Diameter of the eraser tip, in page units, like [strokeWidth]. */
    var eraserWidth: Float = 24f

    /**
     * Reading rather than writing: the pen selects PDF text by dragging, the
     * way a mouse does, instead of waiting out a hold first. The hold exists so
     * that writing stays the default; when reading is the declared intent there
     * is nothing to disambiguate and nothing to wait for.
     */
    var readMode: Boolean = false

    /** Non-null while the pen draws shapes rather than following the hand. */
    var shapeKind: ShapeKind? = null

    /** Pictures can be picked up, moved and resized while this is on. */
    var imageMode: Boolean = false

    /** The pen drags out a rectangle and what is inside it comes back rendered. */
    var captureMode: Boolean = false

    /**
     * Masking tape. The pen writes exactly as it always does; the stroke it
     * leaves is opaque and goes over the ink instead of into it. Lifting a strip
     * to look under it is a tap in reading mode, which leaves writing over tape
     * possible and makes the study gesture the one already used for reading.
     */
    var maskMode: Boolean = false

    /** Reads one page's tape. Same contract as [pageLoader]. */
    var maskLoader: ((Page, Float) -> List<PageMask>)? = null

    /**
     * The lasso: draw a loop and what is inside it comes up as a selection that
     * can be dragged somewhere else or thrown away. A second loop drawn outside
     * the selection replaces it, which is how a lasso is expected to behave.
     */
    var lassoMode: Boolean = false

    /** Fired with how many strokes the loop caught, zero when it is let go. */
    var onLassoSelected: ((Int) -> Unit)? = null

    /** Reads a picture's bytes. Called on the UI thread, so it should cache. */
    var imageLoader: ((String) -> Bitmap?)? = null

    /** Handed a rendering of the captured region, on a background thread. */
    var onCaptured: ((Bitmap) -> Unit)? = null

    /** Fired when a picture is picked up or let go, so the host can offer actions. */
    var onImageSelected: ((Boolean) -> Unit)? = null

    /** Fired whenever committed ink changes, so the host can autosave. */
    var onStrokesChanged: (() -> Unit)? = null

    /** Fired when the page under the middle of the screen changes. */
    var onCurrentPageChanged: ((Int) -> Unit)? = null

    /** Fired when PDF text gets selected or cleared, so the host can offer actions. */
    var onSelectionChanged: ((PdfSelection?) -> Unit)? = null

    /**
     * Reads one page's strokes off disk at the given mesh fidelity. Called on a
     * background thread, only for pages that have come on screen.
     */
    var pageLoader: ((Page, Float) -> List<Stroke>)? = null

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

    // Shape, capture and picture gestures. Each is a start and an end in the
    // coordinates of one page, held only while the pen is down.
    private var shapePage = -1
    private val shapeStart = floatArrayOf(0f, 0f)
    private val shapeEnd = floatArrayOf(0f, 0f)
    private var drawingShape = false

    private var capturePage = -1
    private val captureRect = RectF()
    private var capturing = false

    /** Whether the stroke now being drawn is tape rather than ink. */
    private var strokeIsMask = false

    private var lassoPage = -1
    private val lassoPath = mutableListOf<Float>()
    private var drawingLasso = false
    /** Identity, not equality: two strokes can be equal and still be two strokes. */
    private val lassoStrokes: MutableSet<Stroke> =
        Collections.newSetFromMap(IdentityHashMap())
    private val lassoBounds = RectF()
    private var movingLasso = false
    private val lassoGrab = floatArrayOf(0f, 0f)
    private var lassoDx = 0f
    private var lassoDy = 0f

    private var selectedImage: PageImage? = null
    private var selectedImagePage: Page? = null
    private var movingImage = false
    private var resizingImage = false
    private val imageGrab = floatArrayOf(0f, 0f)
    private val maskProbe = floatArrayOf(0f, 0f)
    private var pressX = 0f
    private var pressY = 0f
    private val longPress = Runnable { beginTextSelection() }

    /**
     * Ticks once per display frame while a stroke is being drawn, so the HUD
     * reports the refresh rate the panel is actually running at rather than the
     * one it is capable of. The two differ, and that difference is the whole
     * reason prediction misjudges its distance on a 60Hz frame.
     */
    private var lastFrameNanos = 0L
    private var frameClockRunning = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (activeStylusPointer == null) {
                // A pen down within a frame of the last pen up would otherwise
                // start a second loop and halve every delta it measures.
                frameClockRunning = false
                lastFrameNanos = 0L
                return
            }
            if (lastFrameNanos != 0L) latency.addFrame(frameTimeNanos - lastFrameNanos)
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Mesh generation is native work; keeping it off the UI thread keeps frames. */
    private val refiner = Executors.newSingleThreadExecutor()
    private val refineRunnable = Runnable { refineVisiblePages() }

    private sealed interface Edit {
        val page: Page

        // Rebuilding a page's geometry replaces every Stroke instance on it, so
        // history has to be able to follow the swap rather than keep pointing at
        // objects that are no longer in the page.
        class Drawn(override val page: Page, var stroke: Stroke) : Edit
        class Erased(override val page: Page, var strokes: List<Stroke>) : Edit

        // Pictures are mutable and moving one is not undoable; adding and
        // removing are, because those are the ones that lose work.
        class ImageAdded(override val page: Page, val image: PageImage) : Edit
        class ImageRemoved(override val page: Page, val image: PageImage, val at: Int) : Edit

        /** A lasso drag: the strokes as they were, and where they ended up. */
        class Moved(
            override val page: Page,
            var before: List<Stroke>,
            var after: List<Stroke>,
        ) : Edit

        class MaskAdded(override val page: Page, val mask: PageMask) : Edit
        class MaskRemoved(override val page: Page, val mask: PageMask, val at: Int) : Edit
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
        // On an adaptive panel the system picks a refresh rate per window from
        // what the content looks like, and a note that is mostly still reads as
        // content that does not need 120Hz. Say otherwise: this view is only
        // still until a pen touches it. Costs nothing while nothing is drawn -
        // the hint applies to frames actually produced.
        if (Build.VERSION.SDK_INT >= 35) {
            requestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
        onTransformChanged()
    }

    fun open(document: Document, pdf: PdfSource?) {
        this.pdf?.close()
        this.document = document
        document.invalidateLayout()
        for (page in document.pages) page.tessellatedFor = 0f
        scheduleRefine()
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
        removeCallbacks(refineRunnable)
        refiner.shutdown()
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
        // A lasso selection describes where strokes are now. Stepping history
        // moves them, so the box would sit over nothing and drag ghosts.
        clearLassoSelection()
        applyInverse(edit)
        redoStack += edit
        afterEdit(edit.page)
    }

    fun redo() {
        val edit = redoStack.removeLastOrNull() ?: return
        clearLassoSelection()
        when (edit) {
            is Edit.Drawn -> edit.page.strokes += edit.stroke
            is Edit.Erased -> edit.page.strokes.removeAll(edit.strokes)
            is Edit.ImageAdded -> edit.page.images += edit.image
            is Edit.ImageRemoved -> edit.page.images.remove(edit.image)
            is Edit.MaskAdded -> edit.page.masks += edit.mask
            is Edit.MaskRemoved -> edit.page.masks.remove(edit.mask)
            is Edit.Moved -> swapStrokes(edit.page, edit.before, edit.after)
        }
        undoStack += edit
        afterEdit(edit.page)
    }

    private fun applyInverse(edit: Edit) {
        when (edit) {
            is Edit.Drawn -> edit.page.strokes.remove(edit.stroke)
            is Edit.Erased -> edit.page.strokes += edit.strokes
            is Edit.Moved -> swapStrokes(edit.page, edit.after, edit.before)
            is Edit.MaskAdded -> edit.page.masks.remove(edit.mask)
            is Edit.MaskRemoved ->
                edit.page.masks.add(edit.at.coerceIn(0, edit.page.masks.size), edit.mask)
            is Edit.ImageAdded -> edit.page.images.remove(edit.image)
            is Edit.ImageRemoved ->
                edit.page.images.add(edit.at.coerceIn(0, edit.page.images.size), edit.image)
        }
    }

    private fun afterEdit(vararg changed: Page) {
        for (page in changed) page.dirty = true
        dry.invalidate()
        onStrokesChanged?.invoke()
        // An undone erase puts back strokes built at whatever zoom they were
        // erased at, so history changes need a refine too.
        scheduleRefine()
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
        // Only once the zoom settles - rebuilding on every pinch frame would
        // cost far more than it buys.
        scheduleRefine()
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
                if (!frameClockRunning) {
                    frameClockRunning = true
                    lastFrameNanos = 0L
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }
                activePage = document.pages[index]
                if (captureMode && !event.isEraserGesture()) {
                    capturePage = index
                    capturing = true
                    pageLocalInto(event.x, event.y, index, shapeStart)
                    captureRect.set(shapeStart[0], shapeStart[1], shapeStart[0], shapeStart[1])
                    dry.invalidate()
                    return true
                }
                if (lassoMode && !event.isEraserGesture()) {
                    beginLasso(event, index)
                    return true
                }
                if (imageMode && !event.isEraserGesture()) {
                    beginImageGesture(event, index)
                    return true
                }
                val shape = shapeKind
                if (shape != null && !readMode && !event.isEraserGesture() &&
                    tool != Tool.ERASER
                ) {
                    shapePage = index
                    drawingShape = true
                    pageLocalInto(event.x, event.y, index, shapeStart)
                    shapeEnd[0] = shapeStart[0]
                    shapeEnd[1] = shapeStart[1]
                    dry.invalidate()
                    return true
                }
                if (readMode && !event.isEraserGesture()) {
                    // Reading is where a covered answer gets looked at.
                    if (toggleMaskAt(event.x, event.y, index)) return true
                    pressX = event.x
                    pressY = event.y
                    selectingPage = index
                    if (document.pages[index].background == PageBackground.PDF) {
                        beginTextSelection()
                    }
                    return true
                }
                strokeIsMask = maskMode
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
                if (capturing) {
                    pageLocalInto(event.getX(index), event.getY(index), capturePage, shapeEnd)
                    captureRect.set(
                        min(shapeStart[0], shapeEnd[0]),
                        min(shapeStart[1], shapeEnd[1]),
                        max(shapeStart[0], shapeEnd[0]),
                        max(shapeStart[1], shapeEnd[1]),
                    )
                    dry.invalidate()
                    return true
                }
                if (movingLasso) {
                    pageLocalInto(event.getX(index), event.getY(index), lassoPage, shapeEnd)
                    lassoDx = shapeEnd[0] - lassoGrab[0]
                    lassoDy = shapeEnd[1] - lassoGrab[1]
                    dry.invalidate()
                    return true
                }
                if (drawingLasso) {
                    pageLocalInto(event.getX(index), event.getY(index), lassoPage, shapeEnd)
                    lassoPath += shapeEnd[0]
                    lassoPath += shapeEnd[1]
                    dry.invalidate()
                    return true
                }
                if (drawingShape) {
                    pageLocalInto(event.getX(index), event.getY(index), shapePage, shapeEnd)
                    dry.invalidate()
                    return true
                }
                if (movingImage || resizingImage) {
                    dragImage(event, index)
                    return true
                }
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
                    if (predicted != null) {
                        // Ground truth for the tip's steadiness: the distance
                        // the prediction reaches past the newest real sample.
                        latency.addPredictionLead(
                            (predicted.eventTimeNanos - event.eventTimeNanos) / 1e6,
                        )
                    }
                    wet.addToStroke(event, pointerId, strokeId, predicted)
                } finally {
                    predicted?.recycle()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = activeStylusPointer ?: return false
                if (capturing) {
                    finishCapture()
                    endStylus()
                    return true
                }
                if (movingLasso) {
                    finishLassoMove()
                    endStylus()
                    return true
                }
                if (drawingLasso) {
                    finishLasso()
                    endStylus()
                    return true
                }
                if (drawingShape) {
                    finishShape()
                    endStylus()
                    return true
                }
                if (movingImage || resizingImage) {
                    movingImage = false
                    resizingImage = false
                    selectedImagePage?.let { afterEdit(it) }
                    endStylus()
                    return true
                }
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
        return Brush.createWithColorIntArgb(
            family = tool.brushFamily(),
            // Alpha included: a highlighter is a saved pen that happens to be
            // translucent and wide, not a tool with its own hidden rules.
            colorIntArgb = colorArgb,
            // Sizes are page units, so a stroke keeps its size on the page and
            // zooming magnifies it like everything else on the paper.
            size = strokeWidth,
            // Drawn at the zoom in use, so a stroke made while zoomed in is
            // already fine enough and never needs rebuilding.
            epsilon = epsilonFor(currentScale()),
        )
    }

    // ---- mesh refinement -----------------------------------------------------

    /**
     * Rebuilds the geometry of the pages on screen for the zoom now in use.
     *
     * The strokes themselves are stored as vectors - the raw pen inputs - so the
     * outline can be generated again at any fidelity. That is the whole reason a
     * zoomed-in stroke can be made crisp instead of magnified soft.
     */
    private fun refineVisiblePages() {
        // Never while a stroke is being drawn: the page list would be swapped
        // out from under the stroke that is about to land on it.
        if (activeStylusPointer != null) return
        val target = tessellationBucket(currentScale())
        val epsilon = epsilonFor(target)

        val toLoad = visiblePages().filter { !it.loaded }
        val toRebuild = visiblePages().filter {
            it.loaded && it.tessellatedFor != target && it.strokes.isNotEmpty()
        }
        if (toLoad.isEmpty() && toRebuild.isEmpty()) return

        // Snapshot what is being rebuilt, so a stroke drawn or erased meanwhile
        // is detected at swap time instead of being silently thrown away.
        val work = toRebuild.map { page -> page to page.strokes.toList() }
        val loader = pageLoader
        refiner.execute {
            val masksLoader = maskLoader
            val loaded = toLoad.mapNotNull { page ->
                loader?.let { page to it(page, epsilon) }
            }
            val loadedMasks = toLoad.mapNotNull { page ->
                masksLoader?.let { page to it(page, epsilon) }
            }
            val built = work.map { (page, snapshot) ->
                Triple(
                    page,
                    snapshot,
                    snapshot.map { Stroke(it.brush.copy(epsilon = epsilon), it.inputs) },
                )
            }
            post {
                if (tessellationBucket(currentScale()) != target) return@post
                for ((page, strokes) in loaded) {
                    if (page.loaded) continue
                    // A stroke can be drawn on a page while its own strokes are
                    // still being read. The ones from disk are older, so they go
                    // underneath - and the dirty flag is left alone, because
                    // clearing it here would throw that new stroke away at the
                    // next save.
                    page.strokes.addAll(0, strokes)
                    loadedMasks.firstOrNull { it.first === page }?.let { (_, masks) ->
                        page.masks.addAll(0, masks)
                    }
                    page.loaded = true
                    page.tessellatedFor = target
                }
                for ((page, snapshot, rebuilt) in built) {
                    if (!sameStrokes(page.strokes, snapshot)) continue
                    val replacements = IdentityHashMap<Stroke, Stroke>()
                    for (i in snapshot.indices) replacements[snapshot[i]] = rebuilt[i]
                    page.strokes.clear()
                    page.strokes += rebuilt
                    page.tessellatedFor = target
                    remapHistory(replacements)
                    // Nothing about the saved file changed: same inputs, same
                    // brush, only the generated outline. Do not dirty the page.
                }
                if (loaded.isNotEmpty()) onStrokesChanged?.invoke()
                dry.invalidate()
            }
        }
    }

    private fun sameStrokes(current: List<Stroke>, snapshot: List<Stroke>): Boolean {
        if (current.size != snapshot.size) return false
        for (i in current.indices) if (current[i] !== snapshot[i]) return false
        return true
    }

    private fun remapHistory(replacements: IdentityHashMap<Stroke, Stroke>) {
        for (edit in undoStack + redoStack) {
            when (edit) {
                is Edit.Drawn -> replacements[edit.stroke]?.let { edit.stroke = it }
                is Edit.Erased ->
                    edit.strokes = edit.strokes.map { replacements[it] ?: it }
                // Pictures are not rebuilt when a page is re-tessellated.
                is Edit.ImageAdded, is Edit.ImageRemoved -> Unit
                is Edit.MaskAdded, is Edit.MaskRemoved -> Unit
                is Edit.Moved -> {
                    edit.before = edit.before.map { replacements[it] ?: it }
                    edit.after = edit.after.map { replacements[it] ?: it }
                }
            }
        }
    }

    private fun visiblePages(): List<Page> {
        if (width == 0 || height == 0) return emptyList()
        val bounds = floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
        screenToDocument.mapPoints(bounds)
        val top = minOf(bounds[1], bounds[3])
        val bottom = maxOf(bounds[1], bounds[3])
        return document.pages.filterIndexed { i, page ->
            val pageTop = document.topOf(i)
            pageTop <= bottom && pageTop + page.height >= top
        }
    }

    private fun scheduleRefine() {
        removeCallbacks(refineRunnable)
        // A page with nothing on it yet should not wait out the settle delay -
        // that delay exists to avoid rebuilding mid-pinch, not to hold up the
        // first paint of a note.
        val delay = if (visiblePages().any { !it.loaded }) 0L else REFINE_DEBOUNCE_MS
        postDelayed(refineRunnable, delay)
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
            epsilon = epsilonFor(currentScale()),
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

    // ---- shapes, pictures and capture ---------------------------------------

    /** A screen point in the coordinates of one page. */
    private fun pageLocalInto(x: Float, y: Float, index: Int, out: FloatArray) {
        out[0] = x
        out[1] = y
        screenToPage(index).mapPoints(out)
    }

    /**
     * Turns the dragged-out shape into a real stroke with the current brush, so
     * a drawn rectangle is ink like any other and erases, saves and zooms the
     * same way. Nothing new has to know what a shape is.
     */
    private fun finishShape() {
        val kind = shapeKind
        val page = document.pages.getOrNull(shapePage)
        drawingShape = false
        if (kind == null || page == null) {
            dry.invalidate()
            return
        }
        val points = shapePoints(kind, shapeStart, shapeEnd)
        if (points.size < 2) {
            dry.invalidate()
            return
        }
        val inputs = MutableStrokeInputBatch()
        for ((i, point) in points.withIndex()) {
            inputs.add(InputToolType.STYLUS, point[0], point[1], i * SHAPE_STEP_MS)
        }
        val stroke = Stroke(currentBrush(), inputs.toImmutable())
        page.strokes += stroke
        undoStack += Edit.Drawn(page, stroke)
        redoStack.clear()
        afterEdit(page)
    }

    /** The outline of [kind], sampled densely enough that the brush follows it. */
    private fun shapePoints(
        kind: ShapeKind,
        from: FloatArray,
        to: FloatArray,
    ): List<FloatArray> {
        val x0 = from[0]
        val y0 = from[1]
        val x1 = to[0]
        val y1 = to[1]
        return when (kind) {
            ShapeKind.LINE -> listOf(floatArrayOf(x0, y0), floatArrayOf(x1, y1))

            ShapeKind.ARROW -> {
                val angle = atan2(y1 - y0, x1 - x0)
                // Barbs sized off the shaft, so a short arrow is not all head.
                val barb = (hypot(x1 - x0, y1 - y0) * 0.22f).coerceIn(12f, 90f)
                val left = angle + ARROW_SPREAD
                val right = angle - ARROW_SPREAD
                listOf(
                    floatArrayOf(x0, y0),
                    floatArrayOf(x1, y1),
                    floatArrayOf(x1 - barb * cos(left), y1 - barb * sin(left)),
                    floatArrayOf(x1, y1),
                    floatArrayOf(x1 - barb * cos(right), y1 - barb * sin(right)),
                )
            }

            ShapeKind.RECT -> listOf(
                floatArrayOf(x0, y0),
                floatArrayOf(x1, y0),
                floatArrayOf(x1, y1),
                floatArrayOf(x0, y1),
                floatArrayOf(x0, y0),
            )

            ShapeKind.OVAL -> {
                val cx = (x0 + x1) / 2f
                val cy = (y0 + y1) / 2f
                val rx = abs(x1 - x0) / 2f
                val ry = abs(y1 - y0) / 2f
                (0..OVAL_STEPS).map {
                    val t = it * 2.0 * Math.PI / OVAL_STEPS
                    floatArrayOf(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat())
                }
            }
        }
    }

    // ---- lasso --------------------------------------------------------------

    private fun beginLasso(event: MotionEvent, index: Int) {
        pageLocalInto(event.x, event.y, index, shapeStart)
        // Inside an existing selection the gesture is a drag, not a new loop.
        if (lassoStrokes.isNotEmpty() && index == lassoPage &&
            lassoBounds.contains(shapeStart[0], shapeStart[1])
        ) {
            movingLasso = true
            lassoGrab[0] = shapeStart[0]
            lassoGrab[1] = shapeStart[1]
            lassoDx = 0f
            lassoDy = 0f
            return
        }
        clearLassoSelection()
        lassoPage = index
        drawingLasso = true
        lassoPath.clear()
        lassoPath += shapeStart[0]
        lassoPath += shapeStart[1]
        dry.invalidate()
    }

    private fun finishLasso() {
        drawingLasso = false
        val index = lassoPage
        if (index < 0 || index >= document.pages.size || lassoPath.size < LASSO_MIN_POINTS) {
            lassoPath.clear()
            dry.invalidate()
            return
        }
        val page = document.pages[index]
        lassoStrokes.clear()
        lassoBounds.setEmpty()
        for (stroke in page.strokes) {
            val box = stroke.shape.computeBoundingBox() ?: continue
            // The centre decides. Requiring every corner inside makes a lasso
            // that is hard to satisfy; the centre is what people aim at.
            if (!insidePolygon(lassoPath,(box.xMin + box.xMax) / 2f, (box.yMin + box.yMax) / 2f)) continue
            lassoStrokes += stroke
            if (lassoBounds.isEmpty) {
                lassoBounds.set(box.xMin, box.yMin, box.xMax, box.yMax)
            } else {
                lassoBounds.union(box.xMin, box.yMin, box.xMax, box.yMax)
            }
        }
        lassoPath.clear()
        dry.invalidate()
        onLassoSelected?.invoke(lassoStrokes.size)
    }

    private fun finishLassoMove() {
        movingLasso = false
        val page = document.pages.getOrNull(lassoPage) ?: return
        val dx = lassoDx
        val dy = lassoDy
        lassoDx = 0f
        lassoDy = 0f
        if (dx == 0f && dy == 0f) {
            dry.invalidate()
            return
        }
        val before = lassoStrokes.toList()
        val after = before.map { translate(it, dx, dy) }
        swapStrokes(page, before, after)
        undoStack += Edit.Moved(page, before, after)
        redoStack.clear()
        lassoStrokes.clear()
        lassoStrokes += after
        lassoBounds.offset(dx, dy)
        afterEdit(page)
    }

    /**
     * A stroke is its inputs, so moving one means saying them again somewhere
     * else. Everything else about each sample is carried across untouched.
     */
    private fun translate(stroke: Stroke, dx: Float, dy: Float): Stroke {
        val inputs = MutableStrokeInputBatch()
        val scratch = StrokeInput()
        for (i in 0 until stroke.inputs.size) {
            val input = stroke.inputs.populate(i, scratch)
            inputs.add(
                type = input.toolType,
                x = input.x + dx,
                y = input.y + dy,
                elapsedTimeMillis = input.elapsedTimeMillis,
                pressure = input.pressure,
                tiltRadians = input.tiltRadians,
                orientationRadians = input.orientationRadians,
            )
        }
        return Stroke(stroke.brush, inputs.toImmutable())
    }

    private fun swapStrokes(page: Page, from: List<Stroke>, to: List<Stroke>) {
        for (i in from.indices) {
            val at = page.strokes.indexOfFirst { it === from[i] }
            if (at >= 0) page.strokes[at] = to[i] else page.strokes += to[i]
        }
    }

    /** Throws away what the loop caught. */
    fun deleteLassoSelection() {
        val page = document.pages.getOrNull(lassoPage) ?: return
        if (lassoStrokes.isEmpty()) return
        val gone = lassoStrokes.toList()
        page.strokes.removeAll { stroke -> gone.any { it === stroke } }
        undoStack += Edit.Erased(page, gone)
        redoStack.clear()
        clearLassoSelection()
        afterEdit(page)
    }

    fun clearLassoSelection() {
        val had = lassoStrokes.isNotEmpty()
        lassoStrokes.clear()
        lassoBounds.setEmpty()
        lassoDx = 0f
        lassoDy = 0f
        dry.invalidate()
        if (had) onLassoSelected?.invoke(0)
    }

    /** Lifts or lowers the strip under the finger. True when there was one. */
    private fun toggleMaskAt(screenX: Float, screenY: Float, index: Int): Boolean {
        val page = document.pages[index]
        if (page.masks.isEmpty()) return false
        pageLocalInto(screenX, screenY, index, maskProbe)
        // The tape is a stroke, so what counts as a hit is the same question the
        // eraser asks: does this little box touch the shape.
        val tip = ImmutableBox.fromCenterAndDimensions(
            ImmutableVec(maskProbe[0], maskProbe[1]),
            MASK_TAP_PX / currentScale(),
            MASK_TAP_PX / currentScale(),
        )
        val mask = with(Intersection) {
            page.masks.lastOrNull { tip.intersects(it.stroke.shape, IDENTITY) }
        } ?: return false
        mask.revealed = !mask.revealed
        dry.invalidate()
        return true
    }

    /** Puts every strip on one page up or down at once, for the page list. */
    fun setMasksRevealed(pageIndex: Int, revealed: Boolean) {
        val page = document.pages.getOrNull(pageIndex) ?: return
        for (mask in page.masks) mask.revealed = revealed
        dry.invalidate()
    }

    /** Peels every strip off one page. Undoable, one strip at a time. */
    fun clearMasks(pageIndex: Int) {
        val page = document.pages.getOrNull(pageIndex) ?: return
        if (page.masks.isEmpty()) return
        // Backwards, so each recorded index is still the one to put it back at.
        for (at in page.masks.indices.reversed()) {
            undoStack += Edit.MaskRemoved(page, page.masks[at], at)
        }
        page.masks.clear()
        redoStack.clear()
        afterEdit(page)
    }

    /** Places a picture in the middle of the page now on screen. */
    fun insertImage(imageId: String, aspect: Float) {
        val index = currentPage.coerceIn(0, document.pages.size - 1)
        val page = document.pages[index]
        val width = page.width * IMAGE_INSERT_FRACTION
        val height = if (aspect > 0f) width / aspect else width
        val image = PageImage(
            id = imageId,
            x = (page.width - width) / 2f,
            y = (page.height - height) / 2f,
            width = width,
            height = height,
        )
        page.images += image
        undoStack += Edit.ImageAdded(page, image)
        redoStack.clear()
        select(image, page)
        afterEdit(page)
    }

    fun deleteSelectedImage() {
        val image = selectedImage ?: return
        val page = selectedImagePage ?: return
        val at = page.images.indexOf(image)
        if (at < 0) return
        page.images.removeAt(at)
        undoStack += Edit.ImageRemoved(page, image, at)
        redoStack.clear()
        select(null, null)
        afterEdit(page)
    }

    fun clearImageSelection() = select(null, null)

    private fun select(image: PageImage?, page: Page?) {
        val had = selectedImage != null
        selectedImage = image
        selectedImagePage = page
        dry.invalidate()
        if (had != (image != null)) onImageSelected?.invoke(image != null)
    }

    private fun beginImageGesture(event: MotionEvent, index: Int) {
        val page = document.pages[index]
        pageLocalInto(event.x, event.y, index, imageGrab)
        val x = imageGrab[0]
        val y = imageGrab[1]
        // The handle is a fixed size on screen, so it stays grabbable however
        // far the page is zoomed out.
        val grab = IMAGE_HANDLE_PX / currentScale()
        val hit = page.images.lastOrNull {
            x >= it.x - grab && x <= it.x + it.width + grab &&
                y >= it.y - grab && y <= it.y + it.height + grab
        }
        if (hit == null) {
            select(null, null)
            return
        }
        select(hit, page)
        resizingImage = hypot(x - (hit.x + hit.width), y - (hit.y + hit.height)) <= grab
        movingImage = !resizingImage
    }

    private fun dragImage(event: MotionEvent, pointerIndex: Int) {
        val image = selectedImage ?: return
        val page = selectedImagePage ?: return
        val index = document.pages.indexOf(page)
        if (index < 0) return
        val point = floatArrayOf(event.getX(pointerIndex), event.getY(pointerIndex))
        screenToPage(index).mapPoints(point)
        if (resizingImage) {
            val aspect = if (image.height > 0f) image.width / image.height else 1f
            // Width leads and height follows, so a picture never gets squashed.
            val width = (point[0] - image.x).coerceAtLeast(IMAGE_MIN_SIZE)
            image.width = width
            image.height = if (aspect > 0f) width / aspect else width
        } else {
            image.x += point[0] - imageGrab[0]
            image.y += point[1] - imageGrab[1]
            imageGrab[0] = point[0]
            imageGrab[1] = point[1]
        }
        dry.invalidate()
    }

    private fun finishCapture() {
        capturing = false
        val page = document.pages.getOrNull(capturePage)
        val rect = RectF(captureRect)
        captureRect.setEmpty()
        dry.invalidate()
        if (page == null || rect.width() < 8f || rect.height() < 8f) return

        // Snapshot on the UI thread: the render runs on a worker, and a stroke
        // arriving on the page mid-render would otherwise be a concurrent
        // modification of the list being walked.
        val strokes = page.strokes.toList()
        val images = page.images.map { it to imageLoader?.invoke(it.id) }
        val pdfIndex = if (page.background == PageBackground.PDF) page.pdfPageIndex else -1
        val pageWidth = page.width
        val pageHeight = page.height
        refiner.execute {
            val bitmap = renderRegion(rect, pageWidth, pageHeight, pdfIndex, strokes, images)
            if (bitmap != null) onCaptured?.invoke(bitmap)
        }
    }

    /** Draws one page's [rect] into a bitmap: background, pictures, then ink. */
    private fun renderRegion(
        rect: RectF,
        pageWidth: Float,
        pageHeight: Float,
        pdfIndex: Int,
        strokes: List<Stroke>,
        images: List<Pair<PageImage, Bitmap?>>,
    ): Bitmap? = runCatching {
        val scale = (CAPTURE_TARGET_PX / max(rect.width(), rect.height()))
            .coerceIn(1f, CAPTURE_MAX_SCALE)
        val width = (rect.width() * scale).toInt().coerceAtLeast(1)
        val height = (rect.height() * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        canvas.translate(-rect.left, -rect.top)

        if (pdfIndex >= 0) {
            pdf?.renderNow(pdfIndex, (pageWidth * scale).toInt().coerceAtLeast(1))?.let {
                canvas.drawBitmap(it, null, RectF(0f, 0f, pageWidth, pageHeight), null)
            }
        }
        for ((placement, picture) in images) {
            if (picture == null) continue
            canvas.drawBitmap(
                picture,
                null,
                RectF(
                    placement.x,
                    placement.y,
                    placement.x + placement.width,
                    placement.y + placement.height,
                ),
                null,
            )
        }
        val renderer = CanvasStrokeRenderer.create()
        val transform = Matrix()
        for (stroke in strokes) renderer.draw(canvas, stroke, transform)
        bitmap
    }.getOrNull()

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
        // The tip is a square of eraserWidth around where the pen is now; the
        // segment covers the gap to the previous sample, so a fast swipe still
        // erases along its whole path instead of leaving holes between samples.
        val tip = ImmutableBox.fromCenterAndDimensions(
            ImmutableVec(point[0], point[1]),
            eraserWidth,
            eraserWidth,
        )
        // ponytail: whole-stroke eraser. A partial (pixel) eraser means splitting
        // the input batch and rebuilding both halves - worth it only if the
        // whole-stroke behaviour actually gets complained about.
        //
        // intersects() is a member extension on the Intersection object, so it
        // only resolves inside its scope.
        val hit = with(Intersection) {
            page.strokes.filter {
                segment.intersects(it.shape, IDENTITY) || tip.intersects(it.shape, IDENTITY)
            }
        }
        val maskHits = with(Intersection) {
            page.masks.filter {
                segment.intersects(it.stroke.shape, IDENTITY) ||
                    tip.intersects(it.stroke.shape, IDENTITY)
            }
        }
        if (hit.isEmpty() && maskHits.isEmpty()) return
        // Backwards, so each recorded index is still where it goes back.
        for (mask in maskHits.reversed()) {
            val at = page.masks.indexOfFirst { it === mask }
            if (at < 0) continue
            page.masks.removeAt(at)
            undoStack += Edit.MaskRemoved(page, mask, at)
        }
        if (hit.isNotEmpty()) {
            page.strokes.removeAll(hit)
            undoStack += Edit.Erased(page, hit)
        }
        redoStack.clear()
        afterEdit(page)
    }

    override fun onStrokesFinished(finished: Map<InProgressStrokeId, Stroke>) {
        val page = activePage
        if (page != null) {
            for (stroke in finished.values) {
                if (strokeIsMask) {
                    val mask = PageMask(stroke)
                    page.masks += mask
                    undoStack += Edit.MaskAdded(page, mask)
                } else {
                    page.strokes += stroke
                    undoStack += Edit.Drawn(page, stroke)
                }
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
        private val imageRect = RectF()
        private val overlay = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
        }
        private val marquee = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            color = 0xFF3B7DDD.toInt()
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
        }
        private val marqueeFill = Paint().apply { color = 0x223B7DDD }
        private val outline = android.graphics.Path()
        private val outlinePoint = MutableVec()
        private val selectionBox = RectF()
        private val handle = Paint().apply { isAntiAlias = true; color = 0xFF3B7DDD.toInt() }

        // Strokes are immutable, so a bounding box is worth computing once.
        // Weak keys let an erased stroke's entry go with the stroke.
        private val boxes = java.util.WeakHashMap<Stroke, RectF>()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            visibleDocumentBounds(viewport)

            // The scope has to be obtained while the canvas is still untransformed.
            // drawStroke reads the canvas matrix at draw time and composes it with
            // the one captured here, which is how the renderer learns the real
            // stroke-to-screen scale and picks a fidelity to match. Concatenating
            // the zoom before this call puts it into both halves, where it cancels
            // out - the renderer then draws a stroke magnified 8x as though it
            // were at 1:1, which is exactly what made zoomed-in ink look soft and
            // bend its corners.
            renderer.drawWithStrokes(canvas) { scoped, scope ->
                for (i in document.pages.indices) {
                    val page = document.pages[i]
                    val top = document.topOf(i)
                    if (top > viewport[3] || top + page.height < viewport[1]) continue
                    val left = document.leftOf(i)

                    scoped.save()
                    scoped.concat(documentToScreen)
                    scoped.translate(left, top)
                    drawPaper(scoped, page, i)
                    drawImages(scoped, page)

                    // Page-level culling alone still redraws every stroke on a page
                    // that is only half on screen. A zoomed-in page of dense notes
                    // is exactly when frames are tightest.
                    val cullLeft = viewport[0] - left
                    val cullTop = viewport[1] - top
                    val cullRight = viewport[2] - left
                    val cullBottom = viewport[3] - top
                    val lifted = i == lassoPage && movingLasso
                    for (stroke in page.strokes) {
                        // Held strokes are drawn again below, at the offset.
                        if (lifted && stroke in lassoStrokes) continue
                        val box = boundsOf(stroke) ?: continue
                        if (box.right < cullLeft || box.left > cullRight ||
                            box.bottom < cullTop || box.top > cullBottom
                        ) {
                            continue
                        }
                        scope.drawStroke(stroke)
                    }
                    if (lifted) {
                        scoped.save()
                        scoped.translate(lassoDx, lassoDy)
                        for (stroke in lassoStrokes) scope.drawStroke(stroke)
                        scoped.restore()
                    }
                    if (i == lassoPage) drawLasso(scoped)
                    if (i == selectingPage) {
                        selection?.boxes?.forEach { scoped.drawRect(it, selectionPaint) }
                    }
                    if (i == shapePage && drawingShape) drawShapePreview(scoped)
                    if (i == capturePage && capturing) {
                        scoped.drawRect(captureRect, marqueeFill)
                        marquee.strokeWidth = 2f / currentScale()
                        scoped.drawRect(captureRect, marquee)
                    }
                    // Over the ink, because covering it is the entire job.
                    drawMasks(scoped, scope, page)
                    if (page === selectedImagePage) drawImageHandles(scoped)
                    scoped.restore()
                }
            }
        }

        private fun boundsOf(stroke: Stroke): RectF? = boxes.getOrPut(stroke) {
            val box = stroke.shape.computeBoundingBox() ?: return null
            RectF(box.xMin, box.yMin, box.xMax, box.yMax)
        }

        private fun drawImages(canvas: Canvas, page: Page) {
            if (page.images.isEmpty()) return
            val loader = imageLoader ?: return
            for (image in page.images) {
                val bitmap = loader(image.id) ?: continue
                imageRect.set(
                    image.x,
                    image.y,
                    image.x + image.width,
                    image.y + image.height,
                )
                canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint)
            }
        }

        /** The loop as it is drawn, and the box around what it caught. */
        private fun drawLasso(canvas: Canvas) {
            val scale = currentScale()
            if (drawingLasso && lassoPath.size >= 4) {
                overlay.color = 0xFF3B7DDD.toInt()
                overlay.strokeWidth = 2f / scale
                overlay.pathEffect = lassoDashes(scale)
                var i = 0
                while (i + 3 < lassoPath.size) {
                    canvas.drawLine(
                        lassoPath[i],
                        lassoPath[i + 1],
                        lassoPath[i + 2],
                        lassoPath[i + 3],
                        overlay,
                    )
                    i += 2
                }
                overlay.pathEffect = null
                return
            }
            if (lassoStrokes.isEmpty() || lassoBounds.isEmpty) return
            selectionBox.set(lassoBounds)
            selectionBox.offset(lassoDx, lassoDy)
            val pad = LASSO_PADDING / scale
            selectionBox.inset(-pad, -pad)
            overlay.color = 0xFF3B7DDD.toInt()
            overlay.strokeWidth = 2f / scale
            overlay.pathEffect = lassoDashes(scale)
            canvas.drawRect(selectionBox, overlay)
            overlay.pathEffect = null
        }

        private fun lassoDashes(scale: Float) =
            android.graphics.DashPathEffect(floatArrayOf(8f / scale, 6f / scale), 0f)

        private fun drawMasks(
            canvas: Canvas,
            scope: androidx.ink.rendering.android.canvas.StrokeDrawScope,
            page: Page,
        ) {
            if (page.masks.isEmpty()) return
            val scale = currentScale()
            for (mask in page.masks) {
                if (!mask.revealed) {
                    scope.drawStroke(mask.stroke)
                    continue
                }
                // Lifted: only the edge of the shape is drawn, so what it covers
                // can be read and there is still something there to tap again.
                outline.reset()
                appendOutline(mask.stroke.shape, outline)
                if (outline.isEmpty) continue
                overlay.color = mask.stroke.brush.colorIntArgb
                overlay.strokeWidth = MASK_OUTLINE_PX / scale
                canvas.drawPath(outline, overlay)
            }
        }

        /** Every closed loop the stroke's mesh is made of, as one path. */
        private fun appendOutline(shape: PartitionedMesh, path: android.graphics.Path) {
            for (group in 0 until shape.getRenderGroupCount()) {
                for (index in 0 until shape.getOutlineCount(group)) {
                    val vertices = shape.getOutlineVertexCount(group, index)
                    if (vertices < 2) continue
                    for (v in 0 until vertices) {
                        shape.populateOutlinePosition(group, index, v, outlinePoint)
                        if (v == 0) {
                            path.moveTo(outlinePoint.x, outlinePoint.y)
                        } else {
                            path.lineTo(outlinePoint.x, outlinePoint.y)
                        }
                    }
                    path.close()
                }
            }
        }

        /** Border and corner grip for the picture currently picked up. */
        private fun drawImageHandles(canvas: Canvas) {
            val image = selectedImage ?: return
            imageRect.set(image.x, image.y, image.x + image.width, image.y + image.height)
            // Sized in screen pixels and divided back out, so the border stays
            // the same weight however far the page is zoomed.
            val scale = currentScale()
            overlay.color = 0xFF3B7DDD.toInt()
            overlay.strokeWidth = 2f / scale
            canvas.drawRect(imageRect, overlay)
            canvas.drawCircle(imageRect.right, imageRect.bottom, IMAGE_HANDLE_PX / scale, handle)
        }

        /** The shape as it is being dragged out, before it becomes a stroke. */
        private fun drawShapePreview(canvas: Canvas) {
            val kind = shapeKind ?: return
            val points = shapePoints(kind, shapeStart, shapeEnd)
            if (points.size < 2) return
            overlay.color = colorArgb
            overlay.strokeWidth = strokeWidth
            overlay.strokeCap = Paint.Cap.ROUND
            overlay.strokeJoin = Paint.Join.ROUND
            for (i in 0 until points.size - 1) {
                canvas.drawLine(
                    points[i][0],
                    points[i][1],
                    points[i + 1][0],
                    points[i + 1][1],
                    overlay,
                )
            }
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
            val scale = currentScale()

            // The whole page at modest resolution is the floor: it is cheap, it
            // is always there, and it means a tile that has not arrived yet
            // shows slightly soft rather than blank.
            val base = source.bitmap(page.pdfPageIndex, (page.width * scale).toInt())
            if (base != null) {
                canvas.drawBitmap(base, null, pageRect, bitmapPaint)
            } else {
                canvas.drawRect(pageRect, paper)
            }

            // Past the point where the whole-page bitmap can hold the detail,
            // lay sharp tiles of the visible region over it.
            if (page.width * scale > PdfSource.baseWidthLimit()) {
                visibleCropOf(page, index, crop)
                if (crop.width() > 0f && crop.height() > 0f) {
                    for (tile in source.tiles(
                        page.pdfPageIndex,
                        crop,
                        page.width,
                        page.height,
                        scale,
                    )) {
                        canvas.drawBitmap(tile.bitmap, null, tile.source, bitmapPaint)
                    }
                }
            }
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
        const val REFINE_DEBOUNCE_MS = 200L
        const val LONG_PRESS_MS = 350L
        const val HIGHLIGHT_HEIGHT = 0.85f
        const val MIN_FLING_VELOCITY = 80f
        const val MAX_FLING_VELOCITY = 12000f
        /** Higher is stickier; this lands close to the platform list fling. */
        const val FLING_FRICTION = 3.2f
        const val DETAIL_THRESHOLD_PX = 2048
        const val SHADOW = 4f
        const val SHAPE_STEP_MS = 8L
        const val OVAL_STEPS = 64
        const val ARROW_SPREAD = 0.5f
        const val IMAGE_INSERT_FRACTION = 0.5f
        const val IMAGE_HANDLE_PX = 22f
        const val IMAGE_MIN_SIZE = 32f
        const val CAPTURE_TARGET_PX = 2048f
        const val CAPTURE_MAX_SCALE = 4f
        const val LASSO_MIN_POINTS = 6
        const val LASSO_PADDING = 10f
        const val MASK_TAP_PX = 6f
        const val MASK_OUTLINE_PX = 2f
        const val MASK_OPAQUE = 0xFF000000.toInt()
        val IDENTITY = ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f)
    }
}

/**
 * Ray cast against a closed polygon given as flat x, y pairs: an odd number of
 * crossings to the right of the point means it is inside. The lasso is never
 * explicitly closed, and it does not need to be - the cast treats the last
 * point and the first as joined, which is what a lasso means anyway.
 */
internal fun insidePolygon(path: List<Float>, x: Float, y: Float): Boolean {
    val count = path.size / 2
    if (count < 3) return false
    var inside = false
    var j = count - 1
    for (i in 0 until count) {
        val xi = path[i * 2]
        val yi = path[i * 2 + 1]
        val xj = path[j * 2]
        val yj = path[j * 2 + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/** True while the barrel button is held, or the pen is flipped to its eraser end. */
private fun MotionEvent.isEraserGesture(): Boolean =
    getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER ||
        buttonState and
        (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
