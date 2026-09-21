@file:Suppress("RestrictedApi")

package com.notesis

import android.os.Build
import android.os.Debug
import android.os.Trace
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.LruCache
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
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
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.TextureBitmapStore
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
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.atan2

/**
 * Deepest zoom worth sharpening for, in screen pixels per page unit.
 *
 * Not the zoom limit - that is [InkCanvasView.MAX_ZOOM], and it is a multiple
 * of fit-to-width, because a limit in page units means a different amount of
 * magnification on every screen. This is where the mesh and the PDF tiles stop
 * being rebuilt finer: past it the picture is magnified rather than redrawn,
 * which costs nothing and is the right trade at the far end of the range.
 */
const val MAX_CANVAS_SCALE = 16f

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

/** 급회전 한 프레임에서는 예측점이 코너 바깥으로 튀지 않게 합니다. */
internal fun shouldSuppressPrediction(
    previousDx: Float,
    previousDy: Float,
    nextDx: Float,
    nextDy: Float,
): Boolean {
    val previousLength = hypot(previousDx, previousDy)
    val nextLength = hypot(nextDx, nextDy)
    if (previousLength < 0.5f || nextLength < 0.5f) return false
    val cosine = (previousDx * nextDx + previousDy * nextDy) /
        (previousLength * nextLength)
    return cosine < PREDICTION_CORNER_COSINE
}

/** Fraction of the predictor's path that lands at the requested fixed horizon. */
internal fun predictionLeadFraction(realTimeMs: Long, predictedTimeMs: Long, leadMs: Long): Float {
    val available = predictedTimeMs - realTimeMs
    if (available <= 0L || available <= leadMs) return 1f
    return (leadMs.toFloat() / available).coerceIn(0f, 1f)
}

private const val PREDICTION_CORNER_COSINE = 0.57f

/** Fidelity for a stroke that has no zoom context yet, such as one just loaded. */
const val STROKE_EPSILON = TESSELLATION_TARGET_PX

private const val STROKE_GRID_CELL = 256f
private const val MAX_GRID_CELLS_PER_STROKE = 64
/** Below this fraction of the tip width, the previous eraser tip still covers the move. */
private const val ERASER_SAMPLE_DISTANCE_FRACTION = 0.08f

/** A page-local spatial index used by both dense-page drawing and culling. */
private class StrokeGrid {
    private class Entry(
        val stroke: Stroke,
        val bounds: RectF,
        val order: Int,
        var cellKeys: LongArray = LongArray(0),
    )

    private val cells = HashMap<Long, MutableList<Entry>>()
    private val spanning = ArrayList<Entry>()
    private val entries = IdentityHashMap<Stroke, Entry>()
    private val ordered = ArrayList<Entry?>()
    private val included = java.util.BitSet()
    private val result = ArrayList<Stroke>()
    private var revision = Long.MIN_VALUE
    private var indexedSize = 0
    private var firstStroke: Stroke? = null
    private var lastStroke: Stroke? = null

    fun visible(page: Page, left: Float, top: Float, right: Float, bottom: Float): List<Stroke> {
        sync(page)
        result.clear()
        if (right < left || bottom < top) return result
        if (left <= 0f && top <= 0f && right >= page.width && bottom >= page.height) {
            return page.strokes
        }
        included.clear()
        val firstX = cell(left)
        val lastX = cell(right)
        val firstY = cell(top)
        val lastY = cell(bottom)
        for (y in firstY..lastY) {
            for (x in firstX..lastX) {
                cells[key(x, y)]?.forEach { entry -> include(entry, left, top, right, bottom) }
            }
        }
        spanning.forEach { entry -> include(entry, left, top, right, bottom) }
        var order = included.nextSetBit(0)
        while (order >= 0) {
            ordered.getOrNull(order)?.let { result += it.stroke }
            order = included.nextSetBit(order + 1)
        }
        return result
    }

    private fun include(entry: Entry, left: Float, top: Float, right: Float, bottom: Float) {
        if (included[entry.order]) return
        val box = entry.bounds
        if (box.right >= left && box.left <= right && box.bottom >= top && box.top <= bottom) {
            included.set(entry.order)
        }
    }

    private fun sync(page: Page) {
        val strokes = page.strokes
        val endpointsMatch = indexedSize == 0 || strokes.isNotEmpty() &&
            firstStroke === strokes.first() && lastStroke === strokes.last()
        if (revision == page.revision && indexedSize == strokes.size && endpointsMatch) return

        // The dominant edit is one stroke appended at pen-up. Index just that
        // suffix; erase, lasso, load-at-front, and retessellation rebuild once.
        val appendOnly = strokes.size > indexedSize &&
            (indexedSize == 0 || strokes.getOrNull(indexedSize - 1) === lastStroke)
        if (!appendOnly) {
            cells.clear()
            spanning.clear()
            entries.clear()
            ordered.clear()
            included.clear()
            indexedSize = 0
        }
        for (i in indexedSize until strokes.size) {
            index(strokes[i], ordered.size)
        }
        indexedSize = strokes.size
        firstStroke = strokes.firstOrNull()
        lastStroke = strokes.lastOrNull()
        revision = page.revision
    }

    fun remove(page: Page, removed: Collection<Stroke>) {
        for (stroke in removed) {
            val entry = entries.remove(stroke) ?: continue
            if (entry.cellKeys.isEmpty()) {
                spanning.remove(entry)
            } else {
                for (key in entry.cellKeys) {
                    val cell = cells[key] ?: continue
                    cell.remove(entry)
                    if (cell.isEmpty()) cells.remove(key)
                }
            }
            ordered[entry.order] = null
            included.clear(entry.order)
        }
        indexedSize = page.strokes.size
        firstStroke = page.strokes.firstOrNull()
        lastStroke = page.strokes.lastOrNull()
        revision = page.revision
    }

    private fun index(stroke: Stroke, order: Int) {
        val box = stroke.shape.computeBoundingBox() ?: return
        val bounds = RectF(box.xMin, box.yMin, box.xMax, box.yMax)
        val entry = Entry(stroke, bounds, order)
        entries[stroke] = entry
        while (ordered.size < order) ordered += null
        ordered += entry
        val firstX = cell(bounds.left)
        val lastX = cell(bounds.right)
        val firstY = cell(bounds.top)
        val lastY = cell(bounds.bottom)
        val count = (lastX.toLong() - firstX + 1L) * (lastY.toLong() - firstY + 1L)
        if (count > MAX_GRID_CELLS_PER_STROKE) {
            spanning += entry
            return
        }
        val keys = LongArray(count.toInt())
        var at = 0
        for (y in firstY..lastY) for (x in firstX..lastX) {
            val key = key(x, y)
            keys[at++] = key
            cells.getOrPut(key) { ArrayList() } += entry
        }
        entry.cellKeys = keys
    }

    private fun cell(value: Float): Int = kotlin.math.floor(value / STROKE_GRID_CELL).toInt()
    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
}

/** Primitive lasso storage: no boxed Float is created for every stylus sample. */
internal class FloatPointBuffer(initialCapacity: Int = 2048) {
    private var values = FloatArray(initialCapacity.coerceAtLeast(2))
    var size: Int = 0
        private set

    operator fun get(index: Int): Float {
        require(index in 0 until size)
        return values[index]
    }

    fun clear() {
        size = 0
    }

    fun add(x: Float, y: Float) {
        ensureCapacity(size + 2)
        values[size++] = x
        values[size++] = y
    }

    /** Keeps enough geometry for the loop while discarding sub-pixel input noise. */
    fun addIfFarEnough(x: Float, y: Float, minimumDistance: Float): Boolean {
        if (size >= 2) {
            val dx = x - values[size - 2]
            val dy = y - values[size - 1]
            if (dx * dx + dy * dy < minimumDistance * minimumDistance) return false
        }
        add(x, y)
        return true
    }

    fun contains(x: Float, y: Float): Boolean {
        val count = size / 2
        if (count < 3) return false
        var inside = false
        var j = count - 1
        for (i in 0 until count) {
            val xi = values[i * 2]
            val yi = values[i * 2 + 1]
            val xj = values[j * 2]
            val yj = values[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun ensureCapacity(wanted: Int) {
        if (wanted <= values.size) return
        values = values.copyOf(maxOf(wanted, values.size * 2))
    }
}

private class TextRender(
    val layout: StaticLayout,
    val logicalWidth: Float,
    val logicalHeight: Float,
)

/** The shapes the pen can be made to draw instead of following the hand. */
enum class ShapeKind { LINE, ARROW, RECT, OVAL }

enum class Tool {
    PEN,
    HIGHLIGHTER,
    ERASER,

    /**
     * Masking tape. A marker in everything but one respect: where a stroke
     * crosses itself the crossing is not drawn twice. Appended rather than
     * inserted, because a tool's ordinal is what is written into saved notes.
     */
    MASK,

    /**
     * The pen, with the pressure the stylus is already reporting mapped to
     * width. Kept as its own tool rather than changing what PEN means, so a
     * note written before this existed still draws the way it was written.
     */
    PRESSURE_PEN,
    PENCIL,

    /** Patterned lines remain one Ink stroke instead of hundreds of marks. */
    DOTTED,
    DASHED,
    DASH_DOT;

    fun brushFamily(): BrushFamily = when (this) {
        HIGHLIGHTER -> highlighter
        MASK -> masking
        PRESSURE_PEN -> pressurePen
        PENCIL -> pencil
        DOTTED -> dotted
        DASHED -> dashed
        DASH_DOT -> dashDot
        else -> pen
    }

    companion object {
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val pen by lazy {
            val stock = StockBrushes.marker()
            val coat = stock.coats.first()
            stock.copy(coat = coat.copy(tip = coat.tip.copy(
                scaleX = 1f, scaleY = 1f, cornerRounding = 1f,
                slantDegrees = 0f, pinch = 0f, rotationDegrees = 0f,
            )))
        }
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val pressurePen by lazy {
            val stock = StockBrushes.pressurePen()
            val coat = stock.coats.first()
            stock.copy(coat = coat.copy(tip = coat.tip.copy(
                scaleX = 1f, scaleY = 1f, cornerRounding = 1f,
                slantDegrees = 0f, pinch = 0f, rotationDegrees = 0f,
            )))
        }
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val pencil by lazy {
            // This stock brush carries graphite grain and pressure/tilt
            // variation. The old pressure-pen copy was still a smooth ballpen.
            StockBrushes.pencilUnstable
        }

        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val dashed by lazy { StockBrushes.dashedLine() }

        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val dotted by lazy {
            val stock = StockBrushes.dashedLine()
            val coat = stock.coats.first()
            stock.copy(coat = coat.copy(tip = coat.tip.copy(
                scaleX = 0.34f,
                scaleY = 0.34f,
                cornerRounding = 1f,
                particleGapDistanceScale = 1.65f,
            )))
        }

        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val dashDot by lazy {
            val stock = StockBrushes.dashedLine()
            val coat = stock.coats.first()
            // A tighter, shorter secondary rhythm keeps this visibly distinct
            // from both round dots and the regular long-dash stock brush while
            // retaining one mesh/one history entry.
            stock.copy(coat = coat.copy(tip = coat.tip.copy(
                scaleX = 0.62f,
                scaleY = 0.62f,
                cornerRounding = 1f,
                particleGapDistanceScale = 2.8f,
            )))
        }

        /**
         * A centred round marker with merged self-overlap keeps a highlighter
         * aligned to the pen and avoids dark seams where it crosses itself.
         */
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val highlighter by lazy {
            // The stock chisel brush carries an offset tip. A centred marker
            // tip keeps the committed line under the stylus at every angle.
            val stock = StockBrushes.marker()
            val coat = stock.coats.first()
            merged(
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
                ),
            )
        }

        private val masking by lazy { merged(StockBrushes.marker()) }

        /**
         * One stroke that doubles back over itself is still one stroke, so the
         * overlap is drawn once. Without this a highlighter darkens wherever the
         * hand crossed its own line, and tape shows every seam - the ink library
         * calls it self overlap, and DISCARD is the "merge" of the two options.
         */
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private fun merged(family: BrushFamily): BrushFamily {
            val coat = family.coats.firstOrNull() ?: return family
            val paints = coat.paintPreferences.map { paint ->
                BrushPaint(paint.textureLayers, paint.colorFunctions, SelfOverlap.DISCARD)
            }
            if (paints.isEmpty()) return family
            return family.copy(coat = coat.copy(paintPreferences = paints))
        }

        /** Reverse lookup for reload: an erased stroke was never saved. */
        fun ofBrushFamily(family: BrushFamily): Tool = when (family) {
            highlighter -> HIGHLIGHTER
            masking -> MASK
            pressurePen -> PRESSURE_PEN
            pencil -> PENCIL
            dotted -> DOTTED
            dashed -> DASHED
            dashDot -> DASH_DOT
            else -> PEN
        }
    }
}

private fun Tool.isFreehandPen(): Boolean =
    this == Tool.PEN || this == Tool.PRESSURE_PEN || this == Tool.PENCIL

/**
 * A tiny, allocation-free visual cap over the platform's transient prediction.
 *
 * The predicted geometry itself still belongs to [InProgressStrokesView] and
 * is replaced by the next real event. This view only softens the last few
 * pixels of a fast predicted tip with two translucent antialiased strokes. It
 * never uses a blur mask and never participates in committed stroke geometry.
 */
private class PredictionHeadView(context: Context) : View(context) {
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var visibleHead = false
    private val hide = Runnable {
        if (visibleHead) {
            visibleHead = false
            postInvalidateOnAnimation()
        }
    }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(
        realX: Float,
        realY: Float,
        predictedX: Float,
        predictedY: Float,
        colorArgb: Int,
        widthPx: Float,
    ) {
        val dx = predictedX - realX
        val dy = predictedY - realY
        val length = hypot(dx, dy)
        if (length < MIN_LENGTH_PX || !length.isFinite()) {
            clear()
            return
        }
        val density = resources.displayMetrics.density
        val cap = maxOf(widthPx * 2.2f, density * MIN_CAP_DP)
            .coerceAtMost(density * MAX_LENGTH_DP)
        val shown = minOf(length, cap)
        startX = predictedX - dx * shown / length
        startY = predictedY - dy * shown / length
        endX = predictedX
        endY = predictedY

        val sourceAlpha = Color.alpha(colorArgb)
        outer.color = Color.argb(sourceAlpha * OUTER_ALPHA / 255,
            Color.red(colorArgb), Color.green(colorArgb), Color.blue(colorArgb))
        inner.color = Color.argb(sourceAlpha * INNER_ALPHA / 255,
            Color.red(colorArgb), Color.green(colorArgb), Color.blue(colorArgb))
        outer.strokeWidth = maxOf(1f, widthPx * 1.45f)
        inner.strokeWidth = maxOf(1f, widthPx * 0.72f)
        visibleHead = true
        removeCallbacks(hide)
        postDelayed(hide, MAX_LIFETIME_MS)
        postInvalidateOnAnimation()
    }

    fun clear() {
        removeCallbacks(hide)
        if (!visibleHead) return
        visibleHead = false
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (!visibleHead) return
        canvas.drawLine(startX, startY, endX, endY, outer)
        canvas.drawLine(startX, startY, endX, endY, inner)
    }

    private companion object {
        const val MIN_LENGTH_PX = 1.25f
        const val MIN_CAP_DP = 4f
        const val MAX_LENGTH_DP = 12f
        const val MAX_LIFETIME_MS = 24L
        const val OUTER_ALPHA = 28
        const val INNER_ALPHA = 48
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
    var stabilizer: Int = 0
    var autoShapeRecognitionEnabled: Boolean = false
    var axisSnapEnabled: Boolean = true
    var dottedPattern: Int = 0
    private var playbackPage = -1
    private var playbackStrokeCount: Int? = null

    fun beginPagePlayback(pageIndex: Int): Int {
        val page = document.pages.getOrNull(pageIndex) ?: return 0
        playbackPage = pageIndex
        playbackStrokeCount = 0
        dry.invalidate()
        return page.strokes.size
    }

    fun showPagePlayback(count: Int) {
        playbackStrokeCount = count.coerceAtLeast(0)
        dry.invalidate()
    }

    fun endPagePlayback() {
        playbackStrokeCount = null
        playbackPage = -1
        dry.invalidate()
    }
    var highlighterAboveInk: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            dry.invalidate()
        }

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
    var textMode: Boolean = false
    var onTextRequested: ((Int, Float, Float) -> Unit)? = null
    private var pendingTextPlacement: Triple<Int, Float, Float>? = null
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
    var templateLoader: ((String) -> Bitmap?)? = null

    /** Handed a rendering of the captured region, on a background thread. */
    var onCaptured: ((Bitmap) -> Unit)? = null

    // ---- finger gestures, alongside the pen -----------------------------
    //
    // Undo, redo and the reference panel all live here rather than in an
    // ancestor Compose modifier: this is the one place every finger touch is
    // actually dispatched to. An AndroidView owns its own MotionEvent stream
    // once it starts handling one, so a Compose pointerInput sitting above it
    // is watching a stream that may already be spoken for - which is exactly
    // why undo and redo went quiet the first time this was tried from there.

    /** Two fingers, tapped twice quickly. */
    var onUndo: (() -> Unit)? = null

    /** Three fingers, the same way. */
    var onRedo: (() -> Unit)? = null

    /** Whether the reference panel is on screen right now - set from outside. */
    var referenceOpen: Boolean = false

    /** Three fingers dragged up from nothing, panel closed: where to open it. */
    var onOpenReference: ((Float, Float) -> Unit)? = null

    /** The same drag the other way up, on the page, while the panel is open. */
    var onCloseReference: (() -> Unit)? = null

    /** 팝업 자체에서도 세 손가락 아래 스와이프로 닫을지 여부입니다. */
    var closeReferenceOnDownwardDrag: Boolean = false

    /** Three fingers moving once it is open: pan in each axis, then the ratio the spread grew by. */
    var onReferenceDrag: ((Float, Float, Float) -> Unit)? = null

    /**
     * The hand has come off a panel drag. Growing a panel while it is being
     * dragged is a picture being stretched; this is where the host turns that
     * back into a panel of the new size with a page drawn for it.
     */
    var onReferenceDragEnd: (() -> Unit)? = null

    /** Whether this gesture has actually moved the panel, so its end is worth reporting. */
    private var draggedReference = false

    /** Fired when a picture is picked up or let go, so the host can offer actions. */
    var onImageSelected: ((Boolean) -> Unit)? = null

    /** Fired whenever committed ink changes, so the host can autosave. */
    var onStrokesChanged: (() -> Unit)? = null

    /**
     * Raised when the pen goes down and again when it comes up.
     *
     * The chrome above this view frosts what is under it, and doing that means
     * recording the page into a layer every frame it moves. That recording is
     * on the same render pass as the ink, and it is the one thing between here
     * and the screen that this app can decide not to do. Nobody is looking at
     * the toolbar while they write, so the answer is to stop while the pen is
     * down and pick the frost back up when it lifts.
     */
    var onDrawingChanged: ((Boolean) -> Unit)? = null

    /**
     * Raised for the lifetime of a finger pan or pinch.
     *
     * Glass records the whole page again to provide a live backdrop. Keeping
     * that second render running while the page itself is moving can turn one
     * busy PDF frame into two. The host freezes the last recording for the
     * gesture and refreshes it when the hand lifts.
     */
    var onViewportInteractionChanged: ((Boolean) -> Unit)? = null

    /**
     * Whether the tip is drawn ahead of the pen.
     *
     * Prediction buys back most of the motion-to-photon gap, and it pays for it
     * with a tip that reaches past where the pen actually is. On a fast change
     * of direction the reach lands on the wrong side of the corner and is taken
     * back a frame later, which is seen as the stroke going briefly angular. It
     * is the right trade for most hands and the wrong one for some, so it is a
     * switch rather than a decision - and it is the first thing to turn off
     * when the ink looks faceted.
     */
    var predictionEnabled: Boolean = true

    /** Zero is automatic; fixed 4/6/9ms values make A/B comparison repeatable. */
    var predictionLeadMs: Int = 0

    /** Enables diagnostic sampling only while its HUD is actually visible. */
    var latencyMonitoringEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            latency.setEnabled(value)
            if (value && !frameClockRunning && isAttachedToWindow) {
                frameClockRunning = true
                lastFrameNanos = 0L
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }

    /**
     * Whether detail work waits for the pinch to finish.
     *
     * Everything that makes a page look right at its new size - rebuilding the
     * stroke meshes at the fidelity the zoom now needs, rendering the PDF at
     * the width it is now drawn at, cutting sharp tiles for the region on
     * screen - is work that only matters once somebody has stopped moving. Done
     * while the fingers are still on the glass it competes for the same frame
     * the pinch is being drawn in, and the pinch is what stutters.
     *
     * On, the page is drawn from whatever is already in hand for the length of
     * the gesture - a little soft at the new zoom - and everything is brought up
     * to the new size the moment the fingers lift. Off, it is done as it goes,
     * which is sharper throughout and slower on a page with a lot on it.
     */
    var deferDetail: Boolean = true

    /** True between the second finger going down and the pinch ending. */
    private var zooming = false
    /** True while fingers own the viewport, including a one-finger pan. */
    private var viewportInteracting = false
    /** A three-finger panel gesture must never leak into the two-finger scaler. */
    private var suppressScaleUntilGestureEnd = false

    /** Whether detail work should be held off right now. */
    private fun holdingDetail(): Boolean = shouldDeferDetail(
        deferDetail,
        viewportInteracting,
        zooming,
        flinging,
    )

    /** 팝업 노트는 아무리 축소해도 페이지 폭이 패널 폭보다 작아지지 않습니다. */
    var minimumScaleIsFitWidth: Boolean = false

    /** 손가락 페이지 이동량과 fling 속도 배율입니다. 일반 화면은 1, 작은 팝업만 높입니다. */
    var viewportPanMultiplier: Float = 1f

    /**
     * The ceiling on zoom, in page units: ten times fit-to-width, which is what
     * the toolbar reads out as 1000%. Measured from the fit rather than fixed,
     * because a fixed number of pixels per page unit is a different amount of
     * magnification on a phone, a portrait tablet and a landscape one - it was
     * eight, and that is barely 350% across a landscape screen.
     */
    private fun maxScale(): Float =
        if (fitScale > 0f) fitScale * MAX_ZOOM else MAX_CANVAS_SCALE

    /** Fired when the page under the middle of the screen changes. */
    var onCurrentPageChanged: ((Int) -> Unit)? = null

    /**
     * Fired when the zoom changes, as a multiple of fit-to-width rather than of
     * document units: 1.0 is the page filling the view, which is the only zoom
     * anybody has a feel for. Only on an actual change, so a pinch that has
     * hit the limit stops reporting instead of waking the host once a frame.
     */
    var onZoomChanged: ((Float) -> Unit)? = null

    /** The scale fit-to-width last chose; the denominator of that multiple. */
    private var fitScale = 0f
    private var reportedZoom = 0f
    /** Keeps the Compose host from recomposing faster than its zoom label can be read. */
    private var lastZoomReportNanos = 0L

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
    private val predictionHead = PredictionHeadView(context)
    private val dry = DryLayer(context)
    /** Android chooses its own horizon, so an overlong result is clipped locally. */
    private val predictor: MotionEventPredictor
    /** The common one-pen prediction path reuses these instead of feeding the GC every sample. */
    private val predictedPointerProperties = arrayOf(MotionEvent.PointerProperties())
    private val predictedPointerCoordinates = arrayOf(MotionEvent.PointerCoords())
    private val predictionPolicy = InkPredictionPolicy()
    private val streamingStabilizer = AdaptiveStrokeStabilizer()
    private val pressureStabilizer = AdaptivePressureStabilizer()
    private var stabilizingStroke = false
    private var stabilizedPointerProperties = arrayOf(MotionEvent.PointerProperties())
    private var stabilizedPointerCoordinates = arrayOf(MotionEvent.PointerCoords())

    private val undoStack = mutableListOf<Edit>()
    private val redoStack = mutableListOf<Edit>()
    private var nextEditGroup = 1L

    /** Document coordinates -> screen. Its inverse maps touches back. */
    private val documentToScreen = Matrix()
    private val screenToDocument = Matrix()
    private val strokeTransform = Matrix()
    private val matrixValues = FloatArray(9)
    private val pageProbe = FloatArray(2)

    /** Brush construction crosses into native Ink code, so reuse it between strokes. */
    private var cachedBrushTool: Tool? = null
    private var cachedBrushColor = 0
    private var cachedBrushSize = Float.NaN
    private var cachedBrushEpsilon = Float.NaN
    private var cachedBrushPattern = -1
    private var cachedBrush: Brush? = null

    private var activeStylusPointer: Int? = null
    private var activeStrokeId: InProgressStrokeId? = null
    private var activePage: Page? = null
    /** Dispatch of ACTION_UP to the committed stroke becoming visible to the model. */
    @Volatile private var penFinalizeStartedNanos = 0L
    private var lastStylusX = Float.NaN
    private var lastStylusY = Float.NaN
    private var lastStylusDx = 0f
    private var lastStylusDy = 0f
    private var erasing = false
    /** Every removal between pen-down and pen-up is one undoable erase. */
    private var activeEraseGroup = 0L
    private var eraseChanged = false
    private val erasePagePoint = FloatArray(2)
    private var lastEraseX = 0f
    private var lastEraseY = 0f
    private var hasLastErasePoint = false
    private val eraseStrokeHits = ArrayList<Stroke>()
    private val eraseStrokeHitSet: MutableSet<Stroke> =
        Collections.newSetFromMap(IdentityHashMap())
    private val eraseMaskHits = ArrayList<PageMask>()
    private var eraserCursorVisible = false
    private var eraserCursorX = 0f
    private var eraserCursorY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastFingerEventTime = 0L
    private var viewportSpeedPxPerSecond = 0f
    private var viewportWasFast = false
    private var currentPage = 0
    private var lastPageDirection = 1
    private var pdfPriorityInitialized = false
    private var fitted = false
    /** Page to restore once the first real view width has established the scale. */
    private var pageToRestore: Int? = null

    // Finger-gesture bookkeeping: one continuous touch, from the first finger
    // down to the last one up, is one "gesture" - these track it.
    private var gestureMaxPointers = 0
    private var gestureStartTime = 0L
    private var gestureMoved = 0f
    // Three fingers are their own zone; these three track only the sustained
    // drag once a third finger has actually landed, not the whole gesture.
    private var have3Fingers = false
    private var opened3fThisGesture = false
    private var closed3fThisGesture = false
    private var gestureStart3fY = 0f
    private var gestureStart3fX = 0f
    private var gestureStart3fSpread = 0f
    private var prev3fX = 0f
    private var prev3fY = 0f
    private var prev3fSpread = 0f
    // The other half of a double tap: what the last one looked like.
    private var lastTapFingers = 0
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

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
    private val lassoPath = FloatPointBuffer()
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
    private val imagePoint = FloatArray(2)
    private val maskProbe = floatArrayOf(0f, 0f)
    private var pressX = 0f
    private var pressY = 0f
    private val longPress = Runnable { beginTextSelection() }

    /**
     * Ticks once per display frame while diagnostics are visible, so the HUD
     * reports the refresh rate the panel is actually running at rather than the
     * one it is capable of. The two differ, and that difference is the whole
     * reason prediction misjudges its distance on a 60Hz frame.
     */
    private var lastFrameNanos = 0L
    private var frameClockRunning = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!latencyMonitoringEnabled || !isAttachedToWindow) {
                // Stop the diagnostic loop as soon as the HUD is hidden.
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
    private val prefetchRunnable = Runnable {
        prioritizePdf(PREFETCH_STOP_RADIUS)
        prefetchNearbyPages(PREFETCH_STOP_RADIUS)
    }
    @Volatile private var refineRequestSerial = 0
    @Volatile private var viewportRenderGeneration = 0
    private var refineFuture: Future<*>? = null
    private var refiningPages: List<Page> = emptyList()
    @Volatile private var disposed = false

    private sealed interface Edit {
        val page: Page

        // Rebuilding a page's geometry replaces every Stroke instance on it, so
        // history has to be able to follow the swap rather than keep pointing at
        // objects that are no longer in the page.
        class Drawn(override val page: Page, var stroke: Stroke, val group: Long = 0L) : Edit
        class Erased(
            override val page: Page,
            var strokes: List<Stroke>,
            val group: Long = 0L,
        ) : Edit

        // Pictures are mutable and moving one is not undoable; adding and
        // removing are, because those are the ones that lose work.
        class ImageReplaced(override val page: Page, val before: PageImage, val after: PageImage, val at: Int) : Edit
        class ImageAdded(override val page: Page, val image: PageImage) : Edit
        class ImageRemoved(override val page: Page, val image: PageImage, val at: Int) : Edit

        /** A lasso drag: the strokes as they were, and where they ended up. */
        class Moved(
            override val page: Page,
            var before: List<Stroke>,
            var after: List<Stroke>,
        ) : Edit

        class MaskAdded(override val page: Page, val mask: PageMask, val group: Long = 0L) : Edit
        class MaskRemoved(
            override val page: Page,
            val mask: PageMask,
            val at: Int,
            val group: Long = 0L,
        ) : Edit
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                // Clamping the *result* rather than the factor, so a pinch that
                // would overshoot the limit still zooms up to it.
                val minimum = if (minimumScaleIsFitWidth && fitScale > 0f) fitScale else MIN_SCALE
                val factor = (current * detector.scaleFactor)
                    .coerceIn(minimum, maxScale()) / current
                documentToScreen.postScale(factor, factor, detector.focusX, detector.focusY)
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
        addView(predictionHead, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wet.textureBitmapStore = PencilTextureStore
        // InProgressStrokesView chooses its front-buffer implementation for
        // supported hardware. Do not force the deprecated high-latency helper;
        // eager initialization below removes its first-contact setup cost.
        wet.eagerInit()
        wet.addFinishedStrokesListener(this)
        wet.setLatencyDataCallback(object : LatencyDataCallback {
            // Off the UI thread, and the instance is pooled and reset once this
            // returns - read the fields, never keep the object.
            override fun onLatencyData(latencyData: LatencyData) {
                if (!latencyMonitoringEnabled) return
                if (latencyData.eventAction != LatencyData.EventAction.MOVE) return
                if (!latencyData.isOsDetectsEventSet) return
                // Unset fields carry Long.MIN_VALUE, and subtracting a real
                // timestamp from that overflows into a huge positive number -
                // which is how the HUD came to report latencies of 9.2e12ms.
                // This panel never estimates a presentation time, so the span
                // that can actually be measured is the one this app is
                // responsible for: the event arriving to the draw calls for it
                // being finished.
                val end = latencyData.estimatedPixelPresentationTime
                    .takeIf { it != LATENCY_UNSET }
                    ?: latencyData.strokesViewFinishesDrawCalls
                if (end == LATENCY_UNSET) return
                latency.add(end - latencyData.osDetectsEvent)
            }
        })
        // On an adaptive panel the system picks a refresh rate per window from
        // what the content looks like, and a note that is mostly still reads as
        // content that does not need 120Hz. Say otherwise: this view is only
        // still until a pen touches it. Costs nothing while nothing is drawn -
        // the hint applies to frames actually produced.
        predictor = MotionEventPredictor.newInstance(this)
        onTransformChanged()
    }

    fun open(document: Document, pdf: PdfSource?, initialPage: Int = 0) {
        this.pdf?.diagnostics = null
        this.pdf?.close()
        this.document = document
        dry.clearStrokeIndexes()
        pdfPriorityInitialized = false
        document.invalidateLayout()
        for (page in document.pages) {
            page.tessellatedFor = 0f
            page.renderState = RenderState.Dirty
        }
        scheduleRefine()
        this.pdf = pdf
        pdf?.diagnostics = latency
        // Has to be the layer that draws the pages: invalidating this ViewGroup
        // leaves the child's cached display list alone, so nothing repaints.
        pdf?.onReady = { dry.postInvalidate() }
        undoStack.clear()
        redoStack.clear()
        fitted = false
        pageToRestore = initialPage.coerceIn(document.pages.indices)
        requestLayout()
        dry.invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted && w > 0) {
            fitWidth()
            pendingZoom = 0f
            pageToRestore?.let(::scrollToPage)
            pageToRestore = null
            return
        }
        val pending = pendingZoom
        if (pending == 0f || w == 0) return
        pendingZoom = 0f
        if (!pendingFit) {
            // 팝업 폭이 바뀌면 최소 배율의 기준도 새 폭으로 함께 이동합니다.
            fitScale = w * FIT_MARGIN / document.fitWidth()
            zoomBy(pending)
            return
        }
        fitWidth()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopFling(resumeDetail = false)
        removeCallbacks(refineRunnable)
        removeCallbacks(prefetchRunnable)
        disposed = true
        refineRequestSerial++
        viewportRenderGeneration++
        refineFuture?.cancel(true)
        refineFuture = null
        refiningPages = emptyList()
        refiner.shutdownNow()
        predictionHead.clear()
        pdf?.diagnostics = null
        pdf?.close()
        pdf = null
    }

    /**
     * 가장 넓은 페이지의 폭을 뷰의 폭과 정확히 맞춥니다.
     *
     * 세로 위치까지 0으로 초기화하면 맞춤 버튼을 누르는 한 프레임 동안 첫
     * 페이지가 현재 페이지가 되어 버립니다. 현재 페이지의 Y 이동을 새 배율과
     * 같은 행렬에 넣고 한 번만 알리므로 페이지 번호와 저장 위치가 바뀌지 않습니다.
     */
    fun fitWidth() {
        if (width == 0) return
        fitted = true
        val scale = width * FIT_MARGIN / document.fitWidth()
        fitScale = scale
        val here = currentPage.coerceIn(document.pages.indices)
        documentToScreen.reset()
        documentToScreen.postScale(scale, scale)
        documentToScreen.getValues(matrixValues)
        matrixValues[Matrix.MTRANS_X] = -document.leftOf(here) * scale +
            (width - document.pages[here].width * scale) / 2f
        matrixValues[Matrix.MTRANS_Y] = -document.topOf(here) * scale + PAGE_TOP_MARGIN_PX
        documentToScreen.setValues(matrixValues)
        onTransformChanged()
        // Pay the native brush-construction cost now, not on the next pen down.
        prepareBrush()
    }

    /**
     * What to do to the page the next time this view is resized: zoom it by
     * [factor], or fit it to the new width instead.
     *
     * The host resizes this view by changing what it asks for in a layout that
     * has not happened yet, so doing the zoom when the host asks for it does it
     * against the old size - the page is scaled up and then clamped back inside
     * a view that is still small, and the whole thing jumps twice as the layout
     * catches up. That was the lurch when a hand came off a panel spread. Held
     * here instead, and spent at the exact moment the new size arrives.
     */
    fun onNextResize(factor: Float, fitInstead: Boolean) {
        pendingZoom = factor
        pendingFit = fitInstead
    }

    private var pendingZoom = 0f
    private var pendingFit = false

    /** Zooms about the top-left corner, which is the corner a resize keeps. */
    private fun zoomBy(factor: Float) {
        if (factor <= 0f || abs(factor - 1f) < 1e-4f) return
        val current = currentScale()
        val minimum = if (minimumScaleIsFitWidth && fitScale > 0f) fitScale else MIN_SCALE
        val applied = (current * factor).coerceIn(minimum, maxScale()) / current
        documentToScreen.postScale(applied, applied, 0f, 0f)
        onTransformChanged()
    }

    fun scrollToPage(index: Int) {
        if (index !in document.pages.indices) return
        val scale = currentScale()
        documentToScreen.getValues(matrixValues)
        matrixValues[Matrix.MTRANS_X] = -document.leftOf(index) * scale +
            (width - document.pages[index].width * scale) / 2f
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
        clearImageSelection()
        applyInverse(edit)
        redoStack += edit
        val group = edit.groupId()
        if (group != 0L) while (undoStack.lastOrNull()?.groupId() == group) {
            val part = undoStack.removeAt(undoStack.lastIndex)
            applyInverse(part)
            redoStack += part
        }
        afterEdit(edit.page)
    }

    fun redo() {
        val edit = redoStack.removeLastOrNull() ?: return
        clearLassoSelection()
        clearImageSelection()
        val parts = mutableListOf(edit)
        val group = edit.groupId()
        if (group != 0L) while (redoStack.lastOrNull()?.groupId() == group) {
            parts += redoStack.removeAt(redoStack.lastIndex)
        }
        for (part in parts) {
            applyForward(part)
            undoStack += part
        }
        afterEdit(edit.page)
    }

    fun setPageLayout(mode: PageLayoutMode) {
        if (document.layoutMode == mode) return
        document.layoutMode = mode
        fitWidth()
        scrollToPage(currentPage.coerceIn(document.pages.indices))
    }

    private fun Edit.groupId(): Long = when (this) {
        is Edit.Drawn -> group
        is Edit.Erased -> group
        is Edit.MaskAdded -> group
        is Edit.MaskRemoved -> group
        else -> 0L
    }

    private fun applyForward(edit: Edit) {
        when (edit) {
            is Edit.Drawn -> edit.page.strokes += edit.stroke
            is Edit.Erased -> edit.page.strokes.removeAll(edit.strokes)
            is Edit.ImageReplaced -> { edit.page.images[edit.at] = edit.after; clearImageSelection() }
            is Edit.ImageAdded -> edit.page.images += edit.image
            is Edit.ImageRemoved -> edit.page.images.remove(edit.image)
            is Edit.MaskAdded -> edit.page.masks += edit.mask
            is Edit.MaskRemoved -> edit.page.masks.remove(edit.mask)
            is Edit.Moved -> swapStrokes(edit.page, edit.before, edit.after)
        }
    }

    private fun applyInverse(edit: Edit) {
        when (edit) {
            is Edit.Drawn -> edit.page.strokes.remove(edit.stroke)
            is Edit.Erased -> edit.page.strokes += edit.strokes
            is Edit.Moved -> swapStrokes(edit.page, edit.after, edit.before)
            is Edit.MaskAdded -> edit.page.masks.remove(edit.mask)
            is Edit.MaskRemoved ->
                edit.page.masks.add(edit.at.coerceIn(0, edit.page.masks.size), edit.mask)
            is Edit.ImageReplaced -> { edit.page.images[edit.at] = edit.before; clearImageSelection() }
            is Edit.ImageAdded -> edit.page.images.remove(edit.image)
            is Edit.ImageRemoved ->
                edit.page.images.add(edit.at.coerceIn(0, edit.page.images.size), edit.image)
        }
    }

    private fun afterEdit(vararg changed: Page) {
        document.markEdited()
        for (page in changed) {
            page.dirty = true
            page.revision++
            page.renderState = RenderState.Dirty
        }
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
            templateId = template?.templateId,
        )
        document.pages.add((after + 1).coerceIn(0, document.pages.size), page)
        document.invalidateLayout()
        afterEdit(page)
    }

    fun deletePage(index: Int) {
        if (document.pages.size <= 1 || index !in document.pages.indices) return
        val removed = document.pages.removeAt(index)
        dry.dropStrokeIndex(removed)
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
        page.height = if (background == PageBackground.INFINITE) Page.A4_HEIGHT * 12f
            else if (page.height > Page.A4_HEIGHT * 2f) Page.A4_HEIGHT else page.height
        if (background != PageBackground.CUSTOM) page.templateId = null
        document.invalidateLayout()
        afterEdit()
    }

    fun movePage(index: Int, delta: Int) {
        val to = index + delta
        if (index !in document.pages.indices || to !in document.pages.indices) return
        val page = document.pages.removeAt(index)
        document.pages.add(to, page)
        document.invalidateLayout()
        currentPage = to
        afterEdit()
        scrollToPage(to)
    }

    fun setPageTemplate(index: Int, templateId: String) {
        val page = document.pages.getOrNull(index) ?: return
        if (page.background == PageBackground.PDF) return
        page.background = PageBackground.CUSTOM
        page.templateId = templateId
        afterEdit(page)
    }

    fun currentPageIndex(): Int = currentPage

    fun strokeCount(): Int = document.pages.sumOf { it.strokes.size }

    fun debugPerformanceReport(): String {
        if (latency.enabled) {
            val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
            val collections = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
            latency.addRuntimeSample(allocated, collections, System.nanoTime())
        }
        return latency.render(strokeCount())
    }

    fun currentScale(): Float {
        documentToScreen.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun onTransformChanged() {
        viewportRenderGeneration++
        clampTransform()
        documentToScreen.invert(screenToDocument)
        // Input can arrive several times inside one display interval. Ask for
        // one paint on the next vsync instead of repeatedly invalidating now.
        dry.postInvalidateOnAnimation()
        updateCurrentPage()
        if (!pdfPriorityInitialized && pdf != null) {
            pdfPriorityInitialized = true
            prioritizePdf(0)
        }
        // A pinch is not a stop. Replacing these delayed jobs for every touch
        // sample used to add queue churn precisely while zoom frames were due.
        if (!zooming) {
            scheduleStoppedPrefetch()
        }
        reportZoom()
        // Only once the zoom settles - rebuilding on every pinch frame would
        // cost far more than it buys.
        if (!holdingDetail()) scheduleRefine()
    }

    private fun reportZoom(force: Boolean = false) {
        if (fitScale <= 0f) return
        val zoom = currentScale() / fitScale
        // The canvas remains fully event-driven. Only the toolbar label is
        // capped at 30Hz during a pinch, avoiding a full Compose state update
        // for 120-240Hz touch streams.
        val now = System.nanoTime()
        if (!force && zooming && now - lastZoomReportNanos < ZOOM_REPORT_INTERVAL_NS) return
        // A percent point of slack: the fling settles by thousandths and there
        // is no reading to be had from those.
        if (force || abs(zoom - reportedZoom) > 0.005f) {
            reportedZoom = zoom
            lastZoomReportNanos = now
            onZoomChanged?.invoke(zoom)
        }
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
        pageProbe[0] = width / 2f
        pageProbe[1] = height / 2f
        screenToDocument.mapPoints(pageProbe)
        val index = document.pageAt(pageProbe[0], pageProbe[1]).takeIf { it >= 0 }
            ?: document.nearestPage(pageProbe[0], pageProbe[1]).coerceAtLeast(0)
        if (index != currentPage) {
            lastPageDirection = if (index > currentPage) 1 else -1
            currentPage = index
            val radius = if (flinging || viewportSpeedPxPerSecond > FAST_VIEWPORT_PX_PER_SECOND) {
                0
            } else {
                PREFETCH_SLOW_RADIUS
            }
            prioritizePdf(radius)
            onCurrentPageChanged?.invoke(index)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val stylus = event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS
        if (!stylus && activeStylusPointer == null) return onFingers(event)
        if (!latencyMonitoringEnabled) return onStylus(event)
        Trace.beginSection(when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> TRACE_STYLUS_DOWN
            MotionEvent.ACTION_MOVE -> TRACE_STYLUS_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> TRACE_STYLUS_UP
            else -> TRACE_STYLUS_OTHER
        })
        return try { onStylus(event) } finally { Trace.endSection() }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val stylus = event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS
        if (!stylus) return super.onHoverEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val show = tool == Tool.ERASER || event.isEraserGesture()
                val changed = show != eraserCursorVisible ||
                    (show && hypot(event.x - eraserCursorX, event.y - eraserCursorY) >= 0.5f)
                eraserCursorVisible = show
                if (show) {
                    eraserCursorX = event.x
                    eraserCursorY = event.y
                }
                if (changed) dry.postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                val changed = eraserCursorVisible
                eraserCursorVisible = false
                if (changed) dry.postInvalidateOnAnimation()
            }
        }
        return true
    }

    private fun onStylus(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeStylusPointer != null) return true
                penFinalizeStartedNanos = 0L
                // A pen may land while a finger fling still owns an animation
                // frame. Freeze the page before capturing screenToPage; if the
                // page moves afterwards, wet ink and the physical tip no longer
                // share the same coordinate system for the rest of the stroke.
                stopFling(resumeDetail = false)
                releaseVelocity()
                zooming = false
                // Unbuffered dispatch is what gets the S Pen's full sample rate
                // instead of one point per display frame.
                requestUnbufferedDispatch(event)
                val index = pageUnder(event, event.actionIndex)
                if (index < 0) return true
                val pointerId = event.getPointerId(event.actionIndex)
                activeStylusPointer = pointerId
                if (Build.VERSION.SDK_INT >= 35) {
                    requestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_HIGH
                }
                // Built per gesture: the Kalman filter is tied to one pointer on
                // one device, and a new stroke is a new pointer.
                if (latencyMonitoringEnabled && !frameClockRunning) {
                    frameClockRunning = true
                    lastFrameNanos = 0L
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }
                activePage = document.pages[index]
                val eraserGesture = event.isEraserGesture()
                if (captureMode && !eraserGesture) {
                    onDrawingChanged?.invoke(true)
                    capturePage = index
                    capturing = true
                    pageLocalInto(event.x, event.y, index, shapeStart)
                    captureRect.set(shapeStart[0], shapeStart[1], shapeStart[0], shapeStart[1])
                    dry.invalidate()
                    return true
                }
                if (lassoMode && !eraserGesture) {
                    onDrawingChanged?.invoke(true)
                    beginLasso(event, index)
                    return true
                }
                if (imageMode && !eraserGesture) {
                    onDrawingChanged?.invoke(true)
                    beginImageGesture(event, index)
                    return true
                }
                val shape = shapeKind
                if (shape != null && !readMode && !eraserGesture &&
                    tool != Tool.ERASER
                ) {
                    onDrawingChanged?.invoke(true)
                    shapePage = index
                    drawingShape = true
                    pageLocalInto(event.x, event.y, index, shapeStart)
                    shapeEnd[0] = shapeStart[0]
                    shapeEnd[1] = shapeStart[1]
                    dry.invalidate()
                    return true
                }
                if (readMode && !eraserGesture) {
                    onDrawingChanged?.invoke(true)
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
                erasing = tool == Tool.ERASER || eraserGesture
                if (erasing) {
                    onDrawingChanged?.invoke(true)
                    hasLastErasePoint = false
                    activeEraseGroup = nextEditGroup++
                    eraseChanged = false
                    eraseAlong(event, event.actionIndex, force = true)
                } else {
                    lastStylusX = event.x
                    lastStylusY = event.y
                    lastStylusDx = 0f
                    lastStylusDy = 0f
                    stabilizingStroke = tool.isFreehandPen() && stabilizer > 0
                    streamingStabilizer.reset(stabilizer, event.getX(event.actionIndex),
                        event.getY(event.actionIndex), event.eventTime)
                    pressureStabilizer.reset(
                        stabilizer,
                        event.getPressure(event.actionIndex),
                        event.eventTime,
                    )
                    predictionPolicy.reset(
                        event.getX(event.actionIndex),
                        event.getY(event.actionIndex),
                        event.eventTime,
                    )
                    predictionHead.clear()
                    // MotionEventPredictor is useful only for wet freehand ink.
                    // Recording eraser/lasso/shape gestures ran its filter at
                    // full S Pen rate even though those tools never call predict().
                    if (predictionEnabled) predictor.record(event)
                    activeStrokeId = wet.startStroke(
                        event = event,
                        pointerId = pointerId,
                        brush = currentBrush(),
                        // "World" here is the page, so the finished stroke comes
                        // back in page-local coordinates and stays with its page.
                        motionEventToWorldTransform = screenToPage(index),
                    )
                    // Put the first wet-ink mark on the front buffer before a
                    // Compose state write freezes the toolbar backdrop.
                    onDrawingChanged?.invoke(true)
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
                    dry.postInvalidateOnAnimation()
                    return true
                }
                if (movingLasso) {
                    pageLocalInto(event.getX(index), event.getY(index), lassoPage, shapeEnd)
                    lassoDx = shapeEnd[0] - lassoGrab[0]
                    lassoDy = shapeEnd[1] - lassoGrab[1]
                    dry.postInvalidateOnAnimation()
                    return true
                }
                if (drawingLasso) {
                    appendLassoSamples(event, index)
                    return true
                }
                if (drawingShape) {
                    pageLocalInto(event.getX(index), event.getY(index), shapePage, shapeEnd)
                    dry.postInvalidateOnAnimation()
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
                if (latencyMonitoringEnabled) latency.addSamples(1 + event.historySize)
                val inkEvent = if (stabilizingStroke) {
                    stabilizedMoveEvent(event, pointerId)
                } else event
                val sharpTurn = if (stabilizingStroke) {
                    false
                } else {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    val dx = if (lastStylusX.isFinite()) x - lastStylusX else 0f
                    val dy = if (lastStylusY.isFinite()) y - lastStylusY else 0f
                    val turn = shouldSuppressPrediction(lastStylusDx, lastStylusDy, dx, dy)
                    if (hypot(dx, dy) >= 0.5f) {
                        lastStylusDx = dx
                        lastStylusDy = dy
                        lastStylusX = x
                        lastStylusY = y
                    }
                    turn
                }
                val maximumLeadMs = predictionLeadForRefresh(
                    predictionLeadMs,
                    display?.refreshRate ?: 60f,
                )
                var leadMs = predictionLeadForEvent(inkEvent, pointerId, maximumLeadMs)
                if (sharpTurn || (stabilizingStroke && !streamingStabilizer.predictionAllowed)) {
                    leadMs = 0
                }
                if (predictionEnabled) predictor.record(inkEvent)
                val rawPrediction = if (predictionEnabled && leadMs > 0) predictor.predict() else null
                val predicted = rawPrediction?.let { predictionAtLead(inkEvent, it, leadMs.toLong()) }
                try {
                    if (latencyMonitoringEnabled && predicted != null) {
                        // Ground truth for the tip's steadiness: the distance
                        // the prediction reaches past the newest real sample.
                        latency.addPredictionLead(
                            (predicted.eventTime - event.eventTime).toDouble(),
                        )
                    }
                    wet.addToStroke(inkEvent, pointerId, strokeId, predicted)
                    updatePredictionHead(inkEvent, predicted, pointerId, leadMs)
                } finally {
                    if (predicted !== rawPrediction) predicted?.recycle()
                    rawPrediction?.recycle()
                    if (inkEvent !== event) inkEvent.recycle()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = activeStylusPointer ?: return false
                // A palm/finger lifting must not finish the stylus stroke.
                if (event.getPointerId(event.actionIndex) != pointerId) return true
                predictionHead.clear()
                pendingTextPlacement?.let { position ->
                    endStylus()
                    onTextRequested?.invoke(position.first, position.second, position.third)
                    return true
                }
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
                if (erasing) {
                    eraseAlong(event, event.actionIndex, force = true)
                    finishEraseGesture()
                    endStylus()
                    return true
                }
                activeStrokeId?.let { strokeId ->
                    penFinalizeStartedNanos = if (latency.enabled) System.nanoTime() else 0L
                    val pointerIndex = event.findPointerIndex(pointerId)
                    val penStroke = tool.isFreehandPen()
                    if (stabilizingStroke) {
                        stabilizedHistoryEvent(event, pointerId, endingStroke = true)?.let { history ->
                            try {
                                if (predictionEnabled) predictor.record(history)
                                wet.addToStroke(history, pointerId, strokeId, null)
                            } finally { history.recycle() }
                        }
                    }
                    val unstable = penStroke && pointerIndex >= 0 && if (stabilizingStroke) {
                        streamingStabilizer.shouldRejectLift(
                            event.getX(pointerIndex), event.getY(pointerIndex))
                    } else {
                        lastStylusX.isFinite() && unstableLift(
                            event.getX(pointerIndex) - lastStylusX,
                            event.getY(pointerIndex) - lastStylusY, lastStylusDx, lastStylusDy)
                    }
                    val end = when {
                        stabilizingStroke -> stabilizedFinalEvent(
                            event,
                            pointerId,
                            rejectPosition = unstable,
                        )
                        unstable -> eventWithActivePoint(event, pointerId, lastStylusX, lastStylusY)
                        else -> event
                    }
                    try {
                        if (predictionEnabled) predictor.record(end)
                        wet.finishStroke(end, pointerId, strokeId)
                    } finally {
                        if (end !== event) end.recycle()
                    }
                }
                endStylus()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                predictionHead.clear()
                if (predictionEnabled && activeStrokeId != null) predictor.record(event)
                activeStrokeId?.let { wet.cancelStroke(it, event) }
                if (erasing) finishEraseGesture()
                endStylus()
                return true
            }
        }
        return false
    }

    private fun ensureStabilizedPointerCapacity(count: Int) {
        if (stabilizedPointerProperties.size >= count) return
        stabilizedPointerProperties = Array(count) { MotionEvent.PointerProperties() }
        stabilizedPointerCoordinates = Array(count) { MotionEvent.PointerCoords() }
    }

    private fun eventWithActivePoint(
        event: MotionEvent,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float? = null,
    ): MotionEvent {
        ensureStabilizedPointerCapacity(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            event.getPointerProperties(i, stabilizedPointerProperties[i])
            event.getPointerCoords(i, stabilizedPointerCoordinates[i])
            if (event.getPointerId(i) == pointerId) {
                stabilizedPointerCoordinates[i].x = x
                stabilizedPointerCoordinates[i].y = y
                if (pressure != null) stabilizedPointerCoordinates[i].pressure = pressure
            }
        }
        return MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            event.action,
            event.pointerCount,
            stabilizedPointerProperties,
            stabilizedPointerCoordinates,
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            event.source,
            event.flags,
        )
    }

    /** Rebuilds one MOVE while preserving every historical S Pen sample. */
    private fun stabilizedMoveEvent(event: MotionEvent, pointerId: Int): MotionEvent {
        val activeIndex = event.findPointerIndex(pointerId)
        if (activeIndex < 0) return MotionEvent.obtain(event)
        val result = stabilizedHistoryEvent(event, pointerId)
        if (result == null) {
            streamingStabilizer.add(
                event.getX(activeIndex), event.getY(activeIndex), event.eventTime)
            val pressure = pressureStabilizer.add(
                event.getPressure(activeIndex), event.eventTime)
            return eventWithActivePoint(
                event, pointerId, streamingStabilizer.x, streamingStabilizer.y, pressure)
        }

        for (i in 0 until event.pointerCount) {
            event.getPointerCoords(i, stabilizedPointerCoordinates[i])
        }
        streamingStabilizer.add(event.getX(activeIndex), event.getY(activeIndex), event.eventTime)
        val pressure = pressureStabilizer.add(
            event.getPressure(activeIndex), event.eventTime)
        stabilizedPointerCoordinates[activeIndex].x = streamingStabilizer.x
        stabilizedPointerCoordinates[activeIndex].y = streamingStabilizer.y
        stabilizedPointerCoordinates[activeIndex].pressure = pressure
        result.addBatch(event.eventTime, stabilizedPointerCoordinates, event.metaState)
        return result
    }

    /** Converts only history; ACTION_UP's current sample is finalized separately. */
    private fun stabilizedHistoryEvent(
        event: MotionEvent,
        pointerId: Int,
        endingStroke: Boolean = false,
    ): MotionEvent? {
        if (event.historySize == 0) return null
        val activeIndex = event.findPointerIndex(pointerId)
        if (activeIndex < 0) return null
        ensureStabilizedPointerCapacity(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            event.getPointerProperties(i, stabilizedPointerProperties[i])
            event.getHistoricalPointerCoords(i, 0, stabilizedPointerCoordinates[i])
        }
        val firstX = event.getHistoricalX(activeIndex, 0)
        val firstY = event.getHistoricalY(activeIndex, 0)
        val firstTime = event.getHistoricalEventTime(0)
        val rejectFirst = endingStroke && streamingStabilizer.shouldRejectLift(firstX, firstY)
        if (!rejectFirst) {
            streamingStabilizer.add(
                firstX,
                firstY,
                firstTime,
                finalSample = endingStroke && event.historySize == 1,
            )
        }
        val firstPressure = pressureStabilizer.add(
            event.getHistoricalPressure(activeIndex, 0),
            firstTime,
            finalSample = endingStroke && event.historySize == 1,
        )
        stabilizedPointerCoordinates[activeIndex].x = streamingStabilizer.x
        stabilizedPointerCoordinates[activeIndex].y = streamingStabilizer.y
        stabilizedPointerCoordinates[activeIndex].pressure = firstPressure
        val result = MotionEvent.obtain(
            event.downTime,
            event.getHistoricalEventTime(0),
            MotionEvent.ACTION_MOVE,
            event.pointerCount,
            stabilizedPointerProperties,
            stabilizedPointerCoordinates,
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            event.source,
            event.flags,
        )
        for (history in 1 until event.historySize) {
            for (i in 0 until event.pointerCount) {
                event.getHistoricalPointerCoords(i, history, stabilizedPointerCoordinates[i])
            }
            val rawX = event.getHistoricalX(activeIndex, history)
            val rawY = event.getHistoricalY(activeIndex, history)
            val time = event.getHistoricalEventTime(history)
            val reject = endingStroke && streamingStabilizer.shouldRejectLift(rawX, rawY)
            if (!reject) {
                streamingStabilizer.add(
                    rawX,
                    rawY,
                    time,
                    finalSample = endingStroke && history == event.historySize - 1,
                )
            }
            val pressure = pressureStabilizer.add(
                event.getHistoricalPressure(activeIndex, history),
                time,
                finalSample = endingStroke && history == event.historySize - 1,
            )
            stabilizedPointerCoordinates[activeIndex].x = streamingStabilizer.x
            stabilizedPointerCoordinates[activeIndex].y = streamingStabilizer.y
            stabilizedPointerCoordinates[activeIndex].pressure = pressure
            result.addBatch(
                time, stabilizedPointerCoordinates, event.metaState)
        }
        return result
    }

    private fun stabilizedFinalEvent(
        event: MotionEvent,
        pointerId: Int,
        rejectPosition: Boolean,
    ): MotionEvent {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return MotionEvent.obtainNoHistory(event)
        if (!rejectPosition) {
            streamingStabilizer.add(
                event.getX(index), event.getY(index), event.eventTime, finalSample = true)
        }
        val pressure = pressureStabilizer.add(
            event.getPressure(index), event.eventTime, finalSample = true)
        return eventWithActivePoint(
            event,
            pointerId,
            streamingStabilizer.x,
            streamingStabilizer.y,
            pressure,
        )
    }

    /** Feeds every real historical sample to the common raw/stabilized gate. */
    private fun predictionLeadForEvent(
        event: MotionEvent,
        pointerId: Int,
        maximumLeadMs: Int,
    ): Int {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return 0
        for (history in 0 until event.historySize) {
            predictionPolicy.add(
                event.getHistoricalX(index, history),
                event.getHistoricalY(index, history),
                event.getHistoricalEventTime(history),
                maximumLeadMs,
            )
        }
        return predictionPolicy.add(
            event.getX(index),
            event.getY(index),
            event.eventTime,
            maximumLeadMs,
        )
    }

    private fun updatePredictionHead(
        real: MotionEvent,
        predicted: MotionEvent?,
        pointerId: Int,
        leadMs: Int,
    ) {
        if (predicted == null || !predictionPolicy.shouldShowSoftHead(leadMs) ||
            !tool.isFreehandPen() || dottedPattern != 0
        ) {
            predictionHead.clear()
            return
        }
        val realIndex = real.findPointerIndex(pointerId)
        val predictedIndex = predicted.findPointerIndex(pointerId)
        if (realIndex < 0 || predictedIndex < 0) {
            predictionHead.clear()
            return
        }
        val pressureScale = if (tool == Tool.PRESSURE_PEN) {
            0.35f + real.getPressure(realIndex).coerceIn(0f, 1f) * 0.65f
        } else 1f
        predictionHead.show(
            real.getX(realIndex),
            real.getY(realIndex),
            predicted.getX(predictedIndex),
            predicted.getY(predictedIndex),
            colorArgb,
            strokeWidth * currentScale() * pressureScale,
        )
    }

    /**
     * MotionEventPredictor has no horizon argument. Keep its filtered direction,
     * but interpolate an overlong result back to the selected adaptive lead.
     */
    private fun predictionAtLead(
        real: MotionEvent,
        predicted: MotionEvent,
        leadMs: Long,
    ): MotionEvent {
        val fraction = predictionLeadFraction(real.eventTime, predicted.eventTime, leadMs)
        if (fraction >= 1f) return predicted
        val properties = if (predicted.pointerCount == 1) {
            predictedPointerProperties
        } else {
            Array(predicted.pointerCount) { MotionEvent.PointerProperties() }
        }
        val coordinates = if (predicted.pointerCount == 1) {
            predictedPointerCoordinates
        } else {
            Array(predicted.pointerCount) { MotionEvent.PointerCoords() }
        }
        for (i in properties.indices) {
            predicted.getPointerProperties(i, properties[i])
            predicted.getPointerCoords(i, coordinates[i])
            val realIndex = real.findPointerIndex(properties[i].id)
            if (realIndex < 0) continue
            val realX = real.getX(realIndex)
            val realY = real.getY(realIndex)
            coordinates[i].x = realX + (coordinates[i].x - realX) * fraction
            coordinates[i].y = realY + (coordinates[i].y - realY) * fraction
        }
        return MotionEvent.obtain(
            real.downTime,
            real.eventTime + leadMs,
            MotionEvent.ACTION_MOVE,
            predicted.pointerCount,
            properties,
            coordinates,
            predicted.metaState,
            predicted.buttonState,
            predicted.xPrecision,
            predicted.yPrecision,
            predicted.deviceId,
            predicted.edgeFlags,
            predicted.source,
            predicted.flags,
        )
    }

    private fun endStylus() {
        pendingTextPlacement = null
        removeCallbacks(longPress)
        selectingText = false
        if (activeStylusPointer != null) onDrawingChanged?.invoke(false)
        predictionHead.clear()
        activeStylusPointer = null
        if (Build.VERSION.SDK_INT >= 35) {
            // Let an idle note fall back to the display's normal adaptive rate.
            requestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
        }
        activeStrokeId = null
        stabilizingStroke = false
        lastStylusX = Float.NaN
        lastStylusY = Float.NaN
        lastStylusDx = 0f
        lastStylusDy = 0f
        erasing = false
        eraseChanged = false
        activeEraseGroup = 0L
        hasLastErasePoint = false
        eraserCursorVisible = false
        dry.postInvalidateOnAnimation()
    }

    private fun onFingers(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            suppressScaleUntilGestureEnd = false
        }
        if (event.pointerCount >= 3 && !suppressScaleUntilGestureEnd) {
            // ScaleGestureDetector otherwise zooms the page with its first two
            // pointers while all three are resizing the popup at the same time.
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            try {
                scaleDetector.onTouchEvent(cancel)
            } finally {
                cancel.recycle()
            }
            suppressScaleUntilGestureEnd = true
        } else if (!suppressScaleUntilGestureEnd) {
            scaleDetector.onTouchEvent(event)
        }
        trackVelocity(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling(resumeDetail = false)
                viewportInteracting = true
                // A delayed idle refine may still be waiting from the previous
                // viewport. Cancel it as soon as a new interaction begins.
                scheduleRefine()
                onViewportInteractionChanged?.invoke(true)
                val focus = focusOf(event, skipPointerIndex = -1)
                lastFocusX = focus[0]
                lastFocusY = focus[1]
                lastFingerEventTime = event.eventTime
                viewportSpeedPxPerSecond = 0f
                viewportWasFast = false
                gestureMaxPointers = 1
                gestureStartTime = System.currentTimeMillis()
                gestureMoved = 0f
                have3Fingers = false
                opened3fThisGesture = false
                closed3fThisGesture = false
                zooming = false
                draggedReference = false
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                stopFling(resumeDetail = false)
                gestureMaxPointers = maxOf(gestureMaxPointers, event.pointerCount)
                // A second finger is the start of a pinch, and from here until
                // the hand lifts the page is drawn from what is already in hand.
                if (event.pointerCount >= 2) zooming = true
                // The centroid jumps when a finger joins or leaves, so re-anchor
                // instead of translating by that jump - and don't count the jump
                // itself as movement for the tap check below.
                val focus = focusOf(event, skipPointerIndex = leavingIndex(event))
                lastFocusX = focus[0]
                lastFocusY = focus[1]
                if (event.pointerCount < 3) have3Fingers = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                gestureMaxPointers = maxOf(gestureMaxPointers, event.pointerCount)
                val focus = focusOf(event, skipPointerIndex = -1)
                val moved = hypot(focus[0] - lastFocusX, focus[1] - lastFocusY)
                gestureMoved += moved
                val elapsed = (event.eventTime - lastFingerEventTime).coerceAtLeast(1L)
                val wasFast = viewportWasFast
                viewportSpeedPxPerSecond = moved * 1000f / elapsed
                viewportWasFast = viewportSpeedPxPerSecond > FAST_VIEWPORT_PX_PER_SECOND
                lastFingerEventTime = event.eventTime
                if (wasFast && !viewportWasFast) prioritizePdf(PREFETCH_SLOW_RADIUS)

                // Three fingers are a zone the page itself never used, so they
                // never pan or zoom it - they only ever move the reference
                // panel, or, held still, redo.
                if (event.pointerCount >= 3) {
                    val spread = averageSpread(event)
                    if (!have3Fingers) {
                        have3Fingers = true
                        gestureStart3fX = focus[0]
                        gestureStart3fY = focus[1]
                        gestureStart3fSpread = spread
                    } else if (referenceOpen && onReferenceDrag != null) {
                        val totalX = focus[0] - gestureStart3fX
                        val totalY = focus[1] - gestureStart3fY
                        val factor = if (prev3fSpread > MIN_SPREAD_PX) {
                            spread / prev3fSpread
                        } else {
                            1f
                        }
                        val totalSpreadFactor = if (gestureStart3fSpread > MIN_SPREAD_PX) {
                            spread / gestureStart3fSpread
                        } else {
                            1f
                        }
                        val isResizing = kotlin.math.abs(totalSpreadFactor - 1f) > 0.06f
                        val tracksDismissDirection = closeReferenceOnDownwardDrag &&
                            !isResizing && totalY > 0f &&
                            totalY > kotlin.math.abs(totalX) * 1.15f
                        val isDownwardDismiss = tracksDismissDirection && totalY > OPEN_DRAG_PX
                        if (isDownwardDismiss && !closed3fThisGesture) {
                            onCloseReference?.invoke()
                            closed3fThisGesture = true
                        } else if (!closed3fThisGesture && !tracksDismissDirection) {
                            onReferenceDrag?.invoke(
                                focus[0] - prev3fX,
                                focus[1] - prev3fY,
                                factor,
                            )
                            draggedReference = true
                        }
                    } else if (referenceOpen) {
                        // On the page rather than on the panel, and the panel is
                        // already open: the same drag that opened it, the other
                        // way up, puts it away. Nothing else here has a use for
                        // three fingers going down.
                        if (!closed3fThisGesture && focus[1] - gestureStart3fY > OPEN_DRAG_PX) {
                            onCloseReference?.invoke()
                            closed3fThisGesture = true
                        }
                    } else if (!opened3fThisGesture && gestureStart3fY - focus[1] > OPEN_DRAG_PX) {
                        onOpenReference?.invoke(focus[0], focus[1])
                        opened3fThisGesture = true
                    }
                    prev3fX = focus[0]
                    prev3fY = focus[1]
                    prev3fSpread = spread
                    lastFocusX = focus[0]
                    lastFocusY = focus[1]
                    return true
                }

                if (!scaleDetector.isInProgress || event.pointerCount > 1) {
                    val multiplier = if (event.pointerCount == 1) {
                        viewportPanMultiplier.coerceIn(0.5f, 3f)
                    } else {
                        1f
                    }
                    documentToScreen.postTranslate(
                        (focus[0] - lastFocusX) * multiplier,
                        (focus[1] - lastFocusY) * multiplier,
                    )
                    onTransformChanged()
                }
                lastFocusX = focus[0]
                lastFocusY = focus[1]
                return true
            }

            MotionEvent.ACTION_UP -> {
                val endedZoom = zooming
                startFling()
                viewportInteracting = false
                viewportSpeedPxPerSecond = 0f
                viewportWasFast = false
                releaseVelocity()
                maybeHandleTap()
                endZoom()
                if (!endedZoom && !flinging) resumeViewportDetail()
                // A real fling keeps moving the page after the hand lifts, so
                // keep the expensive live glass recording paused until it ends.
                if (!flinging) onViewportInteractionChanged?.invoke(false)
                suppressScaleUntilGestureEnd = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                val endedZoom = zooming
                viewportInteracting = false
                viewportSpeedPxPerSecond = 0f
                viewportWasFast = false
                releaseVelocity()
                endZoom()
                if (!endedZoom) resumeViewportDetail()
                onViewportInteractionChanged?.invoke(false)
                suppressScaleUntilGestureEnd = false
                return true
            }
        }
        return true
    }

    /**
     * The hand has lifted: bring the page up to the size it is now drawn at.
     *
     * One repaint, which is what asks the PDF for the width and the tiles it
     * now needs, and one refine, which rebuilds the stroke meshes at the
     * fidelity the new zoom deserves. Everything that was skipped for the
     * length of the pinch happens here, once.
     */
    private fun endZoom() {
        if (draggedReference) {
            draggedReference = false
            onReferenceDragEnd?.invoke()
        }
        if (!zooming) return
        zooming = false
        // Run the work suppressed during the gesture once, from its final
        // viewport and scale, and publish the exact final toolbar value. A
        // fling remains interactive until its last animation frame.
        reportZoom(force = true)
        if (!flinging) resumeViewportDetail()
    }

    private fun resumeViewportDetail() {
        if (disposed) return
        dry.postInvalidateOnAnimation()
        scheduleStoppedPrefetch()
        scheduleRefine()
    }

    /**
     * Undo and redo: two or three fingers, tapped twice quickly in about the
     * same place. Judged once the last finger lifts, against how many fingers
     * were down at the gesture's widest - not how many are left by then.
     */
    private fun maybeHandleTap() {
        val duration = System.currentTimeMillis() - gestureStartTime
        if (gestureMaxPointers !in 2..3 || duration >= TAP_MAX_MS || gestureMoved >= TAP_SLOP_PX) {
            return
        }
        val now = System.currentTimeMillis()
        val sameSpot = hypot(lastFocusX - lastTapX, lastFocusY - lastTapY) < DOUBLE_TAP_SLOP_PX
        if (lastTapFingers == gestureMaxPointers && now - lastTapTime < DOUBLE_TAP_MS && sameSpot) {
            when (gestureMaxPointers) {
                2 -> onUndo?.invoke()
                3 -> onRedo?.invoke()
            }
            lastTapFingers = 0
        } else {
            lastTapFingers = gestureMaxPointers
            lastTapTime = now
            lastTapX = lastFocusX
            lastTapY = lastFocusY
        }
    }

    /** Every pair of fingers' distance, averaged - one number for how spread the hand is. */
    private fun averageSpread(event: MotionEvent): Float {
        val n = event.pointerCount
        if (n < 2) return 0f
        var total = 0f
        var pairs = 0
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                total += hypot(event.getX(i) - event.getX(j), event.getY(i) - event.getY(j))
                pairs++
            }
        }
        return if (pairs > 0) total / pairs else 0f
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
        val multiplier = viewportPanMultiplier.coerceIn(0.5f, 3f)
        val vx = tracker.xVelocity * multiplier
        val vy = tracker.yVelocity * multiplier
        if (hypot(vx, vy) < MIN_FLING_VELOCITY) return
        flingVx = vx
        flingVy = vy
        flingLastNanos = System.nanoTime()
        if (!flinging) {
            flinging = true
            postOnAnimation(flingStep)
        }
    }

    private fun stopFling(resumeDetail: Boolean = true) {
        val wasFlinging = flinging
        flinging = false
        removeCallbacks(flingStep)
        onViewportInteractionChanged?.invoke(false)
        if (wasFlinging && resumeDetail && !viewportInteracting) resumeViewportDetail()
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
        val epsilon = strokeEpsilon(strokeWidth, epsilonFor(currentScale()))
        val brushTool = if (tool.isFreehandPen()) {
            when (dottedPattern) {
                1 -> Tool.DOTTED
                2 -> Tool.DASHED
                3 -> Tool.DASH_DOT
                else -> tool
            }
        } else tool
        cachedBrush?.takeIf {
            cachedBrushTool == brushTool &&
                cachedBrushColor == colorArgb &&
                cachedBrushSize == strokeWidth &&
                cachedBrushEpsilon == epsilon &&
                cachedBrushPattern == dottedPattern
        }?.let { return it }
        return Brush.createWithColorIntArgb(
            family = brushTool.brushFamily(),
            // Alpha included: a highlighter is a saved pen that happens to be
            // translucent and wide, not a tool with its own hidden rules.
            colorIntArgb = colorArgb,
            // Sizes are page units, so a stroke keeps its size on the page and
            // zooming magnifies it like everything else on the paper.
            size = strokeWidth,
            // Drawn at the zoom in use, so a stroke made while zoomed in is
            // already fine enough and never needs rebuilding.
            epsilon = epsilon,
        ).also {
            cachedBrushTool = brushTool
            cachedBrushColor = colorArgb
            cachedBrushSize = strokeWidth
            cachedBrushEpsilon = epsilon
            cachedBrushPattern = dottedPattern
            cachedBrush = it
        }
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
        if (holdingDetail()) return
        val target = tessellationBucket(currentScale())
        val epsilon = epsilonFor(target)
        val serial = refineRequestSerial

        releaseDistantPages()
        val visible = visiblePages()
        val toLoad = visible.filter { !it.loaded }

        // At deep zoom only a fraction of a page is visible. Rebuilding the
        // whole page there produced enormous meshes that were immediately off
        // screen. Refine the viewport plus a small pan margin; newly exposed
        // regions are picked up by the same debounced path.
        val viewport = FloatArray(4)
        dry.visibleDocumentBounds(viewport)
        val margin = REFINE_MARGIN_PX / currentScale().coerceAtLeast(0.01f)
        val work = visible.mapIndexedNotNull { _, page ->
            if (!page.loaded || page.strokes.isEmpty()) return@mapIndexedNotNull null
            val index = document.pages.indexOf(page)
            if (index < 0) return@mapIndexedNotNull null
            val left = viewport[0] - document.leftOf(index) - margin
            val top = viewport[1] - document.topOf(index) - margin
            val right = viewport[2] - document.leftOf(index) + margin
            val bottom = viewport[3] - document.topOf(index) + margin
            val strokes = dry.strokesIn(page, left, top, right, bottom).filter { stroke ->
                val wanted = strokeEpsilon(stroke.brush.size, epsilon)
                abs(stroke.brush.epsilon - wanted) >= EPSILON_SLOP
            }
            (page to strokes).takeIf { strokes.isNotEmpty() }
        }
        if (toLoad.isEmpty() && work.isEmpty()) {
            for (page in visible) {
                page.renderState = completedRenderState(
                    page.loaded,
                    page.strokes.isEmpty() || abs(page.tessellatedFor - target) < EPSILON_SLOP,
                )
            }
            return
        }

        val loader = pageLoader
        val pages = (toLoad + work.map { it.first }).distinct()
        pages.forEach { it.renderState = RenderState.Rendering }
        refiningPages = pages
        val future = runCatching { refiner.submit {
            fun obsolete(): Boolean = disposed || serial != refineRequestSerial ||
                Thread.currentThread().isInterrupted
            if (obsolete()) return@submit
            val refineStarted = if (latency.enabled) System.nanoTime() else 0L
            if (refineStarted != 0L) Trace.beginSection(TRACE_MESH_REFINE)
            val masksLoader = maskLoader
            // Loading a full dense page at maximum zoom creates maximum-detail
            // geometry for ink that may be far off screen. Establish a modest,
            // crisp base mesh first and refine only the visible part below.
            val loadEpsilon = epsilonFor(minOf(target, BASE_TESSELLATION_SCALE))
            val loaded = ArrayList<Pair<Page, List<Stroke>>>(toLoad.size)
            val loadedMasks = ArrayList<Pair<Page, List<PageMask>>>(toLoad.size)
            val built = ArrayList<Triple<Page, List<Stroke>, List<Stroke>>>(work.size)
            var refinedCount = 0
            try {
                for (page in toLoad) {
                    if (obsolete()) return@submit
                    loader?.let {
                        val strokes = it(page, loadEpsilon)
                        loaded += page to strokes
                        refinedCount += strokes.size
                    }
                    if (obsolete()) return@submit
                    masksLoader?.let { loadedMasks += page to it(page, loadEpsilon) }
                }
                for ((page, snapshot) in work) {
                    if (obsolete()) return@submit
                    val rebuilt = ArrayList<Stroke>(snapshot.size)
                    for (stroke in snapshot) {
                        if (obsolete()) return@submit
                        rebuilt += Stroke(
                            stroke.brush.copy(epsilon = strokeEpsilon(stroke.brush.size, epsilon)),
                            stroke.inputs,
                        )
                        refinedCount++
                    }
                    built += Triple(page, snapshot, rebuilt)
                }
            } finally {
                if (refineStarted != 0L) {
                    val elapsed = System.nanoTime() - refineStarted
                    Trace.endSection()
                    latency.addRefine(elapsed, refinedCount)
                }
            }
            if (obsolete()) return@submit
            post {
                if (disposed || serial != refineRequestSerial ||
                    tessellationBucket(currentScale()) != target
                ) return@post
                for ((page, strokes) in loaded) {
                    if (page.loaded) continue
                    // A stroke can be drawn on a page while its own strokes are
                    // still being read. The ones from disk are older, so they go
                    // underneath - and the dirty flag is left alone, because
                    // clearing it here would throw that new stroke away at the
                    // next save.
                    page.strokes.addAll(0, strokes)
                    // The file and memory agree again from here, so the next
                    // save can append rather than rewrite the lot.
                    page.savedOnDisk = if (page.dirty) 0 else page.strokes.size
                    loadedMasks.firstOrNull { it.first === page }?.let { (_, masks) ->
                        page.masks.addAll(0, masks)
                    }
                    page.loaded = true
                    page.tessellatedFor = minOf(target, BASE_TESSELLATION_SCALE)
                    page.renderState = RenderState.ViewportCached
                }
                for ((page, snapshot, rebuilt) in built) {
                    val replacements = IdentityHashMap<Stroke, Stroke>()
                    for (i in snapshot.indices) replacements[snapshot[i]] = rebuilt[i]
                    var replaced = 0
                    for (i in page.strokes.indices) {
                        replacements[page.strokes[i]]?.let {
                            page.strokes[i] = it
                            replaced++
                        }
                    }
                    if (replaced == 0) continue
                    // Mixed fidelity is intentional at deep zoom.
                    page.tessellatedFor = if (replaced == page.strokes.size) target else 0f
                    page.meshRevision++
                    remapHistory(replacements)
                    // Nothing about the saved file changed: same inputs, same
                    // brush, only the generated outline. Do not dirty the page.
                }
                for (page in pages) {
                    page.renderState = completedRenderState(
                        page.loaded,
                        page.strokes.isEmpty() || abs(page.tessellatedFor - target) < EPSILON_SLOP,
                    )
                }
                refiningPages = emptyList()
                refineFuture = null
                dry.postInvalidateOnAnimation()
                if (loaded.isNotEmpty()) scheduleRefine()
            }
        } }.getOrNull()
        if (future == null) {
            pages.forEach { if (it.renderState == RenderState.Rendering) it.renderState = RenderState.Dirty }
            refiningPages = emptyList()
        } else {
            refineFuture = future
        }
    }

    /**
     * Gives back the memory of pages nowhere near the screen. Loading was a one
     * way door: a page read once stayed in memory for the session, so paging to
     * the end of a 120 page book held every mesh in it at once.
     *
     * Three things keep a page: being near the viewport, having unsaved changes,
     * and being named by the undo history - putting a stroke back onto a page
     * whose strokes were dropped would duplicate it when the page reloads.
     */
    private fun releaseDistantPages() {
        if (document.pages.size <= KEEP_PAGES * 2 + 1) return
        val visible = visiblePages()
        if (visible.isEmpty()) return
        val first = document.pages.indexOf(visible.first())
        val last = document.pages.indexOf(visible.last())
        if (first < 0 || last < 0) return
        val keep = (first - KEEP_PAGES)..(last + KEEP_PAGES)
        val protected = IdentityHashMap<Page, Boolean>()
        undoStack.forEach { protected[it.page] = true }
        redoStack.forEach { protected[it.page] = true }
        for ((index, page) in document.pages.withIndex()) {
            if (index in keep || !page.loaded || page.dirty) continue
            if (protected.containsKey(page)) continue
            page.savedStrokeCount = page.strokes.size
            page.strokes.clear()
            page.masks.clear()
            dry.dropStrokeIndex(page)
            page.loaded = false
            page.tessellatedFor = -1f
        }
        // Let ART schedule collection itself. Calling System.gc() here pauses
        // the UI thread precisely when a long note has just been scrolled.
    }

    private fun remapHistory(replacements: IdentityHashMap<Stroke, Stroke>) {
        for (edit in undoStack + redoStack) {
            when (edit) {
                is Edit.Drawn -> replacements[edit.stroke]?.let { edit.stroke = it }
                is Edit.Erased ->
                    edit.strokes = edit.strokes.map { replacements[it] ?: it }
                // Pictures are not rebuilt when a page is re-tessellated.
                is Edit.ImageAdded, is Edit.ImageRemoved, is Edit.ImageReplaced -> Unit
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
        return document.pagesIntersecting(top, bottom).mapNotNull { i ->
            val page = document.pages.getOrNull(i) ?: return@mapNotNull null
            val pageTop = document.topOf(i)
            page.takeIf { pageTop <= bottom && pageTop + page.height >= top }
        }
    }

    /** Warms the immutable Ink brush after tool settings or fit scale change. */
    fun prepareBrush() {
        if (tool != Tool.ERASER) currentBrush()
    }

    /** Current page first, then alternating in the travel direction and behind it. */
    private fun nearbyPageIndices(radius: Int): List<Int> {
        if (document.pages.isEmpty()) return emptyList()
        val center = currentPage.coerceIn(document.pages.indices)
        val result = ArrayList<Int>(radius * 2 + 1)
        result += center
        for (distance in 1..radius) {
            val ahead = center + distance * lastPageDirection
            val behind = center - distance * lastPageDirection
            if (ahead in document.pages.indices) result += ahead
            if (behind in document.pages.indices) result += behind
        }
        return result
    }

    private fun prioritizePdf(radius: Int) {
        val source = pdf ?: return
        val pages = nearbyPageIndices(radius).mapNotNull { index ->
            document.pages[index].pdfPageIndex.takeIf { it >= 0 }
        }
        if (pages.isEmpty()) return
        val center = document.pages[currentPage.coerceIn(document.pages.indices)]
        val width = (center.width * currentScale())
            .toInt().coerceIn(PDF_PREFETCH_MIN_WIDTH, PdfSource.baseWidthLimit())
        source.prioritizePages(pages, width)
    }

    /**
     * Once movement settles, decode a small halo above and below the viewport.
     * The visible page remains the zero-delay path; this work only fills what
     * the next short scroll is likely to expose.
     */
    private fun prefetchNearbyPages(radius: Int) {
        if (disposed) return
        val anchor = currentPage
        val visible = Collections.newSetFromMap(IdentityHashMap<Page, Boolean>())
        visible += visiblePages()
        val pages = nearbyPageIndices(radius).map { document.pages[it] }
            .filter { !it.loaded && it !in visible }
        if (pages.isEmpty()) return
        val loader = pageLoader ?: return
        val masksLoader = maskLoader
        val epsilon = epsilonFor(BASE_TESSELLATION_SCALE)
        val serial = refineRequestSerial
        runCatching { refiner.execute {
            val loaded = ArrayList<Pair<Page, List<Stroke>>>(pages.size)
            val masks = ArrayList<Pair<Page, List<PageMask>>>(pages.size)
            for (page in pages) {
                // A new viewport has a zero-delay visible-page job waiting on
                // this same executor. Yield after at most one disk page instead
                // of making it wait for the old six-page halo.
                if (disposed || serial != refineRequestSerial) break
                loaded += page to loader(page, epsilon)
                masksLoader?.let { masks += page to it(page, epsilon) }
            }
            post {
                if (disposed || currentPage != anchor || serial != refineRequestSerial) return@post
                var changed = false
                for ((page, strokes) in loaded) {
                    if (page.loaded) continue
                    page.strokes.addAll(0, strokes)
                    page.savedOnDisk = if (page.dirty) 0 else page.strokes.size
                    masks.firstOrNull { it.first === page }?.let { (_, pageMasks) ->
                        page.masks.addAll(0, pageMasks)
                    }
                    page.loaded = true
                    page.tessellatedFor = BASE_TESSELLATION_SCALE
                    page.renderState = RenderState.ViewportCached
                    changed = true
                }
                if (changed) dry.invalidate()
            }
        } }
    }

    private fun scheduleStoppedPrefetch() {
        if (disposed) return
        removeCallbacks(prefetchRunnable)
        postDelayed(prefetchRunnable, PREFETCH_SETTLE_MS)
    }

    private fun scheduleRefine() {
        if (disposed) return
        removeCallbacks(refineRunnable)
        refineRequestSerial++
        refineFuture?.cancel(true)
        refineFuture = null
        for (page in refiningPages) {
            if (page.renderState == RenderState.Rendering) page.renderState = RenderState.Dirty
        }
        refiningPages = emptyList()
        // Nothing at all while the pinch is on. The zero-delay path below exists
        // so a page that has never been read does not wait to appear, and during
        // a pinch that path fires on every page scrolled into view - a full read
        // and rebuild on the frame the zoom is being drawn in. endZoom posts one.
        if (holdingDetail()) return
        // A page with nothing on it yet should not wait out the settle delay -
        // that delay exists to avoid rebuilding mid-pinch, not to hold up the
        // first paint of a note.
        val delay = if (visiblePages().any { !it.loaded }) 0L else REFINE_DEBOUNCE_MS
        postDelayed(refineRunnable, delay)
    }

    // ---- PDF text selection -------------------------------------------------

    @Volatile private var selectionSerial = 0

    private fun requestSelection(source: PdfSource, pdfPage: Int, start: RectF, end: RectF) {
        val serial = ++selectionSerial
        refiner.execute {
            if (serial != selectionSerial) return@execute
            val found = source.select(pdfPage, start, end)
            post { if (serial == selectionSerial) setSelection(found) }
        }
    }

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
        requestSelection(source, document.pages[index].pdfPageIndex, point, point)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /** Dragging after the hold runs the selection from the anchor to the pen. */
    private fun extendTextSelection(event: MotionEvent, pointerIndex: Int) {
        val source = pdf ?: return
        val index = selectingPage
        val anchor = selectionAnchor ?: return
        if (index !in document.pages.indices) return
        val point = pageLocal(event.getX(pointerIndex), event.getY(pointerIndex), index)
        requestSelection(source, document.pages[index].pdfPageIndex, anchor, point)
    }

    private fun setSelection(found: PdfSelection?) {
        selection = found
        dry.invalidate()
        onSelectionChanged?.invoke(found)
    }

    fun clearSelection() {
        selectionSerial++
        if (selection == null) return
        selectionAnchor = null
        setSelection(null)
    }

    /**
     * Turns the selected text boxes into real highlighter strokes, so a
     * highlight is ink on the page like any other and needs no new file format.
     */
    fun highlightSelection(color: Int = 0x66F9A825) {
        val found = selection ?: return
        val page = document.pages.getOrNull(selectingPage) ?: return
        val brush = Brush.createWithColorIntArgb(
            family = Tool.HIGHLIGHTER.brushFamily(),
            colorIntArgb = color,
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

    /**
     * The same selected boxes as [highlightSelection], but as tape rather than
     * ink: opaque, and on the mask list so it can be tapped up again later.
     */
    fun maskSelection(color: Int = PageMask.DEFAULT_MASK_COLOR) {
        val found = selection ?: return
        val page = document.pages.getOrNull(selectingPage) ?: return
        val brush = Brush.createWithColorIntArgb(
            family = Tool.MASK.brushFamily(),
            // Full alpha regardless of what the mask tool is set to: a strip
            // that only covers what the selection already showed through would
            // not be covering anything.
            colorIntArgb = (color and 0x00FFFFFF) or MASK_OPAQUE,
            size = 1f,
            epsilon = epsilonFor(currentScale()),
        )
        for (box in found.boxes) {
            if (box.width() <= 0f || box.height() <= 0f) continue
            val middle = box.centerY()
            val inputs = MutableStrokeInputBatch()
            inputs.add(InputToolType.STYLUS, box.left, middle, 0L)
            inputs.add(InputToolType.STYLUS, box.right, middle, 16L)
            // A hair over the line's own height, so the tape's rounded ends
            // do not leave a sliver of the letters showing at top and bottom.
            val sized = brush.copy(size = box.height() * MASK_SELECTION_HEIGHT)
            val stroke = Stroke(sized, inputs.toImmutable())
            val mask = PageMask(stroke)
            page.masks += mask
            undoStack += Edit.MaskAdded(page, mask)
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
        val group = nextEditGroup++
        // A straight line drawn with the mask tool covers exactly like any
        // other strip of tape - it goes on the mask list, not the ink list, or
        // it could never be lifted to read what is underneath.
        for (stroke in shapeStrokes(kind, shapeStart, shapeEnd, currentBrush())) {
            if (tool == Tool.MASK) {
                val mask = PageMask(stroke)
                page.masks += mask
                undoStack += Edit.MaskAdded(page, mask, group)
            } else {
                page.strokes += stroke
                undoStack += Edit.Drawn(page, stroke, group)
            }
        }
        redoStack.clear()
        afterEdit(page)
    }

    private fun shapeStrokes(kind: ShapeKind, from: FloatArray, to: FloatArray, brush: Brush): List<Stroke> {
        val points = shapePoints(kind, from, to)
        if (points.size < 2) return emptyList()
        val inputs = MutableStrokeInputBatch()
        points.forEachIndexed { index, point ->
            inputs.add(
                InputToolType.STYLUS,
                point[0],
                point[1],
                index * SHAPE_STEP_MS,
            )
        }
        return listOf(Stroke(brush, inputs.toImmutable()))
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
            ShapeKind.LINE -> {
                val dx = x1 - x0
                val dy = y1 - y0
                val length = hypot(dx, dy)
                val snap = axisSnapEnabled && length > 20f &&
                    min(abs(dx), abs(dy)) / length < 0.14f
                listOf(floatArrayOf(x0, y0), floatArrayOf(
                    if (snap && abs(dx) < abs(dy)) x0 else x1,
                    if (snap && abs(dy) < abs(dx)) y0 else y1,
                ))
            }

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
        lassoPath.add(shapeStart[0], shapeStart[1])
        dry.invalidate()
    }

    /**
     * AndroidX Ink consumes every historical sample from the original event.
     * Lasso is app-owned, so retain its useful history here as primitive points
     * while dropping samples closer than two screen pixels.
     */
    private fun appendLassoSamples(event: MotionEvent, pointerIndex: Int) {
        val minimumDistance = LASSO_SAMPLE_DISTANCE_PX / currentScale().coerceAtLeast(0.01f)
        var changed = false
        for (historyIndex in 0 until event.historySize) {
            pageLocalInto(
                event.getHistoricalX(pointerIndex, historyIndex),
                event.getHistoricalY(pointerIndex, historyIndex),
                lassoPage,
                shapeEnd,
            )
            changed = lassoPath.addIfFarEnough(
                shapeEnd[0], shapeEnd[1], minimumDistance,
            ) || changed
        }
        pageLocalInto(event.getX(pointerIndex), event.getY(pointerIndex), lassoPage, shapeEnd)
        changed = lassoPath.addIfFarEnough(
            shapeEnd[0], shapeEnd[1], minimumDistance,
        ) || changed
        if (changed) dry.postInvalidateOnAnimation()
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
            if (!lassoPath.contains((box.xMin + box.xMax) / 2f,
                    (box.yMin + box.yMax) / 2f)
            ) continue
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

    /** One strip, not the whole page - the list shows each separately. */
    fun setMaskRevealed(pageIndex: Int, maskIndex: Int, revealed: Boolean) {
        val page = document.pages.getOrNull(pageIndex) ?: return
        page.masks.getOrNull(maskIndex)?.revealed = revealed
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

    /** One strip lifted off for good, undoable like any other edit. */
    fun deleteMask(pageIndex: Int, maskIndex: Int) {
        val page = document.pages.getOrNull(pageIndex) ?: return
        val mask = page.masks.getOrNull(maskIndex) ?: return
        undoStack += Edit.MaskRemoved(page, mask, maskIndex)
        page.masks.removeAt(maskIndex)
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

    fun selectedTextBox(): PageImage? = selectedImage?.takeIf { it.textContent != null }

    fun putTextBox(imageId: String, bitmapWidth: Int, bitmapHeight: Int, content: TextBoxContent,
        replacing: PageImage? = null, at: Triple<Int, Float, Float>? = null) {
        val page = if (replacing != null) document.pages.firstOrNull { replacing in it.images } ?: return
            else document.pages.getOrNull(at?.first ?: currentPage) ?: return
        val boxWidth = bitmapWidth / 2f
        val boxHeight = bitmapHeight / 2f
        val image = PageImage(imageId, replacing?.x ?: at?.second ?: (page.width - boxWidth).coerceAtLeast(0f) / 2f,
            replacing?.y ?: at?.third ?: (page.height - boxHeight).coerceAtLeast(0f) / 2f,
            boxWidth, boxHeight, content)
        if (replacing != null) {
            val at = page.images.indexOf(replacing)
            page.images[at] = image
            undoStack += Edit.ImageReplaced(page, replacing, image, at)
        } else {
            page.images += image
            undoStack += Edit.ImageAdded(page, image)
        }
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
        selectedImage = image
        selectedImagePage = page
        dry.invalidate()
        onImageSelected?.invoke(image != null)
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
            // Open the editor only after the stylus lifts, so the dialog cannot
            // steal ACTION_UP and leave the canvas stuck in an active gesture.
            if (textMode) pendingTextPlacement = Triple(index, x, y)
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
        pageLocalInto(event.getX(pointerIndex), event.getY(pointerIndex), index, imagePoint)
        if (resizingImage) {
            val aspect = if (image.height > 0f) image.width / image.height else 1f
            // Width leads and height follows, so a picture never gets squashed.
            val width = (imagePoint[0] - image.x).coerceAtLeast(IMAGE_MIN_SIZE)
            image.width = width
            image.height = if (aspect > 0f) width / aspect else width
        } else {
            image.x += imagePoint[0] - imageGrab[0]
            image.y += imagePoint[1] - imageGrab[1]
            imageGrab[0] = imagePoint[0]
            imageGrab[1] = imagePoint[1]
        }
        dry.postInvalidateOnAnimation()
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
        val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
        val transform = Matrix()
        for (stroke in strokes) renderer.draw(canvas, stroke, transform)
        bitmap
    }.getOrNull()

    private fun eraseAlong(event: MotionEvent, pointerIndex: Int, force: Boolean = false) {
        val page = activePage ?: return
        val index = document.pages.indexOf(page)
        if (index < 0) return
        eraserCursorVisible = true
        eraserCursorX = event.getX(pointerIndex)
        eraserCursorY = event.getY(pointerIndex)
        dry.postInvalidateOnAnimation()
        pageLocalInto(eraserCursorX, eraserCursorY, index, erasePagePoint)
        val x = erasePagePoint[0]
        val y = erasePagePoint[1]
        if (hasLastErasePoint) {
            val dx = x - lastEraseX
            val dy = y - lastEraseY
            // The previous square tip already covers this movement. Waiting for
            // a useful distance avoids dense-page intersection work at 240 Hz;
            // the next segment, or the forced UP sample, covers the whole gap.
            if (!shouldProcessEraserMove(dx, dy, eraserWidth, force)) return
        }
        val hadPrevious = hasLastErasePoint
        val previousX = lastEraseX
        val previousY = lastEraseY
        lastEraseX = x
        lastEraseY = y
        hasLastErasePoint = true
        val segment = if (hadPrevious) {
            ImmutableSegment(
                ImmutableVec(previousX, previousY),
                ImmutableVec(x, y),
            )
        } else null
        // The tip is a square of eraserWidth around where the pen is now; the
        // segment covers the gap to the previous sample, so a fast swipe still
        // erases along its whole path instead of leaving holes between samples.
        val tip = ImmutableBox.fromCenterAndDimensions(
            ImmutableVec(x, y),
            eraserWidth,
            eraserWidth,
        )
        val radius = eraserWidth / 2f
        val candidateLeft = min(if (hadPrevious) previousX else x, x) - radius
        val candidateTop = min(if (hadPrevious) previousY else y, y) - radius
        val candidateRight = max(if (hadPrevious) previousX else x, x) + radius
        val candidateBottom = max(if (hadPrevious) previousY else y, y) + radius
        val candidates = dry.strokesIn(
            page, candidateLeft, candidateTop, candidateRight, candidateBottom,
        )
        // ponytail: whole-stroke eraser. A partial (pixel) eraser means splitting
        // the input batch and rebuilding both halves - worth it only if the
        // whole-stroke behaviour actually gets complained about.
        //
        // intersects() is a member extension on the Intersection object, so it
        // only resolves inside its scope.
        eraseStrokeHits.clear()
        eraseStrokeHitSet.clear()
        with(Intersection) {
            for (candidate in candidates) {
                if ((segment?.intersects(candidate.shape, IDENTITY) == true) ||
                    tip.intersects(candidate.shape, IDENTITY)
                ) {
                    eraseStrokeHits += candidate
                    eraseStrokeHitSet += candidate
                }
            }
        }
        eraseMaskHits.clear()
        with(Intersection) {
            for (mask in page.masks) {
                if ((segment?.intersects(mask.stroke.shape, IDENTITY) == true) ||
                    tip.intersects(mask.stroke.shape, IDENTITY)
                ) eraseMaskHits += mask
            }
        }
        if (eraseStrokeHits.isEmpty() && eraseMaskHits.isEmpty()) return
        // Backwards, so each recorded index is still where it goes back.
        for (i in eraseMaskHits.lastIndex downTo 0) {
            val mask = eraseMaskHits[i]
            val at = page.masks.indexOfFirst { it === mask }
            if (at < 0) continue
            page.masks.removeAt(at)
            undoStack += Edit.MaskRemoved(page, mask, at, activeEraseGroup)
        }
        val erased = if (eraseStrokeHits.isEmpty()) null else ArrayList(eraseStrokeHits)
        if (erased != null) {
            page.strokes.removeAll(eraseStrokeHitSet::contains)
            undoStack += Edit.Erased(page, erased, activeEraseGroup)
        }
        redoStack.clear()
        // Keep the hot eraser loop local to the View. A Compose state write,
        // autosave restart and mesh-refine request for every 240 Hz sample was
        // the dominant pause on dense pages; commit the gesture once at lift.
        eraseChanged = true
        page.dirty = true
        dry.postInvalidateOnAnimation()
        if (erased != null) dry.removeStrokesFromIndex(page, erased)
    }

    private fun finishEraseGesture() {
        if (!eraseChanged) return
        activePage?.let { afterEdit(it) }
    }

    override fun onStrokesFinished(finished: Map<InProgressStrokeId, Stroke>) {
        val finalizeStarted = penFinalizeStartedNanos
        penFinalizeStartedNanos = 0L
        val page = activePage
        if (page != null) {
            val group = nextEditGroup++
            for (stroke in finished.values) {
                if (strokeIsMask) {
                    val mask = PageMask(stroke)
                    page.masks += mask
                    undoStack += Edit.MaskAdded(page, mask, group)
                } else {
                    val shape = if (autoShapeRecognitionEnabled &&
                        Tool.ofBrushFamily(stroke.brush.family).isFreehandPen()
                    ) recognizeStroke(stroke) else null
                    if (shape != null) {
                        val committed = shapeStrokes(
                            shape.kind, floatArrayOf(shape.fromX, shape.fromY),
                            floatArrayOf(shape.toX, shape.toY), stroke.brush,
                        )
                        for (part in committed) {
                            page.strokes += part
                            undoStack += Edit.Drawn(page, part, group)
                        }
                    } else {
                        // Wet ink, shape recognition and the saved stroke now
                        // share the same stabilized streaming trajectory.
                        page.strokes += stroke
                        undoStack += Edit.Drawn(page, stroke, group)
                    }
                }
            }
            redoStack.clear()
        }
        wet.removeFinishedStrokes(finished.keys)
        if (page != null) afterEdit(page) else afterEdit()
        if (finalizeStarted != 0L) {
            latency.addPenFinalize(System.nanoTime() - finalizeStarted)
        }
    }

    /** Snapshot of handwriting enclosed by the current lasso for region OCR. */
    fun selectedLassoStrokes(): List<Stroke> = lassoStrokes.toList()

    private fun recognizeStroke(stroke: Stroke): RecognizedShape? {
        val sample = StrokeInput()
        val points = (0 until stroke.inputs.size).map { index ->
            stroke.inputs.populate(index, sample)
            floatArrayOf(sample.x, sample.y)
        }
        return recognizeShape(points)
    }

    /** Committed ink and paper. Wet ink keeps its own front buffer above this. */
    private inner class DryLayer(context: Context) : android.view.View(context) {
        var lastVisibleStrokes = 0
            private set
        var lastDrawMs = 0.0
            private set
        private val renderer = ViewStrokeRenderer(
            CanvasStrokeRenderer.create(PencilTextureStore),
            this,
        )
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
        private val highlighterMultiply = Paint().apply { blendMode = BlendMode.MULTIPLY }
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
        private val outlines = java.util.WeakHashMap<Stroke, android.graphics.Path>()
        private val outlinePoint = MutableVec()
        private val selectionBox = RectF()
        private var lassoDashScale = Float.NaN
        private var lassoDashEffect: android.graphics.DashPathEffect? = null
        private val handle = Paint().apply { isAntiAlias = true; color = 0xFF3B7DDD.toInt() }
        private val eraserCursorFill = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = 0x183B7DDD
        }
        private val eraserCursorOuter = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 4f * resources.displayMetrics.density
            color = Color.WHITE
        }
        private val eraserCursorInner = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
            color = 0xCC2459B8.toInt()
        }

        private val strokeIndexes = IdentityHashMap<Page, StrokeGrid>()
        private val textLayouts = java.util.WeakHashMap<PageImage, TextRender>()
        private val inkBitmaps = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private val inkBitmapPending = HashSet<String>()
        private var inkBitmapGeneration = 0

        fun clearStrokeIndexes() {
            strokeIndexes.clear()
            textLayouts.clear()
            inkBitmaps.evictAll()
            inkBitmapPending.clear()
            inkBitmapGeneration++
            viewportRenderGeneration++
        }

        fun dropStrokeIndex(page: Page) {
            strokeIndexes.remove(page)
        }

        fun strokesIn(page: Page, left: Float, top: Float, right: Float, bottom: Float): List<Stroke> =
            strokeIndexes.getOrPut(page) { StrokeGrid() }.visible(page, left, top, right, bottom)

        fun removeStrokesFromIndex(page: Page, strokes: Collection<Stroke>) {
            strokeIndexes[page]?.remove(page, strokes)
        }

        private fun cachedInk(page: Page, visibleCount: Int): Bitmap? {
            if (!page.loaded || visibleCount < 250 || page.strokes.size < 300 || movingLasso) return null
            val scale = tessellationBucket(currentScale())
            val pixels = page.width.toDouble() * page.height * scale * scale
            if (pixels > 8_000_000.0 || pixels <= 0.0) return null
            val key = "${page.id}:${page.revision}:${page.meshRevision}:${page.strokes.size}:$scale"
            inkBitmaps.get(key)?.let { return it }
            if (activeStylusPointer == null && !viewportInteracting && !zooming && !flinging &&
                inkBitmapPending.add(key)
            ) {
                val snapshot = page.strokes.toList()
                val revision = page.revision
                val meshRevision = page.meshRevision
                val generation = inkBitmapGeneration
                val viewportGeneration = viewportRenderGeneration
                runCatching { refiner.execute {
                    val bitmap = runCatching {
                        if (viewportGeneration != viewportRenderGeneration) return@runCatching null
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                            val target = Canvas(image)
                            val transform = Matrix().apply { setScale(scale, scale) }
                            val strokeRenderer = CanvasStrokeRenderer.create(PencilTextureStore)
                            for (stroke in snapshot) {
                                if (Thread.currentThread().isInterrupted ||
                                    viewportGeneration != viewportRenderGeneration
                                ) {
                                    image.recycle()
                                    return@runCatching null
                                }
                                strokeRenderer.draw(target, stroke, transform)
                            }
                        }
                    }.getOrNull()
                    post {
                        inkBitmapPending.remove(key)
                        val visible = visiblePages().any { it === page }
                        if (viewportGeneration == viewportRenderGeneration && visible &&
                            generation == inkBitmapGeneration && page.revision == revision &&
                            page.meshRevision == meshRevision &&
                            page.strokes.size == snapshot.size && bitmap != null
                        ) {
                            inkBitmaps.put(key, bitmap)
                            invalidate()
                        } else bitmap?.recycle()
                    }
                } }.onFailure { inkBitmapPending.remove(key) }
            }
            return null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val started = if (latencyMonitoringEnabled) System.nanoTime() else 0L
            var visibleCount = 0
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
                for (i in document.pagesIntersecting(viewport[1], viewport[3])) {
                    val page = document.pages.getOrNull(i) ?: continue
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
                    val visibleLeft = cullLeft.coerceAtLeast(0f)
                    val visibleTop = cullTop.coerceAtLeast(0f)
                    val visibleRight = cullRight.coerceAtMost(page.width)
                    val visibleBottom = cullBottom.coerceAtMost(page.height)
                    val indexedStrokes = strokeIndexes.getOrPut(page) { StrokeGrid() }.visible(
                        page, visibleLeft, visibleTop, visibleRight, visibleBottom,
                    )
                    val visibleStrokes = if (i == playbackPage && playbackStrokeCount != null) {
                        val allowed = page.strokes.take(playbackStrokeCount!!).toHashSet()
                        indexedStrokes.filter { it in allowed }
                    } else indexedStrokes
                    visibleCount += visibleStrokes.size
                    val hasHighlighter = visibleStrokes.any {
                        it.brush.family == Tool.HIGHLIGHTER.brushFamily()
                    }
                    val inkBitmap = if (!hasHighlighter && !lifted && playbackStrokeCount == null)
                        cachedInk(page, visibleStrokes.size) else null
                    // Put highlighter ink on the chosen side of handwriting.
                    // Keep the source list in its original order within each
                    // layer so overlapping strokes still look predictable.
                    if (inkBitmap != null) {
                        scoped.drawBitmap(inkBitmap, null, pageRect, bitmapPaint)
                    } else if (!hasHighlighter) {
                        for (stroke in visibleStrokes) {
                            if (lifted && stroke in lassoStrokes) continue
                            scope.drawStroke(stroke)
                        }
                    } else for (pass in 0..1) {
                        val highlightPass = if (pass == 0) !highlighterAboveInk else highlighterAboveInk
                        val layer = if (highlightPass)
                            scoped.saveLayer(pageRect, highlighterMultiply) else -1
                        for (stroke in visibleStrokes) {
                            if (lifted && stroke in lassoStrokes) continue
                            val highlight = stroke.brush.family == Tool.HIGHLIGHTER.brushFamily()
                            if (highlight == highlightPass) scope.drawStroke(stroke)
                        }
                        if (layer >= 0) scoped.restoreToCount(layer)
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
            if (eraserCursorVisible) {
                val radius = (eraserWidth * currentScale() / 2f).coerceAtLeast(0.5f)
                canvas.drawCircle(eraserCursorX, eraserCursorY, radius, eraserCursorFill)
                canvas.drawCircle(eraserCursorX, eraserCursorY, radius, eraserCursorOuter)
                canvas.drawCircle(eraserCursorX, eraserCursorY, radius, eraserCursorInner)
            }
            if (started != 0L) {
                lastVisibleStrokes = visibleCount
                lastDrawMs = (System.nanoTime() - started) / 1e6
                latency.addDraw((lastDrawMs * 1e6).toLong(), visibleCount)
            }
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
                val content = image.textContent
                if (content == null) {
                    canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint)
                } else {
                    // Text boxes keep a bitmap for old files and export, but on
                    // screen their glyphs are laid out as vectors. Scaling a
                    // stored bitmap was the pixelated type seen at zoom levels.
                    val text = textLayouts.getOrPut(image) {
                        val logicalWidth = bitmap.width / 2f
                        val logicalHeight = bitmap.height / 2f
                        val paint = TextPaint(
                            Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG,
                        ).apply {
                            textSize = content.size
                            color = content.color
                        }
                        val width = ((bitmap.width - 16) / 2).coerceAtLeast(1)
                        TextRender(
                            StaticLayout.Builder.obtain(content.text, 0, content.text.length, paint, width)
                                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                .setIncludePad(true)
                                .build(),
                            logicalWidth,
                            logicalHeight,
                        )
                    }
                    canvas.save()
                    canvas.clipRect(imageRect)
                    canvas.translate(image.x, image.y)
                    canvas.scale(image.width / text.logicalWidth, image.height / text.logicalHeight)
                    canvas.translate(4f, 4f)
                    text.layout.draw(canvas)
                    canvas.restore()
                }
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

        private fun lassoDashes(scale: Float): android.graphics.DashPathEffect {
            lassoDashEffect?.takeIf { lassoDashScale == scale }?.let { return it }
            return android.graphics.DashPathEffect(floatArrayOf(8f / scale, 6f / scale), 0f)
                .also {
                    lassoDashScale = scale
                    lassoDashEffect = it
                }
        }

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
                val path = outlineOf(mask.stroke)
                if (path.isEmpty) continue
                overlay.color = mask.stroke.brush.colorIntArgb
                overlay.strokeWidth = MASK_OUTLINE_PX / scale
                canvas.drawPath(path, overlay)
            }
        }

        /**
         * The silhouette of a stroke: its mesh loops unioned into one region, so
         * a strip that doubled back over itself is outlined once around the
         * outside rather than showing the seam where it crossed. Kept per
         * stroke, because this is geometry that only changes when the mesh does.
         */
        private fun outlineOf(stroke: Stroke): android.graphics.Path =
            outlines.getOrPut(stroke) {
                val union = android.graphics.Path()
                val loop = android.graphics.Path()
                val shape = stroke.shape
                for (group in 0 until shape.getRenderGroupCount()) {
                    for (index in 0 until shape.getOutlineCount(group)) {
                        val vertices = shape.getOutlineVertexCount(group, index)
                        if (vertices < 3) continue
                        loop.reset()
                        for (v in 0 until vertices) {
                            shape.populateOutlinePosition(group, index, v, outlinePoint)
                            if (v == 0) {
                                loop.moveTo(outlinePoint.x, outlinePoint.y)
                            } else {
                                loop.lineTo(outlinePoint.x, outlinePoint.y)
                            }
                        }
                        loop.close()
                        union.op(loop, android.graphics.Path.Op.UNION)
                    }
                }
                union
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
                PageBackground.INFINITE -> Unit
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

                PageBackground.DOT -> {
                    var y = RULE_SPACING
                    while (y < page.height) {
                        var x = RULE_SPACING
                        while (x < page.width) {
                            canvas.drawCircle(x, y, 2.5f, rule)
                            x += RULE_SPACING
                        }
                        y += RULE_SPACING
                    }
                }

                PageBackground.NARROW_LINED -> {
                    var y = RULE_SPACING / 2f
                    while (y < page.height) {
                        canvas.drawLine(0f, y, page.width, y, rule)
                        y += RULE_SPACING / 2f
                    }
                }

                PageBackground.CORNELL -> {
                    canvas.drawLine(page.width * 0.30f, 0f,
                        page.width * 0.30f, page.height * 0.82f, rule)
                    canvas.drawLine(0f, page.height * 0.82f,
                        page.width, page.height * 0.82f, rule)
                    var y = RULE_SPACING
                    while (y < page.height * 0.82f) {
                        canvas.drawLine(0f, y, page.width, y, rule)
                        y += RULE_SPACING
                    }
                }

                PageBackground.CUSTOM -> {
                    page.templateId?.let { id -> templateLoader?.invoke(id) }?.let { bitmap ->
                        canvas.drawBitmap(bitmap, null, pageRect, bitmapPaint)
                    }
                }

                PageBackground.PDF -> Unit
            }
        }

        /** Returns true when the page was actually painted. */
        private fun drawPdf(canvas: Canvas, page: Page, index: Int): Boolean {
            val source = pdf ?: return false
            val scale = currentScale()
            // Mid-pinch, take what is cached and ask for nothing: rendering a
            // page or a screenful of tiles at each size the zoom passes through
            // is work thrown away by the next frame, and it is thrown away by
            // competing with the frame that is being pinched.
            val render = !holdingDetail()

            // The whole page at modest resolution is the floor: it is cheap, it
            // is always there, and it means a tile that has not arrived yet
            // shows slightly soft rather than blank.
            val base = source.bitmap(page.pdfPageIndex, (page.width * scale).toInt(), render)
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
                        render,
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
        fun visibleDocumentBounds(out: FloatArray) {
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

        /** How far past fit-to-width the zoom goes: 10x, which reads as 1000%. */
        const val MAX_ZOOM = 10f
        // 페이지 폭과 태블릿의 실제 콘텐츠 폭을 1:1로 맞춥니다.
        const val FIT_MARGIN = 1f
        const val HIGHLIGHT_ALPHA = 0x66000000
        const val RULE_SPACING = 60f
        const val PAGE_TOP_MARGIN_PX = 24f
        const val REFINE_DEBOUNCE_MS = 200L
        const val ZOOM_REPORT_INTERVAL_NS = 33_000_000L
        const val REFINE_MARGIN_PX = 160f
        const val BASE_TESSELLATION_SCALE = 1f
        const val EPSILON_SLOP = 0.00001f
        const val PREFETCH_SETTLE_MS = 280L
        const val PREFETCH_SLOW_RADIUS = 1
        const val PREFETCH_STOP_RADIUS = 3
        const val FAST_VIEWPORT_PX_PER_SECOND = 2200f
        const val PDF_PREFETCH_MIN_WIDTH = 1024
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
        const val LASSO_SAMPLE_DISTANCE_PX = 2f
        /** Pages this far either side of the screen stay in memory. */
        const val KEEP_PAGES = 3
        /** What every LatencyData field holds until it is filled in. */
        const val LATENCY_UNSET = Long.MIN_VALUE
        const val MIN_REPORT_MS = 2
        const val MAX_REPORT_MS = 40
        const val MASK_TAP_PX = 6f
        const val MASK_OUTLINE_PX = 2f
        const val MASK_OPAQUE = 0xFF000000.toInt()
        const val MASK_SELECTION_HEIGHT = 1.15f

        const val TRACE_STYLUS_DOWN = "Notesis stylus DOWN"
        const val TRACE_STYLUS_MOVE = "Notesis stylus MOVE"
        const val TRACE_STYLUS_UP = "Notesis stylus UP"
        const val TRACE_STYLUS_OTHER = "Notesis stylus other"
        const val TRACE_MESH_REFINE = "Notesis mesh/refine"

        /** A tap this fast, moved this little, is a tap and not the start of a pan. */
        const val TAP_MAX_MS = 250L
        const val TAP_SLOP_PX = 28f
        const val DOUBLE_TAP_MS = 400L
        const val DOUBLE_TAP_SLOP_PX = 140f

        /** How far up three fingers have to travel before that reads as "open it". */
        const val OPEN_DRAG_PX = 120f

        /** Below this the fingers are practically on top of each other; no factor. */
        const val MIN_SPREAD_PX = 8f
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

internal fun shouldProcessEraserMove(
    dx: Float,
    dy: Float,
    eraserWidth: Float,
    force: Boolean,
): Boolean {
    if (force) return true
    val minimumDistance = eraserWidth * ERASER_SAMPLE_DISTANCE_FRACTION
    return dx * dx + dy * dy >= minimumDistance * minimumDistance
}

/** True while the barrel button is held, or the pen is flipped to its eraser end. */
private fun MotionEvent.isEraserGesture(): Boolean =
    getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER ||
        buttonState and
        (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
