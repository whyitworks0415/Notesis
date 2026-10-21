@file:Suppress("RestrictedApi")

package com.notesis

import android.os.Build
import android.os.Debug
import android.os.Trace
import android.os.SystemClock
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
import android.view.KeyEvent
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
import kotlin.math.ln
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

/** Keep the pen outline well below one screen pixel before path antialiasing. */
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

/** Long edge of the picture a selection keeps in the library. */
private const val LIBRARY_PREVIEW_PX = 512f
private const val STROKE_GRID_CELL = 256f
private const val MAX_GRID_CELLS_PER_STROKE = 64
/** Below this fraction of the tip width, the previous eraser tip still covers the move. */
private const val ERASER_SAMPLE_DISTANCE_FRACTION = 0.08f

/**
 * A page's committed ink recorded once into a hardware display list.
 *
 * Ink's mesh renderer sets a dozen shader uniforms and makes several JNI calls
 * per stroke per draw, so redrawing a dense page directly cost tens of
 * milliseconds every frame it moved. The mesh shader depends on the zoom but
 * not on the pan, so a recording made at one zoom can be replayed at any scroll
 * position for the price of a single draw call.
 */
private class InkNode(val node: android.graphics.RenderNode) {
    var scale = 0f
    var prefix: InkRenderPrefix<Stroke>? = null
    var meshRevision = Long.MIN_VALUE
    var meshInk = false
    var highlighterAbove = false
    /** The translucent layer look it was recorded under, or null for all opaque. */
    var look: LayerLook? = null
}

/** Whether a page held highlighter ink, as of the stroke list it was computed for. */
private class HighlightState(val revision: Long, val meshRevision: Long, val size: Int, val has: Boolean)

/** A page-local spatial index used by both dense-page drawing and culling. */
internal class StrokeGrid {
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
    private var meshRevision = Long.MIN_VALUE
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
        if (revision == page.revision && meshRevision == page.meshRevision &&
            indexedSize == strokes.size && endpointsMatch) return

        // The dominant edit is one stroke appended at pen-up. Index just that
        // suffix; erase, lasso, load-at-front, and retessellation rebuild once.
        val appendOnly = meshRevision == page.meshRevision && strokes.size > indexedSize &&
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
        meshRevision = page.meshRevision
    }

    /**
     * Indexes [strokes] ahead of the page that will hold them, off the UI
     * thread: a page read in used to build its whole index inside the first
     * frame that drew it. [adopt] binds it to the page once it is applied.
     */
    fun prebuild(strokes: List<Stroke>) {
        for (i in strokes.indices) index(strokes[i], ordered.size)
        indexedSize = strokes.size
        firstStroke = strokes.firstOrNull()
        lastStroke = strokes.lastOrNull()
    }

    /** True, and current for [page], if [page] holds exactly what was [prebuild]-indexed. */
    fun adopt(page: Page): Boolean {
        val strokes = page.strokes
        if (strokes.size != indexedSize || strokes.firstOrNull() !== firstStroke || strokes.lastOrNull() !== lastStroke) {
            return false
        }
        revision = page.revision
        meshRevision = page.meshRevision
        return true
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
        meshRevision = page.meshRevision
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
    DASH_DOT,

    /** A pressure nib cut at an angle, so the line swells and thins with direction and weight. */
    FOUNTAIN,
    /** A flat nib held at 45 degrees: broad on the down-strokes, hairline across. */
    CALLIGRAPHY,
    /** Translucent wash whose overlaps build up, like layers of paint. */
    WATERCOLOR,
    /** A broad, squarish, pressure-loaded bristle laid on opaque. */
    OIL,

    /** The pencil's dab looks. See [PencilBrushes]. */
    STAMP,
    BUBBLE,
    SPRAY,

    // Added after the rest so every saved stroke keeps the tool it was made with.

    /** The fountain pen whose line thins as it moves faster. */
    SPEED_FOUNTAIN,
    /** A soft, grainy lead whose darkness follows pressure. See [PencilBrushes.soft]. */
    SOFT_PENCIL,
    /**
     * The pressure pen, for writing that is not meant to stay: its strokes are
     * never written to disk, so they are gone once the note is left. See
     * [NoteStore.save].
     */
    VANISHING_PEN;

    fun brushFamily(): BrushFamily = when (this) {
        HIGHLIGHTER -> highlighter
        MASK -> masking
        PRESSURE_PEN -> pressurePen
        PENCIL -> pencil
        DOTTED -> dotted
        DASHED -> dashed
        DASH_DOT -> dashDot
        FOUNTAIN -> fountain
        CALLIGRAPHY -> calligraphy
        WATERCOLOR -> watercolor
        OIL -> oil
        STAMP -> PencilBrushes.stamp
        BUBBLE -> PencilBrushes.bubble
        SPRAY -> PencilBrushes.spray
        SPEED_FOUNTAIN -> speedFountain
        SOFT_PENCIL -> PencilBrushes.soft
        VANISHING_PEN -> vanishing
        else -> pen
    }

    companion object {
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private fun roundTip(stock: BrushFamily): BrushFamily {
            val coat = stock.coats.first()
            return stock.copy(coat = coat.copy(tip = coat.tip.copy(
                scaleX = 1f, scaleY = 1f, cornerRounding = 1f,
                slantDegrees = 0f, pinch = 0f, rotationDegrees = 0f,
            )))
        }

        // The saved families. DISCARD makes CanvasStrokeRenderer fill the
        // outline as an antialiased Path, the same rule the PDF exporter uses.
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val pen by lazy { merged(roundTip(StockBrushes.marker())).copy(clientBrushFamilyId = "notesis.pen") }
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val pressurePen by lazy { merged(roundTip(StockBrushes.pressurePen())).copy(clientBrushFamilyId = "notesis.pressure-pen") }
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val vanishing by lazy { merged(roundTip(StockBrushes.pressurePen())).copy(clientBrushFamilyId = "notesis.vanishing") }

        /** Whether a stroke is the vanishing pen's, which is kept only while the note is open. */
        fun isVanishing(family: BrushFamily): Boolean = family == vanishing

        // Screen-only twins: same tip, so Stroke.copy keeps the mesh, but a
        // self-overlap the mesh renderer accepts. Its shader antialiases in
        // screen space; the Path fill above is left to the GPU path renderer.
        // Overlap only shows through translucent ink, so opaque pens lose
        // nothing. Never saved - see [storedFamilyOf].
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val penMesh by lazy {
            withSelfOverlap(pen, SelfOverlap.ANY)
        }
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val pressurePenMesh by lazy {
            withSelfOverlap(pressurePen, SelfOverlap.ANY)
        }

        /** The mesh-rendered twin of a saved pen family, or null for any other family. */
        fun meshFamilyOf(family: BrushFamily): BrushFamily? = when (family) {
            pen -> penMesh
            pressurePen -> pressurePenMesh
            else -> null
        }

        /** Undo [meshFamilyOf], so notes on disk keep the families they always had. */
        fun storedFamilyOf(family: BrushFamily): BrushFamily = when (family) {
            penMesh -> pen
            pressurePenMesh -> pressurePen
            else -> family
        }
        /** Graphite through paper tooth, answering pressure and tilt. See [PencilBrushes]. */
        private val pencil by lazy { PencilBrushes.graphite }

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

        @OptIn(ExperimentalInkCustomBrushApi::class)
        private fun nib(
            stock: BrushFamily, id: String, scaleY: Float, rotation: Float, rounding: Float, overlap: SelfOverlap,
        ): BrushFamily {
            val coat = stock.coats.first()
            return withSelfOverlap(stock.copy(coat = coat.copy(tip = coat.tip.copy(
                scaleX = 1f, scaleY = scaleY, cornerRounding = rounding,
                slantDegrees = 0f, pinch = 0f, rotationDegrees = rotation,
            ))), overlap).copy(clientBrushFamilyId = id)
        }

        private val fountain by lazy {
            nib(StockBrushes.pressurePen(), "notesis.fountain", 0.55f, -30f, 0.8f, SelfOverlap.DISCARD)
        }
        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val speedFountain by lazy {
            val base = nib(StockBrushes.pressurePen(), "notesis.fountain.speed", 0.55f, -30f, 0.8f, SelfOverlap.DISCARD)
            val coat = base.coats.first()
            base.copy(coat = coat.copy(tip = coat.tip.copy(
                behaviors = coat.tip.behaviors + PencilBrushes.speedThinning(),
            )))
        }
        private val calligraphy by lazy {
            nib(StockBrushes.marker(), "notesis.calligraphy", 0.18f, -45f, 0.25f, SelfOverlap.DISCARD)
        }
        private val watercolor by lazy {
            nib(StockBrushes.pressurePen(), "notesis.watercolor", 1f, 0f, 1f, SelfOverlap.ACCUMULATE)
        }
        private val oil by lazy {
            nib(StockBrushes.pressurePen(), "notesis.oil", 0.8f, 0f, 0.3f, SelfOverlap.DISCARD)
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
            ).copy(clientBrushFamilyId = "notesis.highlighter")
        }

        @OptIn(ExperimentalInkCustomBrushApi::class)
        private val masking by lazy { merged(StockBrushes.marker()).copy(clientBrushFamilyId = "notesis.mask") }

        /**
         * One stroke that doubles back over itself is still one stroke, so the
         * overlap is drawn once. Without this a highlighter darkens wherever the
         * hand crossed its own line, and tape shows every seam - the ink library
         * calls it self overlap, and DISCARD is the "merge" of the two options.
         */
        private fun merged(family: BrushFamily): BrushFamily =
            withSelfOverlap(family, SelfOverlap.DISCARD)

        @OptIn(ExperimentalInkCustomBrushApi::class)
        private fun withSelfOverlap(family: BrushFamily, overlap: SelfOverlap): BrushFamily {
            val coat = family.coats.firstOrNull() ?: return family
            val paints = coat.paintPreferences.map { paint ->
                BrushPaint(paint.textureLayers, paint.colorFunctions, overlap)
            }
            if (paints.isEmpty()) return family
            return family.copy(coat = coat.copy(paintPreferences = paints))
        }

        /** Reverse lookup for reload: an erased stroke was never saved. */
        fun ofBrushFamily(family: BrushFamily): Tool = when (family) {
            highlighter -> HIGHLIGHTER
            masking -> MASK
            pressurePen, pressurePenMesh -> PRESSURE_PEN
            pencil -> PENCIL
            dotted -> DOTTED
            dashed -> DASHED
            dashDot -> DASH_DOT
            fountain -> FOUNTAIN
            calligraphy -> CALLIGRAPHY
            watercolor -> WATERCOLOR
            oil -> OIL
            PencilBrushes.stamp -> STAMP
            PencilBrushes.bubble -> BUBBLE
            PencilBrushes.spray -> SPRAY
            speedFountain -> SPEED_FOUNTAIN
            PencilBrushes.soft -> SOFT_PENCIL
            vanishing -> VANISHING_PEN
            else -> PEN
        }
    }
}

private fun Tool.isFreehandPen(): Boolean =
    this == Tool.PEN || this == Tool.PRESSURE_PEN || this == Tool.PENCIL || this == Tool.FOUNTAIN ||
        this == Tool.CALLIGRAPHY || this == Tool.WATERCOLOR || this == Tool.OIL || this == Tool.SPEED_FOUNTAIN ||
        this == Tool.VANISHING_PEN || isDabbed()

/**
 * Drawn as scattered particles rather than one line. A line pattern would
 * replace the look outright, and the solid predicted head would show as a
 * plain line ahead of the dabs, so neither applies to these.
 */
internal fun Tool.isDabbed(): Boolean =
    this == Tool.STAMP || this == Tool.BUBBLE || this == Tool.SPRAY || this == Tool.SOFT_PENCIL

/**
 * What the pen just did in reading (gesture) mode, in screen pixels: the
 * path a drag took, and a ring where a tap with the button landed. A fixed
 * ring of samples that age out after [LIFE_MS], so the trail follows the pen
 * and fades behind it without ever touching the page or its history.
 */
private class LaserView(context: Context) : View(context) {
    private val xs = FloatArray(CAPACITY)
    private val ys = FloatArray(CAPACITY)
    private val times = LongArray(CAPACITY)
    private var start = 0
    private var count = 0
    private var touching = false
    private val density = resources.displayMetrics.density
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFE0DC.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var tapX = 0f
    private var tapY = 0f
    private var tapTime = Long.MIN_VALUE

    /** False draws only the dot under the pen, with nothing trailing behind it. */
    var trail = true

    /** The trail's colour: a glow of it, with a pale core. */
    fun tint(color: Int) {
        glow.color = color
        dot.color = color
        ring.color = color
        core.color = blend(color, 0xFFFFFFFF.toInt(), 0.78f)
    }

    /** A ring spreading from where a tap landed, so the tap shows where it was. */
    fun tapAt(x: Float, y: Float, time: Long) {
        tapX = x
        tapY = y
        tapTime = time
        postInvalidateOnAnimation()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() shl shift
        return -0x1000000 or ch(16) or ch(8) or ch(0)
    }

    init {
        setWillNotDraw(false)
    }

    fun add(x: Float, y: Float, time: Long, down: Boolean) {
        touching = down
        if (count == CAPACITY) {
            start = (start + 1) % CAPACITY
            count--
        }
        val at = (start + count) % CAPACITY
        xs[at] = x
        ys[at] = y
        times[at] = time
        count++
        postInvalidateOnAnimation()
    }

    fun lift() {
        touching = false
        postInvalidateOnAnimation()
    }

    fun clear() {
        count = 0
        touching = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Event times share this clock.
        val now = SystemClock.uptimeMillis()
        while (count > 0 && now - times[start] > LIFE_MS) {
            start = (start + 1) % CAPACITY
            count--
        }
        val tapAge = now - tapTime
        val tapping = tapTime != Long.MIN_VALUE && tapAge in 0..TAP_LIFE_MS
        if (tapping) {
            val t = tapAge.toFloat() / TAP_LIFE_MS
            ring.alpha = (220 * (1f - t)).toInt()
            ring.strokeWidth = 2.5f * density
            canvas.drawCircle(tapX, tapY, (10f + 22f * t) * density, ring)
            dot.alpha = (200 * (1f - t)).toInt()
            canvas.drawCircle(tapX, tapY, 4f * density, dot)
            dot.alpha = 255
        }
        if (count == 0) {
            if (tapping) postInvalidateOnAnimation()
            return
        }
        if (trail) for (i in 1 until count) {
            val a = (start + i - 1) % CAPACITY
            val b = (start + i) % CAPACITY
            val fade = (1f - (now - times[b]).toFloat() / LIFE_MS).coerceIn(0f, 1f)
            glow.alpha = (110 * fade).toInt()
            glow.strokeWidth = 12f * density * (0.35f + 0.65f * fade)
            canvas.drawLine(xs[a], ys[a], xs[b], ys[b], glow)
            core.alpha = (255 * fade).toInt()
            core.strokeWidth = 3f * density * (0.35f + 0.65f * fade)
            canvas.drawLine(xs[a], ys[a], xs[b], ys[b], core)
        }
        if (touching) {
            val head = (start + count - 1) % CAPACITY
            canvas.drawCircle(xs[head], ys[head], 6f * density, dot)
        }
        postInvalidateOnAnimation()
    }

    private companion object {
        const val CAPACITY = 512
        const val LIFE_MS = 650L
        const val TAP_LIFE_MS = 600L
    }
}

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
) : FrameLayout(context, attrs, defStyleAttr), InProgressStrokesFinishedListener, MemoryTrim.Trimmable {

    var tool: Tool = Tool.PEN
    var colorArgb: Int = 0xFF000000.toInt()
    var strokeWidth: Float = 5f
    var stabilizer: Int = 0
    var autoShapeRecognitionEnabled: Boolean = false

    /** A pen stroke held still at its end for [CIRCLE_HOLD_MS] becomes the shape it looks like. */
    var holdToDraw: Boolean = false
    private var holdTracking = false
    /** Set at pen-up when the hold was long enough; the commit that follows reads and clears it. */
    private var recognizeOnFinish = false
    var axisSnapEnabled: Boolean = true

    /** How round a polygon shape's corners are drawn, in page units; 0 is sharp. */
    var shapeCornerRadius: Float = 0f
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

    /**
     * Draw opaque pen and pressure-pen ink with Ink's mesh shader instead of a
     * filled Path. Screen only: saved strokes keep their family either way.
     */
    var meshInk: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            dry.clearInkTiles()
            dry.invalidate()
        }

    /**
     * [stroke] sharing its mesh but with its on-screen brush, or null if that is
     * the same brush. Built from the stroke's own shape: Stroke.copy re-tessellates
     * whenever the family differs, which made every pen stroke on a page be
     * built twice. Only the self-overlap differs between the two families, and
     * the twin is used for opaque ink alone, where overlap does not show.
     */
    private fun meshTwinOf(stroke: Stroke): Stroke? {
        val brush = screenBrush(stroke.brush)
        if (brush === stroke.brush) return null
        return runCatching { Stroke(brush, stroke.inputs, stroke.shape) }.getOrElse { stroke.copy(brush) }
    }

    /**
     * Twin brushes by the brush they stand for. A page of one pen shares one
     * Brush, so this turns a native copy per stroke into one per pen.
     */
    private val screenBrushes = IdentityHashMap<Brush, Brush>()

    /** The brush Ink should draw [brush] with on screen; see [meshInk]. */
    private fun screenBrush(brush: Brush): Brush {
        if (!meshInk || Color.alpha(brush.colorIntArgb) != 255) return brush
        synchronized(screenBrushes) { screenBrushes[brush]?.let { return it } }
        val family = Tool.meshFamilyOf(brush.family)
        val twin = if (family == null) brush else brush.copy(family = family)
        synchronized(screenBrushes) {
            if (screenBrushes.size > MAX_SCREEN_BRUSHES) screenBrushes.clear()
            screenBrushes[brush] = twin
        }
        return twin
    }

    /** The pen points instead of writing: a fading red trail that is never saved. */
    var laserMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) laser.clear()
        }

    /** The laser as a dot alone, or a dot with a fading trail. */
    var laserTrail: Boolean
        get() = laser.trail
        set(value) { laser.trail = value }

    /** Cut strokes where the eraser crosses them instead of removing them whole. */
    var partialEraser: Boolean = false

    /**
     * Diameter of the eraser tip in dp on screen. Unlike [strokeWidth] it does
     * not follow the zoom: the same eraser covers the same part of the screen
     * zoomed in or out; see [eraserPageWidth].
     */
    var eraserWidth: Float = 24f

    /** What the eraser may take: pen ink (everything but highlighter), highlighter, tape. */
    var eraseInk: Boolean = true
    var eraseHighlighter: Boolean = true
    var eraseTape: Boolean = true

    /** A pen stroke that scribbles back and forth over ink erases that ink instead. */
    var scribbleErase: Boolean = false

    /** A pen loop held still at its end turns into a lasso selection. */
    var circleToLasso: Boolean = false

    /** The lasso takes only strokes lying wholly inside the loop, not those whose middle is. */
    var lassoWholeOnly: Boolean = false

    /** What a loop picks up: pen ink, highlighter, pictures, text boxes. */
    var lassoInk: Boolean = true
    var lassoHighlighter: Boolean = true
    var lassoPictures: Boolean = true
    var lassoText: Boolean = true

    /** Locked objects are skipped by the lasso and the eraser unless these say otherwise. */
    var selectLocked: Boolean = false
    var eraseLocked: Boolean = false

    /** Pictures and text boxes go whole when the eraser touches them. Off, like ink-only erasing. */
    var eraseImages: Boolean = false
    var eraseText: Boolean = false

    /** A dragged selection catches on the page's edges and middle and on pictures' edges. */
    var snapToAlign: Boolean = true

    /** Dragging a picture's corner keeps its proportions; off, width and height go their own ways. */
    var keepAspect: Boolean = true

    /** What one finger does: [FINGER_SCROLL], [FINGER_IGNORED] or [FINGER_DRAW]. */
    var oneFinger: Int = FINGER_SCROLL
    /** What two fingers do: [TWO_ZOOM_PAN], [TWO_SCROLL] or [TWO_IGNORED]. */
    var twoFingers: Int = TWO_ZOOM_PAN
    /** Pinching moves the page but never changes its zoom. */
    var zoomLocked: Boolean = false
    /** A one-finger double tap zooms in, or back out to the page width. */
    var doubleTapZoom: Boolean = false
    /** What a two- and a three-finger double tap do: [TAP_NONE], [TAP_UNDO], [TAP_REDO]. */
    var twoFingerTap: Int = TAP_UNDO
    var threeFingerTap: Int = TAP_REDO
    /** Off, three fingers on the page do nothing: no tap action, no reference panel, no turning. */
    var threeFingerGestures: Boolean = true
    /** A finger held still on the page; null leaves long presses alone. */
    var onLongPressCanvas: ((Float, Float) -> Unit)? = null
    private var fingerDrawing = false
    private val fingerLongPress = Runnable {
        if (gestureMaxPointers == 1 && gestureMoved < TAP_SLOP_PX) {
            onLongPressCanvas?.invoke(lastFocusX, lastFocusY)
            longPressFired = true
        }
    }
    private var longPressFired = false

    /** What holding the pen's barrel button while writing does: one of PEN_BUTTON_*. */
    var penButton: Int = PEN_BUTTON_ERASE

    /** The eraser grows with pressure: a light touch takes a fraction of [eraserWidth], a firm one all of it. */
    var pressureEraser: Boolean = false

    /** See [pressureCurve]. */
    var pressureResponse: Int = PRESSURE_RESPONSE_DEFAULT

    /** See [AdaptiveStrokeStabilizer.slidingWindow]. */
    var windowedStabilizer: Boolean = false

    /** The lasso is a rectangle from where the pen landed to where it is. */
    var lassoRect: Boolean = false
    private val lassoCorner = FloatArray(2)

    /**
     * How the page is coloured on screen: [PAGE_TONE_NORMAL], [PAGE_TONE_SEPIA]
     * or [PAGE_TONE_NIGHT]. Only the display: the note, its export and its
     * PDF keep their colours. Applied to the whole recorded page, ink
     * included, so black writing stays readable on a darkened page.
     */
    var pageTone: Int = PAGE_TONE_NORMAL
        set(value) {
            if (field == value) return
            field = value
            pageTonePaint.colorFilter = pageToneFilter(value)
            wetToneMatrix = pageToneMatrix(value)
            dry.invalidate()
        }
    private val pageTonePaint = Paint()
    /**
     * The same tone as a matrix, for ink that is not drawn inside the page:
     * wet ink lives on a front-buffered surface the page's colour filter never
     * reaches, so its brush colour is put through this instead; see [wetInkArgb].
     */
    private var wetToneMatrix: FloatArray? = null
    /** An eraser gesture with the eraser tool has ended. */
    var onEraseFinished: (() -> Unit)? = null

    /** The page is scrolled as far as it goes toward its end. */
    private var atDocumentEnd = false
    /** How far a finger has pulled on past the end, in screen pixels. */
    private var pullPastEnd = 0f
    private var pullArmed = false
    /** A pull past the last page is far enough to add one (true), or let go (false). */
    var onPullForPage: ((Boolean) -> Unit)? = null

    /** Linked objects wear a pale blue wash so they can be found. */
    var showLinkOverlay: Boolean = true
    /** A link to another note was followed: its id and the page index to open at. */
    var onOpenNoteLink: ((String, Int) -> Unit)? = null
    /** Pages left by following a link, newest last, for going back. */
    private val linkBackStack = ArrayList<Int>()
    /** Whether there is somewhere to go back to; called after every jump and return. */
    var onLinkBackChanged: ((Boolean) -> Unit)? = null

    /** Where the dragged selection caught, in page units, or NaN; drawn as guides. */
    private var guideX = Float.NaN
    private var guideY = Float.NaN

    /**
     * Reading rather than writing: the pen selects PDF text by dragging, the
     * way a mouse does, instead of waiting out a hold first. The hold exists so
     * that writing stays the default; when reading is the declared intent there
     * is nothing to disambiguate and nothing to wait for.
     * The S Pen button instead captures a dragged rectangle, or selects the
     * text between two separate taps when the pen stays within touch slop.
     */
    var readMode: Boolean = false
        set(value) {
            if (field != value) readButtonGesture.reset()
            field = value
        }

    /** Non-null while the pen draws shapes rather than following the hand. */
    var shapeKind: ShapeKind? = null
        set(value) {
            // Leaving the polygon tool keeps what was tapped out so far.
            if (field == ShapeKind.POLYGON && value != ShapeKind.POLYGON) finishPolygon(close = false)
            field = value
        }

    /** Corners of the polygon being tapped out, in page units of [polyPage]. */
    private val polyPoints = ArrayList<FloatArray>()
    private var polyPage = -1

    /** A shape's corner being dragged: which inputs sit there, and the stroke before the drag. */
    private var vertexInputs: IntArray? = null
    private var vertexOriginal: Stroke? = null
    private var vertexCurrent: Stroke? = null

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

    /** Stores a bitmap in this note and returns its picture id; for pictures pasted from another note. */
    var imageAdder: ((Bitmap) -> String?)? = null
    var templateLoader: ((String) -> Bitmap?)? = null

    /** Handed a rendering of the captured region, on a background thread. */
    var onCaptured: ((Bitmap) -> Unit)? = null

    /**
     * Fired on the UI thread, once per stroke, as real ink starts: a pen,
     * pencil, highlighter, tape or shape stroke, or an erase. Not for taps,
     * selections, captures, scrolling or zooming. What closes panels that
     * would otherwise sit over the writing.
     */
    var onWritingStarted: (() -> Unit)? = null

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

    /**
     * The soft head drawn past fast front-buffer ink. It is an ordinary View,
     * so each sample that moves it redraws the window - and with it a docked
     * web page, a reference popup or a split-screen neighbour's share of the
     * GPU - while the front buffer waits on the same RenderThread. The host
     * turns it off whenever such content shares the screen.
     */
    var softPredictionHead: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) { predictionHead.clear(); subframe.clear() }
        }

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

    /** 손가락 페이지 이동량과 fling 속도 배율입니다. */
    var viewportPanMultiplier: Float = 1f

    /**
     * The page's sides stay at or past the view's sides: no sliding the page
     * off to one side. For the popup, where the slack meant to bring a page
     * edge to the middle of the screen only lost the page in a small window.
     */
    var keepPageAcross: Boolean = false

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
    private val compatWet = CompatWetInkView(context)
    private var compatWetStrokeId: InProgressStrokeId? = null

    /**
     * Also draw the stroke being written on a surface of its own, for tablets
     * whose front-buffer layer only appears at pen-up; see [CompatWetInkView].
     */
    var compatWetInk: Boolean = frontBufferInkUnreliable()
        set(value) {
            if (field == value) return
            field = value
            compatWetStrokeId = null
            compatWet.inkEnabled = value
        }

    /**
     * False while the host rotates this view: the compatible wet ink then
     * draws inside the window, because a surface cannot follow a rotation.
     */
    var wetSurfaceAllowed: Boolean
        get() = compatWet.surfaceAllowed
        set(value) { compatWet.surfaceAllowed = value }

    /**
     * Degrees the note is turned on screen, about the middle of this view.
     *
     * The view itself is never turned. Turning it from outside clipped the
     * page to the turned rectangle, so the corners went missing, and the wet
     * ink - drawn on a surface, which cannot follow a rotation - landed away
     * from the pen. Instead everything in here keeps working in the unturned
     * ("layout") frame: touches are turned into it on the way in, children
     * are drawn turned on the way out, and only the wet layer stays in the
     * screen's frame, fed screen-frame events and a matching transform.
     */
    var viewRotation: Float = 0f
        set(value) {
            val turned = ((value % 360f) + 360f) % 360f
            if (field == turned) return
            field = turned
            updateRotation()
            dry.invalidate()
            invalidate()
            // A turned screen shows parts of the page an upright one did not.
            scheduleRefine()
        }
    private val layoutToScreen = Matrix()
    private val screenToLayout = Matrix()
    private val wetTransform = Matrix()
    private val rotationCorners = FloatArray(8)

    private fun updateRotation() {
        layoutToScreen.setRotate(viewRotation, width / 2f, height / 2f)
        layoutToScreen.invert(screenToLayout)
        // Turned, the children draw past their own edges into the corners.
        clipChildren = viewRotation == 0f
    }

    /** The part of the layout frame that is on screen, as the box round it: [left, top, right, bottom]. */
    private fun layoutViewport(out: FloatArray) {
        if (viewRotation == 0f) {
            out[0] = 0f; out[1] = 0f; out[2] = width.toFloat(); out[3] = height.toFloat()
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        rotationCorners[0] = 0f; rotationCorners[1] = 0f
        rotationCorners[2] = w; rotationCorners[3] = 0f
        rotationCorners[4] = w; rotationCorners[5] = h
        rotationCorners[6] = 0f; rotationCorners[7] = h
        screenToLayout.mapPoints(rotationCorners)
        out[0] = minOf(rotationCorners[0], rotationCorners[2], rotationCorners[4], rotationCorners[6])
        out[1] = minOf(rotationCorners[1], rotationCorners[3], rotationCorners[5], rotationCorners[7])
        out[2] = maxOf(rotationCorners[0], rotationCorners[2], rotationCorners[4], rotationCorners[6])
        out[3] = maxOf(rotationCorners[1], rotationCorners[3], rotationCorners[5], rotationCorners[7])
    }

    /** [event], in the layout frame, back in the screen's frame for the wet layer. Recycle it when not the same. */
    private fun wetEvent(event: MotionEvent): MotionEvent {
        if (viewRotation == 0f) return event
        return mappedEvent(event, layoutToScreen)
    }

    private var mappedProperties = emptyArray<MotionEvent.PointerProperties>()
    private var mappedCoordinates = emptyArray<MotionEvent.PointerCoords>()

    /**
     * A new event with every sample of [event] passed through [matrix], baked
     * into its coordinates rather than kept as the event's own transform.
     *
     * MotionEvent.transform only records a transform beside the untouched raw
     * samples, and the platform's motion predictor works on those raw samples
     * and applies the newest event's transform to its guess. Fed a mix of
     * transformed copies (raw = screen) and events rebuilt from coordinates
     * (raw = turned), it extrapolated across the jump between the two frames:
     * the long straight line that shot off the pen on a turned note.
     */
    private fun mappedEvent(event: MotionEvent, matrix: Matrix): MotionEvent {
        val turned = MotionEvent.obtain(event)
        turned.transform(matrix)
        try {
            val count = turned.pointerCount
            if (mappedProperties.size < count) {
                mappedProperties = Array(count) { MotionEvent.PointerProperties() }
                mappedCoordinates = Array(count) { MotionEvent.PointerCoords() }
            }
            for (i in 0 until count) turned.getPointerProperties(i, mappedProperties[i])
            val samples = turned.historySize
            fun fill(history: Int) {
                for (i in 0 until count) {
                    if (history < 0) turned.getPointerCoords(i, mappedCoordinates[i])
                    else turned.getHistoricalPointerCoords(i, history, mappedCoordinates[i])
                }
            }
            fill(if (samples > 0) 0 else -1)
            val result = MotionEvent.obtain(
                turned.downTime,
                if (samples > 0) turned.getHistoricalEventTime(0) else turned.eventTime,
                turned.action,
                count,
                mappedProperties,
                mappedCoordinates,
                turned.metaState,
                turned.buttonState,
                turned.xPrecision,
                turned.yPrecision,
                turned.deviceId,
                turned.edgeFlags,
                turned.source,
                turned.flags,
            )
            if (samples > 0) {
                for (history in 1 until samples) {
                    fill(history)
                    result.addBatch(turned.getHistoricalEventTime(history), mappedCoordinates, turned.metaState)
                }
                fill(-1)
                result.addBatch(turned.eventTime, mappedCoordinates, turned.metaState)
            }
            return result
        } finally {
            turned.recycle()
        }
    }

    private inline fun <T> withWetEvent(event: MotionEvent, block: (MotionEvent) -> T): T {
        val screen = wetEvent(event)
        try {
            return block(screen)
        } finally {
            if (screen !== event) screen.recycle()
        }
    }

    /** [toPage], from the layout frame, made to start from the screen's frame instead. */
    private fun wetTransformOf(toPage: Matrix): Matrix {
        if (viewRotation == 0f) return toPage
        wetTransform.set(toPage)
        wetTransform.preConcat(screenToLayout)
        return wetTransform
    }

    /** The touch as the screen saw it, while a turned copy of it is being handled. */
    private var screenEvent: MotionEvent? = null

    /** Runs [handle] on [event] turned into the layout frame. */
    private inline fun inLayoutFrame(event: MotionEvent, handle: (MotionEvent) -> Boolean): Boolean {
        // Upright, every event goes through untouched: rebuilding each one cost
        // an allocation per 240Hz sample for nothing.
        if (viewRotation == 0f) return handle(event)
        val turned = mappedEvent(event, screenToLayout)
        val outer = screenEvent
        screenEvent = event
        try {
            return handle(turned)
        } finally {
            screenEvent = outer
            turned.recycle()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (viewRotation == 0f) {
            super.dispatchDraw(canvas)
            return
        }
        canvas.save()
        canvas.concat(layoutToScreen)
        try {
            super.dispatchDraw(canvas)
        } finally {
            canvas.restore()
        }
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        // The wet layer's surface cannot turn; its window-side drawing must not either.
        if (viewRotation == 0f || child !== wet) return super.drawChild(canvas, child, drawingTime)
        canvas.save()
        canvas.concat(screenToLayout)
        try {
            return super.drawChild(canvas, child, drawingTime)
        } finally {
            canvas.restore()
        }
    }

    private fun cancelCompatWet(strokeId: InProgressStrokeId?) {
        if (strokeId == null) return
        if (strokeId == compatWetStrokeId) compatWetStrokeId = null
        compatWet.cancel(strokeId)
    }

    /**
     * The eraser keeps one size on screen whatever the zoom: [eraserWidth] is
     * its diameter in dp, and this is how much page that covers right now.
     */
    private fun eraserPageWidth(): Float =
        (eraserWidth * resources.displayMetrics.density / currentScale()).coerceAtLeast(0.01f)

    private fun eraserScreenRadius(): Float =
        (eraserWidth * resources.displayMetrics.density / 2f).coerceAtLeast(0.5f)
    private val predictionHead = PredictionHeadView(context)
    private val subframe = SubframeBlurView(context)
    private val subframeTrack = SubframeTrack()

    /** [SUBFRAME_OFF], [SUBFRAME_BLUR] or [SUBFRAME_GHOST]: the pen's in-between travel shown ahead of the tip. */
    var subframeMode: Int
        get() = subframe.mode
        set(value) { subframe.mode = value }
    // The laser pointer is gone; its view draws the ring a button tap leaves in reading mode.
    private val laser = LaserView(context).apply { tint(GESTURE_TRAIL_COLOR) }
    private var gestureTrail = false
    private var gestureButton = false
    private var gestureTrailMoved = false
    private var gestureDownX = 0f
    private var gestureDownY = 0f

    /**
     * In reading mode the pen is only followed far enough to tell a tap from a
     * drag; nothing is drawn where it goes.
     */
    private fun startGestureTrail(event: MotionEvent, button: Boolean) {
        val i = event.actionIndex
        gestureTrail = true
        gestureButton = button
        gestureTrailMoved = false
        gestureDownX = event.getX(i)
        gestureDownY = event.getY(i)
    }

    private fun addGestureTrail(event: MotionEvent, index: Int) {
        if (hypot(event.getX(index) - gestureDownX, event.getY(index) - gestureDownY) > touchSlop) gestureTrailMoved = true
    }

    /** A tap with the button held leaves a ring where it landed; a drag leaves nothing. */
    private fun finishGestureTrail(event: MotionEvent) {
        if (!gestureTrail) return
        gestureTrail = false
        if (gestureButton && !gestureTrailMoved) laser.tapAt(gestureDownX, gestureDownY, event.eventTime)
        laser.lift()
    }
    private val ruler = RulerView(context).apply { visibility = GONE }
    private val eyedropper = EyedropperView(context)

    /** The next touch picks a colour off the screen instead of drawing; [onPicked] gets it. */
    fun pickColor(onPicked: (Int) -> Unit) {
        eyedropper.onPicked = onPicked
    }

    /** The straightedge laid on the screen, or null for none. */
    var rulerKind: RulerKind? = null
        set(value) {
            field = value
            if (value != null) ruler.kind = value
            ruler.visibility = if (value == null) GONE else VISIBLE
        }

    var rulerInches: Boolean
        get() = ruler.inches
        set(value) { ruler.inches = value }

    /** The ruler asked to go away: pinched shut or dragged off the screen. */
    var onRulerClosed: (() -> Unit)?
        get() = ruler.onClosed
        set(value) { ruler.onClosed = value }

    fun setRulerDegrees(degrees: Float) = ruler.setDegrees(degrees)

    /** The edge the pen is drawing along, and the line so far in page units. */
    private var rulerEdge: RulerEdge? = null
    private val rulerPoints = ArrayList<FloatArray>()
    private val rulerScratch = FloatArray(2)
    private var laserPointer: Int? = null
    private val dry = DryLayer(context)
    /** Android chooses its own horizon, so an overlong result is clipped locally. */
    private val predictor: MotionEventPredictor
    /** The common one-pen prediction path reuses these instead of feeding the GC every sample. */
    private val predictedPointerProperties = arrayOf(MotionEvent.PointerProperties())
    private val predictedPointerCoordinates = arrayOf(MotionEvent.PointerCoords())
    /** Lets the latency readout's measurement steer automatic prediction; see [measuredInkLatencyMs]. */
    var latencyTunesPrediction: Boolean = false
    private var measuredLatencyAt = Long.MIN_VALUE
    private var measuredLatency: Double? = null

    /**
     * The wet ink's measured latency, for automatic prediction: known only
     * while the latency readout is on, and re-read twice a second at most,
     * since reading it sorts every sample kept.
     */
    private fun measuredInkLatencyMs(now: Long): Double? {
        // Off unless asked for: with the readout feeding the horizon, turning
        // the readout on changed how the pen behaved, and a with/without
        // comparison measured two different prediction policies.
        if (!latencyMonitoringEnabled || !latencyTunesPrediction) return null
        if (now - measuredLatencyAt >= MEASURED_LATENCY_REFRESH_MS) {
            measuredLatencyAt = now
            measuredLatency = latency.snapshot(PerformanceMetric.WET_INK)?.p50
        }
        return measuredLatency
    }

    private val predictionPolicy = InkPredictionPolicy().apply {
        pxPerMm = resources.displayMetrics.xdpi / 25.4f
    }
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

    /**
     * Three fingers turning on the page turn the note, by the degrees passed;
     * [onRotateNoteEnd] when the hand lets go. Without a listener three
     * fingers keep only their panel and redo uses.
     */
    var onRotateNote: ((Float) -> Unit)? = null
    var onRotateNoteEnd: (() -> Unit)? = null
    private var rotating3f = false
    /** Whether this hand turned the note at all, so its lift is not also a three-finger tap. */
    private var turnedThisGesture = false
    private var turn3fSoFar = 0f
    private var fingerAngleCount = 0
    private val fingerAngleIds = IntArray(MAX_TURN_POINTERS)
    private val fingerAngles = FloatArray(MAX_TURN_POINTERS)
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

    /** The once-a-frame part of a moving viewport; see onTransformChanged. Declared before init, which calls it. */
    private var viewportFramePosted = false
    private val viewportFrame = Runnable {
        viewportFramePosted = false
        if (!disposed) afterTransform()
    }

    /** The zoom factor the scale detector found for the MOVE being handled; applied with its pan. */
    private var pendingPinch = 1f
    /** Scale, translate x, translate y of [documentToScreen], for [pinchStep]. */
    private val viewportState = FloatArray(3)

    /**
     * Whether this canvas has told [InteractionGovernor] the page is moving:
     * from the first finger movement that moves it until the hand lifts, or,
     * if it flings, until the fling ends or a touch stops it.
     */
    private var movingHeld = false

    private fun holdMoving() {
        if (movingHeld) return
        movingHeld = true
        InteractionGovernor.beginMoving(this)
    }

    private fun releaseMoving() {
        if (!movingHeld) return
        movingHeld = false
        InteractionGovernor.endMoving(this)
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val readButtonGesture = ReadButtonGesture(touchSlop)
    private var readingWithButton = false
    private var selection: PdfSelection? = null
    private var selectingPage = -1
    private var selectionAnchor: RectF? = null
    private var selectingText = false
    private var readPressTime = 0L

    /** Ink as it lands, for timing it against a recording. */
    var onStrokesCommitted: ((Int, List<Stroke>) -> Unit)? = null

    /**
     * While set, a reading tap on handwriting is offered here first - how a
     * recording seeks to when that ink was written. Return true to consume it.
     */
    var onStrokeTapped: ((Int, Stroke) -> Boolean)? = null

    private val strokeKeys = java.util.WeakHashMap<Stroke, String>()
    private var playbackHighlights: Set<Stroke> = emptySet()
    private var playbackHighlightKeys: Map<Int, Set<String>> = emptyMap()

    /** The stable key a recording timeline stores for a stroke. */
    fun keyOf(pageIndex: Int, stroke: Stroke): String = strokeKeys.getOrPut(stroke) {
        MaskStudy.keyOf(document.pages.getOrNull(pageIndex)?.id.orEmpty(), stroke)
    }

    /** Marks the strokes written around the current playback time; empty clears it. */
    fun setPlaybackHighlight(keys: Map<Int, Set<String>>) {
        if (keys == playbackHighlightKeys) return
        playbackHighlightKeys = keys
        val found: MutableSet<Stroke> = Collections.newSetFromMap(IdentityHashMap())
        for ((pageIndex, pageKeys) in keys) {
            val page = document.pages.getOrNull(pageIndex) ?: continue
            for (stroke in page.strokes) if (keyOf(pageIndex, stroke) in pageKeys) found += stroke
        }
        playbackHighlights = found
        dry.invalidate()
    }

    /** A web link tapped in a PDF; page links are followed here directly. */
    var onLinkUrl: ((String) -> Unit)? = null

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
    /** Pictures and text boxes the loop caught, alongside [lassoStrokes]. */
    private val lassoImages = ArrayList<PageImage>()
    /**
     * What the loop would take if the pen lifted now, refreshed while it is
     * drawn so the catch shows before it is made. Never the selection itself.
     */
    private val lassoPreviewStrokes: MutableSet<Stroke> = Collections.newSetFromMap(IdentityHashMap())
    private val lassoPreviewImages = ArrayList<PageImage>()
    private var lassoPreviewAt = 0L
    private var movingLasso = false
    private var liftedLassoStrokes: Set<Stroke>? = null
    private val lassoGrab = floatArrayOf(0f, 0f)
    private var lassoDx = 0f
    private var lassoDy = 0f

    // Circle to lasso: the pen's path is kept in [lassoPath] while it writes,
    // and where it last came to rest. Moving only updates these fields; the
    // timer is what looks at them, once the pen has stood still long enough.
    private var circleTracking = false
    private var holdX = 0f
    private var holdY = 0f
    private var holdSince = 0L
    /** The rest already judged not to be a loop, so a pen left standing is not asked again. */
    private var checkedHold = -1L
    private val circleHold: Runnable = object : Runnable {
        override fun run() {
            if (!circleTracking) return
            val waited = SystemClock.uptimeMillis() - holdSince
            if (waited >= CIRCLE_HOLD_MS && checkedHold != holdSince) {
                checkedHold = holdSince
                convertCircleToLasso()
                if (!circleTracking) return
            }
            postDelayed(this, if (waited < CIRCLE_HOLD_MS) CIRCLE_HOLD_MS - waited else CIRCLE_HOLD_MS)
        }
    }

    private var selectedImage: PageImage? = null
    private var selectedImagePage: Page? = null
    private var movingImage = false
    private var resizingImage = false
    private val imageGrab = floatArrayOf(0f, 0f)
    private val imagePoint = FloatArray(2)
    private val maskProbe = floatArrayOf(0f, 0f)
    private var pressX = 0f
    private var pressY = 0f
    private var holdingStationaryStart = false
    private var stationaryStartX = 0f
    private var stationaryStartY = 0f
    private var stationaryStartTime = 0L
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
    private var lastInkEditMillis = 0L
    @Volatile private var inkCacheWorkGeneration = 0
    private val inkCacheRunnable = Runnable { if (!disposed) dry.postInvalidateOnAnimation() }
    private class CachedInk(
        val bitmap: Bitmap,
        val prefix: InkRenderPrefix<Stroke>,
        val meshRevision: Long,
    )
    private var refineRunnablePosted = false
    private val refineRunnable = Runnable {
        refineRunnablePosted = false
        refineVisiblePages()
    }
    /**
     * The pages round the screen, once it has stopped. How far is the
     * governor's call (see [prefetchSpan]): nothing while a pen writes or the
     * page moves - the request waits, and [prefetchWhenQuiet] brings it back
     * when the hands come off - narrower in battery saver, none when hot.
     * Never re-posts itself: an idle canvas schedules nothing.
     */
    private val prefetchRunnable = Runnable {
        if (disposed) return@Runnable
        val span = prefetchSpan(InteractionGovernor.state, InteractionGovernor.powerSave, InteractionGovernor.thermalStatus)
        if (span == null) {
            prefetchWanted = true
            return@Runnable
        }
        prefetchWanted = false
        if (span.isEmpty) return@Runnable
        prioritizePdf(span)
        prefetchNearbyPages(span)
    }

    /** A stopped-viewport prefetch was asked for while the hands were busy. */
    private var prefetchWanted = false
    private val prefetchWhenQuiet: (Interaction) -> Unit = { state ->
        if (prefetchWanted && state == Interaction.IDLE && !disposed && isAttachedToWindow) {
            removeCallbacks(prefetchRunnable)
            post(prefetchRunnable)
        }
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
        class ImageReplaced(
            override val page: Page,
            val before: PageImage,
            val after: PageImage,
            val at: Int,
            val group: Long = 0L,
        ) : Edit
        class ImageAdded(override val page: Page, val image: PageImage) : Edit
        class ImageRemoved(override val page: Page, val image: PageImage, val at: Int, val group: Long = 0L) : Edit
        class ImageAddedInGroup(override val page: Page, val image: PageImage, val group: Long) : Edit

        /** A lasso drag: the strokes as they were, and where they ended up. */
        class Moved(
            override val page: Page,
            var before: List<Stroke>,
            var after: List<Stroke>,
            val group: Long = 0L,
        ) : Edit

        class MaskAdded(override val page: Page, val mask: PageMask, val group: Long = 0L) : Edit
        class MaskRemoved(
            override val page: Page,
            val mask: PageMask,
            val at: Int,
            val group: Long = 0L,
        ) : Edit

        /** A page put in at [at], whether new or a copy. */
        class PageInserted(override val page: Page, val at: Int) : Edit
        /** A page taken out of [at]; it keeps its ink in memory so undo can put it back. */
        class PageRemoved(override val page: Page, val at: Int) : Edit
        class PageMoved(override val page: Page, val from: Int, val to: Int) : Edit
        /** Paper changed: background, template, and the height an infinite page takes. */
        class PagePaper(
            override val page: Page,
            val before: PaperState,
            val after: PaperState,
            val group: Long = 0L,
        ) : Edit
        /** A quarter turn of everything on a page; undone by the opposite turn. */
        class PageRotated(override val page: Page, val clockwise: Boolean) : Edit

        /**
         * A layer's ink on one page, taken away with the layer ([gone]) or
         * brought by a copy of it. Shown or hidden alike, unlike [Erased].
         */
        class LayerInk(
            override val page: Page,
            var strokes: List<Stroke>,
            val gone: Boolean,
            val group: Long,
        ) : Edit

        /**
         * A layer taken out of the note's list ([gone]) or put in as a copy.
         * Note-wide; [page] is only where history files it. Its ink rides in
         * [LayerInk] steps of the same group.
         */
        class LayerEntry(
            override val page: Page,
            val removed: NoteLayers.Removed,
            val gone: Boolean,
            val group: Long,
        ) : Edit
    }

    private data class PaperState(
        val background: PageBackground,
        val templateId: String?,
        val height: Float,
        val width: Float = 0f,
    )

    private fun Page.paper() = PaperState(background, templateId, height, width)

    private fun Page.setPaper(state: PaperState) {
        background = state.background
        templateId = state.templateId
        height = state.height
        if (state.width > 0f) width = state.width
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                // Clamping the *result* rather than the factor, so a pinch that
                // would overshoot the limit still zooms up to it.
                val minimum = if (minimumScaleIsFitWidth && fitScale > 0f) fitScale else MIN_SCALE
                val factor = pinchedScale(current, detector.scaleFactor)
                    .coerceIn(minimum, maxScale()) / current
                // Held for the MOVE that brought it, which scales about the
                // old centroid and carries that point to the new one in a
                // single step (see pinchStep); the page under the fingers
                // stays under them.
                pendingPinch *= factor
                return true
            }
        },
    ).apply {
        isQuickScaleEnabled = false
        isStylusScaleEnabled = false
    }
    private val referenceDismissGesture = ReferenceDismissGesture(touchSlop * 2f)

    init {
        // A ViewGroup only gets onTouchEvent once no child has taken the event;
        // being clickable is what keeps the DOWN from being dropped outright.
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        addView(dry, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(compatWet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(predictionHead, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(subframe, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(laser, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ruler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(eyedropper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wet.textureBitmapStore = PencilTextureStore
        compatWet.inkEnabled = compatWetInk
        compatWet.onFramePosted = { nanos -> if (latencyMonitoringEnabled) latency.add(nanos) }
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
                // The front buffer is not what this panel shows; the compatible
                // surface reports its own frames instead.
                if (compatWetInk) return
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

    private var registeredDocument: Document? = null
    private val sharedInkChanged: () -> Unit = {
        if (!disposed) {
            inkCacheWorkGeneration++
            dry.postInvalidateOnAnimation()
        }
    }
    private var pendingClose: (() -> Unit)? = null
    private val pendingStrokeCommits = HashSet<InProgressStrokeId>()

    /** Finger contact may close immediately; a pen stroke finishes before saving/removal. */
    fun requestSafeClose(onReady: () -> Unit) {
        if (activeStylusPointer != null || pendingStrokeCommits.isNotEmpty() || wet.hasUnfinishedStrokes()) pendingClose = onReady
        else onReady()
    }

    private fun completePendingClose() {
        if (activeStylusPointer != null || pendingStrokeCommits.isNotEmpty() || wet.hasUnfinishedStrokes()) return
        val ready = pendingClose ?: return
        pendingClose = null
        ready()
    }

    /** A second canvas must not replace geometry referenced by this canvas's undo history. */
    fun suspendSharedDetail() {
        if (disposed) return
        removeCallbacks(refineRunnable)
        refineRunnablePosted = false
        refineRequestSerial++
        refineFuture?.cancel(true)
        refineFuture = null
        for (page in refiningPages) {
            if (page.renderState == RenderState.Rendering) page.renderState = RenderState.Dirty
        }
        refiningPages = emptyList()
    }

    fun refreshSharedInk() {
        if (disposed) return
        dry.postInvalidateOnAnimation()
        onStrokesChanged?.invoke()
        scheduleRefine()
    }

    /**
     * What becomes of this view's PDF when it is done with it. Closed, unless
     * the screen hands it to [PdfHold] so that coming straight back is quick.
     */
    var releasePdf: (PdfSource) -> Unit = { it.close() }

    fun open(document: Document, pdf: PdfSource?, initialPage: Int = 0) {
        registeredDocument?.detachCanvas(sharedInkChanged)
        registeredDocument = document
        document.attachCanvas(sharedInkChanged)
        this.pdf?.diagnostics = null
        this.pdf?.takeIf { it !== pdf }?.let(releasePdf)
        if (this.document.latency === latency) this.document.latency = null
        this.document = document
        document.latency = latency
        prefetchedPending.clear()
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
        // Only a page on screen is worth a redraw. Prefetched pages landing
        // each redrew the whole canvas, and with it every pane of glass over it.
        pdf?.onReady = { index ->
            post { if (!disposed && visiblePages().any { it.pdfPageIndex == index }) dry.postInvalidateOnAnimation() }
        }
        undoStack.clear()
        redoStack.clear()
        lockedNoticeLayer = -1
        layersRevision.intValue++
        fitted = false
        pageToRestore = initialPage.coerceIn(document.pages.indices)
        requestLayout()
        dry.invalidate()
    }

    /**
     * A pen stroke that starts at the screen's edge is writing, not the system's
     * back gesture. Android honours up to 200dp of each edge; ask for the middle.
     */
    private fun excludeEdgesFromBackGesture(w: Int, h: Int) {
        val band = (resources.displayMetrics.density * EDGE_EXCLUSION_DP).toInt()
        val tall = (resources.displayMetrics.density * 200).toInt().coerceAtMost(h)
        val top = (h - tall) / 2
        systemGestureExclusionRects = listOf(
            android.graphics.Rect(0, top, band, top + tall),
            android.graphics.Rect(w - band, top, w, top + tall),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateRotation()
        excludeEdgesFromBackGesture(w, h)
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MemoryTrim.register(this)
        InteractionGovernor.init(context)
        InteractionGovernor.noteRefreshRate(display?.refreshRate)
        InteractionGovernor.addListener(prefetchWhenQuiet)
    }

    /**
     * The pen is down (or has just lifted). Work the pen does not need gives
     * way while it writes: PDF prefetch is put aside, page loads use fewer cores.
     */
    private fun penWorking(active: Boolean) {
        pdf?.penActive = active
        // Counted per canvas: the reference panel lifting its pen, or being
        // closed, no longer ends the note's own stroke for the background work.
        if (active) InteractionGovernor.beginWriting(this) else InteractionGovernor.endWriting(this)
    }

    /**
     * How hot the device says it is. From moderate on, background work is cut
     * back - fewer cores for page loads, no prefetch of the pages around - so
     * the tablet spends its budget on the ink on screen and cools.
     */
    private val thermalHot: Boolean get() = InteractionGovernor.thermalStatus >= THERMAL_MODERATE

    /** Android wants memory back: everything here that a redraw can rebuild goes. */
    override fun trimMemory(severe: Boolean) {
        pdf?.trimMemory(severe)
        dry.trimCaches(severe)
        if (!disposed) dry.postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        MemoryTrim.unregister(this)
        // Detached mid-stroke would leave background work waiting for a lift that never comes.
        pdf?.penActive = false
        stopFling(resumeDetail = false)
        movingHeld = false
        InteractionGovernor.forget(this)
        InteractionGovernor.removeListener(prefetchWhenQuiet)
        if (document.latency === latency) document.latency = null
        registeredDocument?.detachCanvas(sharedInkChanged)
        registeredDocument = null
        removeCallbacks(refineRunnable)
        removeCallbacks(prefetchRunnable)
        removeCallbacks(viewportFrame)
        viewportFramePosted = false
        prefetchWanted = false
        prefetchedPending.clear()
        removeCallbacks(inkCacheRunnable)
        dry.discardInkNodes()
        disposed = true
        refineRequestSerial++
        viewportRenderGeneration++
        refineFuture?.cancel(true)
        refineFuture = null
        refiningPages = emptyList()
        refiner.shutdownNow()
        selectionWorker.shutdownNow()
        thumbnailWorker.shutdownNow()
        predictionHead.clear(); subframe.clear(); subframeTrack.reset()
        pdf?.diagnostics = null
        pdf?.let(releasePdf)
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

    /**
     * Where a pinch takes the scale. Up to [LINEAR_ZOOM_FROM] times the fit it
     * multiplies, as pinches do, so the page stays under the fingers. Past it
     * the same spread of the fingers adds the same amount of zoom wherever it
     * starts: multiplying all the way made each pinch worth more than the last,
     * so the percentage shot up and 1000% came after barely any movement.
     * Measured in the log of the spread so that pinching in undoes exactly
     * what spreading out did.
     */
    private fun pinchedScale(current: Float, scaleFactor: Float): Float {
        if (fitScale <= 0f || scaleFactor <= 0f) return current * scaleFactor
        val knee = fitScale * LINEAR_ZOOM_FROM
        val natural = current * scaleFactor
        return when {
            current < knee && natural <= knee -> natural
            // Crossing upward: only the part past the knee goes linear.
            current < knee -> knee + knee * ln(natural / knee)
            else -> {
                val next = current + knee * ln(scaleFactor)
                // Crossing downward: what is left below the knee multiplies again.
                if (next >= knee) next else knee * exp((next - knee) / knee)
            }
        }
    }

    /** Zooms to [zoom] times fit-to-width, about the middle of the view. */
    fun zoomTo(zoom: Float) {
        if (fitScale <= 0f || width == 0 || zoom <= 0f) return
        zoomBy(fitScale * zoom / currentScale(), width / 2f, height / 2f)
        reportZoom(force = true)
    }

    /** Zooms about the top-left corner by default, which is the corner a resize keeps. */
    private fun zoomBy(factor: Float, pivotX: Float = 0f, pivotY: Float = 0f) {
        if (factor <= 0f || abs(factor - 1f) < 1e-4f) return
        val current = currentScale()
        val minimum = if (minimumScaleIsFitWidth && fitScale > 0f) fitScale else MIN_SCALE
        val applied = (current * factor).coerceIn(minimum, maxScale()) / current
        documentToScreen.postScale(applied, applied, pivotX, pivotY)
        onTransformChanged()
    }

    /**
     * A hardware keyboard: Ctrl/Cmd+Z, Ctrl/Cmd+Shift+Z or +Y, Ctrl/Cmd and +/-
     * to zoom, arrows to move, Alt+arrows and Page Up/Down for the next page.
     */
    /** App-level keyboard commands the canvas does not own: tools, go-to-page. True when handled. */
    var onShortcut: ((Int, KeyEvent) -> Boolean)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (onShortcut?.invoke(keyCode, event) == true) return true
        val command = event.isCtrlPressed || event.isMetaPressed
        val step = min(width, height) * KEY_PAN_FRACTION
        when {
            command && keyCode == KeyEvent.KEYCODE_Z ->
                if (event.isShiftPressed) onRedo?.invoke() else onUndo?.invoke()
            command && keyCode == KeyEvent.KEYCODE_Y -> onRedo?.invoke()
            command && (keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_PLUS ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ADD) -> zoomBy(KEY_ZOOM_STEP, width / 2f, height / 2f)
            command && (keyCode == KeyEvent.KEYCODE_MINUS || keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT) ->
                zoomBy(1f / KEY_ZOOM_STEP, width / 2f, height / 2f)
            keyCode == KeyEvent.KEYCODE_PAGE_UP ||
                (event.isAltPressed && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -> scrollToPage(currentPage - 1)
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
                (event.isAltPressed && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) -> scrollToPage(currentPage + 1)
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> panBy(0f, step)
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> panBy(0f, -step)
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> panBy(step, 0f)
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> panBy(-step, 0f)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun panBy(dx: Float, dy: Float) {
        documentToScreen.postTranslate(dx, dy)
        onTransformChanged()
    }

    /**
     * A mouse wheel or a trackpad's two-finger scroll: the wheel moves the
     * page, Shift+wheel moves it sideways, and Ctrl (or Cmd)+wheel zooms
     * about the pointer, so the spot under it stays put.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean = inLayoutFrame(event) { onGenericInLayout(it) }

    private fun onGenericInLayout(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL ||
            !event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)
        ) return super.onGenericMotionEvent(event)
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val meta = event.metaState
        if (meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON) != 0) {
            if (vertical != 0f) zoomBy(exp(vertical * WHEEL_ZOOM_PER_NOTCH), event.x, event.y)
            return true
        }
        val config = android.view.ViewConfiguration.get(context)
        var dx = -horizontal * config.scaledHorizontalScrollFactor
        var dy = vertical * config.scaledVerticalScrollFactor
        if (meta and KeyEvent.META_SHIFT_ON != 0 && horizontal == 0f) {
            dx = dy
            dy = 0f
        }
        if (dx != 0f || dy != 0f) panBy(dx, dy)
        return true
    }

    private val thumbnailRenderer by lazy { CanvasStrokeRenderer.create(PencilTextureStore) }
    private val thumbnailCache = android.util.LruCache<String, Bitmap>(THUMBNAIL_CACHE_COUNT)
    private val thumbnailPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Draws thumbnails off the UI thread: a dense page's ink took long enough to stall a pinch. */
    private val thumbnailWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "notesis-thumbnails").apply { priority = Thread.MIN_PRIORITY }
    }
    private val thumbnailsPending = HashSet<String>()

    /**
     * A small picture of page [index] - its paper or PDF and its ink - [widthPx]
     * wide, for the page scrubber and the minimap. Null until it has been
     * drawn, which happens on a worker; ask again shortly. Kept per page,
     * stroke count and size, so a page drawn on is pictured afresh.
     */
    fun pageThumbnail(index: Int, widthPx: Int): Bitmap? {
        val page = document.pages.getOrNull(index) ?: return null
        if (page.width <= 0f || page.height <= 0f || widthPx <= 0) return null
        val key = "${page.id}:${page.strokes.size}:${page.loaded}:$widthPx"
        thumbnailCache.get(key)?.let { return it }
        if (key in thumbnailsPending) return null
        // Only a page already in memory: a thumbnail that asked for renders put
        // them in the queue ahead of the pages actually on screen, and every page
        // the scrubber passed over slowed the PDF that came after.
        val pdfPicture = if (page.background == PageBackground.PDF) {
            pdf?.cachedPage(page.pdfPageIndex) ?: return null
        } else null
        // Snapshot on the UI thread; the drawing happens on the worker.
        val strokes = if (page.loaded) page.strokes.toList() else emptyList()
        val look = document.layers.translucentLook()
        val pageWidth = page.width
        val pageHeight = page.height
        thumbnailsPending += key
        val accepted = runCatching {
            thumbnailWorker.execute {
                val bitmap = runCatching {
                    val height = (widthPx * pageHeight / pageWidth).toInt().coerceAtLeast(1)
                    val picture = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(picture)
                    canvas.drawColor(Color.WHITE)
                    pdfPicture?.takeIf { !it.isRecycled }?.let {
                        canvas.drawBitmap(it, null, RectF(0f, 0f, widthPx.toFloat(), height.toFloat()), thumbnailPaint)
                    }
                    val scale = widthPx / pageWidth
                    val transform = Matrix().apply { setScale(scale, scale) }
                    canvas.scale(scale, scale)
                    drawLayered(canvas, strokes, look, null) { stroke ->
                        runCatching { thumbnailRenderer.draw(canvas, stroke, transform) }
                    }
                    picture
                }.getOrNull()
                post {
                    thumbnailsPending -= key
                    if (bitmap != null) thumbnailCache.put(key, bitmap)
                }
            }
        }.isSuccess
        if (!accepted) thumbnailsPending -= key
        return null
    }

    /** The part of page [index] on screen, in page units clamped to the page, or null if none of it is. */
    fun visiblePartOf(index: Int): RectF? {
        val page = document.pages.getOrNull(index) ?: return null
        val bounds = FloatArray(4)
        layoutViewport(bounds)
        screenToDocument.mapPoints(bounds)
        val left = document.leftOf(index)
        val top = document.topOf(index)
        val rect = RectF(
            minOf(bounds[0], bounds[2]) - left, minOf(bounds[1], bounds[3]) - top,
            maxOf(bounds[0], bounds[2]) - left, maxOf(bounds[1], bounds[3]) - top,
        )
        return if (rect.intersect(0f, 0f, page.width, page.height)) rect else null
    }

    /** Moves the view so page [index]'s point ([x], [y]), in page units, is in the middle of it. */
    fun centerOn(index: Int, x: Float, y: Float) {
        if (index !in document.pages.indices) return
        val point = floatArrayOf(document.leftOf(index) + x, document.topOf(index) + y)
        documentToScreen.mapPoints(point)
        documentToScreen.postTranslate(width / 2f - point[0], height / 2f - point[1])
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
        val touched = arrayListOf(edit.page)
        var layerStep = edit is Edit.LayerEntry
        val group = edit.groupId()
        if (group != 0L) while (undoStack.lastOrNull()?.groupId() == group) {
            val part = undoStack.removeAt(undoStack.lastIndex)
            applyInverse(part)
            redoStack += part
            // A layer step spans pages; each one it touched is settled, not only the first.
            if (touched.none { it === part.page }) touched += part.page
            if (part is Edit.LayerEntry) layerStep = true
        }
        afterEdit(*touched.toTypedArray())
        if (layerStep) layersChanged()
        showPageEdit(edit)
    }

    /** A page that came back, moved or changed is brought into view; one that went, its neighbour. */
    private fun showPageEdit(edit: Edit) {
        if (edit !is Edit.PageInserted && edit !is Edit.PageRemoved &&
            edit !is Edit.PageMoved && edit !is Edit.PagePaper && edit !is Edit.PageRotated) return
        val at = document.pages.indexOf(edit.page)
        scrollToPage(if (at >= 0) at else currentPage.coerceIn(document.pages.indices))
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
        val touched = ArrayList<Page>()
        for (part in parts) {
            applyForward(part)
            undoStack += part
            if (touched.none { it === part.page }) touched += part.page
        }
        afterEdit(*touched.toTypedArray())
        if (parts.any { it is Edit.LayerEntry }) layersChanged()
        showPageEdit(edit)
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
        is Edit.PagePaper -> group
        is Edit.Moved -> group
        is Edit.ImageReplaced -> group
        is Edit.ImageRemoved -> group
        is Edit.ImageAddedInGroup -> group
        is Edit.LayerInk -> group
        is Edit.LayerEntry -> group
        else -> 0L
    }

    private fun applyForward(edit: Edit) {
        when (edit) {
            is Edit.Drawn -> edit.page.strokes += edit.stroke
            is Edit.Erased -> edit.page.strokes.removeAll(edit.strokes)
            is Edit.ImageReplaced -> { edit.page.images[edit.at] = edit.after; clearImageSelection() }
            is Edit.ImageAdded -> edit.page.images += edit.image
            is Edit.ImageAddedInGroup -> edit.page.images += edit.image
            is Edit.ImageRemoved -> edit.page.images.remove(edit.image)
            is Edit.MaskAdded -> edit.page.masks += edit.mask
            is Edit.MaskRemoved -> edit.page.masks.remove(edit.mask)
            is Edit.Moved -> swapStrokes(edit.page, edit.before, edit.after)
            is Edit.PageInserted -> putPageBack(edit.page, edit.at)
            is Edit.PageRemoved -> takePageOut(edit.page)
            is Edit.PageMoved -> movePageObject(edit.page, edit.to)
            is Edit.PagePaper -> { edit.page.setPaper(edit.after); document.invalidateLayout() }
            is Edit.PageRotated -> turnPage(edit.page, edit.clockwise)
            is Edit.LayerInk -> applyLayerInk(edit, forward = true)
            is Edit.LayerEntry -> applyLayerEntry(edit, forward = true)
        }
    }

    /**
     * A page coming back after an autosave may have had its files swept away,
     * so its ink is written whole again rather than appended to what was there.
     */
    private fun putPageBack(page: Page, at: Int) {
        page.savedOnDisk = 0
        document.pages.add(at.coerceIn(0, document.pages.size), page)
        document.invalidateLayout()
    }

    private fun takePageOut(page: Page) {
        document.pages.remove(page)
        dry.dropStrokeIndex(page)
        document.invalidateLayout()
    }

    private fun movePageObject(page: Page, to: Int) {
        document.pages.remove(page)
        document.pages.add(to.coerceIn(0, document.pages.size), page)
        document.invalidateLayout()
        currentPage = document.pages.indexOf(page)
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
            is Edit.ImageAddedInGroup -> edit.page.images.remove(edit.image)
            is Edit.ImageRemoved ->
                edit.page.images.add(edit.at.coerceIn(0, edit.page.images.size), edit.image)
            is Edit.PageInserted -> takePageOut(edit.page)
            is Edit.PageRemoved -> putPageBack(edit.page, edit.at)
            is Edit.PageMoved -> movePageObject(edit.page, edit.from)
            is Edit.PagePaper -> { edit.page.setPaper(edit.before); document.invalidateLayout() }
            is Edit.PageRotated -> turnPage(edit.page, !edit.clockwise)
            is Edit.LayerInk -> applyLayerInk(edit, forward = false)
            is Edit.LayerEntry -> applyLayerEntry(edit, forward = false)
        }
    }

    /**
     * History holds strokes - meshes and all - for as long as the note is open,
     * so a long session grew without bound. Drop the oldest entries, whole
     * groups at a time so one undo never restores half a gesture.
     */
    private fun trimHistory() {
        if (undoStack.size <= MAX_UNDO_EDITS) return
        var cut = undoStack.size - MAX_UNDO_EDITS
        val group = undoStack[cut - 1].groupId()
        if (group != 0L) while (cut < undoStack.size && undoStack[cut].groupId() == group) cut++
        undoStack.subList(0, cut).clear()
    }

    private fun afterEdit(vararg changed: Page) {
        trimHistory()
        inkCacheWorkGeneration++
        lastInkEditMillis = SystemClock.uptimeMillis()
        removeCallbacks(inkCacheRunnable)
        postDelayed(inkCacheRunnable, INK_CACHE_IDLE_MS)
        document.markEdited()
        if (changed.isNotEmpty()) Trace.beginSection(TRACE_LAYER_SETTLE)
        for (page in changed) {
            page.settleLayers(document.layers)
            page.dirty = true
            page.revision++
            page.renderState = RenderState.Dirty
        }
        if (changed.isNotEmpty()) Trace.endSection()
        dry.invalidate()
        document.notifyOtherCanvases(sharedInkChanged)
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
        insertPage(page, (after + 1).coerceIn(0, document.pages.size))
    }

    private fun insertPage(page: Page, at: Int) {
        putPageBack(page, at)
        recordPageEdit(Edit.PageInserted(page, at))
    }

    private fun recordPageEdit(edit: Edit) {
        clearLassoSelection()
        undoStack += edit
        redoStack.clear()
        afterEdit(edit.page)
    }

    /** Reads a page's ink in now if it has not been yet. False when there is no way to. */
    private fun ensureLoaded(page: Page): Boolean {
        if (page.loaded) return true
        val loader = pageLoader ?: return false
        val bucket = minOf(tessellationBucket(currentScale()), LOAD_TESSELLATION_SCALE)
        val epsilon = epsilonFor(bucket)
        page.strokes.addAll(0, loader(page, epsilon))
        maskLoader?.let { page.masks.addAll(0, it(page, epsilon)) }
        page.savedOnDisk = if (page.dirty) 0 else page.strokes.size
        page.loaded = true
        page.settleLayers(document.layers)
        page.tessellatedFor = bucket
        return true
    }

    /**
     * Inserts a copy of page [index] right after it: paper, ink, tape and
     * pictures. Strokes are immutable, so the copy shares them; tape and
     * pictures carry per-page state and get their own objects.
     */
    fun duplicatePage(index: Int) {
        val source = document.pages.getOrNull(index) ?: return
        // Nothing of a page not read in yet is in memory to copy.
        if (!ensureLoaded(source)) return
        val copy = Page(
            width = source.width,
            height = source.height,
            background = source.background,
            templateId = source.templateId,
            pdfPageIndex = source.pdfPageIndex,
        )
        copy.strokes += source.strokes
        copy.hiddenStrokes += source.hiddenStrokes
        copy.masks += source.masks.map { PageMask(it.stroke) }
        copy.images += source.images.map {
            it.copy()
        }
        insertPage(copy, index + 1)
        scrollToPage(index + 1)
    }

    /**
     * Copies of the pages at [indices] with their ink read in, safe to hand to
     * another thread: their lists are their own.
     */
    fun snapshotPages(indices: List<Int>): List<Page> = indices.sorted().mapNotNull { index ->
        val page = document.pages.getOrNull(index) ?: return@mapNotNull null
        if (!ensureLoaded(page)) return@mapNotNull null
        Page(
            id = page.id, width = page.width, height = page.height, background = page.background,
            templateId = page.templateId, pdfPageIndex = page.pdfPageIndex, tocTitle = page.tocTitle,
            tocHighlighted = page.tocHighlighted, tocLevel = page.tocLevel, bookmarked = page.bookmarked,
            links = page.links.toMutableMap(),
            images = page.images.map { it.copy() }.toMutableList(),
            masks = page.masks.map { PageMask(it.stroke) }.toMutableList(),
            strokes = page.strokes.toMutableList(),
            hiddenStrokes = page.hiddenStrokes.toMutableList(),
        )
    }

    /** Removes several pages, last first so each index still means what it did. */
    fun deletePages(indices: List<Int>) {
        for (index in indices.distinct().sortedDescending()) deletePage(index)
    }

    fun deletePage(index: Int) {
        if (document.pages.size <= 1 || index !in document.pages.indices) return
        val removed = document.pages[index]
        // Undo puts the page object back, so its ink has to be in it: autosave
        // deletes the files of a page that is no longer in the note.
        val keepable = ensureLoaded(removed)
        takePageOut(removed)
        if (keepable) {
            recordPageEdit(Edit.PageRemoved(removed, index))
        } else {
            // Nothing to put back, so drop history that would resurrect strokes onto nothing.
            undoStack.removeAll { it.page === removed }
            redoStack.removeAll { it.page === removed }
            afterEdit()
        }
    }

    fun setPageToc(index: Int, title: String?) {
        val page = document.pages.getOrNull(index) ?: return
        val normalized = title?.trim()?.takeIf { it.isNotEmpty() }
        if (page.tocTitle == normalized) return
        page.tocTitle = normalized
        if (normalized == null) page.tocHighlighted = false
        afterEdit()
    }

    fun setPageTocHighlighted(index: Int, highlighted: Boolean) {
        val page = document.pages.getOrNull(index) ?: return
        if (page.tocTitle == null || page.tocHighlighted == highlighted) return
        page.tocHighlighted = highlighted
        afterEdit()
    }

    fun setBackground(index: Int, background: PageBackground) {
        val page = document.pages.getOrNull(index) ?: return
        if (page.background == PageBackground.PDF) return
        val height = if (background == PageBackground.INFINITE) Page.A4_HEIGHT * 12f
            else if (page.height > Page.A4_HEIGHT * 2f) Page.A4_HEIGHT else page.height
        val template = if (background == PageBackground.CUSTOM) page.templateId else null
        changePaper(page, PaperState(background, template, height, page.width))
    }

    private fun changePaper(page: Page, after: PaperState) {
        val before = page.paper()
        if (before == after) return
        page.setPaper(after)
        document.invalidateLayout()
        recordPageEdit(Edit.PagePaper(page, before, after))
    }

    fun movePage(index: Int, delta: Int) {
        val to = index + delta
        if (index !in document.pages.indices || to !in document.pages.indices) return
        val page = document.pages[index]
        movePageObject(page, to)
        recordPageEdit(Edit.PageMoved(page, index, to))
        scrollToPage(to)
    }

    fun setPageTemplate(index: Int, templateId: String) {
        val page = document.pages.getOrNull(index) ?: return
        if (page.background == PageBackground.PDF) return
        changePaper(page, page.paper().copy(background = PageBackground.CUSTOM, templateId = templateId))
    }

    /** One template for every page that can take one; a single undo puts them all back. */
    /**
     * Gives page [index] - or every page that is not a PDF page, with [all] -
     * a new size in page units. Ink stays where it is on the paper. One undo.
     */
    fun setPageSize(index: Int, width: Float, height: Float, all: Boolean) {
        val targets = if (all) document.pages.filter { it.background != PageBackground.PDF }
            else listOfNotNull(document.pages.getOrNull(index)?.takeIf { it.background != PageBackground.PDF })
        val group = nextEditGroup++
        var changed = false
        for (page in targets) {
            val before = page.paper()
            val after = before.copy(width = width, height = height)
            if (before == after) continue
            page.setPaper(after)
            undoStack += Edit.PagePaper(page, before, after, group)
            changed = true
        }
        if (!changed) return
        clearLassoSelection()
        redoStack.clear()
        document.invalidateLayout()
        fitWidth()
        afterEdit(*targets.toTypedArray())
    }

    fun setBackgroundAll(background: PageBackground, templateId: String? = null) {
        val group = nextEditGroup++
        var changed = false
        for (page in document.pages) {
            if (page.background == PageBackground.PDF) continue
            val height = if (background == PageBackground.INFINITE) Page.A4_HEIGHT * 12f
                else if (page.height > Page.A4_HEIGHT * 2f) Page.A4_HEIGHT else page.height
            val before = page.paper()
            val after = PaperState(background, templateId, height, page.width)
            if (before == after) continue
            page.setPaper(after)
            undoStack += Edit.PagePaper(page, before, after, group)
            changed = true
        }
        if (!changed) return
        clearLassoSelection()
        redoStack.clear()
        document.invalidateLayout()
        afterEdit(*document.pages.toTypedArray())
    }

    fun setPageBookmarked(index: Int, bookmarked: Boolean) {
        val page = document.pages.getOrNull(index) ?: return
        if (page.bookmarked == bookmarked) return
        page.bookmarked = bookmarked
        afterEdit()
    }

    /** Moves an outline entry in or out one level, never deeper than one below the entry above it. */
    fun setPageTocLevel(index: Int, level: Int) {
        val page = document.pages.getOrNull(index) ?: return
        if (page.tocTitle == null) return
        val above = document.pages.subList(0, index).lastOrNull { it.tocTitle != null }
        val clamped = level.coerceIn(0, (above?.tocLevel ?: -1) + 1)
        if (page.tocLevel == clamped) return
        page.tocLevel = clamped
        afterEdit()
    }

    /**
     * Turns a page a quarter. PDF pages are left alone: the PDF draws itself
     * the way it was printed, and ink turned over it would no longer line up.
     */
    fun rotatePage(index: Int, clockwise: Boolean) {
        val page = document.pages.getOrNull(index) ?: return
        if (page.background == PageBackground.PDF || page.pdfPageIndex >= 0) return
        if (!ensureLoaded(page)) return
        clearLassoSelection()
        clearImageSelection()
        turnPage(page, clockwise)
        recordPageEdit(Edit.PageRotated(page, clockwise))
        scrollToPage(document.pages.indexOf(page))
    }

    fun canRotatePage(index: Int): Boolean =
        document.pages.getOrNull(index)?.let { it.background != PageBackground.PDF && it.pdfPageIndex < 0 } == true

    /**
     * Turns everything on [page] - and every stroke history still holds for it -
     * so undoing an erase made before the turn puts the ink back where it now belongs.
     * Pictures keep their own orientation; only where they sit turns.
     */
    private fun turnPage(page: Page, clockwise: Boolean) {
        val w = page.width
        val h = page.height
        fun turn(x: Float, y: Float, out: FloatArray) {
            if (clockwise) { out[0] = h - y; out[1] = x } else { out[0] = y; out[1] = w - x }
        }
        val replacements = IdentityHashMap<Stroke, Stroke>()
        fun turned(stroke: Stroke): Stroke =
            replacements.getOrPut(stroke) { mapInputs(stroke, ::turn).also { StrokeTags.carry(stroke, it) } }
        for (i in page.strokes.indices) page.strokes[i] = turned(page.strokes[i])
        // Ink on hidden layers turns too, or showing the layer brings it back sideways.
        for (i in page.hiddenStrokes.indices) page.hiddenStrokes[i] = turned(page.hiddenStrokes[i])
        val masks = Collections.newSetFromMap(IdentityHashMap<PageMask, Boolean>())
        val images = Collections.newSetFromMap(IdentityHashMap<PageImage, Boolean>())
        masks += page.masks
        images += page.images
        for (edit in undoStack + redoStack) {
            if (edit.page !== page) continue
            when (edit) {
                is Edit.Drawn -> turned(edit.stroke)
                is Edit.Erased -> edit.strokes.forEach { turned(it) }
                is Edit.LayerInk -> edit.strokes.forEach { turned(it) }
                is Edit.Moved -> { edit.before.forEach { turned(it) }; edit.after.forEach { turned(it) } }
                is Edit.MaskAdded -> masks += edit.mask
                is Edit.MaskRemoved -> masks += edit.mask
                is Edit.ImageAdded -> images += edit.image
                is Edit.ImageAddedInGroup -> images += edit.image
                is Edit.ImageRemoved -> images += edit.image
                is Edit.ImageReplaced -> { images += edit.before; images += edit.after }
                else -> Unit
            }
        }
        for (mask in masks) mask.stroke = turned(mask.stroke)
        val center = FloatArray(2)
        for (image in images) {
            turn(image.x + image.width / 2f, image.y + image.height / 2f, center)
            image.x = center[0] - image.width / 2f
            image.y = center[1] - image.height / 2f
        }
        remapHistory(replacements)
        page.savedOnDisk = 0
        page.width = h
        page.height = w
        page.tessellatedFor = 0f
        dry.dropStrokeIndex(page)
        document.invalidateLayout()
    }

    fun currentPageIndex(): Int = currentPage

    fun strokeCount(): Int = document.pages.sumOf { it.strokes.size }

    fun debugPerformanceReport(): String {
        if (latency.enabled) {
            val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
            val collections = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
            latency.addRuntimeSample(allocated, collections, System.nanoTime())
        }
        return latency.render(strokeCount()) + "\n" + WorkStats.render() +
            "\ntiles deferred ${WorkStats.tilesDeferred.get()} · save pages reused ${WorkStats.savePagesReused.get()}"
    }

    fun currentScale(): Float {
        documentToScreen.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /** Fired whenever the page moves under the view: what a minimap needs to follow. */
    var onViewportChanged: (() -> Unit)? = null

    private fun onTransformChanged() {
        viewportRenderGeneration++
        clampTransform()
        // Mapping has to be current at once: the next touch or pen down
        // is placed with it.
        documentToScreen.invert(screenToDocument)
        // Input can arrive several times inside one display interval. Ask for
        // one paint on the next vsync instead of repeatedly invalidating now.
        dry.postInvalidateOnAnimation()
        // The rest only has to be right once per frame. While fingers or a
        // fling move the page it runs from the frame itself, once, however
        // many samples came in since; a jump from code still runs it now.
        if (viewportInteracting || flinging) {
            if (!viewportFramePosted) {
                viewportFramePosted = true
                postOnAnimation(viewportFrame)
            }
            return
        }
        removeCallbacks(viewportFrame)
        viewportFramePosted = false
        afterTransform()
    }

    /** Everything a new viewport means beyond its matrix, at most once a frame while it moves. */
    private fun afterTransform() {
        onViewportChanged?.invoke()
        ruler.pxPerMm = currentScale() * Page.A4_WIDTH / A4_MM_WIDTH
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
        // New pages need their stored ink even while the viewport is moving.
        // Detail rebuilding still waits for the movement to settle.
        if (!holdingDetail() || anyVisibleUnloaded()) scheduleRefine()
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

        // 0.8 of a note's width of slack past either side, so the edge of the
        // page can be brought to the middle of the screen to write on.
        val slack = if (keepPageAcross) 0f else document.fitWidth() * scale * 0.8f
        val centred = (width - docWidth) / 2f
        x = x.coerceIn(minOf(width - docWidth, centred) - slack, maxOf(0f, centred) + slack)
        y = if (docHeight <= height) {
            (height - docHeight) / 2f
        } else {
            // Half a screen of overscroll at each end, so the last page is
            // reachable without fighting the edge.
            y.coerceIn(height - docHeight - height / 2f, height / 2f)
        }
        // At the far end, a pull further on is asking for another page.
        atDocumentEnd = if (document.layoutMode == PageLayoutMode.HORIZONTAL) {
            docWidth > width && x <= width - docWidth + 1f
        } else {
            docHeight <= height || y <= height - docHeight - height / 2f + 1f
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
            val fast = flinging || viewportSpeedPxPerSecond > FAST_VIEWPORT_PX_PER_SECOND
            prioritizePdf(movingPdfRadius(fast, InteractionGovernor.thermalStatus))
            onCurrentPageChanged?.invoke(index)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = inLayoutFrame(event) { onTouchEventInLayout(it) }

    private fun onTouchEventInLayout(event: MotionEvent): Boolean {
        // Samsung reports a touch made with the S Pen side button held through
        // its own action codes instead of DOWN/MOVE/UP with a button state, so
        // without this the barrel-button eraser never sees a single event.
        val sideButtonAction = samsungSidePenAction(event.actionMasked)
            ?: run {
                samsungSideButton = false
                return onTouch(event)
            }
        samsungSideButton = true
        val normalized = MotionEvent.obtain(event)
        normalized.action = sideButtonAction
        return try { onTouch(normalized) } finally { normalized.recycle() }
    }

    /** True while the gesture in progress came in as Samsung's side-button actions. */
    private var samsungSideButton = false

    private fun onTouch(event: MotionEvent): Boolean {
        // Keyboard shortcuts need focus; a keyboard can be attached at any time.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !isFocused) requestFocus()
        if (activeStylusPointer == null && eyedropper.onTouch(event)) return true
        val stylus = event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS
        // A pen landing while fingers still hold the page: the pen wins, and
        // the rest of that hand's events go the pen's way, so the finger
        // gesture would never see its own lift.
        if (stylus && viewportInteracting && (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)) {
            endFingerGestureForPen()
        }
        if (!stylus && activeStylusPointer == null && laserPointer == null) {
            if (ruler.onFinger(event)) return true
            // A finger set to write takes the pen's path, until a second finger says otherwise.
            if (oneFinger == FINGER_DRAW && event.actionMasked == MotionEvent.ACTION_DOWN) {
                fingerDrawing = true
                return onStylus(event)
            }
            return onFingers(event)
        }
        if (fingerDrawing && !stylus && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // Two fingers are for moving the page: drop the line and start a pan.
            fingerDrawing = false
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            val down = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_DOWN }
            try {
                onStylus(cancel)
                onFingers(down)
            } finally {
                cancel.recycle()
                down.recycle()
            }
            return onFingers(event)
        }
        if (fingerDrawing && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            fingerDrawing = false
        }
        if (!latencyMonitoringEnabled) return onStylus(event)
        Trace.beginSection(when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> TRACE_STYLUS_DOWN
            MotionEvent.ACTION_MOVE -> TRACE_STYLUS_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> TRACE_STYLUS_UP
            else -> TRACE_STYLUS_OTHER
        })
        return try { onStylus(event) } finally { Trace.endSection() }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean = inLayoutFrame(event) { onHoverInLayout(it) }

    private fun onHoverInLayout(event: MotionEvent): Boolean {
        val stylus = event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS
        if (!stylus) return super.onHoverEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val show = !readMode && (tool == Tool.ERASER || event.isEraserGesture())
                // The cursor has a view of its own: drawn with the page, every
                // hover sample re-recorded the whole page and its glass backdrop.
                if (show) compatWet.showCursor(event.x, event.y, eraserScreenRadius())
                else compatWet.hideCursor()
            }
            MotionEvent.ACTION_HOVER_EXIT -> compatWet.hideCursor()
        }
        return true
    }

    private fun onLaser(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (laserPointer != null) return true
                laserPointer = event.getPointerId(event.actionIndex)
                laser.add(event.getX(event.actionIndex), event.getY(event.actionIndex), event.eventTime, true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(laserPointer ?: return true)
                if (index < 0) return true
                for (h in 0 until event.historySize) {
                    laser.add(event.getHistoricalX(index, h), event.getHistoricalY(index, h),
                        event.getHistoricalEventTime(h), true)
                }
                laser.add(event.getX(index), event.getY(index), event.eventTime, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked != MotionEvent.ACTION_CANCEL &&
                    event.getPointerId(event.actionIndex) != laserPointer) return true
                laserPointer = null
                laser.lift()
            }
        }
        return true
    }

    private fun onStylus(event: MotionEvent): Boolean {
        val masked = event.actionMasked
        if (activeStylusPointer == null &&
            (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_POINTER_DOWN)
        ) {
            // Judged once per stroke, at the sample that starts it: every
            // sample of one stroke has to agree on whether pressure is real.
            strokePressureReal = reportsRealPressure(
                event.getToolType(event.actionIndex),
                hasPressureAxis(event),
                event.getPressure(event.actionIndex),
            )
        }
        val flat = !strokePressureReal
        if (!flat && (pressureResponse == PRESSURE_RESPONSE_DEFAULT ||
            event.getToolType(event.actionIndex) != MotionEvent.TOOL_TYPE_STYLUS)
        ) return onStylusEvent(event)
        // Every sample through the curve once, here, so the wet ink, the
        // stabilizer, the predictor and the saved stroke all see one pressure.
        // Input without real pressure gets one steady value the same way, so
        // a pressure brush under a finger or a mouse does not throb.
        val curved = withPressureCurve(event, flat)
        return try { onStylusEvent(curved) } finally { curved.recycle() }
    }

    /** Whether the stroke in progress has real pen pressure; see [reportsRealPressure]. */
    private var strokePressureReal = true
    private var pressureAxisDevice = Int.MIN_VALUE
    private var pressureAxisKnown: Boolean? = null

    /** What the device says about its pressure axis, asked once per device rather than once per stroke. */
    private fun hasPressureAxis(event: MotionEvent): Boolean? {
        if (event.deviceId != pressureAxisDevice) {
            pressureAxisDevice = event.deviceId
            pressureAxisKnown = event.device?.let { device ->
                val range = device.getMotionRange(MotionEvent.AXIS_PRESSURE)
                range != null && range.range > 0f
            }
        }
        return pressureAxisKnown
    }

    private var curvePointerProperties = emptyArray<MotionEvent.PointerProperties>()
    private var curvePointerCoordinates = emptyArray<MotionEvent.PointerCoords>()

    /**
     * A copy of [event], history included, with each pressure passed through
     * [pressureCurve], or set to [NEUTRAL_PRESSURE] when [flat].
     */
    private fun withPressureCurve(event: MotionEvent, flat: Boolean = false): MotionEvent {
        val count = event.pointerCount
        if (curvePointerProperties.size < count) {
            curvePointerProperties = Array(count) { MotionEvent.PointerProperties() }
            curvePointerCoordinates = Array(count) { MotionEvent.PointerCoords() }
        }
        // Longer arrays are fine: obtain and addBatch read only the first [count].
        val properties = curvePointerProperties
        val coordinates = curvePointerCoordinates
        for (i in 0 until count) event.getPointerProperties(i, properties[i])
        fun fill(history: Int) {
            for (i in 0 until count) {
                if (history < 0) event.getPointerCoords(i, coordinates[i])
                else event.getHistoricalPointerCoords(i, history, coordinates[i])
                coordinates[i].pressure = if (flat) normalizedPressure(coordinates[i].pressure, false)
                    else pressureCurve(coordinates[i].pressure, pressureResponse)
            }
        }
        val samples = event.historySize
        fill(if (samples > 0) 0 else -1)
        val result = MotionEvent.obtain(
            event.downTime,
            if (samples > 0) event.getHistoricalEventTime(0) else event.eventTime,
            event.action,
            count,
            properties,
            coordinates,
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            event.source,
            event.flags,
        )
        if (samples > 0) {
            for (history in 1 until samples) {
                fill(history)
                result.addBatch(event.getHistoricalEventTime(history), coordinates, event.metaState)
            }
            fill(-1)
            result.addBatch(event.eventTime, coordinates, event.metaState)
        }
        return result
    }

    private fun onStylusEvent(event: MotionEvent): Boolean {
        if (laserMode || laserPointer != null) return onLaser(event)
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
                inkCacheWorkGeneration++
                if (Build.VERSION.SDK_INT >= 35) {
                    requestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_HIGH
                }
                // While the pen is down, background work gives way: PDF
                // prefetch waits, and page loads use fewer cores.
                penWorking(true)
                // Built per gesture: the Kalman filter is tied to one pointer on
                // one device, and a new stroke is a new pointer.
                if (latencyMonitoringEnabled && !frameClockRunning) {
                    frameClockRunning = true
                    lastFrameNanos = 0L
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }
                activePage = document.pages[index]
                // The barrel button held (not the pen turned to its eraser end) can mean something else.
                val buttonHeld = samsungSideButton || (event.getToolType(event.actionIndex) != MotionEvent.TOOL_TYPE_ERASER &&
                    event.isEraserGesture())
                if (buttonHeld && !readMode && penButton == PEN_BUTTON_LASSO) {
                    onDrawingChanged?.invoke(true)
                    beginLasso(event, index)
                    return true
                }
                val eraserGesture = event.isEraserGesture() || samsungSideButton
                if (readMode) startGestureTrail(event, eraserGesture)
                if (readMode && eraserGesture) {
                    onDrawingChanged?.invoke(true)
                    readingWithButton = true
                    compatWet.hideCursor()
                    capturePage = index
                    val x = event.getX(event.actionIndex)
                    val y = event.getY(event.actionIndex)
                    pageLocalInto(x, y, index, shapeStart)
                    readButtonGesture.begin(ReadTextPoint(index, shapeStart[0], shapeStart[1]), x, y)
                    captureRect.setEmpty()
                    dry.invalidate()
                    return true
                }
                readButtonGesture.reset()
                if (captureMode && !eraserGesture) {
                    onDrawingChanged?.invoke(true)
                    capturePage = index
                    capturing = true
                    pageLocalInto(event.x, event.y, index, shapeStart)
                    captureRect.set(shapeStart[0], shapeStart[1], shapeStart[0], shapeStart[1])
                    dry.invalidate()
                    return true
                }
                // A selection made without the lasso tool (circle to lasso, a
                // paste): the pen inside it carries on moving it, and anywhere
                // else lets it go and writes as usual.
                if (!lassoMode && !readMode && !eraserGesture && hasLassoSelection()) {
                    pageLocalInto(event.x, event.y, index, shapeStart)
                    if (index == lassoPage && lassoBounds.contains(shapeStart[0], shapeStart[1])) {
                        onDrawingChanged?.invoke(true)
                        beginLasso(event, index)
                        return true
                    }
                    clearLassoSelection()
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
                if (!eraserGesture && tool != Tool.ERASER &&
                    toggleCheckboxAt(event.getX(event.actionIndex), event.getY(event.actionIndex), index)
                ) return true
                // Ink - pen, shape, ruler line, polygon - on a locked or hidden
                // layer is refused before any of it is drawn. Tape is not on a
                // layer, and the eraser only ever skips what it may not touch.
                if (!readMode && !eraserGesture && tool != Tool.ERASER && !maskMode && refuseLockedTarget()) {
                    return true
                }
                if (rulerKind != null && !readMode && !eraserGesture && tool != Tool.ERASER) {
                    val edge = ruler.edgeAt(event.x, event.y)
                    if (edge != null) {
                        onDrawingChanged?.invoke(true)
                        onWritingStarted?.invoke()
                        rulerEdge = edge
                        shapePage = index
                        drawingShape = true
                        rulerPoints.clear()
                        addRulerPoint(event.x, event.y)
                        dry.invalidate()
                        return true
                    }
                }
                val shape = shapeKind
                if (shape == ShapeKind.POLYGON && !readMode && !eraserGesture && tool != Tool.ERASER) {
                    onDrawingChanged?.invoke(true)
                    onWritingStarted?.invoke()
                    tapPolygon(event, index)
                    return true
                }
                if (shape != null && !readMode && !eraserGesture &&
                    tool != Tool.ERASER
                ) {
                    onDrawingChanged?.invoke(true)
                    onWritingStarted?.invoke()
                    shapePage = index
                    drawingShape = true
                    pageLocalInto(event.x, event.y, index, shapeStart)
                    shapeEnd[0] = shapeStart[0]
                    shapeEnd[1] = shapeStart[1]
                    dry.invalidate()
                    return true
                }
                if (readMode && !eraserGesture) {
                    readPressTime = event.eventTime
                    onDrawingChanged?.invoke(true)
                    if (onStrokeTapped != null && tapStrokeAt(event.x, event.y, index)) return true
                    // Reading is where a covered answer gets looked at.
                    if (toggleMaskAt(event.x, event.y, index)) return true
                    if (followObjectLink(event.x, event.y)) return true
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
                // Past every tap, selection and capture above: this is ink.
                onWritingStarted?.invoke()
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
                    streamingStabilizer.slidingWindow = windowedStabilizer
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
                    predictionHead.clear(); subframe.clear(); subframeTrack.reset()
                    // MotionEventPredictor is useful only for wet freehand ink.
                    // Recording eraser/lasso/shape gestures ran its filter at
                    // full S Pen rate even though those tools never call predict().
                    if (predictionEnabled) predictor.record(event)
                    // Keep front-buffer authoring on the stable path renderer.
                    // Mesh twins are used by the committed-ink RenderNodes;
                    // asynchronous mesh draws must not outlive the wet surface.
                    // Shown in the colour the committed stroke will have (layer
                    // opacity, page tone); its own colour is put back at pen-up.
                    val stored = currentBrush()
                    val brush = wetBrushFor(stored)
                    wetColorArgb = brush.colorIntArgb
                    if (pendingStrokeCommits.isEmpty()) wetTrueColors.clear()
                    // "World" here is the page, so the finished stroke comes
                    // back in page-local coordinates and stays with its page.
                    val toPage = screenToPage(index)
                    activeStrokeId = withWetEvent(event) { screen ->
                        wet.startStroke(
                            event = screen,
                            pointerId = pointerId,
                            brush = brush,
                            motionEventToWorldTransform = wetTransformOf(toPage),
                        )
                    }
                    if (brush !== stored) activeStrokeId?.let { wetTrueColors[it] = stored.colorIntArgb }
                    if (compatWetInk) {
                        activeStrokeId?.let { id ->
                            compatWet.start(id, event, pointerId, brush, toPage)
                            compatWetStrokeId = id
                        }
                    }
                    holdingStationaryStart = tool == Tool.PEN && !stabilizingStroke && dottedPattern == 0
                    stationaryStartX = event.getX(event.actionIndex)
                    stationaryStartY = event.getY(event.actionIndex)
                    stationaryStartTime = event.eventTime
                    circleTracking = circleToLasso && tool.isFreehandPen() && !strokeIsMask
                    holdTracking = holdToDraw && (tool.isFreehandPen() || tool == Tool.HIGHLIGHTER) && !strokeIsMask
                    recognizeOnFinish = false
                    if (holdTracking) {
                        holdX = stationaryStartX
                        holdY = stationaryStartY
                        holdSince = event.eventTime
                    }
                    if (circleTracking) {
                        lassoPage = index
                        lassoPath.clear()
                        pageLocalInto(stationaryStartX, stationaryStartY, index, shapeStart)
                        lassoPath.add(shapeStart[0], shapeStart[1])
                        holdX = stationaryStartX
                        holdY = stationaryStartY
                        holdSince = event.eventTime
                        postDelayed(circleHold, CIRCLE_HOLD_MS)
                    }
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
                if (gestureTrail) addGestureTrail(event, index)
                if (readingWithButton) {
                    updateReadButtonCapture(event, index)
                    return true
                }
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
                    if (snapToAlign) snapLassoMove()
                    dry.postInvalidateOnAnimation()
                    return true
                }
                if (drawingLasso) {
                    appendLassoSamples(event, index)
                    return true
                }
                if (polyPoints.isNotEmpty() && polyPage >= 0 && shapeKind == ShapeKind.POLYGON) {
                    pageLocalInto(event.getX(index), event.getY(index), polyPage, polyPoints.last())
                    dry.postInvalidateOnAnimation()
                    return true
                }
                if (vertexInputs != null) {
                    dragVertex(event, index)
                    return true
                }
                if (drawingShape && rulerEdge != null) {
                    for (h in 0 until event.historySize) {
                        addRulerPoint(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                    }
                    addRulerPoint(event.getX(index), event.getY(index))
                    dry.postInvalidateOnAnimation()
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
                if (circleTracking) trackCircle(event, index)
                else if (holdTracking) trackHold(event.getX(index), event.getY(index), event.eventTime)
                val strokeId = activeStrokeId ?: return false
                if (latencyMonitoringEnabled) latency.addSamples(1 + event.historySize)
                // Hold back the digitizer's near-stationary samples at contact,
                // the same cluster withoutStationaryStart drops at pen-up, so
                // the wet start already looks like the committed one.
                var firstMove: MotionEvent? = null
                if (holdingStationaryStart) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    val threshold = wetStartHoldThreshold(strokeWidth) * currentScale()
                    if (event.eventTime - stationaryStartTime > STATIONARY_START_MS) {
                        holdingStationaryStart = false
                    } else if (hypot(x - stationaryStartX, y - stationaryStartY) < threshold) {
                        return true
                    } else {
                        holdingStationaryStart = false
                        firstMove = eventWithActivePoint(event, pointerId, x, y)
                    }
                }
                val inkEvent = if (stabilizingStroke) {
                    stabilizedMoveEvent(event, pointerId)
                } else firstMove ?: event
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
                val compatStroke = compatWetStrokeId == strokeId
                val refreshRate = display?.refreshRate ?: 60f
                val fixedLeadMs = predictionLeadForRefresh(
                    predictionLeadMs,
                    refreshRate,
                    vsyncBoundInk = compatStroke,
                    measuredLatencyMs = measuredInkLatencyMs(event.eventTime),
                )
                // Automatic horizon on the compatible path: as far ahead as
                // this sample will wait to be seen, from where it fell in the frame.
                val maximumLeadMs = if (compatStroke && predictionLeadMs !in 1..MAX_LEAD_MS) {
                    vsyncAlignedLeadMs(fixedLeadMs, event.eventTime * 1_000_000L, compatWet.lastVsyncNanos, refreshRate)
                } else fixedLeadMs
                var leadMs = predictionLeadForEvent(inkEvent, pointerId, maximumLeadMs)
                if (sharpTurn || (stabilizingStroke && !streamingStabilizer.predictionAllowed)) {
                    leadMs = 0
                } else if (stabilizingStroke) {
                    leadMs = stabilizedLeadMs(leadMs, stabilizer)
                }
                if (predictionEnabled) predictor.record(inkEvent)
                // Turned, no prediction: the predictor's guess came back in a
                // frame the turn had not been applied to, and its head shot
                // off from the pen in a direction that followed the angle.
                val rawPrediction = if (predictionEnabled && leadMs > 0 && viewRotation == 0f) predictor.predict() else null
                val predicted = rawPrediction?.let { predictionAtLead(inkEvent, it, leadMs.toLong()) }
                try {
                    if (latencyMonitoringEnabled && predicted != null) {
                        // Ground truth for the tip's steadiness: the distance
                        // the prediction reaches past the newest real sample.
                        latency.addPredictionLead(
                            (predicted.eventTime - event.eventTime).toDouble(),
                        )
                    }
                    // Where the front buffer is not what the panel shows, it
                    // still renders; spare it the prediction it would redraw on
                    // every sample, and a stale head when HWUI does reveal it.
                    withWetEvent(inkEvent) { screen ->
                        val head = if (compatStroke) null else predicted?.let(::wetEvent)
                        try {
                            wet.addToStroke(screen, pointerId, strokeId, head)
                        } finally {
                            if (head != null && head !== predicted) head.recycle()
                        }
                    }
                    if (compatStroke) {
                        // The prediction is already part of the compatible
                        // stroke. A separate head View would put every pen
                        // frame back into the window the surface avoids.
                        compatWet.add(strokeId, inkEvent, pointerId, predicted)
                    } else {
                        updatePredictionHead(inkEvent, predicted, pointerId, leadMs)
                        updateSubframe(inkEvent, pointerId)
                    }
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
                finishGestureTrail(event)
                predictionHead.clear(); subframe.clear(); subframeTrack.reset()
                if (readingWithButton) {
                    // Include the lift position even when no final MOVE was delivered.
                    updateReadButtonCapture(event, event.actionIndex)
                    when (val result = readButtonGesture.finish()) {
                        ReadButtonGesture.Result.Capture -> finishCapture()
                        is ReadButtonGesture.Result.Select -> {
                            val page = document.pages.getOrNull(result.end.page)
                            val source = pdf
                            if (page?.background == PageBackground.PDF && source != null) {
                                selectingPage = result.end.page
                                selectionAnchor = RectF(result.start.x, result.start.y,
                                    result.start.x, result.start.y)
                                requestSelection(source, page.pdfPageIndex,
                                    RectF(result.start.x, result.start.y, result.start.x, result.start.y),
                                    RectF(result.end.x, result.end.y, result.end.x, result.end.y))
                            } else {
                                readButtonGesture.reset()
                            }
                        }
                        null -> Unit
                    }
                    endStylus()
                    return true
                }
                pendingTextPlacement?.let { position ->
                    endStylus()
                    onTextRequested?.invoke(position.first, position.second, position.third)
                    return true
                }
                if (pendingTextEdit) {
                    // After the lift, so the dialog cannot take ACTION_UP from the canvas.
                    pendingTextEdit = false
                    endStylus()
                    onEditTextBox?.invoke()
                    return true
                }
                if (capturing) {
                    finishCapture()
                    endStylus()
                    return true
                }
                if (vertexInputs != null) {
                    finishVertexDrag()
                    endStylus()
                    return true
                }
                if (polyPoints.isNotEmpty() && shapeKind == ShapeKind.POLYGON) {
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
                    // A quick tap that did not travel is also how a link is
                    // followed; the word it selected is dropped if it was one.
                    val pointer = event.actionIndex
                    val tapped = event.eventTime - readPressTime < LINK_TAP_MS &&
                        hypot(event.getX(pointer) - pressX, event.getY(pointer) - pressY) < touchSlop
                    if (tapped) followLinkAt(selectingPage, pressX, pressY)
                    endStylus()
                    return true
                }
                if (erasing) {
                    eraseAlong(event, event.actionIndex, force = true)
                    finishEraseGesture()
                    endStylus()
                    if (tool == Tool.ERASER) onEraseFinished?.invoke()
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
                                withWetEvent(history) { wet.addToStroke(it, pointerId, strokeId, null) }
                                if (compatWetStrokeId == strokeId) compatWet.add(strokeId, history, pointerId, null)
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
                        pendingStrokeCommits.add(strokeId)
                        withWetEvent(end) { wet.finishStroke(it, pointerId, strokeId) }
                        if (compatWetStrokeId == strokeId) compatWet.finish(strokeId, end, pointerId)
                    } finally {
                        if (end !== event) end.recycle()
                    }
                }
                endStylus()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (gestureTrail) {
                    gestureTrail = false
                    laser.lift()
                }
                if (readingWithButton) {
                    readButtonGesture.reset()
                    capturing = false
                    captureRect.setEmpty()
                }
                predictionHead.clear(); subframe.clear(); subframeTrack.reset()
                if (predictionEnabled && activeStrokeId != null) predictor.record(event)
                activeStrokeId?.let { id -> withWetEvent(event) { wet.cancelStroke(id, it) } }
                cancelCompatWet(activeStrokeId)
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

    /**
     * Feeds every sample of the event, history included, to the sub-frame
     * track and shows how far the pen will have gone by the next frame.
     */
    private fun updateSubframe(real: MotionEvent, pointerId: Int) {
        if (subframe.mode == SUBFRAME_OFF) return
        if (!softPredictionHead || viewRotation != 0f || !tool.isFreehandPen() || tool.isDabbed() || dottedPattern != 0) {
            subframe.clear()
            return
        }
        val index = real.findPointerIndex(pointerId)
        if (index < 0) return
        for (h in 0 until real.historySize) {
            subframeTrack.add(real.getHistoricalX(index, h), real.getHistoricalY(index, h), real.getHistoricalEventTime(h))
        }
        subframeTrack.add(real.getX(index), real.getY(index), real.eventTime)
        val frameMs = 1000f / (display?.refreshRate?.takeIf { it > 1f } ?: 60f)
        val lead = subframeTrack.lead(frameMs) ?: run { subframe.clear(); return }
        val pressureScale = if (tool == Tool.PRESSURE_PEN || tool == Tool.VANISHING_PEN) {
            0.35f + real.getPressure(index).coerceIn(0f, 1f) * 0.65f
        } else 1f
        subframe.show(real.getX(index), real.getY(index), lead[0], lead[1], wetColorArgb,
            strokeWidth * currentScale() * pressureScale)
    }

    private fun updatePredictionHead(
        real: MotionEvent,
        predicted: MotionEvent?,
        pointerId: Int,
        leadMs: Int,
    ) {
        if (predicted == null || !softPredictionHead || !predictionPolicy.shouldShowSoftHead(leadMs) ||
            !tool.isFreehandPen() || tool.isDabbed() || dottedPattern != 0
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
        val pressureScale = if (tool == Tool.PRESSURE_PEN || tool == Tool.VANISHING_PEN) {
            0.35f + real.getPressure(realIndex).coerceIn(0f, 1f) * 0.65f
        } else 1f
        predictionHead.show(
            real.getX(realIndex),
            real.getY(realIndex),
            predicted.getX(predictedIndex),
            predicted.getY(predictedIndex),
            wetColorArgb,
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
        if (holdTracking) {
            holdTracking = false
            recognizeOnFinish = activeStrokeId != null &&
                SystemClock.uptimeMillis() - holdSince >= CIRCLE_HOLD_MS
        }
        readingWithButton = false
        pendingTextPlacement = null
        removeCallbacks(longPress)
        if (circleTracking) {
            circleTracking = false
            removeCallbacks(circleHold)
            lassoPath.clear()
        }
        selectingText = false
        if (activeStylusPointer != null) onDrawingChanged?.invoke(false)
        predictionHead.clear(); subframe.clear(); subframeTrack.reset()
        activeStylusPointer = null
        if (Build.VERSION.SDK_INT >= 35) {
            // Let an idle note fall back to the display's normal adaptive rate.
            requestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
        }
        penWorking(false)
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
        compatWet.hideCursor()
        dry.postInvalidateOnAnimation()
        if (pendingClose != null) completePendingClose()
    }

    private fun onFingers(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            suppressScaleUntilGestureEnd = false
            referenceDismissGesture.reset()
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
        } else if (!suppressScaleUntilGestureEnd && !zoomLocked && twoFingers == TWO_ZOOM_PAN) {
            scaleDetector.onTouchEvent(event)
        }
        trackVelocity(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Owned before the fling stops, so stopping it does not tell
                // the host the page has come to rest for the moment between.
                viewportInteracting = true
                stopFling(resumeDetail = false)
                pendingPinch = 1f
                // A delayed idle refine may still be waiting from the previous
                // viewport, or running: its meshes would land mid-gesture and
                // have every tile on screen recorded again. Cancel it as soon
                // as a new interaction begins; a page it was reading in is
                // still kept (see refineVisiblePages).
                cancelDetailWork()
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
                rotating3f = false
                turnedThisGesture = false
                turn3fSoFar = 0f
                fingerAngleCount = 0
                opened3fThisGesture = false
                closed3fThisGesture = false
                zooming = false
                draggedReference = false
                longPressFired = false
                if (onLongPressCanvas != null) postDelayed(fingerLongPress, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                removeCallbacks(fingerLongPress)
                stopFling(resumeDetail = false)
                // Whatever the detector made of the old set of fingers is not
                // a factor between this set's spread and the next.
                pendingPinch = 1f
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
                // A pointer-count change is a fresh anchor; never compare its
                // centroid/spread with the previous set of fingers.
                have3Fingers = false
                fingerAngleCount = 0
                endNoteTurn()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Taken first, so a sample that returns early below never
                // leaves its factor for the next one.
                val pinch = pendingPinch
                pendingPinch = 1f
                gestureMaxPointers = maxOf(gestureMaxPointers, event.pointerCount)
                val focus = focusOf(event, skipPointerIndex = -1)
                val moved = hypot(focus[0] - lastFocusX, focus[1] - lastFocusY)
                gestureMoved += moved
                val elapsed = (event.eventTime - lastFingerEventTime).coerceAtLeast(1L)
                val wasFast = viewportWasFast
                viewportSpeedPxPerSecond = moved * 1000f / elapsed
                viewportWasFast = viewportSpeedPxPerSecond > FAST_VIEWPORT_PX_PER_SECOND
                lastFingerEventTime = event.eventTime
                if (wasFast && !viewportWasFast) prioritizePdf(movingPdfRadius(false, InteractionGovernor.thermalStatus))

                // Three fingers are a zone the page itself never used, so they
                // never pan or zoom it - they only ever move the reference
                // panel, or, held still, redo.
                if (event.pointerCount >= 3) {
                    if (!threeFingerGestures) {
                        lastFocusX = focus[0]
                        lastFocusY = focus[1]
                        return true
                    }
                    val rawFocus = rawFingerFocus(event)
                    val spread = rawFingerSpread(event, rawFocus[0], rawFocus[1])
                    // Turning the hand turns the note; sliding it still moves the panel.
                    if (have3Fingers && trackNoteTurn(event)) {
                        recordFingerAngles(event)
                        prev3fX = rawFocus[0]
                        prev3fY = rawFocus[1]
                        prev3fSpread = spread
                        lastFocusX = focus[0]
                        lastFocusY = focus[1]
                        return true
                    }
                    if (!have3Fingers) {
                        have3Fingers = true
                        referenceDismissGesture.reset()
                        gestureStart3fX = rawFocus[0]
                        gestureStart3fY = rawFocus[1]
                        gestureStart3fSpread = spread
                    } else if (referenceOpen && onReferenceDrag != null) {
                        val totalX = rawFocus[0] - gestureStart3fX
                        val totalY = rawFocus[1] - gestureStart3fY
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
                        val tracksDismissDirection = closeReferenceOnDownwardDrag &&
                            referenceDismissGesture.tracks(totalX, totalY, totalSpreadFactor)
                        val isDownwardDismiss = tracksDismissDirection && totalY > OPEN_DRAG_PX
                        if (isDownwardDismiss && !closed3fThisGesture) {
                            onCloseReference?.invoke()
                            closed3fThisGesture = true
                        } else if (!closed3fThisGesture && !tracksDismissDirection) {
                            onReferenceDrag?.invoke(
                                rawFocus[0] - prev3fX,
                                rawFocus[1] - prev3fY,
                                factor,
                            )
                            draggedReference = true
                        }
                    } else if (referenceOpen) {
                        // On the page rather than on the panel, and the panel is
                        // already open: the same drag that opened it, the other
                        // way up, puts it away. Nothing else here has a use for
                        // three fingers going down.
                        if (!closed3fThisGesture && rawFocus[1] - gestureStart3fY > OPEN_DRAG_PX) {
                            onCloseReference?.invoke()
                            closed3fThisGesture = true
                        }
                    } else if (!opened3fThisGesture && gestureStart3fY - rawFocus[1] > OPEN_DRAG_PX) {
                        onOpenReference?.invoke(focus[0], focus[1])
                        opened3fThisGesture = true
                    }
                    recordFingerAngles(event)
                    prev3fX = rawFocus[0]
                    prev3fY = rawFocus[1]
                    prev3fSpread = spread
                    lastFocusX = focus[0]
                    lastFocusY = focus[1]
                    return true
                }

                if (suppressScaleUntilGestureEnd) {
                    lastFocusX = focus[0]
                    lastFocusY = focus[1]
                    return true
                }
                val panAllowed = if (event.pointerCount == 1) oneFinger == FINGER_SCROLL
                    else twoFingers != TWO_IGNORED
                if (panAllowed && event.pointerCount == 1 && atDocumentEnd && onPullForPage != null) {
                    val along = if (document.layoutMode == PageLayoutMode.HORIZONTAL) focus[0] - lastFocusX
                        else focus[1] - lastFocusY
                    pullPastEnd = (pullPastEnd - along).coerceAtLeast(0f)
                    val armed = pullPastEnd > PULL_FOR_PAGE_PX * resources.displayMetrics.density
                    if (armed != pullArmed) { pullArmed = armed; onPullForPage?.invoke(armed) }
                } else if (!atDocumentEnd) pullPastEnd = 0f
                val pans = panAllowed && (!scaleDetector.isInProgress || event.pointerCount > 1)
                val multiplier = if (event.pointerCount == 1) {
                    viewportPanMultiplier.coerceIn(0.5f, 3f)
                } else {
                    1f
                }
                // Scale about where the fingers were and carry that point to
                // where they are, together; without a pan the zoom stays
                // about the old centroid, as it did before.
                val toX = if (pans) lastFocusX + (focus[0] - lastFocusX) * multiplier else lastFocusX
                val toY = if (pans) lastFocusY + (focus[1] - lastFocusY) * multiplier else lastFocusY
                if (pinch != 1f || toX != lastFocusX || toY != lastFocusY) {
                    documentToScreen.getValues(matrixValues)
                    viewportState[0] = matrixValues[Matrix.MSCALE_X]
                    viewportState[1] = matrixValues[Matrix.MTRANS_X]
                    viewportState[2] = matrixValues[Matrix.MTRANS_Y]
                    pinchStep(viewportState, pinch, lastFocusX, lastFocusY, toX, toY)
                    matrixValues[Matrix.MSCALE_X] = viewportState[0]
                    matrixValues[Matrix.MSCALE_Y] = viewportState[0]
                    matrixValues[Matrix.MTRANS_X] = viewportState[1]
                    matrixValues[Matrix.MTRANS_Y] = viewportState[2]
                    documentToScreen.setValues(matrixValues)
                    holdMoving()
                    onTransformChanged()
                }
                lastFocusX = focus[0]
                lastFocusY = focus[1]
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(fingerLongPress)
                endNoteTurn()
                if (pullArmed) {
                    pullArmed = false
                    pullPastEnd = 0f
                    // The hand is off: without this the page stayed "held"
                    // after a pull for a new page, and PDF detail with it.
                    viewportInteracting = false
                    zooming = false
                    releaseVelocity()
                    releaseMoving()
                    suppressScaleUntilGestureEnd = false
                    resumeViewportDetail()
                    onViewportInteractionChanged?.invoke(false)
                    onPullForPage?.invoke(false)
                    addPage(document.pages.lastIndex)
                    scrollToPage(document.pages.lastIndex)
                    return true
                }
                pullPastEnd = 0f
                val endedZoom = zooming
                val openedLink = !longPressFired && (unfoldTap(event) || objectLinkTap(event) ||
                    maybeOpenPdfLink(event.x, event.y))
                // Three fingers never move the page, so they never fling it: the
                // last finger off after moving the reference panel flung the
                // page inside it, which read as the panel jumping on release.
                if (!openedLink && gestureMaxPointers < 3) startFling()
                viewportInteracting = false
                viewportSpeedPxPerSecond = 0f
                viewportWasFast = false
                releaseVelocity()
                // A fling keeps the page moving; it lets go when it ends.
                if (!flinging) releaseMoving()
                if (!openedLink && !longPressFired) maybeHandleTap()
                endZoom()
                if (!endedZoom && !flinging) resumeViewportDetail()
                // A real fling keeps moving the page after the hand lifts, so
                // keep the expensive live glass recording paused until it ends.
                if (!flinging) onViewportInteractionChanged?.invoke(false)
                suppressScaleUntilGestureEnd = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(fingerLongPress)
                endNoteTurn()
                val endedZoom = zooming
                viewportInteracting = false
                viewportSpeedPxPerSecond = 0f
                viewportWasFast = false
                releaseVelocity()
                releaseMoving()
                endZoom()
                if (!endedZoom) resumeViewportDetail()
                onViewportInteractionChanged?.invoke(false)
                suppressScaleUntilGestureEnd = false
                return true
            }
        }
        return true
    }

    /** The finger gesture ends where a pen takes over: the page is no longer held or moving. */
    private fun endFingerGestureForPen() {
        removeCallbacks(fingerLongPress)
        pendingPinch = 1f
        viewportInteracting = false
        viewportSpeedPxPerSecond = 0f
        viewportWasFast = false
        releaseMoving()
        suppressScaleUntilGestureEnd = false
        onViewportInteractionChanged?.invoke(false)
    }

    private fun objectLinkTap(event: MotionEvent): Boolean {
        val duration = System.currentTimeMillis() - gestureStartTime
        if (gestureMaxPointers != 1 || duration >= TAP_MAX_MS || gestureMoved >= TAP_SLOP_PX) return false
        return followObjectLink(event.x, event.y)
    }

    private fun unfoldTap(event: MotionEvent): Boolean {
        val duration = System.currentTimeMillis() - gestureStartTime
        if (gestureMaxPointers != 1 || duration >= TAP_MAX_MS || gestureMoved >= TAP_SLOP_PX) return false
        return unfoldAt(event.x, event.y)
    }

    /** A short one-finger tap follows a portable, pre-indexed PDF link. */
    private fun maybeOpenPdfLink(screenX: Float, screenY: Float): Boolean {
        val duration = System.currentTimeMillis() - gestureStartTime
        if (gestureMaxPointers != 1 || duration >= TAP_MAX_MS || gestureMoved >= TAP_SLOP_PX) {
            return false
        }
        scratch[0] = screenX
        scratch[1] = screenY
        screenToDocument.mapPoints(scratch)
        val pageIndex = document.pageAt(scratch[0], scratch[1])
        val page = document.pages.getOrNull(pageIndex) ?: return false
        val source = pdf ?: return false
        if (page.background != PageBackground.PDF || page.pdfPageIndex !in 0 until source.pageCount) {
            return false
        }
        pageLocalInto(screenX, screenY, pageIndex, pageProbe)
        val link = source.indexedLinkAt(
            page.pdfPageIndex,
            pageProbe[0],
            pageProbe[1],
            touchSlop / currentScale().coerceAtLeast(0.001f),
        ) ?: return false
        if (link is PdfLink.Web) {
            val open = onLinkUrl ?: return false
            open(link.url)
            performClick()
            return true
        }
        val targetPdfPage = (link as PdfLink.Page).pdfPageIndex
        val targetDocumentPage = document.pages.indexOfFirst {
            it.background == PageBackground.PDF && it.pdfPageIndex == targetPdfPage
        }
        if (targetDocumentPage < 0) return false
        jumpToPage(targetDocumentPage)
        performClick()
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
        if (gestureMaxPointers !in 1..3 || duration >= TAP_MAX_MS || gestureMoved >= TAP_SLOP_PX || turnedThisGesture) {
            return
        }
        if (gestureMaxPointers == 1 && !doubleTapZoom) return
        val now = System.currentTimeMillis()
        val sameSpot = hypot(lastFocusX - lastTapX, lastFocusY - lastTapY) < DOUBLE_TAP_SLOP_PX
        if (lastTapFingers == gestureMaxPointers && now - lastTapTime < DOUBLE_TAP_MS && sameSpot) {
            when (gestureMaxPointers) {
                1 -> if (!zoomLocked) {
                    if (fitScale > 0f && currentScale() > fitScale * 1.2f) fitWidth()
                    else zoomBy(DOUBLE_TAP_ZOOM, lastFocusX, lastFocusY)
                }
                2 -> runTapAction(twoFingerTap)
                3 -> if (threeFingerGestures) runTapAction(threeFingerTap)
            }
            lastTapFingers = 0
        } else {
            lastTapFingers = gestureMaxPointers
            lastTapTime = now
            lastTapX = lastFocusX
            lastTapY = lastFocusY
        }
    }

    private fun runTapAction(action: Int) {
        when (action) {
            TAP_UNDO -> onUndo?.invoke()
            TAP_REDO -> onRedo?.invoke()
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
        // Frame times from here on; the first step covers the time from the
        // lift to the first frame, which is never more than a frame.
        flingLastNanos = System.nanoTime()
        if (!flinging) {
            flinging = true
            holdMoving()
            Choreographer.getInstance().postFrameCallback(flingStep)
        }
    }

    /**
     * Ends a fling, if there is one. Any touch or pen landing calls this
     * first: a new contact always wins over the glide.
     */
    private fun stopFling(resumeDetail: Boolean = true) {
        val wasFlinging = flinging
        flinging = false
        Choreographer.getInstance().removeFrameCallback(flingStep)
        if (!wasFlinging) return
        // Only a fling ending says the page has come to rest; a finger that
        // owns it now says otherwise straight after.
        if (!viewportInteracting) {
            releaseMoving()
            onViewportInteractionChanged?.invoke(false)
        }
        if (resumeDetail && !viewportInteracting) resumeViewportDetail()
    }

    /**
     * One fling frame, moved by the vsync time rather than by when the
     * callback happened to run: frames that start a little late or early
     * still move the page by even steps, which is what reads as smooth at
     * 60Hz. The step after a long frame is clamped (see [flingDt]).
     */
    private val flingStep = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!flinging || disposed) return
            val dt = flingDt(frameTimeNanos, flingLastNanos)
            // A frame time can trail the lift's own clock reading; never step backwards.
            flingLastNanos = maxOf(flingLastNanos, frameTimeNanos)

            documentToScreen.getValues(matrixValues)
            val beforeX = matrixValues[Matrix.MTRANS_X]
            val beforeY = matrixValues[Matrix.MTRANS_Y]
            val stepX = flingDistance(flingVx, dt, FLING_FRICTION)
            val stepY = flingDistance(flingVy, dt, FLING_FRICTION)
            documentToScreen.postTranslate(stepX, stepY)
            onTransformChanged()

            // Exponential decay rather than a fixed per-frame factor, so the
            // glide feels the same whether the display is at 60Hz or 120Hz.
            flingVx = flingVelocityAfter(flingVx, dt, FLING_FRICTION)
            flingVy = flingVelocityAfter(flingVy, dt, FLING_FRICTION)

            documentToScreen.getValues(matrixValues)
            val movedX = matrixValues[Matrix.MTRANS_X] - beforeX
            val movedY = matrixValues[Matrix.MTRANS_Y] - beforeY
            // An axis held at the document's edge has spent its speed. Kept,
            // it pressed against the clamp for the rest of the glide while
            // the other axis crawled, a frame drawn each time for nothing.
            if (flingAxisBlocked(stepX, movedX)) flingVx = 0f
            if (flingAxisBlocked(stepY, movedY)) flingVy = 0f
            if (hypot(flingVx, flingVy) < MIN_FLING_VELOCITY || (dt > 0f && abs(movedX) + abs(movedY) < 0.05f)) {
                stopFling()
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun leavingIndex(event: MotionEvent): Int =
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1

    private val focus = FloatArray(2)
    private val rawFocus = FloatArray(2)
    private val scratch = FloatArray(2)

    /** Each finger's angle about the hand's centre, in screen terms, for the next [trackNoteTurn]. */
    private fun recordFingerAngles(event: MotionEvent) {
        val screen = screenEvent ?: event
        val count = min(screen.pointerCount, MAX_TURN_POINTERS)
        handCentre(screen)
        for (i in 0 until count) {
            fingerAngleIds[i] = screen.getPointerId(i)
            fingerAngles[i] = atan2(screen.getY(i) - handCentre[1], screen.getX(i) - handCentre[0])
        }
        fingerAngleCount = count
    }

    private val handCentre = FloatArray(2)

    /** The fingers' centre, in the screen's frame - the turn is measured there, not in the turning one. */
    private fun handCentre(screen: MotionEvent) {
        var x = 0f
        var y = 0f
        for (i in 0 until screen.pointerCount) {
            x += screen.getX(i)
            y += screen.getY(i)
        }
        handCentre[0] = x / screen.pointerCount
        handCentre[1] = y / screen.pointerCount
    }

    /**
     * How far the fingers turned about their centre since the last sample,
     * passed on as a turn of the note once it is clearly a turn and not a
     * slide: [ROTATE_START_DEGREES] of it before anything moves. Raw screen
     * coordinates, because the view itself is what is turning. True while
     * the gesture is a turn.
     */
    private fun trackNoteTurn(event: MotionEvent): Boolean {
        val handler = onRotateNote ?: return false
        if (fingerAngleCount == 0) return rotating3f
        val screen = screenEvent ?: event
        handCentre(screen)
        var sum = 0f
        var counted = 0
        for (i in 0 until min(screen.pointerCount, MAX_TURN_POINTERS)) {
            val dx = screen.getX(i) - handCentre[0]
            val dy = screen.getY(i) - handCentre[1]
            // A finger near the centre swings wildly for no turn at all.
            if (hypot(dx, dy) < MIN_SPREAD_PX) continue
            val id = screen.getPointerId(i)
            var before = -1
            for (j in 0 until fingerAngleCount) if (fingerAngleIds[j] == id) { before = j; break }
            if (before < 0) continue
            var delta = atan2(dy, dx) - fingerAngles[before]
            if (delta > PI_F) delta -= 2f * PI_F
            if (delta < -PI_F) delta += 2f * PI_F
            sum += delta
            counted++
        }
        if (counted == 0) return rotating3f
        val turn = Math.toDegrees((sum / counted).toDouble()).toFloat()
        if (!rotating3f) {
            if (draggedReference || opened3fThisGesture || closed3fThisGesture) return false
            turn3fSoFar += turn
            if (abs(turn3fSoFar) < ROTATE_START_DEGREES) return false
            rotating3f = true
            turnedThisGesture = true
            handler(turn3fSoFar)
            return true
        }
        handler(turn)
        return true
    }

    private fun endNoteTurn() {
        if (!rotating3f) return
        rotating3f = false
        turn3fSoFar = 0f
        onRotateNoteEnd?.invoke()
    }

    private fun rawFingerFocus(turned: MotionEvent): FloatArray {
        // Raw from the touch as it came: the turned copy's raw is in the layout frame.
        val event = screenEvent ?: turned
        var x = 0f
        var y = 0f
        for (i in 0 until event.pointerCount) {
            x += event.getRawX(i)
            y += event.getRawY(i)
        }
        rawFocus[0] = x / event.pointerCount
        rawFocus[1] = y / event.pointerCount
        return rawFocus
    }

    private fun rawFingerSpread(turned: MotionEvent, x: Float, y: Float): Float {
        val event = screenEvent ?: turned
        var total = 0f
        for (i in 0 until event.pointerCount) {
            total += hypot(event.getRawX(i) - x, event.getRawY(i) - y)
        }
        return total / event.pointerCount
    }

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

    /**
     * The paper and the committed ink, for a glass backdrop to record - and
     * nothing else. Recording the whole view took its wet-ink SurfaceViews
     * along, and a SurfaceView sits wherever it was drawn last in a frame: the
     * glass bars draw their recording again, shifted and a little scaled, so
     * while the pen was down the wet ink showed there instead of under the
     * pen, and only the committed stroke, drawn by the page, landed where it
     * was written.
     */
    fun drawBackdrop(canvas: Canvas) {
        if (!canvas.isHardwareAccelerated || !dry.pageNode.hasDisplayList()) return
        canvas.drawRenderNode(dry.pageNode)
    }

    private fun screenToPage(index: Int): Matrix {
        strokeTransform.set(screenToDocument)
        strokeTransform.postTranslate(-document.leftOf(index), -document.topOf(index))
        return strokeTransform
    }

    private fun currentBrush(): Brush {
        val brushTool = if (tool.isFreehandPen() && !tool.isDabbed()) {
            when (dottedPattern) {
                1 -> Tool.DOTTED
                2 -> Tool.DASHED
                3 -> Tool.DASH_DOT
                else -> tool
            }
        } else tool
        // The bucketed zoom, the same one refineVisiblePages asks for. At the
        // raw zoom a new stroke's epsilon differed from what the refine wanted
        // at most zooms, so every stroke was rebuilt and its page re-recorded
        // just after the pen lifted - a visible hitch for broad pencil strokes.
        val epsilon = if (brushTool.isDabbed()) dabEpsilon(strokeWidth)
            else strokeEpsilon(strokeWidth, epsilonFor(tessellationBucket(currentScale())))
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
        val loadOnly = holdingDetail() || document.liveCanvasCount > 1
        val target = tessellationBucket(currentScale())
        val epsilon = epsilonFor(target)
        val serial = refineRequestSerial

        releaseDistantPages()
        val visible = visiblePages()
        // Read in already and waiting for its frame: no second read from disk.
        takePrefetched(visible)
        val toLoad = visible.filter { !it.loaded }

        // At deep zoom only a fraction of a page is visible. Rebuilding the
        // whole page there produced enormous meshes that were immediately off
        // screen. Refine the viewport plus a small pan margin; newly exposed
        // regions are picked up by the same debounced path.
        val viewport = FloatArray(4)
        dry.visibleDocumentBounds(viewport)
        val margin = REFINE_MARGIN_PX / currentScale().coerceAtLeast(0.01f)
        val work = visible.mapIndexedNotNull { _, page ->
            if (loadOnly) return@mapIndexedNotNull null
            if (!page.loaded || page.strokes.isEmpty()) return@mapIndexedNotNull null
            val index = document.pages.indexOf(page)
            if (index < 0) return@mapIndexedNotNull null
            val left = viewport[0] - document.leftOf(index) - margin
            val top = viewport[1] - document.topOf(index) - margin
            val right = viewport[2] - document.leftOf(index) + margin
            val bottom = viewport[3] - document.topOf(index) + margin
            val strokes = dry.strokesIn(page, left, top, right, bottom).filter { stroke ->
                val wanted = inkEpsilon(stroke.brush.family, stroke.brush.size, epsilon)
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
            // Built once at the zoom in use, up to the scale where a whole page
            // is on screen: loading at 1 and rebuilding at 2 straight after
            // tessellated a dense page twice and re-recorded all its tiles.
            val loadEpsilon = epsilonFor(minOf(target, LOAD_TESSELLATION_SCALE))
            val loaded = ArrayList<Pair<Page, List<Stroke>>>(toLoad.size)
            val loadedMasks = ArrayList<Pair<Page, List<PageMask>>>(toLoad.size)
            val built = ArrayList<Triple<Page, List<Stroke>, List<Stroke>>>(work.size)
            val twins = ArrayList<Pair<Stroke, Stroke?>>()
            val builtTwins = ArrayList<Pair<Stroke, Stroke?>>()
            // Each loaded page's stroke index, built here so the first frame
            // that shows the page does not build it.
            val grids = IdentityHashMap<Page, StrokeGrid>()
            var refinedCount = 0
            // Mesh rebuilding that a newer request overtook stops; pages
            // already read in still go to the screen.
            var meshesOvertaken = false
            // A page read in full is kept even when a newer request has come
            // in: throwing it away and reading it again was the same dense page
            // decoded and tessellated twice for one fling, on every core.
            var abandoned = false
            try {
                loading@ for (page in toLoad) {
                    if (obsolete()) { abandoned = true; break@loading }
                    loader?.let {
                        // The screen is waiting on this page: it keeps a
                        // couple of cores for its meshes even mid-gesture.
                        val strokes = StrokeBuilder.forScreen { it(page, loadEpsilon) }
                        // Interrupted, the reader hands back what it had: a partial page.
                        if (Thread.currentThread().isInterrupted || disposed) { abandoned = true; break@loading }
                        loaded += page to strokes
                        refinedCount += strokes.size
                    }
                    masksLoader?.let {
                        val pageMasks = it(page, loadEpsilon)
                        if (Thread.currentThread().isInterrupted || disposed) {
                            loaded.removeAll { entry -> entry.first === page }
                            abandoned = true
                            break@loading
                        }
                        loadedMasks += page to pageMasks
                    }
                }
                if (meshInk && !disposed) {
                    for ((_, strokes) in loaded) for (stroke in strokes) twins += stroke to meshTwinOf(stroke)
                }
                for ((page, strokes) in loaded) {
                    if (disposed) break
                    grids[page] = StrokeGrid().also { it.prebuild(strokes) }
                }
                if (!abandoned) rebuilding@ for ((page, snapshot) in work) {
                    if (obsolete()) { meshesOvertaken = true; break@rebuilding }
                    val rebuilt = ArrayList<Stroke>(snapshot.size)
                    for (stroke in snapshot) {
                        if (obsolete()) { meshesOvertaken = true; break@rebuilding }
                        rebuilt += Stroke(
                            stroke.brush.copy(epsilon = inkEpsilon(stroke.brush.family, stroke.brush.size, epsilon)),
                            stroke.inputs,
                        )
                        refinedCount++
                    }
                    built += Triple(page, snapshot, rebuilt)
                }
                // The mesh-rendered twins of what was just built, made here
                // rather than on the first frame that draws them: thousands of
                // Brush and Stroke allocations in one onDraw was a visible hitch
                // every time a dense page came on screen or was retessellated.
                if (meshInk && !abandoned && !meshesOvertaken) {
                    twinning@ for ((_, _, rebuilt) in built) for (stroke in rebuilt) {
                        if (obsolete()) { meshesOvertaken = true; break@twinning }
                        builtTwins += stroke to meshTwinOf(stroke)
                    }
                }
            } finally {
                if (refineStarted != 0L) {
                    val elapsed = System.nanoTime() - refineStarted
                    Trace.endSection()
                    latency.addRefine(elapsed, refinedCount)
                }
            }
            if (disposed || (loaded.isEmpty() && obsolete())) return@submit
            post {
                if (disposed) return@post
                val applyStarted = System.nanoTime()
                // Rebuilt meshes are for one zoom and one request; pages read
                // from disk are good whatever has happened since.
                val current = !abandoned && !meshesOvertaken && serial == refineRequestSerial &&
                    (loadOnly || tessellationBucket(currentScale()) == target)
                if (abandoned) WorkStats.loadsAbandoned.incrementAndGet()
                if (!current && built.isNotEmpty()) WorkStats.refinesStale.incrementAndGet()
                if (!current && loaded.isNotEmpty()) WorkStats.pagesLoadedStale.addAndGet(loaded.size.toLong())
                WorkStats.pagesLoaded.addAndGet(loaded.size.toLong())
                Trace.beginSection(TRACE_REFINE_APPLY)
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
                    // Settling reorders by layer; an index built for the file's
                    // order is only good if it left the order alone.
                    if (!page.settleLayers(document.layers)) dry.adoptStrokeIndex(page, grids[page])
                    page.tessellatedFor = minOf(target, LOAD_TESSELLATION_SCALE)
                    page.renderState = RenderState.ViewportCached
                }
                dry.adoptTwins(twins)
                if (!current) {
                    Trace.endSection()
                    latency.record(PerformanceMetric.PAGE_APPLY, System.nanoTime() - applyStarted)
                    if (loaded.isNotEmpty()) dry.postInvalidateOnAnimation()
                    // Nothing newer was asked for (the zoom moved on without a
                    // request, say): this job's pages still need their pass.
                    if (serial == refineRequestSerial) {
                        refiningPages = emptyList()
                        refineFuture = null
                        scheduleRefine()
                    }
                    return@post
                }
                // One map for every page, and history walked once with it:
                // walking the whole undo stack per refined page was most of
                // this apply on a page with a long history.
                val replacements = IdentityHashMap<Stroke, Stroke>()
                for ((page, snapshot, rebuilt) in built) {
                    val before = replacements.size
                    for (i in snapshot.indices) replacements[snapshot[i]] = rebuilt[i]
                    if (replacements.size == before) continue
                    var replaced = 0
                    for (i in page.strokes.indices) {
                        replacements[page.strokes[i]]?.let {
                            StrokeTags.carry(page.strokes[i], it)
                            page.strokes[i] = it
                            replaced++
                        }
                    }
                    if (replaced == 0) continue
                    // Mixed fidelity is intentional at deep zoom.
                    page.tessellatedFor = if (replaced == page.strokes.size) target else 0f
                    page.meshRevision++
                    // Nothing about the saved file changed: same inputs, same
                    // brush, only the generated outline. Do not dirty the page.
                }
                if (replacements.isNotEmpty()) remapHistory(replacements)
                for (page in pages) {
                    page.renderState = completedRenderState(
                        page.loaded,
                        page.strokes.isEmpty() || abs(page.tessellatedFor - target) < EPSILON_SLOP,
                    )
                }
                refiningPages = emptyList()
                refineFuture = null
                dry.adoptTwins(builtTwins)
                if (built.isNotEmpty()) WorkStats.refinesApplied.incrementAndGet()
                Trace.endSection()
                latency.record(PerformanceMetric.PAGE_APPLY, System.nanoTime() - applyStarted)
                dry.postInvalidateOnAnimation()
                // Read in at the zoom in use, a loaded page needs no second pass
                // unless the zoom is finer than a page is first read at.
                if (loaded.isNotEmpty() && target > LOAD_TESSELLATION_SCALE) scheduleRefine()
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
    private var lastReleaseStamp = LongArray(0)

    private fun releaseDistantPages() {
        if (document.liveCanvasCount > 1) return
        if (document.pages.size <= KEEP_PAGES * 2 + 1) return
        val visible = visiblePages()
        if (visible.isEmpty()) return
        val first = document.pages.indexOf(visible.first())
        val last = document.pages.indexOf(visible.last())
        if (first < 0 || last < 0) return
        // Every refine came through here, building a set of the whole history
        // and walking every page, even while writing on one page. Nothing new
        // can be released until the view moves or another page is read in.
        val stamp = longArrayOf(first.toLong(), last.toLong(), document.pages.size.toLong(),
            document.pages.count { it.loaded && !it.dirty }.toLong())
        if (stamp.contentEquals(lastReleaseStamp)) return
        lastReleaseStamp = stamp
        val keep = (first - KEEP_PAGES)..(last + KEEP_PAGES)
        val protected = IdentityHashMap<Page, Boolean>()
        undoStack.forEach { protected[it.page] = true }
        redoStack.forEach { protected[it.page] = true }
        for ((index, page) in document.pages.withIndex()) {
            if (index in keep || !page.loaded || page.dirty) continue
            if (protected.containsKey(page)) continue
            page.savedStrokeCount = page.allStrokes.size
            page.strokes.clear()
            page.hiddenStrokes.clear()
            page.masks.clear()
            dry.dropStrokeIndex(page)
            // The tile cache and recorded page held their own list of the
            // page's strokes, keeping the ink alive after the page let go of it.
            dry.forgetPage(page)
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
                is Edit.ImageAdded, is Edit.ImageRemoved, is Edit.ImageReplaced, is Edit.ImageAddedInGroup -> Unit
                is Edit.MaskAdded, is Edit.MaskRemoved -> Unit
                is Edit.PageInserted, is Edit.PageRemoved, is Edit.PageMoved, is Edit.PagePaper,
                is Edit.PageRotated, is Edit.LayerEntry -> Unit
                is Edit.LayerInk -> edit.strokes = edit.strokes.map { replacements[it] ?: it }
                is Edit.Moved -> {
                    edit.before = edit.before.map { replacements[it] ?: it }
                    edit.after = edit.after.map { replacements[it] ?: it }
                }
            }
        }
    }

    private val visibleProbe = FloatArray(4)

    /**
     * Whether a page on screen still has its ink to read in. Asked on every
     * moving frame, so it walks the pages in view without building a list.
     */
    private fun anyVisibleUnloaded(): Boolean {
        if (width == 0 || height == 0) return false
        val bounds = visibleProbe
        layoutViewport(bounds)
        screenToDocument.mapPoints(bounds)
        val top = minOf(bounds[1], bounds[3])
        val bottom = maxOf(bounds[1], bounds[3])
        val left = minOf(bounds[0], bounds[2])
        val right = maxOf(bounds[0], bounds[2])
        for (i in document.pagesIntersecting(top, bottom)) {
            val page = document.pages.getOrNull(i) ?: continue
            if (page.loaded) continue
            val pageTop = document.topOf(i)
            val pageLeft = document.leftOf(i)
            if (pageTop <= bottom && pageTop + page.height >= top &&
                pageLeft <= right && pageLeft + page.width >= left) return true
        }
        return false
    }

    private fun visiblePages(): List<Page> {
        if (width == 0 || height == 0) return emptyList()
        val bounds = FloatArray(4)
        layoutViewport(bounds)
        screenToDocument.mapPoints(bounds)
        val top = minOf(bounds[1], bounds[3])
        val bottom = maxOf(bounds[1], bounds[3])
        val left = minOf(bounds[0], bounds[2])
        val right = maxOf(bounds[0], bounds[2])
        return document.pagesIntersecting(top, bottom).mapNotNull { i ->
            val page = document.pages.getOrNull(i) ?: return@mapNotNull null
            val pageTop = document.topOf(i)
            val pageLeft = document.leftOf(i)
            // Across too: in a row or spread layout the band of rows holds pages
            // well off either side, and each was being loaded and drawn.
            page.takeIf { pageTop <= bottom && pageTop + page.height >= top &&
                pageLeft <= right && pageLeft + page.width >= left }
        }
    }

    /** Warms the immutable Ink brush after tool settings or fit scale change. */
    fun prepareBrush() {
        if (tool != Tool.ERASER) currentBrush()
    }

    /** Current page first, then alternating in the travel direction and behind it. */
    private fun nearbyPageIndices(span: PrefetchSpan): List<Int> =
        nearbyPages(currentPage, document.pages.size, span, lastPageDirection)

    private fun prioritizePdf(radius: Int) = prioritizePdf(PrefetchSpan(radius, radius))

    private fun prioritizePdf(span: PrefetchSpan) {
        val source = pdf ?: return
        val pages = nearbyPageIndices(span).mapNotNull { index ->
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
    private fun prefetchNearbyPages(span: PrefetchSpan) {
        if (disposed || thermalHot) return
        val visible = Collections.newSetFromMap(IdentityHashMap<Page, Boolean>())
        visible += visiblePages()
        val pages = nearbyPageIndices(span).map { document.pages[it] }
            .filter { !it.loaded && it !in visible }
        if (pages.isEmpty()) return
        val loader = pageLoader ?: return
        val masksLoader = maskLoader
        val scale = minOf(tessellationBucket(currentScale()), LOAD_TESSELLATION_SCALE)
        val epsilon = epsilonFor(scale)
        val serial = refineRequestSerial
        val withTwins = meshInk
        runCatching { refiner.execute {
            val loaded = ArrayList<PrefetchedPage>(pages.size)
            for (page in pages) {
                // A new viewport has a zero-delay visible-page job waiting on
                // this same executor. Yield after at most one disk page instead
                // of making it wait for the old six-page halo.
                if (disposed || serial != refineRequestSerial) break
                val strokes = loader(page, epsilon)
                val pageMasks = masksLoader?.let { it(page, epsilon) }
                // Twins here too: made by the first frame that showed the page,
                // they were thousands of allocations inside one onDraw.
                val twins = if (withTwins) strokes.map { it to meshTwinOf(it) } else emptyList()
                val grid = StrokeGrid().also { it.prebuild(strokes) }
                loaded += PrefetchedPage(page, strokes, pageMasks, twins, grid, scale)
            }
            if (loaded.isEmpty()) return@execute
            post {
                // Kept even if the view has moved on: the pages are read, and a
                // page scrolled back to would only be read again.
                if (disposed) return@post
                WorkStats.prefetched.addAndGet(loaded.size.toLong())
                // Put in one page a frame: nothing on screen waits for these,
                // and several dense pages put in together were one long frame.
                // A page the screen reaches first is put in by the refine
                // that wants it (see takePrefetched).
                for (item in loaded) prefetchedPending[item.page] = item
                val queue = ArrayDeque(loaded)
                val step = object : Runnable {
                    override fun run() {
                        if (disposed) return
                        while (queue.isNotEmpty()) {
                            val item = queue.removeFirst()
                            if (prefetchedPending.remove(item.page) == null) continue
                            applyPrefetched(item)
                            break
                        }
                        if (queue.isNotEmpty()) postOnAnimation(this)
                    }
                }
                step.run()
            }
        } }
    }

    /** A page read in by the prefetch, waiting for its turn on the UI thread. */
    private class PrefetchedPage(
        val page: Page,
        val strokes: List<Stroke>,
        val masks: List<PageMask>?,
        val twins: List<Pair<Stroke, Stroke?>>,
        val grid: StrokeGrid,
        val scale: Float,
    )

    private val prefetchedPending = IdentityHashMap<Page, PrefetchedPage>()

    private fun applyPrefetched(item: PrefetchedPage) {
        val page = item.page
        if (page.loaded || document.pages.none { it === page }) return
        val started = System.nanoTime()
        dry.adoptTwins(item.twins)
        page.strokes.addAll(0, item.strokes)
        page.savedOnDisk = if (page.dirty) 0 else page.strokes.size
        item.masks?.let { page.masks.addAll(0, it) }
        page.loaded = true
        if (!page.settleLayers(document.layers)) dry.adoptStrokeIndex(page, item.grid)
        page.tessellatedFor = item.scale
        page.renderState = RenderState.ViewportCached
        latency.record(PerformanceMetric.PAGE_APPLY, System.nanoTime() - started)
        // Off screen, as a prefetch is meant to be, there is nothing to redraw.
        if (visiblePages().any { it === page }) dry.postInvalidateOnAnimation()
    }

    /** Puts in at once any prefetched page among [pages], which the screen now wants. */
    private fun takePrefetched(pages: List<Page>) {
        if (prefetchedPending.isEmpty()) return
        for (page in pages) prefetchedPending.remove(page)?.let(::applyPrefetched)
    }

    private fun scheduleStoppedPrefetch() {
        if (disposed) return
        removeCallbacks(prefetchRunnable)
        postDelayed(prefetchRunnable, PREFETCH_SETTLE_MS)
    }

    /**
     * Drops the refine waiting to start or running for the viewport being
     * left. Not interrupted: a job reading a page finishes that page and
     * hands it over (see refineVisiblePages), and a job rebuilding meshes
     * stops at its next stroke when it sees the serial has moved on.
     */
    private fun cancelDetailWork() {
        if (disposed) return
        removeCallbacks(refineRunnable)
        refineRunnablePosted = false
        refineRequestSerial++
        refineFuture?.cancel(false)
        refineFuture = null
        for (page in refiningPages) {
            if (page.renderState == RenderState.Rendering) page.renderState = RenderState.Dirty
        }
        refiningPages = emptyList()
    }

    private fun scheduleRefine() {
        if (disposed) return
        if (holdingDetail() && (refineRunnablePosted || refineFuture?.isDone == false)) return
        cancelDetailWork()
        // Keep loading newly exposed pages during movement. Mesh refinement
        // still waits until the gesture ends.
        val missing = anyVisibleUnloaded()
        if (holdingDetail() && !missing) return
        // A page with nothing on it yet should not wait out the settle delay -
        // that delay exists to avoid rebuilding mid-pinch, not to hold up the
        // first paint of a note.
        val delay = if (missing) 0L else REFINE_DEBOUNCE_MS
        refineRunnablePosted = true
        postDelayed(refineRunnable, delay)
    }

    // ---- PDF text selection -------------------------------------------------

    @Volatile private var selectionSerial = 0

    /** Results from requests at or below this were cancelled and are never shown. */
    @Volatile private var selectionCancelledAt = 0

    /** The newest request whose result is on screen. */
    private var selectionShown = 0

    /**
     * Text selection's own worker. On the refiner it queued behind stroke
     * rebuilds; and a result was only shown if no newer request had come in
     * meanwhile, which while the pen was still dragging was never - so the
     * selection appeared only once the pen lifted. Now the newest request
     * waiting is the one worked out next, and each answer is shown as it
     * arrives unless something newer is already on screen.
     */
    private val selectionWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "notesis-text-select").apply { isDaemon = true }
    }

    private fun requestSelection(source: PdfSource, pdfPage: Int, start: RectF, end: RectF) {
        val serial = ++selectionSerial
        if (selectionWorker.isShutdown) return
        selectionWorker.execute {
            // A newer request is queued behind this one: let it answer instead.
            if (serial != selectionSerial) return@execute
            val found = source.select(pdfPage, start, end)
            post {
                if (serial <= selectionCancelledAt || serial < selectionShown) return@post
                selectionShown = serial
                setSelection(found)
            }
        }
    }

    /**
     * The hold won: throw away the stroke it started and select the word under
     * the pen instead.
     */
    private fun followLinkAt(index: Int, screenX: Float, screenY: Float) {
        val page = document.pages.getOrNull(index) ?: return
        val source = pdf ?: return
        if (page.background != PageBackground.PDF || page.pdfPageIndex < 0) return
        val point = pageLocal(screenX, screenY, index)
        val pdfIndex = page.pdfPageIndex
        // Off the UI thread: the renderer lock can be held by a page render.
        Thread {
            val link = source.linkAt(pdfIndex, point.left, point.top) ?: return@Thread
            post {
                if (disposed) return@post
                when (link) {
                    is PdfLink.Web -> {
                        clearSelection()
                        onLinkUrl?.invoke(link.url)
                    }
                    is PdfLink.Page -> {
                        val target = document.pages.indexOfFirst {
                            it.background == PageBackground.PDF && it.pdfPageIndex == link.pdfPageIndex
                        }
                        if (target >= 0) {
                            clearSelection()
                            jumpToPage(target)
                        }
                    }
                }
            }
        }.start()
    }

    private fun beginTextSelection() {
        val index = selectingPage
        val source = pdf ?: return
        if (index !in document.pages.indices) return
        activeStrokeId?.let { wet.cancelStroke(it) }
        cancelCompatWet(activeStrokeId)
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
        selectionCancelledAt = ++selectionSerial
        readButtonGesture.reset()
        selectionAnchor = null
        if (selection == null) return
        setSelection(null)
    }

    /**
     * Turns the selected text boxes into real highlighter strokes, so a
     * highlight is ink on the page like any other and needs no new file format.
     */
    fun highlightSelection(color: Int = 0x66F9A825) {
        val found = selection ?: return
        val page = document.pages.getOrNull(selectingPage) ?: return
        if (refuseLockedTarget()) return
        val brush = Brush.createWithColorIntArgb(
            family = Tool.HIGHLIGHTER.brushFamily(),
            colorIntArgb = color,
            size = 1f,
            epsilon = epsilonFor(currentScale()),
        )
        val group = nextEditGroup++
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
            undoStack += Edit.Drawn(page, stroke, group)
        }
        redoStack.clear()
        clearSelection()
        afterEdit(page)
    }

    /** A pen line through the middle of every selected line of text, one undo for all of them. */
    fun strikeSelection(color: Int = 0xFFE53935.toInt()) {
        val found = selection ?: return
        val page = document.pages.getOrNull(selectingPage) ?: return
        if (refuseLockedTarget()) return
        val group = nextEditGroup++
        for (box in found.boxes) {
            if (box.width() <= 0f || box.height() <= 0f) continue
            val brush = Brush.createWithColorIntArgb(
                family = Tool.PEN.brushFamily(),
                colorIntArgb = color,
                size = (box.height() * STRIKE_HEIGHT).coerceAtLeast(1f),
                epsilon = epsilonFor(currentScale()),
            )
            val inputs = MutableStrokeInputBatch()
            inputs.add(InputToolType.STYLUS, box.left, box.centerY(), 0L)
            inputs.add(InputToolType.STYLUS, box.right, box.centerY(), 16L)
            val stroke = Stroke(brush, inputs.toImmutable())
            page.strokes += stroke
            undoStack += Edit.Drawn(page, stroke, group)
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
    /**
     * Lays screen (x, y) against the ruler edge in hand and keeps it in page
     * units. A straight edge needs only its two ends; an arc keeps its path.
     */
    private fun addRulerPoint(x: Float, y: Float) {
        val edge = rulerEdge ?: return
        ruler.project(edge, x, y, strokeWidth * currentScale() / 2f + RULER_GAP_PX, rulerScratch)
        val point = FloatArray(2)
        pageLocalInto(rulerScratch[0], rulerScratch[1], shapePage, point)
        if (!edge.isArc && rulerPoints.size >= 2) rulerPoints[1] = point else rulerPoints += point
    }

    private fun finishShape() {
        val kind = shapeKind
        val page = document.pages.getOrNull(shapePage)
        drawingShape = false
        val ruled = rulerEdge != null
        rulerEdge = null
        if ((kind == null && !ruled) || page == null) {
            rulerPoints.clear()
            dry.invalidate()
            return
        }
        if (!ruled && kind == ShapeKind.CHECKBOX) {
            rulerPoints.clear()
            placeCheckbox(page, shapeStart, shapeEnd)
            return
        }
        val points = if (ruled) rulerPoints.toList() else shapePoints(kind!!, shapeStart, shapeEnd)
        rulerPoints.clear()
        if (points.size < 2) {
            dry.invalidate()
            return
        }
        val group = nextEditGroup++
        // A straight line drawn with the mask tool covers exactly like any
        // other strip of tape - it goes on the mask list, not the ink list, or
        // it could never be lifted to read what is underneath.
        for (stroke in strokesThrough(points, currentBrush())) {
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

    /**
     * A checkbox where the pen went down: one line tall where it was only
     * tapped, centred on the tap, or the square the pen dragged out. In the
     * pen's colour, unticked.
     */
    private fun placeCheckbox(page: Page, from: FloatArray, to: FloatArray) {
        val dx = to[0] - from[0]
        val dy = to[1] - from[1]
        val dragged = max(abs(dx), abs(dy))
        val box = if (dragged < CHECKBOX_MIN_SIZE) {
            val side = CHECKBOX_SIZE
            RectF(from[0] - side / 2f, from[1] - side / 2f, from[0] + side / 2f, from[1] + side / 2f)
        } else {
            val side = dragged
            val left = if (dx >= 0f) from[0] else from[0] - side
            val top = if (dy >= 0f) from[1] else from[1] - side
            RectF(left, top, left + side, top + side)
        }
        // Kept on the page, so a tap at the very edge does not hang off it.
        box.offset(
            (-box.left).coerceAtLeast(0f) - (box.right - page.width).coerceAtLeast(0f),
            (-box.top).coerceAtLeast(0f) - (box.bottom - page.height).coerceAtLeast(0f),
        )
        val image = PageImage(
            x = box.left,
            y = box.top,
            width = box.width(),
            height = box.height(),
            box = PageImage.BOX_CHECK,
            color = colorArgb or -0x1000000,
        )
        page.images += image
        undoStack += Edit.ImageAdded(page, image)
        redoStack.clear()
        afterEdit(page)
    }

    /**
     * Ticks or unticks the checkbox under a pen that has just landed at
     * ([screenX], [screenY]) on page [index]. True when there was one, and
     * the touch is then spent on it rather than on ink. Undoable, since a
     * ticked list is work too.
     */
    private fun toggleCheckboxAt(screenX: Float, screenY: Float, index: Int): Boolean {
        val page = document.pages.getOrNull(index) ?: return false
        if (page.images.none { it.box == PageImage.BOX_CHECK }) return false
        pageLocalInto(screenX, screenY, index, scratch)
        val x = scratch[0]
        val y = scratch[1]
        // A little past the edge counts: the box is small and the pen is not.
        val slop = CHECKBOX_TOUCH_SLOP_PX / currentScale().coerceAtLeast(0.01f)
        val at = page.images.indexOfLast {
            it.box == PageImage.BOX_CHECK &&
                x >= it.x - slop && x <= it.x + it.width + slop &&
                y >= it.y - slop && y <= it.y + it.height + slop
        }
        if (at < 0) return false
        val before = page.images[at]
        val after = before.copy(checked = !before.checked)
        page.images[at] = after
        undoStack += Edit.ImageReplaced(page, before, after, at)
        redoStack.clear()
        afterEdit(page)
        return true
    }

    private fun shapeStrokes(kind: ShapeKind, from: FloatArray, to: FloatArray, brush: Brush): List<Stroke> =
        strokesThrough(shapePoints(kind, from, to), brush)

    private fun strokesThrough(points: List<FloatArray>, brush: Brush): List<Stroke> {
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

    /** The outline of [kind]; a nearly square box is made square. See [squaredEnd]. */
    private fun shapePoints(
        kind: ShapeKind,
        from: FloatArray,
        to: FloatArray,
    ): List<FloatArray> {
        val end = squaredShapeEnd(kind, from, to) ?: to
        val outline = shapeOutline(kind, from[0], from[1], end[0], end[1], axisSnapEnabled)
        return if (kind.cornered && shapeCornerRadius > 0f) roundCorners(outline, shapeCornerRadius) else outline
    }

    private fun squaredShapeEnd(kind: ShapeKind, from: FloatArray, to: FloatArray): FloatArray? =
        if (kind.squarable) squaredEnd(from[0], from[1], to[0], to[1]) else null

    // ---- lasso --------------------------------------------------------------

    /** The one stroke selected, when it is a shape with few enough corners to drag. */
    private fun editableShape(): Stroke? =
        lassoStrokes.singleOrNull()?.takeIf { lassoImages.isEmpty() && it.inputs.size in 2..MAX_EDITABLE_INPUTS }

    private fun beginLasso(event: MotionEvent, index: Int) {
        pageLocalInto(event.x, event.y, index, shapeStart)
        val shape = editableShape()
        if (shape != null && index == lassoPage) {
            val reach = VERTEX_HANDLE_PX * 1.6f / currentScale()
            val sample = StrokeInput()
            var hit = -1
            for (i in 0 until shape.inputs.size) {
                shape.inputs.populate(i, sample)
                if (hypot(sample.x - shapeStart[0], sample.y - shapeStart[1]) <= reach) { hit = i; break }
            }
            if (hit >= 0) {
                shape.inputs.populate(hit, sample)
                val hx = sample.x
                val hy = sample.y
                // Every input sitting on this corner moves with it: a closed shape's
                // first and last, an arrow's tip and its barbs' return.
                vertexInputs = (0 until shape.inputs.size).filter { i ->
                    shape.inputs.populate(i, sample)
                    abs(sample.x - hx) < 0.01f && abs(sample.y - hy) < 0.01f
                }.toIntArray()
                vertexOriginal = shape
                vertexCurrent = shape
                return
            }
        }
        // Inside an existing selection the gesture is a drag, not a new loop.
        if (hasLassoSelection() && index == lassoPage &&
            lassoBounds.contains(shapeStart[0], shapeStart[1])
        ) {
            movingLasso = true
            liftedLassoStrokes = Collections.newSetFromMap(IdentityHashMap<Stroke, Boolean>()).apply {
                addAll(lassoStrokes)
            }
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
        lassoCorner[0] = shapeStart[0]
        lassoCorner[1] = shapeStart[1]
        dry.invalidate()
    }

    /**
     * AndroidX Ink consumes every historical sample from the original event.
     * Lasso is app-owned, so retain its useful history here as primitive points
     * while dropping samples closer than two screen pixels.
     */
    private fun appendLassoSamples(event: MotionEvent, pointerIndex: Int, invalidate: Boolean = true) {
        if (lassoRect && drawingLasso) {
            // Corner to corner: the loop is the rectangle's four sides.
            pageLocalInto(event.getX(pointerIndex), event.getY(pointerIndex), lassoPage, shapeEnd)
            val x0 = lassoCorner[0]
            val y0 = lassoCorner[1]
            val x1 = shapeEnd[0]
            val y1 = shapeEnd[1]
            lassoPath.clear()
            lassoPath.add(x0, y0)
            lassoPath.add(x1, y0)
            lassoPath.add(x1, y1)
            lassoPath.add(x0, y1)
            lassoPath.add(x0, y0)
            if (invalidate) {
                refreshLassoPreview(event.eventTime)
                dry.postInvalidateOnAnimation()
            }
            return
        }
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
        if (changed && invalidate && drawingLasso) refreshLassoPreview(event.eventTime)
        if (changed && invalidate) dry.postInvalidateOnAnimation()
    }

    /** Re-collects [lassoPreviewStrokes] at most every [LASSO_PREVIEW_MS], or at once when [force]. */
    private fun refreshLassoPreview(time: Long, force: Boolean = false) {
        if (!force && time - lassoPreviewAt < LASSO_PREVIEW_MS) return
        lassoPreviewAt = time
        val page = document.pages.getOrNull(lassoPage) ?: return
        if (lassoPath.size < LASSO_MIN_POINTS) {
            lassoPreviewStrokes.clear()
            lassoPreviewImages.clear()
            return
        }
        collectInsideLasso(page, lassoPreviewStrokes, lassoPreviewImages)
    }

    private fun clearLassoPreview() {
        lassoPreviewStrokes.clear()
        lassoPreviewImages.clear()
        lassoPreviewAt = 0L
    }

    /** Keeps the pen's path for circle to lasso; never redraws, the wet ink is the picture. */
    private fun trackCircle(event: MotionEvent, pointerIndex: Int) {
        appendLassoSamples(event, pointerIndex, invalidate = false)
        trackHold(event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime)
    }

    /** Where the pen last came to rest, and since when. */
    private fun trackHold(x: Float, y: Float, time: Long) {
        if (hypot(x - holdX, y - holdY) > touchSlop * HOLD_SLOP_FRACTION) {
            holdX = x
            holdY = y
            holdSince = time
        }
    }

    /**
     * The pen came to rest at the end of a loop: if the loop closes and has
     * something in it, the ink being written is dropped and what it went round
     * is selected, still under the pen, so carrying on moving drags it away.
     * A loop round nothing stays ink.
     */
    private fun convertCircleToLasso() {
        val strokeId = activeStrokeId ?: return
        if (lassoPage !in document.pages.indices || lassoPath.size < LASSO_MIN_POINTS * 2) return
        var left = lassoPath[0]
        var right = left
        var top = lassoPath[1]
        var bottom = top
        for (i in 2 until lassoPath.size step 2) {
            left = min(left, lassoPath[i]); right = max(right, lassoPath[i])
            top = min(top, lassoPath[i + 1]); bottom = max(bottom, lassoPath[i + 1])
        }
        val endX = lassoPath[lassoPath.size - 2]
        val endY = lassoPath[lassoPath.size - 1]
        val gap = hypot(endX - lassoPath[0], endY - lassoPath[1])
        if (!isLassoLoop(gap, right - left, bottom - top, CIRCLE_MIN_EXTENT_PX / currentScale())) return
        if (!selectInsideLasso()) return
        circleTracking = false
        lassoPath.clear()
        removeCallbacks(longPress)
        wet.cancelStroke(strokeId)
        cancelCompatWet(strokeId)
        activeStrokeId = null
        predictionHead.clear(); subframe.clear(); subframeTrack.reset()
        movingLasso = true
        liftedLassoStrokes = Collections.newSetFromMap(IdentityHashMap<Stroke, Boolean>()).apply {
            addAll(lassoStrokes)
        }
        lassoGrab[0] = endX
        lassoGrab[1] = endY
        lassoDx = 0f
        lassoDy = 0f
        dry.invalidate()
        onLassoSelected?.invoke(lassoSelectionSize())
    }

    private fun finishLasso() {
        drawingLasso = false
        clearLassoPreview()
        val index = lassoPage
        if (index < 0 || index >= document.pages.size || lassoPath.size < LASSO_MIN_POINTS) {
            lassoPath.clear()
            dry.invalidate()
            return
        }
        selectInsideLasso()
        lassoPath.clear()
        dry.invalidate()
        onLassoSelected?.invoke(lassoSelectionSize())
    }

    /** Selects what [lassoPath] goes round on [lassoPage], leaving the path. True when that is anything. */
    private fun selectInsideLasso(): Boolean {
        val page = document.pages[lassoPage]
        lassoBounds.setEmpty()
        collectInsideLasso(page, lassoStrokes, lassoImages)
        for (container in lassoImages.filter { it.box != PageImage.BOX_NONE }) addContainedBy(page, container)
        recomputeLassoBounds()
        return hasLassoSelection()
    }

    /** Puts what [lassoPath] goes round on [page] into [strokes] and [images], whole groups included. */
    private fun collectInsideLasso(page: Page, strokes: MutableSet<Stroke>, images: MutableList<PageImage>) {
        strokes.clear()
        images.clear()
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (point in 0 until lassoPath.size step 2) {
            left = minOf(left, lassoPath[point])
            right = maxOf(right, lassoPath[point])
            top = minOf(top, lassoPath[point + 1])
            bottom = maxOf(bottom, lassoPath[point + 1])
        }
        val sample = StrokeInput()
        val groups = HashSet<Int>()
        for (stroke in dry.strokesIn(page, left, top, right, bottom)) {
            if (!selectable(stroke)) continue
            val box = stroke.shape.computeBoundingBox() ?: continue
            // The centre decides by default. Requiring every corner inside makes
            // a lasso that is hard to satisfy; the centre is what people aim at.
            if (!lassoPath.contains((box.xMin + box.xMax) / 2f,
                    (box.yMin + box.yMax) / 2f)
            ) continue
            if (lassoWholeOnly && !wholeInsideLasso(stroke, sample)) continue
            strokes += stroke
            StrokeTags.group(stroke).let { if (it != 0) groups += it }
        }
        for (image in page.images) {
            if (!selectable(image)) continue
            val inside = if (lassoWholeOnly) {
                lassoPath.contains(image.x, image.y) && lassoPath.contains(image.x + image.width, image.y) &&
                    lassoPath.contains(image.x, image.y + image.height) &&
                    lassoPath.contains(image.x + image.width, image.y + image.height)
            } else lassoPath.contains(image.x + image.width / 2f, image.y + image.height / 2f)
            if (!inside) continue
            images += image
            if (image.group != 0) groups += image.group
        }
        // A group comes whole, wherever the loop happened to cut it.
        if (groups.isNotEmpty()) {
            for (stroke in page.strokes) {
                if (StrokeTags.group(stroke) in groups && onActiveLayer(stroke) && stroke !in strokes) strokes += stroke
            }
            for (image in page.images) {
                if (image.group in groups && images.none { it === image }) images += image
            }
        }
    }

    private fun selectable(stroke: Stroke): Boolean {
        if (!selectLocked && StrokeTags.locked(stroke)) return false
        if (!onActiveLayer(stroke)) return false
        return if (isHighlighter(stroke)) lassoHighlighter else lassoInk
    }

    private fun selectable(image: PageImage): Boolean {
        if (!selectLocked && image.locked) return false
        return if (image.textContent != null) lassoText else lassoPictures
    }

    private fun hasLassoSelection(): Boolean = lassoStrokes.isNotEmpty() || lassoImages.isNotEmpty()

    private fun lassoSelectionSize(): Int = lassoStrokes.size + lassoImages.size

    private fun recomputeLassoBounds() {
        lassoBounds.setEmpty()
        for (stroke in lassoStrokes) {
            val box = stroke.shape.computeBoundingBox() ?: continue
            if (lassoBounds.isEmpty) lassoBounds.set(box.xMin, box.yMin, box.xMax, box.yMax)
            else lassoBounds.union(box.xMin, box.yMin, box.xMax, box.yMax)
        }
        for (image in lassoImages) {
            if (lassoBounds.isEmpty) lassoBounds.set(image.x, image.y, image.x + image.width, image.y + image.height)
            else lassoBounds.union(image.x, image.y, image.x + image.width, image.y + image.height)
        }
    }

    /**
     * Swaps each selected picture for [change]'s copy of it, as part of edit
     * [group], and keeps the selection pointing at the copies.
     */
    private fun replaceLassoImages(page: Page, group: Long, change: (PageImage) -> PageImage) {
        for (i in lassoImages.indices) {
            val before = lassoImages[i]
            val at = page.images.indexOfFirst { it === before }
            if (at < 0) continue
            val after = change(before)
            page.images[at] = after
            undoStack += Edit.ImageReplaced(page, before, after, at, group)
            lassoImages[i] = after
        }
    }

    /** Locks or unlocks everything selected, then lets go of it: locked objects are not selectable. */
    fun lockLassoSelection(locked: Boolean) {
        val page = document.pages.getOrNull(lassoPage) ?: return
        if (!hasLassoSelection()) return
        for (stroke in lassoStrokes) StrokeTags.set(stroke, StrokeTags.tag(locked, StrokeTags.group(stroke)))
        for (image in lassoImages) image.locked = locked
        // Same strokes, new tags: the file has to be rewritten, not appended to.
        page.savedOnDisk = 0
        clearLassoSelection()
        afterEdit(page)
    }

    fun lassoSelectionLocked(): Boolean =
        lassoStrokes.any { StrokeTags.locked(it) } || lassoImages.any { it.locked }

    /** Puts everything selected in one group, or takes the selection's groups apart. */
    fun groupLassoSelection(grouped: Boolean) {
        val page = document.pages.getOrNull(lassoPage) ?: return
        if (!hasLassoSelection()) return
        val group = if (grouped) StrokeTags.newGroup() else 0
        for (stroke in lassoStrokes) StrokeTags.set(stroke, StrokeTags.tag(StrokeTags.locked(stroke), group))
        for (image in lassoImages) image.group = group
        page.savedOnDisk = 0
        afterEdit(page)
        onLassoSelected?.invoke(lassoSelectionSize())
    }

    /**
     * Where the selection is - page, box - and a text size that matches the
     * height of one line of it, for typed text taking its place.
     */
    fun lassoPlacement(): Triple<Int, RectF, Float>? {
        if (!hasLassoSelection() || lassoBounds.isEmpty) return null
        return Triple(lassoPage, RectF(lassoBounds), (lassoBounds.height() * 0.6f).coerceIn(14f, 72f))
    }

    /** The link on the selection's group, if it has one. */
    fun lassoLink(): String? {
        val page = document.pages.getOrNull(lassoPage) ?: return null
        return sharedLassoGroup()?.let { page.links[it] }
    }

    private fun sharedLassoGroup(): Int? {
        val groups = lassoStrokes.map { StrokeTags.group(it) } + lassoImages.map { it.group }
        val first = groups.firstOrNull() ?: return null
        return first.takeIf { it != 0 && groups.all { g -> g == it } }
    }

    /**
     * Links the selection to [target], or removes its link when null. The
     * selection is grouped first, so the link stays with the objects wherever
     * they are moved.
     */
    fun setLassoLink(target: String?) {
        val page = document.pages.getOrNull(lassoPage) ?: return
        if (!hasLassoSelection()) return
        val group = sharedLassoGroup() ?: run {
            groupLassoSelection(true)
            sharedLassoGroup()
        } ?: return
        if (target.isNullOrBlank()) page.links.remove(group) else page.links[group] = target.trim()
        afterEdit(page)
    }

    /** Pages are found by id, so a link still finds its page after reordering. */
    fun pageIdAt(index: Int): String? = document.pages.getOrNull(index)?.id

    /** Goes to page [index], remembering where from so [goBack] can return. */
    fun jumpToPage(index: Int) {
        if (index !in document.pages.indices || index == currentPage) return
        linkBackStack += currentPage
        scrollToPage(index)
        onLinkBackChanged?.invoke(true)
    }

    fun goBack(): Boolean {
        val back = linkBackStack.removeLastOrNull() ?: return false
        scrollToPage(back.coerceIn(document.pages.indices))
        onLinkBackChanged?.invoke(linkBackStack.isNotEmpty())
        return true
    }

    /** Box round every object in [group] on [page], or empty. */
    private fun groupBounds(page: Page, group: Int, out: RectF) {
        out.setEmpty()
        for (stroke in page.strokes) {
            if (StrokeTags.group(stroke) != group) continue
            val b = stroke.shape.computeBoundingBox() ?: continue
            if (out.isEmpty) out.set(b.xMin, b.yMin, b.xMax, b.yMax) else out.union(b.xMin, b.yMin, b.xMax, b.yMax)
        }
        for (image in page.images) {
            if (image.group != group) continue
            if (out.isEmpty) out.set(image.x, image.y, image.x + image.width, image.y + image.height)
            else out.union(image.x, image.y, image.x + image.width, image.y + image.height)
        }
    }

    private val linkBox = RectF()

    /** A tap on a linked object follows it. True when there was one under the tap. */
    private fun followObjectLink(screenX: Float, screenY: Float): Boolean {
        scratch[0] = screenX
        scratch[1] = screenY
        screenToDocument.mapPoints(scratch)
        val index = document.pageAt(scratch[0], scratch[1])
        val page = document.pages.getOrNull(index) ?: return false
        if (page.links.isEmpty() || !page.loaded) return false
        pageLocalInto(screenX, screenY, index, pageProbe)
        val reach = touchSlop / currentScale().coerceAtLeast(0.001f)
        for ((group, target) in page.links) {
            groupBounds(page, group, linkBox)
            linkBox.inset(-reach, -reach)
            if (!linkBox.contains(pageProbe[0], pageProbe[1])) continue
            when {
                target.startsWith("page:") -> {
                    val at = document.pages.indexOfFirst { it.id == target.removePrefix("page:") }
                    if (at >= 0) jumpToPage(at)
                }
                target.startsWith("note:") -> {
                    val rest = target.removePrefix("note:")
                    onOpenNoteLink?.invoke(rest.substringBefore('#'), rest.substringAfter('#', "0").toIntOrNull() ?: 0)
                }
                else -> onLinkUrl?.invoke(target)
            }
            performClick()
            return true
        }
        return false
    }

    fun lassoSelectionGrouped(): Boolean =
        lassoStrokes.any { StrokeTags.group(it) != 0 } || lassoImages.any { it.group != 0 }

    /** Whether the pen's layer allows editing [stroke]: always, on every layer at once. */
    private fun onActiveLayer(stroke: Stroke): Boolean = document.layers.editable(StrokeLayers.of(stroke))

    /** The note's layers, for the layer panel. Change them through the functions below, which save. */
    val layers: NoteLayers get() = document.layers

    /**
     * Bumped on every change to the layers made through this view, undo
     * included. Compose state, so the layer panel and the toolbar's layer chip
     * follow without polling; the layers themselves are plain objects.
     */
    val layersRevision = androidx.compose.runtime.mutableIntStateOf(0)

    /**
     * The lock and opacity tables the dry ink was last drawn with, when some
     * layer is translucent. Volatile: [renderRegion] reads it on a worker.
     */
    @Volatile private var drawnLook: LayerLook? = null

    /** Saved, shared, announced: the end of every layer change. */
    private fun layersSaved() {
        document.markEdited()
        document.notifyOtherCanvases(sharedInkChanged)
        onStrokesChanged?.invoke()
        layersRevision.intValue++
    }

    /**
     * Applies an edit of the layer list: what is shown, the order, and which
     * layer edits are held to. Selections that the new layer does not allow are
     * dropped. Only pages already in memory are settled; the rest are when read.
     */
    fun layersChanged() {
        clearLassoSelection()
        for (page in document.pages) if (page.loaded) {
            if (page.settleLayers(document.layers)) page.renderState = RenderState.Dirty
        }
        dry.invalidate()
        invalidate()
        layersSaved()
    }

    /**
     * Where new ink goes. Editing one layer, that layer becomes the one edits
     * are held to as well; editing all, only the target moves. A hidden layer
     * picked to write on is shown: ink vanishing as it is drawn helps no one.
     * A locked one stays locked - picking is not unlocking.
     */
    fun setWriteLayer(id: Int) {
        val layers = document.layers
        val layer = layers.layer(id) ?: return
        layers.writeTo = id
        if (layers.active != NoteLayers.ALL) layers.active = id
        layer.visible = true
        layersChanged()
    }

    /** Erase and select on every unlocked layer, or only on the target. */
    fun setEditAllLayers(all: Boolean) {
        val layers = document.layers
        if ((layers.active == NoteLayers.ALL) == all) return
        layers.active = if (all) NoteLayers.ALL else layers.target
        layersChanged()
    }

    fun setLayerVisible(id: Int, visible: Boolean) {
        val layer = document.layers.layer(id) ?: return
        if (layer.visible == visible) return
        layer.visible = visible
        layersChanged()
    }

    /**
     * Locks or unlocks a layer's ink against writing, erasing and selecting.
     * Neither the stroke lists nor their order change, so no page is settled
     * and none is read in; a selection that holds the layer's ink is let go.
     */
    fun setLayerLocked(id: Int, locked: Boolean) {
        if (!document.layers.setLocked(id, locked)) return
        if (locked && lassoStrokes.any { StrokeLayers.of(it) == id }) clearLassoSelection()
        lockedNoticeLayer = -1
        dry.invalidate()
        layersSaved()
    }

    /**
     * Shows a layer at [opacity] while a slider moves: the ink is composited
     * again, nothing is rebuilt, and nothing is saved until [commitLayerOpacity].
     */
    fun previewLayerOpacity(id: Int, opacity: Float) {
        if (!document.layers.setOpacity(id, opacity)) return
        dry.invalidate()
    }

    /** Saves what [previewLayerOpacity] showed. */
    fun commitLayerOpacity() {
        layersSaved()
    }

    fun renameLayer(id: Int, name: String) {
        val layer = document.layers.layer(id) ?: return
        val trimmed = name.trim().take(NoteLayers.MAX_NAME)
        if (trimmed.isEmpty() || trimmed == layer.name) return
        layer.name = trimmed
        layersSaved()
    }

    /** A new empty layer on top, which new ink then goes to. */
    fun addLayer(): NoteLayer? {
        val layers = document.layers
        val layer = layers.addOrNull() ?: return null
        layers.writeTo = layer.id
        if (layers.active != NoteLayers.ALL) layers.active = layer.id
        layersChanged()
        return layer
    }

    /**
     * Moves layer [id] to [to] in the drawing order, bottom first. Strokes keep
     * their layer id; only the rank changes, and loaded pages are re-sorted once.
     */
    fun moveLayer(id: Int, to: Int) {
        if (!document.layers.move(id, to)) return
        layersChanged()
    }

    /**
     * How many strokes layer [id] holds across the note, for the question
     * before deleting it. Pages not read in yet are read now: deleting has to
     * reach them anyway, and an estimate is no answer to "how much will I lose".
     */
    fun layerInkCount(id: Int): Int {
        var count = 0
        for (page in document.pages) {
            if (!ensureLoaded(page)) continue
            for (stroke in page.strokes) if (StrokeLayers.of(stroke) == id) count++
            for (stroke in page.hiddenStrokes) if (StrokeLayers.of(stroke) == id) count++
        }
        return count
    }

    /**
     * Removes a layer and its ink from every page, as one undo step: the
     * strokes and the layer are kept in history, so undo puts both back with
     * the layer at its old place. The rest of history is left as it is.
     */
    fun deleteLayer(id: Int): Boolean {
        val layers = document.layers
        if (layers.list.size <= 1 || layers.layer(id) == null) return false
        clearLassoSelection()
        val group = nextEditGroup++
        val changed = ArrayList<Page>()
        for (page in document.pages) {
            if (!ensureLoaded(page)) continue
            val doomed = page.allStrokes.filter { StrokeLayers.of(it) == id }
            if (doomed.isEmpty()) continue
            takeOut(page, doomed)
            undoStack += Edit.LayerInk(page, doomed, gone = true, group = group)
            changed += page
        }
        val removed = layers.remove(id) ?: return false
        undoStack += Edit.LayerEntry(historyPage(), removed, gone = true, group = group)
        redoStack.clear()
        afterEdit(*changed.toTypedArray())
        layersChanged()
        return true
    }

    /** Whether the newest step in history is a layer deletion, for the panel's undo offer. */
    fun canUndoLayerDelete(): Boolean = (undoStack.lastOrNull() as? Edit.LayerEntry)?.gone == true

    fun undoLayerDelete(): Boolean {
        if (!canUndoLayerDelete()) return false
        undo()
        return true
    }

    /**
     * Copies layer [id] and its ink on every page under a new id, placed
     * directly above it, as one undo step. The copies share the originals'
     * geometry, so nothing is rebuilt. Lock and group tags are carried, with
     * groups renumbered so a copy does not join its original's group.
     */
    fun duplicateLayer(id: Int): NoteLayer? {
        val layers = document.layers
        val at = layers.list.indexOfFirst { it.id == id }
        if (at < 0 || !layers.canAdd()) return null
        clearLassoSelection()
        // Read everything in before the list changes, so loading settles under the old layers.
        for (page in document.pages) ensureLoaded(page)
        val copy = layers.duplicate(id) ?: return null
        val group = nextEditGroup++
        val entry = NoteLayers.Removed(copy, at + 1, layers.active, layers.writeTo)
        undoStack += Edit.LayerEntry(historyPage(), entry, gone = false, group = group)
        val changed = ArrayList<Page>()
        val groups = HashMap<Int, Int>()
        for (page in document.pages) {
            if (!page.loaded) continue
            val source = page.allStrokes.filter { StrokeLayers.of(it) == id }
            if (source.isEmpty()) continue
            val copies = source.map { stroke ->
                val twin = runCatching { Stroke(stroke.brush, stroke.inputs, stroke.shape) }
                    .getOrElse { Stroke(stroke.brush, stroke.inputs) }
                val tagGroup = StrokeTags.group(stroke)
                val newGroup = if (tagGroup == 0) 0 else groups.getOrPut(tagGroup) { StrokeTags.newGroup() }
                StrokeTags.set(twin, StrokeTags.tag(StrokeTags.locked(stroke), newGroup))
                StrokeLayers.set(twin, copy.id)
                twin
            }
            page.strokes += copies
            undoStack += Edit.LayerInk(page, copies, gone = false, group = group)
            changed += page
        }
        redoStack.clear()
        afterEdit(*changed.toTypedArray())
        layersChanged()
        return copy
    }

    /** The page a note-wide history step is filed under; any page will do, the one in view reads best. */
    private fun historyPage(): Page = document.pages[currentPage.coerceIn(document.pages.indices)]

    /** Takes [strokes] off [page], shown or hidden, in one pass over each list. */
    private fun takeOut(page: Page, strokes: List<Stroke>) {
        val gone: MutableSet<Stroke> = Collections.newSetFromMap(IdentityHashMap())
        gone.addAll(strokes)
        page.strokes.removeAll(gone::contains)
        if (page.hiddenStrokes.isNotEmpty()) page.hiddenStrokes.removeAll(gone::contains)
        dry.dropStrokeIndex(page)
        page.savedOnDisk = 0
    }

    /** Applies a layer step of history forward ([forward]) or back. */
    private fun applyLayerEntry(edit: Edit.LayerEntry, forward: Boolean) {
        val layers = document.layers
        val step = edit.removed
        if (forward == edit.gone) {
            // A deletion redone, or a duplicate undone: the layer goes.
            layers.remove(step.layer.id)
            if (!edit.gone) {
                // Undoing a duplicate puts the pen and the edit mode back where they were.
                layers.active = step.active.takeIf { a -> a == NoteLayers.ALL || layers.layer(a) != null }
                    ?: NoteLayers.ALL
                layers.writeTo = step.writeTo
            }
        } else if (edit.gone) {
            layers.restore(step)
        } else {
            layers.insert(step.layer, step.at)
        }
    }

    /** Applies a layer's ink step forward ([forward]) or back. */
    private fun applyLayerInk(edit: Edit.LayerInk, forward: Boolean) {
        if (forward == edit.gone) takeOut(edit.page, edit.strokes)
        else {
            // Back at the end; settling puts them in layer order, or with the hidden ones.
            edit.page.strokes += edit.strokes
            dry.dropStrokeIndex(edit.page)
            edit.page.savedOnDisk = 0
        }
    }

    /** Layer [lockedNoticeLayer] was last said to be unwritable at [lockedNoticeAt]; see [refuseLockedTarget]. */
    private var lockedNoticeLayer = -1
    private var lockedNoticeAt = 0L

    /**
     * True when new ink would land on a layer that cannot take it - locked, or
     * hidden - and then nothing is drawn. Says why, once: again only for
     * another layer, after the lock is touched, or after a while, so a pen
     * tapping away at a locked layer does not stack up a message per stroke.
     */
    private fun refuseLockedTarget(): Boolean {
        val layers = document.layers
        val target = layers.target
        val locked = layers.isLocked(target)
        if (!locked && layers.isVisible(target)) return false
        val now = SystemClock.uptimeMillis()
        if (target != lockedNoticeLayer || now - lockedNoticeAt > LOCKED_NOTICE_MS) {
            lockedNoticeLayer = target
            lockedNoticeAt = now
            val name = layers.layer(target)?.name ?: "This layer"
            val message = if (locked) "Layer '$name' is locked. Unlock it in Layers to write."
                else "Layer '$name' is hidden. Show it in Layers to write."
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /**
     * The brush wet ink is shown with for [brush]: the target layer's opacity
     * and the page tone, as the committed stroke will look; see [wetInkArgb].
     * Tape is not on a layer and takes the tone alone. The same brush object
     * when neither applies, so the default path allocates nothing.
     */
    private fun wetBrushFor(brush: Brush): Brush {
        val opacity = if (strokeIsMask) 1f else document.layers.opacityOf(document.layers.target)
        val shown = wetInkArgb(brush.colorIntArgb, opacity, wetToneMatrix)
        if (shown == brush.colorIntArgb) return brush
        wetBrushCache?.takeIf { wetBrushSource === brush && it.colorIntArgb == shown }?.let { return it }
        return brush.copyWithColorIntArgb(colorIntArgb = shown).also {
            wetBrushSource = brush
            wetBrushCache = it
        }
    }
    private var wetBrushSource: Brush? = null
    private var wetBrushCache: Brush? = null

    /**
     * Wet strokes started in a display colour, and the colour each really is.
     * The finished stroke gets its own colour back before it is kept, so the
     * note and its exports never see the display one.
     */
    private val wetTrueColors = HashMap<InProgressStrokeId, Int>()

    /** The display colour of the stroke being written, for the prediction head and the afterimage. */
    private var wetColorArgb = 0

    /** [colorArgb] at the target layer's opacity, for previews drawn inside the page (shapes, polygons). */
    private fun previewArgb(colorArgb: Int): Int {
        val opacity = document.layers.opacityOf(document.layers.target)
        return if (opacity >= 1f) colorArgb else wetInkArgb(colorArgb, opacity, null)
    }

    /** Everything that can be erased on page [index] goes, in one undo step. Locked objects stay unless erasable. */
    fun clearPage(index: Int) {
        val page = document.pages.getOrNull(index) ?: return
        if (!ensureLoaded(page)) return
        clearLassoSelection()
        clearImageSelection()
        val group = nextEditGroup++
        val gone = page.strokes.filter { (eraseLocked || !StrokeTags.locked(it)) && onActiveLayer(it) }
        if (gone.isNotEmpty()) {
            val goneSet: MutableSet<Stroke> = Collections.newSetFromMap(IdentityHashMap())
            goneSet.addAll(gone)
            page.strokes.removeAll(goneSet::contains)
            dry.removeStrokesFromIndex(page, gone)
            undoStack += Edit.Erased(page, gone, group)
        }
        for (at in page.images.indices.reversed()) {
            val image = page.images[at]
            if (image.locked && !eraseLocked) continue
            page.images.removeAt(at)
            undoStack += Edit.ImageRemoved(page, image, at, group)
        }
        for (at in page.masks.indices.reversed()) {
            undoStack += Edit.MaskRemoved(page, page.masks[at], at, group)
            page.masks.removeAt(at)
        }
        redoStack.clear()
        afterEdit(page)
    }

    /** Every input of [stroke] inside the loop, judged on at most [WHOLE_CHECK_SAMPLES] of them. */
    private fun wholeInsideLasso(stroke: Stroke, sample: StrokeInput): Boolean {
        val count = stroke.inputs.size
        val step = (count / WHOLE_CHECK_SAMPLES).coerceAtLeast(1)
        var i = 0
        while (i < count) {
            stroke.inputs.populate(i, sample)
            if (!lassoPath.contains(sample.x, sample.y)) return false
            i = if (i + step >= count && i != count - 1) count - 1 else i + step
        }
        return true
    }

    /**
     * Nudges the drag so the selection's left, middle or right lands exactly on
     * a page edge, the page's middle, or a picture's edge when within reach.
     */
    private fun snapLassoMove() {
        val page = document.pages.getOrNull(lassoPage) ?: return
        val reach = SNAP_ALIGN_PX / currentScale()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        xs += 0f; xs += page.width / 2f; xs += page.width
        ys += 0f; ys += page.height / 2f; ys += page.height
        for (image in page.images) {
            if (lassoImages.any { it === image }) continue
            xs += image.x; xs += image.x + image.width
            ys += image.y; ys += image.y + image.height
        }
        val b = lassoBounds
        val snapX = alignOffset(floatArrayOf(b.left + lassoDx, b.centerX() + lassoDx, b.right + lassoDx), xs, reach)
        val snapY = alignOffset(floatArrayOf(b.top + lassoDy, b.centerY() + lassoDy, b.bottom + lassoDy), ys, reach)
        guideX = snapX?.second ?: Float.NaN
        guideY = snapY?.second ?: Float.NaN
        snapX?.let { lassoDx += it.first }
        snapY?.let { lassoDy += it.first }
    }

    private fun dragVertex(event: MotionEvent, pointerIndex: Int) {
        val page = document.pages.getOrNull(lassoPage) ?: return
        val inputs = vertexInputs ?: return
        val current = vertexCurrent ?: return
        pageLocalInto(event.getX(pointerIndex), event.getY(pointerIndex), lassoPage, shapeEnd)
        val tx = shapeEnd[0]
        val ty = shapeEnd[1]
        var i = 0
        val moved = mapInputs(current) { x, y, out ->
            if (i in inputs) { out[0] = tx; out[1] = ty } else { out[0] = x; out[1] = y }
            i++
        }
        swapStrokes(page, listOf(current), listOf(moved))
        lassoStrokes.clear()
        lassoStrokes += moved
        vertexCurrent = moved
        recomputeLassoBounds()
        page.revision++
        page.renderState = RenderState.Dirty
        dry.postInvalidateOnAnimation()
    }

    private fun finishVertexDrag() {
        val page = document.pages.getOrNull(lassoPage)
        val before = vertexOriginal
        val after = vertexCurrent
        vertexInputs = null
        vertexOriginal = null
        vertexCurrent = null
        if (page == null || before == null || after == null || before === after) return
        undoStack += Edit.Moved(page, listOf(before), listOf(after))
        redoStack.clear()
        afterEdit(page)
    }

    /** A pen tap with the polygon tool: a new corner, or the end of the shape. */
    private fun tapPolygon(event: MotionEvent, index: Int) {
        val point = FloatArray(2)
        pageLocalInto(event.x, event.y, index, point)
        if (polyPoints.isNotEmpty() && index != polyPage) finishPolygon(close = false)
        if (polyPoints.isEmpty()) polyPage = index
        val reach = VERTEX_HANDLE_PX * 2f / currentScale()
        val first = polyPoints.firstOrNull()
        val last = polyPoints.lastOrNull()
        when {
            first != null && polyPoints.size >= 3 && hypot(point[0] - first[0], point[1] - first[1]) <= reach ->
                finishPolygon(close = true)
            last != null && polyPoints.size >= 2 && hypot(point[0] - last[0], point[1] - last[1]) <= reach ->
                finishPolygon(close = false)
            else -> polyPoints += point
        }
        dry.invalidate()
    }

    /** Commits the polygon tapped out so far, closed back to its first corner or left open. */
    fun finishPolygon(close: Boolean) {
        val page = document.pages.getOrNull(polyPage)
        val points = ArrayList(polyPoints)
        polyPoints.clear()
        polyPage = -1
        if (page == null || points.size < 2) { dry.invalidate(); return }
        if (close) points += points.first()
        val group = nextEditGroup++
        for (stroke in strokesThrough(points, currentBrush())) {
            page.strokes += stroke
            undoStack += Edit.Drawn(page, stroke, group)
        }
        redoStack.clear()
        afterEdit(page)
    }

    private fun finishLassoMove() {
        guideX = Float.NaN
        guideY = Float.NaN
        movingLasso = false
        liftedLassoStrokes = null
        val page = document.pages.getOrNull(lassoPage) ?: return
        val dx = lassoDx
        val dy = lassoDy
        lassoDx = 0f
        lassoDy = 0f
        if (dx == 0f && dy == 0f) {
            dry.invalidate()
            return
        }
        val group = nextEditGroup++
        val before = lassoStrokes.toList()
        val after = before.map { translate(it, dx, dy) }
        swapStrokes(page, before, after)
        undoStack += Edit.Moved(page, before, after, group)
        replaceLassoImages(page, group) { it.copy(x = it.x + dx, y = it.y + dy) }
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
        // Replaced in place, so the file's prefix no longer matches: appending
        // the next new stroke would leave these where they were on disk.
        page.savedOnDisk = 0
        for (i in from.indices) {
            StrokeTags.carry(from[i], to[i])
            val at = page.strokes.indexOfFirst { it === from[i] }
            if (at >= 0) page.strokes[at] = to[i] else page.strokes += to[i]
        }
    }

    /**
     * Scales the selection by [factor] and turns it by [degrees] about the
     * middle of its box. Pen widths scale with it, so a shrunk word still looks
     * like the same hand wrote it smaller.
     */
    fun transformLassoSelection(factor: Float, degrees: Float) {
        val page = document.pages.getOrNull(lassoPage) ?: return
        if (!hasLassoSelection() || lassoBounds.isEmpty || factor <= 0f) return
        val cx = lassoBounds.centerX()
        val cy = lassoBounds.centerY()
        val radians = Math.toRadians(degrees.toDouble())
        val cosine = (cos(radians) * factor).toFloat()
        val sine = (sin(radians) * factor).toFloat()
        val before = lassoStrokes.toList()
        val after = before.map { stroke ->
            val moved = mapInputs(stroke) { x, y, out ->
                val dx = x - cx
                val dy = y - cy
                out[0] = cx + dx * cosine - dy * sine
                out[1] = cy + dx * sine + dy * cosine
            }
            if (factor == 1f) moved
            else Stroke(stroke.brush.copy(size = (stroke.brush.size * factor).coerceAtLeast(0.1f)), moved.inputs)
        }
        val group = nextEditGroup++
        // Pictures turn about their own middles, which go round with the rest.
        replaceLassoImages(page, group) { image ->
            val dx = image.x + image.width / 2f - cx
            val dy = image.y + image.height / 2f - cy
            val mx = cx + dx * cosine - dy * sine
            val my = cy + dx * sine + dy * cosine
            val w = image.width * factor
            val h = image.height * factor
            image.copy(x = mx - w / 2f, y = my - h / 2f, width = w, height = h)
                .also { it.rotation = (image.rotation + degrees) % 360f }
        }
        replaceLassoStrokes(page, before, after, group)
    }

    /** Gives the selection [colorArgb], keeping each stroke's own transparency. */
    fun recolorLassoSelection(colorArgb: Int) {
        val page = document.pages.getOrNull(lassoPage) ?: return
        if (lassoStrokes.isEmpty()) return
        val before = lassoStrokes.toList()
        val after = before.map { stroke ->
            val alpha = Color.alpha(stroke.brush.colorIntArgb)
            val color = (colorArgb and 0x00FFFFFF) or (alpha shl 24)
            // Only the colour changes, so Stroke.copy keeps the mesh.
            stroke.copy(stroke.brush.copyWithColorIntArgb(colorIntArgb = color))
        }
        replaceLassoStrokes(page, before, after)
    }

    /** Copies the selection a little down and right, and selects the copy. */
    fun duplicateLassoSelection() {
        val page = document.pages.getOrNull(lassoPage) ?: return
        if (!hasLassoSelection()) return
        val offset = LASSO_DUPLICATE_OFFSET
        // Copies stay on their originals' layers, which the selection says are editable.
        val copies = lassoStrokes.map { stroke -> translate(stroke, offset, offset).also { StrokeLayers.carry(stroke, it) } }
        val group = nextEditGroup++
        for (copy in copies) {
            page.strokes += copy
            undoStack += Edit.Drawn(page, copy, group)
        }
        val imageCopies = lassoImages.map { it.copy(x = it.x + offset, y = it.y + offset).also { copy -> copy.locked = false } }
        for (copy in imageCopies) {
            page.images += copy
            undoStack += Edit.ImageAddedInGroup(page, copy, group)
        }
        redoStack.clear()
        lassoStrokes.clear()
        lassoStrokes += copies
        lassoImages.clear()
        lassoImages += imageCopies
        lassoBounds.offset(offset, offset)
        afterEdit(page)
        onLassoSelected?.invoke(lassoSelectionSize())
    }

    /** Puts the selection on [InkClipboard]. */
    fun copyLassoSelection() {
        if (!hasLassoSelection()) return
        // Pictures go with their pixels: the file they point at belongs to this
        // note, and the paste may land in another one.
        val pictures = lassoImages.map { it.copy() to imageLoader?.invoke(it.id) }
        InkClipboard.push(lassoStrokes.toList(), lassoBounds, pictures)
    }

    /** The selection as a clip and a small picture of it, for keeping in the library. */
    internal fun lassoClipWithPreview(): Pair<InkClipboard.Clip, Bitmap?>? {
        val page = document.pages.getOrNull(lassoPage) ?: return null
        if (!hasLassoSelection()) return null
        val pictures = lassoImages.map { it.copy() to imageLoader?.invoke(it.id) }
        val clip = InkClipboard.Clip(lassoStrokes.toList(), RectF(lassoBounds), pictures)
        val preview = renderRegion(RectF(lassoBounds).apply { inset(-8f, -8f) }, page.width, page.height, -1,
            lassoStrokes.toList(), pictures,
            // A library thumbnail, not a capture: about 512 px on its long edge.
            minOf(1f, LIBRARY_PREVIEW_PX / maxOf(lassoBounds.width(), lassoBounds.height(), 1f)))
        return clip to preview
    }

    fun cutLassoSelection() {
        copyLassoSelection()
        deleteLassoSelection()
    }

    /**
     * Puts [clip] on the page in the middle of the screen, centred there, and
     * selects it so it can be dragged into place straight away.
     */
    internal fun paste(clip: InkClipboard.Clip) {
        if (document.pages.isEmpty()) return
        val index = currentPage.coerceIn(document.pages.indices)
        val page = document.pages[index]
        // Ink added to a page not read in yet would be skipped by the next save.
        if (!ensureLoaded(page)) return
        // Pasted ink goes where new ink goes, which a locked layer refuses.
        if (clip.strokes.isNotEmpty() && refuseLockedTarget()) return
        pageLocalInto(width / 2f, height / 2f, index, shapeEnd)
        val dx = shapeEnd[0].coerceIn(0f, page.width) - clip.bounds.centerX()
        val dy = shapeEnd[1].coerceIn(0f, page.height) - clip.bounds.centerY()
        val target = document.layers.target
        // On the target layer, not the one it was copied from: that may be
        // locked or hidden by now, or belong to another note altogether.
        val copies = clip.strokes.map { translate(it, dx, dy).also { copy -> StrokeLayers.set(copy, target) } }
        val group = nextEditGroup++
        for (copy in copies) {
            page.strokes += copy
            undoStack += Edit.Drawn(page, copy, group)
        }
        val pictures = clip.images.mapNotNull { (image, bitmap) ->
            // Same note: the file is already here. Another note: bring the pixels in.
            val id = if (imageLoader?.invoke(image.id) != null) image.id
                else bitmap?.let { imageAdder?.invoke(it) } ?: return@mapNotNull null
            image.copy(id = id, x = image.x + dx, y = image.y + dy).also { it.locked = false }
        }
        for (picture in pictures) {
            page.images += picture
            undoStack += Edit.ImageAddedInGroup(page, picture, group)
        }
        redoStack.clear()
        clearLassoSelection()
        lassoPage = index
        lassoStrokes += copies
        lassoImages += pictures
        lassoBounds.set(clip.bounds)
        lassoBounds.offset(dx, dy)
        afterEdit(page)
        onLassoSelected?.invoke(lassoSelectionSize())
    }

    private fun replaceLassoStrokes(page: Page, before: List<Stroke>, after: List<Stroke>, group: Long = 0L) {
        swapStrokes(page, before, after)
        undoStack += Edit.Moved(page, before, after, group)
        redoStack.clear()
        lassoStrokes.clear()
        lassoStrokes += after
        recomputeLassoBounds()
        afterEdit(page)
    }

    /** Rebuilds [stroke] with every input position passed through [map]. */
    private inline fun mapInputs(stroke: Stroke, map: (Float, Float, FloatArray) -> Unit): Stroke {
        val inputs = MutableStrokeInputBatch()
        val scratch = StrokeInput()
        val point = FloatArray(2)
        for (i in 0 until stroke.inputs.size) {
            val input = stroke.inputs.populate(i, scratch)
            map(input.x, input.y, point)
            inputs.add(
                type = input.toolType,
                x = point[0],
                y = point[1],
                elapsedTimeMillis = input.elapsedTimeMillis,
                pressure = input.pressure,
                tiltRadians = input.tiltRadians,
                orientationRadians = input.orientationRadians,
            )
        }
        return Stroke(stroke.brush, inputs.toImmutable())
    }

    /** Throws away what the loop caught. */
    fun deleteLassoSelection() {
        val page = document.pages.getOrNull(lassoPage) ?: return
        if (!hasLassoSelection()) return
        val group = nextEditGroup++
        val gone = lassoStrokes.toList()
        val goneSet: MutableSet<Stroke> = Collections.newSetFromMap(IdentityHashMap())
        goneSet.addAll(gone)
        page.strokes.removeAll(goneSet::contains)
        undoStack += Edit.Erased(page, gone, group)
        for (image in lassoImages) {
            val at = page.images.indexOfFirst { it === image }
            if (at < 0) continue
            page.images.removeAt(at)
            undoStack += Edit.ImageRemoved(page, image, at, group)
        }
        redoStack.clear()
        clearLassoSelection()
        afterEdit(page)
    }

    fun clearLassoSelection() {
        val had = hasLassoSelection()
        movingLasso = false
        liftedLassoStrokes = null
        lassoStrokes.clear()
        lassoImages.clear()
        clearLassoPreview()
        lassoBounds.setEmpty()
        lassoDx = 0f
        lassoDy = 0f
        dry.invalidate()
        if (had) onLassoSelected?.invoke(0)
    }

    /** Lifts or lowers the strip under the finger. True when there was one. */
    private fun tapStrokeAt(screenX: Float, screenY: Float, index: Int): Boolean {
        val callback = onStrokeTapped ?: return false
        val page = document.pages[index]
        pageLocalInto(screenX, screenY, index, maskProbe)
        val half = MASK_TAP_PX / currentScale() / 2f
        val tip = ImmutableBox.fromCenterAndDimensions(
            ImmutableVec(maskProbe[0], maskProbe[1]), half * 2f, half * 2f,
        )
        val candidates = dry.strokesIn(
            page, maskProbe[0] - half, maskProbe[1] - half, maskProbe[0] + half, maskProbe[1] + half,
        )
        val stroke = with(Intersection) {
            candidates.lastOrNull { tip.intersects(it.shape, IDENTITY) }
        } ?: return false
        return callback(index, stroke)
    }

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
        replacing: PageImage? = null, at: Triple<Int, Float, Float>? = null, box: Int = PageImage.BOX_NONE) {
        val page = if (replacing != null) document.pages.firstOrNull { replacing in it.images } ?: return
            else document.pages.getOrNull(at?.first ?: currentPage) ?: return
        val boxWidth = bitmapWidth / 2f
        val boxHeight = bitmapHeight / 2f
        val image = replacing?.copy(id = imageId, width = boxWidth, height = boxHeight, textContent = content)
            ?: PageImage(imageId, at?.second ?: (page.width - boxWidth).coerceAtLeast(0f) / 2f,
                at?.third ?: (page.height - boxHeight).coerceAtLeast(0f) / 2f,
                boxWidth, boxHeight, content, box = box)
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

    fun selectedPicture(): PageImage? = selectedImage?.takeIf { it.textContent == null && it.box == PageImage.BOX_NONE }

    fun selectedImageBox(): Int = selectedImage?.box ?: PageImage.BOX_NONE

    /** A text box or sticky note was tapped twice; open it for editing. */
    var onEditTextBox: (() -> Unit)? = null
    private var lastImageTap = 0L
    private var pendingTextEdit = false

    /**
     * Puts picture [imageId] where the part [area] (fractions of the selected
     * picture's box) of the selected picture was: a crop keeps what it kept in place.
     */
    fun replaceSelectedImage(imageId: String, area: RectF = RectF(0f, 0f, 1f, 1f)) {
        val image = selectedImage ?: return
        val page = selectedImagePage ?: return
        val at = page.images.indexOf(image)
        if (at < 0) return
        val after = image.copy(
            id = imageId,
            x = image.x + area.left * image.width,
            y = image.y + area.top * image.height,
            width = area.width() * image.width,
            height = area.height() * image.height,
        )
        page.images[at] = after
        undoStack += Edit.ImageReplaced(page, image, after, at)
        redoStack.clear()
        select(after, page)
        afterEdit(page)
    }

    fun setSelectedImageOpacity(opacity: Float) {
        val image = selectedImage ?: return
        image.opacity = opacity.coerceIn(0.05f, 1f)
        selectedImagePage?.let { afterEdit(it) }
    }

    fun rotateSelectedImage(degrees: Float) {
        val image = selectedImage ?: return
        val page = selectedImagePage ?: return
        val at = page.images.indexOf(image)
        if (at < 0) return
        val after = image.copy().also { it.rotation = (image.rotation + degrees) % 360f }
        page.images[at] = after
        undoStack += Edit.ImageReplaced(page, image, after, at)
        redoStack.clear()
        select(after, page)
        afterEdit(page)
    }

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
            (selectLocked || !it.locked) &&
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
        // A second tap on a text box or sticky note already in hand opens it for editing.
        if (hit === selectedImage && hit.textContent != null &&
            event.eventTime - lastImageTap < DOUBLE_TAP_MS && onEditTextBox != null) {
            lastImageTap = 0L
            pendingTextEdit = true
            return
        }
        lastImageTap = event.eventTime
        select(hit, page)
        resizingImage = hypot(x - (hit.x + hit.width), y - (hit.y + hit.height)) <= grab
        movingImage = !resizingImage
        if (movingImage && hit.box != PageImage.BOX_NONE) {
            // A container moves as a selection of itself and everything on it.
            clearLassoSelection()
            lassoPage = index
            lassoImages += hit
            addContainedBy(page, hit)
            recomputeLassoBounds()
            movingImage = false
            movingLasso = true
            liftedLassoStrokes = Collections.newSetFromMap(IdentityHashMap<Stroke, Boolean>()).apply { addAll(lassoStrokes) }
            lassoGrab[0] = x
            lassoGrab[1] = y
            lassoDx = 0f
            lassoDy = 0f
        }
    }

    /** Ink and pictures whose middles lie on [container], added to the lasso selection. */
    private fun addContainedBy(page: Page, container: PageImage) {
        if (container.collapsed) return
        val right = container.x + container.width
        val bottom = container.y + container.height
        for (stroke in dry.strokesIn(page, container.x, container.y, right, bottom)) {
            val b = stroke.shape.computeBoundingBox() ?: continue
            val cx = (b.xMin + b.xMax) / 2f
            val cy = (b.yMin + b.yMax) / 2f
            if (cx in container.x..right && cy in container.y..bottom && onActiveLayer(stroke) && stroke !in lassoStrokes) lassoStrokes += stroke
        }
        for (image in page.images) {
            if (image === container || lassoImages.any { it === image }) continue
            val cx = image.x + image.width / 2f
            val cy = image.y + image.height / 2f
            if (cx in container.x..right && cy in container.y..bottom) lassoImages += image
        }
    }

    /** Folds the selected sticky note to a tab, or unfolds it. */
    fun toggleSelectedFolded() {
        val image = selectedImage ?: return
        val page = selectedImagePage ?: return
        foldSticky(page, image)
    }

    private fun foldSticky(page: Page, image: PageImage) {
        val at = page.images.indexOf(image)
        if (at < 0 || image.box != PageImage.BOX_STICKY) return
        val after = if (image.collapsed) image.copy(width = image.expandedWidth, height = image.expandedHeight)
            .also { it.collapsed = false }
        else image.copy(width = PageImage.FOLDED_SIZE, height = PageImage.FOLDED_SIZE).also {
            it.collapsed = true
            it.expandedWidth = image.width
            it.expandedHeight = image.height
        }
        page.images[at] = after
        undoStack += Edit.ImageReplaced(page, image, after, at)
        redoStack.clear()
        select(after, page)
        afterEdit(page)
    }

    /** A finger tap on a folded sticky note opens it. True when there was one there. */
    private fun unfoldAt(screenX: Float, screenY: Float): Boolean {
        scratch[0] = screenX
        scratch[1] = screenY
        screenToDocument.mapPoints(scratch)
        val index = document.pageAt(scratch[0], scratch[1])
        if (index < 0) return false
        val page = document.pages[index]
        pageLocalInto(screenX, screenY, index, scratch)
        val hit = page.images.lastOrNull {
            it.collapsed && scratch[0] in it.x..(it.x + it.width) && scratch[1] in it.y..(it.y + it.height)
        } ?: return false
        foldSticky(page, hit)
        clearImageSelection()
        return true
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
            image.height = if (!keepAspect) (imagePoint[1] - image.y).coerceAtLeast(IMAGE_MIN_SIZE)
                else if (aspect > 0f) width / aspect else width
        } else {
            image.x += imagePoint[0] - imageGrab[0]
            image.y += imagePoint[1] - imageGrab[1]
            imageGrab[0] = imagePoint[0]
            imageGrab[1] = imagePoint[1]
        }
        dry.postInvalidateOnAnimation()
    }

    private fun updateReadButtonCapture(event: MotionEvent, pointerIndex: Int) {
        for (historyIndex in 0 until event.historySize) {
            readButtonGesture.move(event.getHistoricalX(pointerIndex, historyIndex),
                event.getHistoricalY(pointerIndex, historyIndex))
        }
        readButtonGesture.move(event.getX(pointerIndex), event.getY(pointerIndex))
        if (!readButtonGesture.dragging) return
        if (!capturing) {
            // Clear the text overlay without resetting the active button drag.
            selectionCancelledAt = ++selectionSerial
            selectionAnchor = null
            if (selection != null) setSelection(null)
        }
        capturing = true
        pageLocalInto(event.getX(pointerIndex), event.getY(pointerIndex), capturePage, shapeEnd)
        captureRect.set(min(shapeStart[0], shapeEnd[0]), min(shapeStart[1], shapeEnd[1]),
            max(shapeStart[0], shapeEnd[0]), max(shapeStart[1], shapeEnd[1]))
        dry.postInvalidateOnAnimation()
    }

    /** The page the last capture was taken from, for linking a pasted copy back to it. */
    var lastCapturePage = -1
        private set

    /**
     * Puts a picture on the current page, grouped with a link to [link] - the
     * same "page:" and "note:" targets the link dialog makes - so tapping it
     * goes where it came from.
     */
    fun insertLinkedImage(imageId: String, aspect: Float, link: String) {
        insertImage(imageId, aspect)
        val image = selectedImage ?: return
        val page = document.pages.firstOrNull { p -> p.images.any { it === image } } ?: return
        val group = StrokeTags.newGroup()
        image.group = group
        page.links[group] = link
        afterEdit(page)
    }

    // ---- in-note search results ------------------------------------------

    /** The page an in-note search result is being shown on, or -1. */
    private var searchHitPage = -1
    /** Its boxes in page-local units; empty flashes the whole page instead. */
    private var searchHitRects: List<RectF> = emptyList()
    private val clearSearchHit = Runnable {
        searchHitPage = -1
        searchHitRects = emptyList()
        dry.invalidate()
    }

    /**
     * Brings a search result into view and marks it for a moment: [rects]
     * (page-local) when its place on the page is known, the whole page
     * flashed when it is not. Two redraws, on and off - nothing animates.
     */
    fun showSearchHit(pageIndex: Int, rects: List<RectF>) {
        if (pageIndex !in document.pages.indices) return
        scrollToPage(pageIndex)
        if (rects.isNotEmpty()) {
            val union = RectF(rects[0])
            for (rect in rects) union.union(rect)
            val left = document.leftOf(pageIndex)
            val top = document.topOf(pageIndex)
            val corners = floatArrayOf(left + union.left, top + union.top, left + union.right, top + union.bottom)
            documentToScreen.mapPoints(corners)
            // Paging puts the top of the page in view; a match further down is
            // brought up to a third of the way down the screen.
            val dy = if (corners[1] < 0f || corners[3] > height) height / 3f - corners[1] else 0f
            val dx = if (corners[0] < 0f || corners[2] > width) width / 2f - (corners[0] + corners[2]) / 2f else 0f
            if (dx != 0f || dy != 0f) {
                documentToScreen.postTranslate(dx, dy)
                onTransformChanged()
            }
        }
        searchHitPage = pageIndex
        searchHitRects = rects.map { RectF(it) }
        removeCallbacks(clearSearchHit)
        postDelayed(clearSearchHit, if (rects.isEmpty()) SEARCH_FLASH_MS else SEARCH_HIT_MS)
        dry.invalidate()
    }

    /**
     * Where the [occurrence]th match of [query] is on PDF page [pdfPageIndex],
     * in page-local units; empty when the PDF cannot say. Blocking: call it off
     * the main thread.
     */
    fun pdfMatchBounds(pdfPageIndex: Int, query: String, occurrence: Int): List<RectF> =
        pdf?.matchBounds(pdfPageIndex, query, occurrence).orEmpty()

    /** The text of PDF page [pdfPageIndex], from the PDF already open here. Blocking. */
    fun pdfPageText(pdfPageIndex: Int): String = pdf?.textOf(pdfPageIndex).orEmpty()

    /**
     * The last capture's region, kept so the capture dialog can render it again
     * at whatever size is picked for it. Holds its own snapshot of the ink and
     * pictures, so it stays valid while the page carries on being written on.
     */
    @Volatile var lastCapture: CaptureRegion? = null
        private set

    private fun finishCapture() {
        capturing = false
        lastCapturePage = capturePage
        val page = document.pages.getOrNull(capturePage)
        val rect = RectF(captureRect)
        captureRect.setEmpty()
        dry.invalidate()
        if (page == null || rect.width() < 8f || rect.height() < 8f) return

        // Snapshot on the UI thread: the render runs on a worker, and a stroke
        // arriving on the page mid-render would otherwise be a concurrent
        // modification of the list being walked.
        val region = CaptureRegion(
            pageIndex = capturePage,
            rect = rect,
            screenScale = currentScale(),
            pageWidth = page.width,
            pageHeight = page.height,
            pdfIndex = if (page.background == PageBackground.PDF) page.pdfPageIndex else -1,
            strokes = page.strokes.toList(),
            images = page.images.map { it to imageLoader?.invoke(it.id) },
        )
        lastCapture = region
        // What the dialog shows: small and quick. The file that is saved, shared
        // or pasted is rendered again from the region at the size picked there.
        val preview = (CAPTURE_PREVIEW_PX / max(rect.width(), rect.height())).coerceAtMost(CAPTURE_MAX_SCALE)
        refiner.execute {
            val bitmap = renderCapture(region, preview)
            if (bitmap != null) onCaptured?.invoke(bitmap)
        }
    }

    /**
     * Renders [region] at [scale] pixels per page unit - from the vectors and
     * the PDF, never by enlarging a smaller picture. Blocking: call it off the
     * main thread. The size should come from [planCapture]; the bitmap budget
     * is applied again here as a backstop.
     */
    fun renderCapture(region: CaptureRegion, scale: Float): Bitmap? = renderRegion(
        region.rect, region.pageWidth, region.pageHeight, region.pdfIndex, region.strokes, region.images, scale)

    /** Draws one page's [rect] into a bitmap at [requestedScale]: background, pictures, then ink. */
    private fun renderRegion(
        rect: RectF,
        pageWidth: Float,
        pageHeight: Float,
        pdfIndex: Int,
        strokes: List<Stroke>,
        images: List<Pair<PageImage, Bitmap?>>,
        requestedScale: Float,
    ): Bitmap? = runCatching {
        val wanted = requestedScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val scale = wanted * BitmapBudget.scaleFor(
            capturePixels(rect.width(), wanted), capturePixels(rect.height(), wanted))
        // Counted the way the capture dialog counts, so what it promised is what comes out.
        val width = capturePixels(rect.width(), scale)
        val height = capturePixels(rect.height(), scale)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        canvas.translate(-rect.left, -rect.top)

        if (pdfIndex >= 0) {
            // Only the region, rasterised at the output size: the whole page at
            // a 4x capture's density was many times the pixels, and the source
            // of the blur when the budget then shrank it.
            val crop = pdf?.renderCropNow(pdfIndex, rect, width, height)
            if (crop != null) {
                canvas.drawBitmap(crop, null, rect, null)
                crop.recycle()
            } else {
                pdf?.renderNow(pdfIndex, (pageWidth * scale).toInt().coerceIn(1, PdfSource.baseWidthLimit()))?.let {
                    canvas.drawBitmap(it, null, RectF(0f, 0f, pageWidth, pageHeight), null)
                }
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
        // Each stroke's mesh was built for the zoom it was last seen at; one
        // inside the region and coarser than this scale needs is rebuilt from
        // its inputs, so a 4x capture is sharp ink rather than magnified facets.
        val epsilon = epsilonFor(scale)
        drawLayered(canvas, strokes, drawnLook, null, include = { stroke ->
            val box = stroke.shape.computeBoundingBox()
            box == null || !(box.xMax < rect.left || box.xMin > rect.right ||
                box.yMax < rect.top || box.yMin > rect.bottom)
        }) { stroke ->
            val fine = inkEpsilon(stroke.brush.family, stroke.brush.size, epsilon)
            val drawn = if (stroke.brush.epsilon > fine + EPSILON_SLOP) {
                runCatching { Stroke(stroke.brush.copy(epsilon = fine), stroke.inputs) }.getOrDefault(stroke)
            } else stroke
            renderer.draw(canvas, drawn, transform)
        }
        bitmap
    }.getOrNull()

    private fun eraseAlong(event: MotionEvent, pointerIndex: Int, force: Boolean = false) {
        val page = activePage ?: return
        val index = document.pages.indexOf(page)
        if (index < 0) return
        val screenX = event.getX(pointerIndex)
        val screenY = event.getY(pointerIndex)
        // A finger reports no real pressure, so only the pen's grows. The set
        // size is the lightest touch: pressing only ever makes it bigger, so a
        // pen landing on a stroke - at next to no pressure - already erases at
        // full size instead of starting as a pinprick.
        val squeeze = if (pressureEraser && strokePressureReal &&
            event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS) {
            pressureEraserScale(event.getPressure(pointerIndex))
        } else 1f
        compatWet.showCursor(screenX, screenY, eraserScreenRadius() * squeeze)
        val eraserSize = eraserPageWidth() * squeeze
        pageLocalInto(screenX, screenY, index, erasePagePoint)
        val x = erasePagePoint[0]
        val y = erasePagePoint[1]
        if (hasLastErasePoint) {
            val dx = x - lastEraseX
            val dy = y - lastEraseY
            // The previous square tip already covers this movement. Waiting for
            // a useful distance avoids dense-page intersection work at 240 Hz;
            // the next segment, or the forced UP sample, covers the whole gap.
            if (!shouldProcessEraserMove(dx, dy, eraserSize, force)) return
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
        // The tip is a square of eraserSize around where the pen is now; the
        // segment covers the gap to the previous sample, so a fast swipe still
        // erases along its whole path instead of leaving holes between samples.
        val tip = ImmutableBox.fromCenterAndDimensions(
            ImmutableVec(x, y),
            eraserSize,
            eraserSize,
        )
        val radius = eraserSize / 2f
        val candidateLeft = min(if (hadPrevious) previousX else x, x) - radius
        val candidateTop = min(if (hadPrevious) previousY else y, y) - radius
        val candidateRight = max(if (hadPrevious) previousX else x, x) + radius
        val candidateBottom = max(if (hadPrevious) previousY else y, y) + radius
        val candidates = dry.strokesIn(
            page, candidateLeft, candidateTop, candidateRight, candidateBottom,
        )
        // Whole-stroke by default; [partialEraser] then cuts the hits apart
        // below instead of removing them.
        //
        // intersects() is a member extension on the Intersection object, so it
        // only resolves inside its scope.
        eraseStrokeHits.clear()
        eraseStrokeHitSet.clear()
        // Both on is the default and costs nothing; only a filter asks about families.
        val filtered = !eraseInk || !eraseHighlighter
        with(Intersection) {
            for (candidate in candidates) {
                if (filtered && !erasable(candidate)) continue
                if (!eraseLocked && StrokeTags.locked(candidate)) continue
                if (!onActiveLayer(candidate)) continue
                if ((segment?.intersects(candidate.shape, IDENTITY) == true) ||
                    tip.intersects(candidate.shape, IDENTITY)
                ) {
                    eraseStrokeHits += candidate
                    eraseStrokeHitSet += candidate
                }
            }
        }
        eraseMaskHits.clear()
        if (eraseTape) with(Intersection) {
            for (mask in page.masks) {
                if ((segment?.intersects(mask.stroke.shape, IDENTITY) == true) ||
                    tip.intersects(mask.stroke.shape, IDENTITY)
                ) eraseMaskHits += mask
            }
        }
        // Partial: each hit is cut where the eraser crossed it. A stroke the
        // tip only grazed between inputs can come back untouched.
        val pieces = ArrayList<Stroke>()
        if (partialEraser && eraseStrokeHits.isNotEmpty()) {
            val ax = if (hadPrevious) previousX else x
            val ay = if (hadPrevious) previousY else y
            val cut = ArrayList<Stroke>(eraseStrokeHits.size)
            for (stroke in eraseStrokeHits) {
                val parts = splitStroke(stroke, ax, ay, x, y, radius + stroke.brush.size / 2f)
                    ?: continue
                cut += stroke
                // A piece of a grouped stroke stays in its group.
                parts.forEach { StrokeTags.carry(stroke, it) }
                pieces += parts
            }
            eraseStrokeHits.clear()
            eraseStrokeHitSet.clear()
            eraseStrokeHits += cut
            eraseStrokeHitSet += cut
        }
        // Pictures and text boxes go whole, when asked for, if the tip's sweep reaches their box.
        var imagesErased = false
        if (eraseImages || eraseText) {
            val sweepLeft = candidateLeft
            val sweepTop = candidateTop
            val sweepRight = candidateRight
            val sweepBottom = candidateBottom
            for (at in page.images.indices.reversed()) {
                val image = page.images[at]
                if (image.locked && !eraseLocked) continue
                if (if (image.textContent != null) !eraseText else !eraseImages) continue
                if (image.x > sweepRight || image.x + image.width < sweepLeft ||
                    image.y > sweepBottom || image.y + image.height < sweepTop) continue
                page.images.removeAt(at)
                undoStack += Edit.ImageRemoved(page, image, at, activeEraseGroup)
                imagesErased = true
            }
        }
        if (eraseStrokeHits.isEmpty() && eraseMaskHits.isEmpty()) {
            if (imagesErased) {
                redoStack.clear()
                eraseChanged = true
                page.dirty = true
                dry.postInvalidateOnAnimation()
            }
            return
        }
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
            // Before the pieces go in: removal marks the index current, and
            // the appended pieces must still read as new to it.
            dry.removeStrokesFromIndex(page, erased)
            undoStack += Edit.Erased(page, erased, activeEraseGroup)
            for (piece in pieces) {
                page.strokes += piece
                undoStack += Edit.Drawn(page, piece, activeEraseGroup)
            }
        }
        redoStack.clear()
        // Keep the hot eraser loop local to the View. A Compose state write,
        // autosave restart and mesh-refine request for every 240 Hz sample was
        // the dominant pause on dense pages; commit the gesture once at lift.
        eraseChanged = true
        page.dirty = true
        dry.postInvalidateOnAnimation()
    }

    private val highlighterFamily by lazy { Tool.HIGHLIGHTER.brushFamily() }

    private fun isHighlighter(stroke: Stroke): Boolean =
        stroke.brush.family.let { it === highlighterFamily || it == highlighterFamily }

    private fun erasable(stroke: Stroke): Boolean =
        if (isHighlighter(stroke)) eraseHighlighter else eraseInk

    /** The pieces of [stroke] the eraser swept a→b did not reach, or null if it reached none. */
    private fun splitStroke(stroke: Stroke, ax: Float, ay: Float, bx: Float, by: Float, reach: Float): List<Stroke>? {
        val inputs = stroke.inputs
        val count = inputs.size
        if (count == 0) return null
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val sample = StrokeInput()
        for (i in 0 until count) {
            inputs.populate(i, sample)
            xs[i] = sample.x
            ys[i] = sample.y
        }
        val runs = keptRuns(xs, ys, count, ax, ay, bx, by, reach) ?: return null
        val other = StrokeInput()
        return runs.map { run ->
            val batch = MutableStrokeInputBatch()
            // Each piece runs on to where the eraser's edge crossed it, an
            // input made between the last kept sample and the first cut one.
            fun addBetween(kept: Int, cut: Int) {
                val t = firstReachFraction(xs[kept], ys[kept], xs[cut], ys[cut], ax, ay, bx, by, reach)
                if (t <= 0.001f) return
                inputs.populate(kept, sample)
                inputs.populate(cut, other)
                batch.add(
                    type = sample.toolType,
                    x = sample.x + (other.x - sample.x) * t,
                    y = sample.y + (other.y - sample.y) * t,
                    elapsedTimeMillis = sample.elapsedTimeMillis +
                        ((other.elapsedTimeMillis - sample.elapsedTimeMillis) * t).toLong(),
                    pressure = lerpInput(sample.pressure, other.pressure, t),
                    tiltRadians = lerpInput(sample.tiltRadians, other.tiltRadians, t),
                    orientationRadians = lerpInput(sample.orientationRadians, other.orientationRadians, t),
                )
            }
            // Before the first kept sample, the boundary has to carry an earlier time.
            if (run.first > 0) {
                val t = firstReachFraction(xs[run.first], ys[run.first], xs[run.first - 1], ys[run.first - 1],
                    ax, ay, bx, by, reach)
                if (t > 0.001f) {
                    inputs.populate(run.first, sample)
                    inputs.populate(run.first - 1, other)
                    batch.add(
                        type = sample.toolType,
                        x = sample.x + (other.x - sample.x) * t,
                        y = sample.y + (other.y - sample.y) * t,
                        elapsedTimeMillis = sample.elapsedTimeMillis -
                            ((sample.elapsedTimeMillis - other.elapsedTimeMillis) * t).toLong(),
                        pressure = lerpInput(sample.pressure, other.pressure, t),
                        tiltRadians = lerpInput(sample.tiltRadians, other.tiltRadians, t),
                        orientationRadians = lerpInput(sample.orientationRadians, other.orientationRadians, t),
                    )
                }
            }
            for (i in run) {
                inputs.populate(i, sample)
                batch.add(
                    type = sample.toolType,
                    x = sample.x,
                    y = sample.y,
                    elapsedTimeMillis = sample.elapsedTimeMillis,
                    pressure = sample.pressure,
                    tiltRadians = sample.tiltRadians,
                    orientationRadians = sample.orientationRadians,
                )
            }
            if (run.last < count - 1) addBetween(run.last, run.last + 1)
            Stroke(stroke.brush, batch)
        }
    }

    /** Pressure, tilt and orientation are absent as -1; between absent and present stays absent. */
    private fun lerpInput(a: Float, b: Float, t: Float): Float =
        if (a < 0f || b < 0f) a else a + (b - a) * t

    private fun finishEraseGesture() {
        if (!eraseChanged) return
        activePage?.let { afterEdit(it) }
    }

    override fun onStrokesFinished(finished: Map<InProgressStrokeId, Stroke>) {
        pendingStrokeCommits.removeAll(finished.keys)
        val finalizeStarted = penFinalizeStartedNanos
        penFinalizeStartedNanos = 0L
        val page = activePage
        val committed = ArrayList<Stroke>(finished.size)
        if (page != null) {
            val group = nextEditGroup++
            for ((wetId, wetStroke) in finished) {
                // Back to the saved family and the stroke's own colour if it was
                // shown in another; the tip is unchanged, so the mesh is reused.
                val storedFamily = Tool.storedFamilyOf(wetStroke.brush.family)
                val trueColor = wetTrueColors.remove(wetId)
                val familyBrush = if (storedFamily === wetStroke.brush.family) wetStroke.brush
                    else wetStroke.brush.copy(family = storedFamily)
                val storedBrush = if (trueColor == null || trueColor == familyBrush.colorIntArgb) familyBrush
                    else familyBrush.copyWithColorIntArgb(colorIntArgb = trueColor)
                val finishedStroke = if (storedBrush === wetStroke.brush) wetStroke
                    else wetStroke.copy(storedBrush)
                val finishedTool = Tool.ofBrushFamily(finishedStroke.brush.family)
                val stroke = when {
                    // One pass, one rebuild at most: the spur and the dwell tidying
                    // used to build a stroke each, so a pen-up tessellated twice.
                    finishedTool == Tool.PEN -> tidiedPenStroke(finishedStroke)
                    // Dabs are laid by distance from the first input; dropping
                    // one would move every dab after it, and a cap spur cannot
                    // be seen under a scatter anyway.
                    finishedTool.isDabbed() -> finishedStroke
                    finishedTool.isFreehandPen() -> withoutSpursAndDwells(finishedStroke)
                    else -> finishedStroke
                }

                if (strokeIsMask) {
                    val mask = PageMask(stroke)
                    page.masks += mask
                    undoStack += Edit.MaskAdded(page, mask, group)
                } else if (scribbleErase && finishedTool.isFreehandPen() && scribbleOut(page, stroke, group)) {
                    // The scribble took the ink under it and is not kept itself.
                } else {
                    val shape = when {
                        // A held highlighter only ever straightens: it marks lines of text.
                        recognizeOnFinish && finishedTool == Tool.HIGHLIGHTER && stroke.inputs.size >= 2 -> {
                            val first = stroke.inputs.get(0)
                            val last = stroke.inputs.get(stroke.inputs.size - 1)
                            RecognizedShape(ShapeKind.LINE, first.x, first.y, last.x, last.y)
                        }
                        (autoShapeRecognitionEnabled || recognizeOnFinish) &&
                            Tool.ofBrushFamily(stroke.brush.family).isFreehandPen() -> recognizeStroke(stroke)
                        else -> null
                    }
                    if (shape != null) {
                        val parts = shapeStrokes(
                            shape.kind, floatArrayOf(shape.fromX, shape.fromY),
                            floatArrayOf(shape.toX, shape.toY), stroke.brush,
                        )
                        for (part in parts) {
                            page.strokes += part
                            undoStack += Edit.Drawn(page, part, group)
                            committed += part
                        }
                    } else {
                        // Wet ink, shape recognition and the saved stroke now
                        // share the same stabilized streaming trajectory.
                        page.strokes += stroke
                        undoStack += Edit.Drawn(page, stroke, group)
                        committed += stroke
                    }
                }
            }
            redoStack.clear()
        }
        recognizeOnFinish = false
        wet.removeFinishedStrokes(finished.keys)
        if (compatWetStrokeId in finished.keys) compatWetStrokeId = null
        if (page != null && committed.isNotEmpty()) {
            onStrokesCommitted?.invoke(document.pages.indexOf(page), committed)
        }
        if (page != null) afterEdit(page) else afterEdit()
        // After the page was invalidated: the compatible ink waits for the
        // frame that draws these strokes before it lets go of them.
        compatWet.handOff(finished.keys)
        if (finalizeStarted != 0L) {
            latency.addPenFinalize(System.nanoTime() - finalizeStarted)
        }
        if (pendingClose != null) completePendingClose()
    }

    /**
     * Erases the ink [scribble] scribbles over, as part of edit [group]. Runs
     * once per stroke at pen-up, never while writing. Only strokes whose middle
     * lies under the scribble and whose ink it touches go, so a scribble across
     * the end of a long line does not take the whole line with it.
     */
    private fun scribbleOut(page: Page, scribble: Stroke, group: Long): Boolean {
        val inputs = scribble.inputs
        val count = inputs.size
        if (count < 8) return false
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val sample = StrokeInput()
        for (i in 0 until count) {
            inputs.populate(i, sample)
            xs[i] = sample.x
            ys[i] = sample.y
        }
        if (!isScribble(xs, ys, count)) return false
        val box = scribble.shape.computeBoundingBox() ?: return false
        val hits = ArrayList<Stroke>()
        with(Intersection) {
            for (candidate in dry.strokesIn(page, box.xMin, box.yMin, box.xMax, box.yMax)) {
                if (!eraseLocked && StrokeTags.locked(candidate)) continue
                if (!onActiveLayer(candidate)) continue
                val bounds = candidate.shape.computeBoundingBox() ?: continue
                val cx = (bounds.xMin + bounds.xMax) / 2f
                val cy = (bounds.yMin + bounds.yMax) / 2f
                if (cx < box.xMin || cx > box.xMax || cy < box.yMin || cy > box.yMax) continue
                if (candidate.shape.intersects(scribble.shape, IDENTITY, IDENTITY)) hits += candidate
            }
        }
        if (hits.isEmpty()) return false
        val gone: MutableSet<Stroke> = Collections.newSetFromMap(IdentityHashMap())
        gone.addAll(hits)
        page.strokes.removeAll(gone::contains)
        dry.removeStrokesFromIndex(page, hits)
        undoStack += Edit.Erased(page, hits, group)
        return true
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
        private val searchHitFill = Paint().apply { color = 0x55FFC107 }
        private val searchHitEdge = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            color = 0xFFFF9800.toInt()
        }
        private val searchFlash = Paint().apply { color = 0x223B7DDD }
        private val searchHitBox = RectF()

        /** Where an in-note search result is, over everything else on its page. */
        private fun drawSearchHit(canvas: Canvas, page: Page) {
            val rects = searchHitRects
            if (rects.isEmpty()) {
                canvas.drawRect(0f, 0f, page.width, page.height, searchFlash)
                return
            }
            val pad = SEARCH_HIT_PAD_PX / currentScale().coerceAtLeast(0.01f)
            searchHitEdge.strokeWidth = 2f / currentScale().coerceAtLeast(0.01f)
            for (rect in rects) {
                searchHitBox.set(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
                canvas.drawRoundRect(searchHitBox, pad, pad, searchHitFill)
                canvas.drawRoundRect(searchHitBox, pad, pad, searchHitEdge)
            }
        }
        private val outlines = java.util.WeakHashMap<Stroke, android.graphics.Path>()
        private val outlinePoint = MutableVec()
        private val selectionBox = RectF()
        private var lassoDashScale = Float.NaN
        private var lassoDashEffect: android.graphics.DashPathEffect? = null
        private val handle = Paint().apply { isAntiAlias = true; color = 0xFF3B7DDD.toInt() }

        // Twins share the stroke's inputs and mesh, so they cost a Brush and a
        // Stroke wrapper each. Weak keys: a stroke that leaves the page goes.
        private val meshTwins = java.util.WeakHashMap<Stroke, Stroke>()
        private val noMeshTwin: MutableSet<Stroke> =
            Collections.newSetFromMap(java.util.WeakHashMap())

        private val highlightStates = IdentityHashMap<Page, HighlightState>()

        /**
         * Whether any stroke on the page is highlighter ink. Asked every frame,
         * and comparing brush families stroke by stroke was a full pass over a
         * dense page each time; the answer only changes when the strokes do.
         */
        fun pageHasHighlighter(page: Page): Boolean {
            highlightStates[page]?.takeIf {
                it.revision == page.revision && it.meshRevision == page.meshRevision &&
                    it.size == page.strokes.size
            }?.let { return it.has }
            val family = Tool.HIGHLIGHTER.brushFamily()
            val has = page.strokes.any { it.brush.family == family }
            highlightStates[page] = HighlightState(page.revision, page.meshRevision, page.strokes.size, has)
            return has
        }

        private var playbackAllowed: Set<Stroke> = emptySet()
        private var playbackAllowedPage: Page? = null
        private var playbackAllowedCount = -1

        private fun playbackStrokes(page: Page, count: Int): Set<Stroke> {
            if (playbackAllowedPage !== page || playbackAllowedCount != count) {
                val allowed: MutableSet<Stroke> = Collections.newSetFromMap(IdentityHashMap())
                for (i in 0 until minOf(count, page.strokes.size)) allowed += page.strokes[i]
                playbackAllowed = allowed
                playbackAllowedPage = page
                playbackAllowedCount = count
            }
            return playbackAllowed
        }

        /** Takes twins prepared off the UI thread; a null twin means draw the stroke as is. */
        fun adoptTwins(prepared: List<Pair<Stroke, Stroke?>>) {
            for ((stroke, twin) in prepared) {
                if (twin != null) meshTwins[stroke] = twin else noMeshTwin += stroke
            }
        }

        private val inkNodes = object : LinkedHashMap<Page, InkNode>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Page, InkNode>): Boolean {
                val evict = size > MAX_INK_NODES
                if (evict) eldest.value.node.discardDisplayList()
                return evict
            }
        }
        private val nodeRenderer = CanvasStrokeRenderer.create(PencilTextureStore)
        private val nodeTransform = Matrix()

        fun discardInkNodes() {
            for (ink in inkNodes.values) ink.node.discardDisplayList()
            inkNodes.clear()
            inkTiles.clear()
        }

        private val inkTileViewport = RectF()
        private val inkTiles = InkTileCache(
            byteBudget = inkTileBudget(context),
            screenStroke = ::onScreen,
            query = ::strokesIn,
        )

        fun clearInkTiles() = inkTiles.clear()

        /** Ink bitmaps always; when [severe], the recorded ink tiles and page nodes too. */
        fun trimCaches(severe: Boolean) {
            inkBitmaps.evictAll()
            if (severe) {
                inkTiles.clear()
                discardInkNodes()
            }
        }

        /**
         * The page's ink node, re-recorded when it no longer matches. Strokes
         * appended since the recording are left for the caller to draw, so a
         * pen-up on a dense page does not re-record the whole page; that waits
         * until writing pauses. During a pinch or fling a recording made at the
         * previous zoom is replayed scaled rather than re-recorded every frame.
         */
        private fun inkNodeFor(page: Page, scale: Float, look: LayerLook?): InkNode {
            val ink = inkNodes.getOrPut(page) { InkNode(android.graphics.RenderNode("ink")) }
            val strokes = page.strokes
            val prefix = ink.prefix
            // Re-recorded when a layer's opacity changes: a display list, no geometry.
            val sameContent = ink.node.hasDisplayList() && prefix != null && ink.meshRevision == page.meshRevision &&
                ink.meshInk == meshInk && ink.highlighterAbove == highlighterAboveInk &&
                sameLook(ink.look, look) && prefix.matches(strokes, page.revision)
            var record = !sameContent
            if (!record) {
                val recorded = prefix!!.strokes.size
                val appended = strokes.size - recorded
                if (appended > 0) {
                    val idle = activeStylusPointer == null &&
                        SystemClock.uptimeMillis() - lastInkEditMillis >= INK_CACHE_IDLE_MS
                    val family = Tool.HIGHLIGHTER.brushFamily()
                    // Appended highlighter would land on the wrong side of the
                    // recorded ink and miss its multiply layer; record it in.
                    // So would ink on a translucent layer, drawn at full strength
                    // outside its layer's composite.
                    record = idle || appended > MAX_NODE_APPENDED || look != null ||
                        (recorded until strokes.size).any { strokes[it].brush.family == family }
                }
            }
            if (!record && abs(ink.scale / scale - 1f) >= 0.001f && !zooming && !viewportInteracting && !flinging) {
                record = true
            }
            if (record) recordInk(ink, page, scale, look)
            return ink
        }

        private fun recordInk(ink: InkNode, page: Page, scale: Float, look: LayerLook?) {
            val snapshot = page.strokes.toList()
            val width = kotlin.math.ceil(page.width * scale).toInt().coerceAtLeast(1)
            val height = kotlin.math.ceil(page.height * scale).toInt().coerceAtLeast(1)
            ink.node.setPosition(0, 0, width, height)
            val canvas = ink.node.beginRecording(width, height)
            try {
                canvas.scale(scale, scale)
                nodeTransform.setScale(scale, scale)
                if (!pageHasHighlighter(page)) {
                    drawLayered(canvas, snapshot, look, null) { nodeRenderer.draw(canvas, onScreen(it), nodeTransform) }
                } else {
                    val family = Tool.HIGHLIGHTER.brushFamily()
                    for (pass in 0..1) {
                        val highlightPass = if (pass == 0) !highlighterAboveInk else highlighterAboveInk
                        val layer = if (highlightPass) {
                            canvas.saveLayer(0f, 0f, page.width, page.height, highlighterMultiply)
                        } else -1
                        drawLayered(canvas, snapshot, look, null,
                            include = { (it.brush.family == family) == highlightPass }) {
                            nodeRenderer.draw(canvas, onScreen(it), nodeTransform)
                        }
                        if (layer >= 0) canvas.restoreToCount(layer)
                    }
                }
            } finally {
                ink.node.endRecording()
            }
            ink.scale = scale
            ink.prefix = InkRenderPrefix(snapshot).also { it.matches(snapshot, page.revision) }
            ink.meshRevision = page.meshRevision
            ink.meshInk = meshInk
            ink.highlighterAbove = highlighterAboveInk
            ink.look = look
        }

        /** The stroke as it should be drawn on screen; see [meshInk]. */
        private fun onScreen(stroke: Stroke): Stroke {
            if (!meshInk) return stroke
            meshTwins[stroke]?.let { return it }
            if (stroke in noMeshTwin) return stroke
            val twin = meshTwinOf(stroke)
            if (twin == null) noMeshTwin += stroke else meshTwins[stroke] = twin
            return twin ?: stroke
        }

        private val strokeIndexes = IdentityHashMap<Page, StrokeGrid>()
        private val textLayouts = java.util.WeakHashMap<PageImage, TextRender>()
        private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val inkBitmaps = object : LruCache<String, CachedInk>(48 * 1024 * 1024) {
            override fun sizeOf(key: String, value: CachedInk): Int = value.bitmap.byteCount
        }
        private val inkBitmapPending = HashSet<String>()
        private var inkBitmapGeneration = 0

        fun clearStrokeIndexes() {
            strokeIndexes.clear()
            highlightStates.clear()
            discardInkNodes()
            textLayouts.clear()
            inkBitmaps.evictAll()
            inkBitmapPending.clear()
            inkBitmapGeneration++
            inkCacheWorkGeneration++
            viewportRenderGeneration++
        }

        fun dropStrokeIndex(page: Page) {
            strokeIndexes.remove(page)
        }

        /** An index built off the UI thread for what [page] now holds; dropped if it no longer matches. */
        fun adoptStrokeIndex(page: Page, grid: StrokeGrid?) {
            if (grid != null && grid.adopt(page)) strokeIndexes[page] = grid
        }

        /** Everything cached for a page that has been unloaded. It is off screen, so nothing drawn needs it. */
        fun forgetPage(page: Page) {
            inkTiles.forgetPage(page)
            inkNodes.remove(page)?.node?.discardDisplayList()
            linkBoxes.remove(page)
        }

        fun strokesIn(page: Page, left: Float, top: Float, right: Float, bottom: Float): List<Stroke> =
            strokeIndexes.getOrPut(page) { StrokeGrid() }.visible(page, left, top, right, bottom)

        fun removeStrokesFromIndex(page: Page, strokes: Collection<Stroke>) {
            strokeIndexes[page]?.remove(page, strokes)
        }

        private fun cachedInk(page: Page, visibleCount: Int): CachedInk? {
            if (!page.loaded || visibleCount < 250 || page.strokes.size < 300 || movingLasso ||
                currentScale() < 1f) return null
            val scale = tessellationBucket(currentScale() * 1.5f)
            val pixels = page.width.toDouble() * page.height * scale * scale
            if (pixels > 12_000_000.0 || pixels <= 0.0) return null
            val key = "${page.id}:$scale"
            val cached = inkBitmaps.get(key)?.takeIf {
                it.meshRevision == page.meshRevision && it.prefix.matches(page.strokes, page.revision)
            }
            if (cached != null && cached.prefix.strokes.size == page.strokes.size) return cached
            // Keep the previous bitmap underneath the appended vector strokes.
            // Rebuilding a dense page between every pair of pen contacts is
            // quadratic in the amount of writing and competes with wet ink.
            if (activeStylusPointer == null && !viewportInteracting && !zooming && !flinging &&
                SystemClock.uptimeMillis() - lastInkEditMillis >= INK_CACHE_IDLE_MS &&
                !pageHasHighlighter(page) &&
                inkBitmapPending.add(key)
            ) {
                val snapshot = page.strokes.toList()
                val revision = page.revision
                val meshRevision = page.meshRevision
                val generation = inkBitmapGeneration
                val viewportGeneration = viewportRenderGeneration
                val workGeneration = inkCacheWorkGeneration
                runCatching { refiner.execute {
                    val bitmap = runCatching {
                        if (viewportGeneration != viewportRenderGeneration ||
                            workGeneration != inkCacheWorkGeneration) return@runCatching null
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        rasterizeInk(width, height, scale, snapshot) {
                            Thread.currentThread().isInterrupted ||
                                viewportGeneration != viewportRenderGeneration ||
                                workGeneration != inkCacheWorkGeneration
                        }
                    }.getOrNull()
                    // Build prefix membership on the worker too; publishing a
                    // dense page should not allocate its identity table on UI.
                    val rendered = bitmap?.let {
                        val prefix = InkRenderPrefix(snapshot)
                        prefix.matches(snapshot, revision)
                        CachedInk(it, prefix, meshRevision)
                    }
                    post {
                        inkBitmapPending.remove(key)
                        val visible = visiblePages().any { it === page }
                        if (!disposed && viewportGeneration == viewportRenderGeneration && visible &&
                            workGeneration == inkCacheWorkGeneration &&
                            generation == inkBitmapGeneration && page.revision == revision &&
                            page.meshRevision == meshRevision &&
                            page.strokes.size == snapshot.size && rendered != null
                        ) {
                            inkBitmaps.put(key, rendered)
                            invalidate()
                        } else bitmap?.recycle()
                    }
                } }.onFailure { inkBitmapPending.remove(key) }
            }
            return cached
        }

        /**
         * The page, recorded on its own so the glass above can show it
         * without drawing this whole view a second time. See [drawBackdrop].
         */
        val pageNode = android.graphics.RenderNode("notesis-page")

        override fun draw(canvas: Canvas) {
            if (!canvas.isHardwareAccelerated) {
                super.draw(canvas)
                return
            }
            pageNode.setPosition(0, 0, width, height)
            // Turned, the page reaches past the view's edges into the screen's corners.
            pageNode.setClipToBounds(viewRotation == 0f)
            pageNode.setUseCompositingLayer(pageTone != PAGE_TONE_NORMAL, pageTonePaint)
            val recording = pageNode.beginRecording(width, height)
            try {
                super.draw(recording)
            } finally {
                pageNode.endRecording()
            }
            canvas.drawRenderNode(pageNode)
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
            // Tiles with nothing recorded yet - a new zoom, rebuilt meshes, a
            // page just read in - take at most this much of the frame; the
            // rest follow in the frames after (see InkTileCache.backlog).
            // Tiles are only drawn on a hardware canvas; a software capture of
            // this view must not end a frame the screen's recording still uses.
            val tileFrame = canvas.isHardwareAccelerated
            if (tileFrame) inkTiles.beginFrame(tileRecordBudgetNanos(InteractionGovernor.frameMs, InteractionGovernor.state))
            // Null unless some layer is translucent: then each such layer's
            // ink is composited once at its opacity (see drawLayered), and
            // every path below - tiles, page node, direct - does the same.
            val look = document.layers.translucentLook()
            drawnLook = look
            visiblePdfPages = 0
            for (i in document.pagesIntersecting(viewport[1], viewport[3])) {
                val page = document.pages.getOrNull(i) ?: continue
                val top = document.topOf(i)
                if (top > viewport[3] || top + page.height < viewport[1]) continue
                val left = document.leftOf(i)
                if (left > viewport[2] || left + page.width < viewport[0]) continue
                if (page.background == PageBackground.PDF) visiblePdfPages++
            }
            renderer.drawWithStrokes(canvas) { scoped, scope ->
                for (i in document.pagesIntersecting(viewport[1], viewport[3])) {
                    val page = document.pages.getOrNull(i) ?: continue
                    val top = document.topOf(i)
                    if (top > viewport[3] || top + page.height < viewport[1]) continue
                    val left = document.leftOf(i)
                    // A spread or row of pages shares a band: the ones off to
                    // the side were painted, papered and asked of the PDF too.
                    if (left > viewport[2] || left + page.width < viewport[0]) continue

                    // Page-level culling alone still redraws every stroke on a page
                    // that is only half on screen. A zoomed-in page of dense notes
                    // is exactly when frames are tightest.
                    val cullLeft = viewport[0] - left
                    val cullTop = viewport[1] - top
                    val cullRight = viewport[2] - left
                    val cullBottom = viewport[3] - top
                    pageCull.set(cullLeft, cullTop, cullRight, cullBottom)

                    scoped.save()
                    scoped.concat(documentToScreen)
                    scoped.translate(left, top)
                    drawPaper(scoped, page, i)
                    drawImages(scoped, page)
                    if (playbackHighlights.isNotEmpty()) drawPlaybackHighlights(scoped, page)
                    val lifted = i == lassoPage && movingLasso
                    val visibleLeft = cullLeft.coerceAtLeast(0f)
                    val visibleTop = cullTop.coerceAtLeast(0f)
                    val visibleRight = cullRight.coerceAtMost(page.width)
                    val visibleBottom = cullBottom.coerceAtMost(page.height)
                    val tiledInk = scoped.isHardwareAccelerated && meshInk &&
                        page.strokes.size >= 256 && playbackStrokeCount == null
                    // The tiles query the index themselves; a culled list here
                    // was built every frame and only ever counted.
                    val visibleStrokes = if (tiledInk) emptyList() else {
                        val indexedStrokes = strokeIndexes.getOrPut(page) { StrokeGrid() }.visible(
                            page, visibleLeft, visibleTop, visibleRight, visibleBottom,
                        )
                        if (i == playbackPage && playbackStrokeCount != null) {
                            val allowed = playbackStrokes(page, playbackStrokeCount!!)
                            indexedStrokes.filter { it in allowed }
                        } else indexedStrokes
                    }
                    visibleCount += if (tiledInk) page.strokes.size else visibleStrokes.size
                    val hasHighlighter = pageHasHighlighter(page) && (tiledInk || visibleStrokes.any {
                        it.brush.family == Tool.HIGHLIGHTER.brushFamily()
                    })
                    val inkNode = if (!tiledInk && scoped.isHardwareAccelerated && !lifted && playbackStrokeCount == null)
                        inkNodeFor(page, currentScale(), look) else null
                    // The bitmap is drawn flat; translucent layers take the direct path instead.
                    val inkBitmap = if (look == null && !tiledInk && inkNode == null && !hasHighlighter && !lifted &&
                        playbackStrokeCount == null) cachedInk(page, visibleStrokes.size) else null
                    // Put highlighter ink on the chosen side of handwriting.
                    // Keep the source list in its original order within each
                    // layer so overlapping strokes still look predictable.
                    if (tiledInk) {
                        inkTileViewport.set(visibleLeft, visibleTop, visibleRight, visibleBottom)
                        val interacting = zooming || viewportInteracting || flinging
                        val idle = activeStylusPointer == null && !interacting &&
                            SystemClock.uptimeMillis() - lastInkEditMillis >= INK_CACHE_IDLE_MS
                        inkTiles.draw(scoped, page, currentScale(), inkTileViewport,
                            interacting, idle, hasHighlighter, highlighterAboveInk, highlighterMultiply,
                            if (lifted) liftedLassoStrokes else null, look)
                    } else if (inkNode != null) {
                        // Recorded in pixels at its own zoom; undo that scale so
                        // it lands on the page exactly at the zoom in use.
                        val inverse = 1f / inkNode.scale
                        scoped.save()
                        scoped.scale(inverse, inverse)
                        scoped.drawRenderNode(inkNode.node)
                        scoped.restore()
                        val prefix = inkNode.prefix
                        if (prefix != null && prefix.strokes.size != page.strokes.size) {
                            for (stroke in visibleStrokes) {
                                if (!prefix.contains(stroke)) scope.drawStroke(onScreen(stroke))
                            }
                        }
                    } else if (inkBitmap != null) {
                        scoped.drawBitmap(inkBitmap.bitmap, null, pageRect, bitmapPaint)
                        for (stroke in visibleStrokes) {
                            if (!inkBitmap.prefix.contains(stroke)) scope.drawStroke(onScreen(stroke))
                        }
                    } else if (!hasHighlighter) {
                        drawLayered(scoped, visibleStrokes, look, pageRect,
                            include = { !(lifted && it in lassoStrokes) }) { scope.drawStroke(onScreen(it)) }
                    } else for (pass in 0..1) {
                        val highlightPass = if (pass == 0) !highlighterAboveInk else highlighterAboveInk
                        // A translucent layer's highlighter is composited inside
                        // the multiply layer, so it keeps its blend with the page.
                        val layer = if (highlightPass)
                            scoped.saveLayer(pageRect, highlighterMultiply) else -1
                        drawLayered(scoped, visibleStrokes, look, pageRect, include = { stroke ->
                            !(lifted && stroke in lassoStrokes) &&
                                (stroke.brush.family == Tool.HIGHLIGHTER.brushFamily()) == highlightPass
                        }) { scope.drawStroke(onScreen(it)) }
                        if (layer >= 0) scoped.restoreToCount(layer)
                    }
                    if (lifted) {
                        scoped.save()
                        scoped.translate(lassoDx, lassoDy)
                        drawLayered(scoped, lassoStrokes, look, null) { scope.drawStroke(onScreen(it)) }
                        scoped.restore()
                    }
                    if (showLinkOverlay && page.links.isNotEmpty()) drawLinks(scoped, page)
                    if (i == lassoPage) drawLasso(scoped)
                    if (i == selectingPage) {
                        selection?.boxes?.forEach { scoped.drawRect(it, selectionPaint) }
                    }
                    if (i == shapePage && drawingShape) drawShapePreview(scoped)
                    if (i == polyPage && polyPoints.isNotEmpty()) {
                        overlay.color = previewArgb(colorArgb)
                        overlay.strokeWidth = strokeWidth
                        for (k in 1 until polyPoints.size) {
                            scoped.drawLine(polyPoints[k - 1][0], polyPoints[k - 1][1], polyPoints[k][0], polyPoints[k][1], overlay)
                        }
                        scoped.drawCircle(polyPoints[0][0], polyPoints[0][1], VERTEX_HANDLE_PX / currentScale(), vertexPaint)
                    }
                    if (i == searchHitPage) drawSearchHit(scoped, page)
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
            if (tileFrame) {
                inkTiles.endFrame()
                // Ends by itself: once every tile on screen is recorded, nothing asks again.
                if (inkTiles.backlog) postInvalidateOnAnimation()
            }
            if (started != 0L) {
                lastVisibleStrokes = visibleCount
                lastDrawMs = (System.nanoTime() - started) / 1e6
                latency.addDraw((lastDrawMs * 1e6).toLong(), visibleCount)
                latency.addInkCache(inkTiles.stats())
            }
        }

        private val playbackPaint = Paint().apply { color = 0x66FFD54F }
        private val playbackBox = RectF()

        /** A soft band behind the ink being heard, under the strokes so they stay legible. */
        private fun drawPlaybackHighlights(canvas: Canvas, page: Page) {
            val pad = PLAYBACK_HIGHLIGHT_PAD / currentScale().coerceAtLeast(0.01f)
            for (stroke in page.strokes) {
                if (stroke !in playbackHighlights) continue
                val box = stroke.shape.computeBoundingBox() ?: continue
                playbackBox.set(box.xMin - pad, box.yMin - pad, box.xMax + pad, box.yMax + pad)
                canvas.drawRoundRect(playbackBox, pad, pad, playbackPaint)
            }
        }

        /** This frame's viewport in the page being drawn, for skipping what is off screen. */
        private val pageCull = RectF()

        /**
         * Whether a picture can show on screen: its box grown to the circle it
         * sweeps when turned, so a rotated corner is never cut off.
         */
        private fun onScreenImage(image: PageImage): Boolean {
            var dx = 0f
            var dy = 0f
            if (movingLasso && lassoImages.any { it === image }) { dx = lassoDx; dy = lassoDy }
            val reach = if (image.rotation != 0f) hypot(image.width, image.height) / 2f else 0f
            val cx = image.x + image.width / 2f + dx
            val cy = image.y + image.height / 2f + dy
            val halfW = maxOf(image.width / 2f, reach) + IMAGE_CULL_MARGIN
            val halfH = maxOf(image.height / 2f, reach) + IMAGE_CULL_MARGIN
            return cx + halfW >= pageCull.left && cx - halfW <= pageCull.right &&
                cy + halfH >= pageCull.top && cy - halfH <= pageCull.bottom
        }

        private fun drawImages(canvas: Canvas, page: Page) {
            if (page.images.isEmpty()) return
            val loader = imageLoader ?: return
            val liftedPage = movingLasso && lassoImages.isNotEmpty() &&
                document.pages.getOrNull(lassoPage) === page
            for (image in page.images) {
                // Before anything is decoded: a page of pictures zoomed in on
                // one corner read and uploaded every one of them.
                if (!onScreenImage(image)) continue
                if (image.collapsed) {
                    // Folded: a tab in the note's own colour, with its corner turned down.
                    imageRect.set(image.x, image.y, image.x + image.width, image.y + image.height)
                    if (liftedPage && lassoImages.any { it === image }) imageRect.offset(lassoDx, lassoDy)
                    foldPaint.color = image.textContent?.background?.takeIf { it != 0 } ?: 0xFFFFF59D.toInt()
                    canvas.drawRoundRect(imageRect, 6f, 6f, foldPaint)
                    foldPaint.color = 0x33000000
                    val corner = imageRect.width() * 0.35f
                    canvas.drawPath(android.graphics.Path().apply {
                        moveTo(imageRect.right - corner, imageRect.bottom)
                        lineTo(imageRect.right, imageRect.bottom - corner)
                        lineTo(imageRect.right - corner, imageRect.bottom - corner)
                        close()
                    }, foldPaint)
                    continue
                }
                if (image.box == PageImage.BOX_CHECK) {
                    imageRect.set(image.x, image.y, image.x + image.width, image.y + image.height)
                    if (liftedPage && lassoImages.any { it === image }) imageRect.offset(lassoDx, lassoDy)
                    val turned = image.rotation != 0f
                    if (turned) {
                        canvas.save()
                        canvas.rotate(image.rotation, imageRect.centerX(), imageRect.centerY())
                    }
                    drawCheckbox(canvas, imageRect, image.color, image.checked, image.opacity)
                    if (turned) canvas.restore()
                    continue
                }
                // A text box draws its glyphs as vectors; its bitmap only told
                // the layout its size. Once laid out, it is not decoded again -
                // nor kept in the picture cache, pushing real pictures out.
                val laidOut = if (image.textContent != null) textLayouts[image] else null
                val bitmap = if (laidOut != null) null else loader(image.id) ?: continue
                imageRect.set(
                    image.x,
                    image.y,
                    image.x + image.width,
                    image.y + image.height,
                )
                if (liftedPage && lassoImages.any { it === image }) imageRect.offset(lassoDx, lassoDy)
                val turned = image.rotation != 0f
                if (turned) {
                    canvas.save()
                    canvas.rotate(image.rotation, imageRect.centerX(), imageRect.centerY())
                }
                val content = image.textContent
                if (content == null) {
                    // Pictures always have one: only a laid-out text box skips the load.
                    val picture = bitmap!!
                    if (image.opacity < 1f) {
                        bitmapPaint.alpha = (image.opacity * 255).toInt()
                        canvas.drawBitmap(picture, null, imageRect, bitmapPaint)
                        bitmapPaint.alpha = 255
                    } else canvas.drawBitmap(picture, null, imageRect, bitmapPaint)
                } else {
                    // Text boxes keep a bitmap for old files and export, but on
                    // screen their glyphs are laid out as vectors. Scaling a
                    // stored bitmap was the pixelated type seen at zoom levels.
                    val text = laidOut ?: textLayouts.getOrPut(image) {
                        val source = bitmap!!
                        val logicalWidth = source.width / 2f
                        val logicalHeight = source.height / 2f
                        val width = (logicalWidth - content.padding * 2f).toInt().coerceAtLeast(1)
                        TextRender(textBoxLayout(content, width, 1f), logicalWidth, logicalHeight)
                    }
                    canvas.save()
                    canvas.clipRect(imageRect)
                    canvas.translate(imageRect.left, imageRect.top)
                    canvas.scale(image.width / text.logicalWidth, image.height / text.logicalHeight)
                    drawTextBox(canvas, content, text.layout, text.logicalWidth, text.logicalHeight, 1f)
                    canvas.restore()
                }
                if (turned) canvas.restore()
            }
        }

        private val linkWash = Paint().apply { color = 0x332196F3 }
        private val lassoPreviewWash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x403B7DDD }
        /** The orange corner handles of a shape being edited. */
        private val vertexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF9800.toInt() }

        /**
         * Each link group's box, kept until the page changes. Worked out per
         * frame it was a walk of every stroke on the page for every link.
         */
        private inner class LinkBoxes(val stamp: List<Long>, val boxes: List<RectF>)
        private val linkBoxes = java.util.WeakHashMap<Page, LinkBoxes>()

        private fun drawLinks(canvas: Canvas, page: Page) {
            val stamp = listOf(page.revision, page.meshRevision, page.strokes.size.toLong(),
                page.images.size.toLong(), page.links.size.toLong())
            val cached = linkBoxes[page]?.takeIf { it.stamp == stamp } ?: LinkBoxes(stamp,
                page.links.keys.mapNotNull { group ->
                    groupBounds(page, group, linkBox)
                    if (linkBox.isEmpty) null else RectF(linkBox)
                }).also { linkBoxes[page] = it }
            val pad = 4f / currentScale()
            for (box in cached.boxes) {
                linkBox.set(box)
                linkBox.inset(-pad, -pad)
                canvas.drawRoundRect(linkBox, pad, pad, linkWash)
            }
        }

        /** The loop as it is drawn, and the box around what it caught. */
        private fun drawLasso(canvas: Canvas) {
            val scale = currentScale()
            if (drawingLasso && lassoPath.size >= 4) {
                // What lifting the pen now would take, washed in the loop's blue.
                if (lassoPreviewStrokes.isNotEmpty() || lassoPreviewImages.isNotEmpty()) {
                    val pad = LASSO_PREVIEW_PAD_PX / scale
                    for (stroke in lassoPreviewStrokes) {
                        val box = stroke.shape.computeBoundingBox() ?: continue
                        selectionBox.set(box.xMin - pad, box.yMin - pad, box.xMax + pad, box.yMax + pad)
                        canvas.drawRoundRect(selectionBox, pad, pad, lassoPreviewWash)
                    }
                    for (image in lassoPreviewImages) {
                        selectionBox.set(image.x - pad, image.y - pad, image.x + image.width + pad, image.y + image.height + pad)
                        canvas.drawRoundRect(selectionBox, pad, pad, lassoPreviewWash)
                    }
                }
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
            if (!hasLassoSelection() || lassoBounds.isEmpty) return
            editableShape()?.let { shape ->
                val sample = StrokeInput()
                for (i in 0 until shape.inputs.size) {
                    shape.inputs.populate(i, sample)
                    canvas.drawCircle(sample.x, sample.y, VERTEX_HANDLE_PX / scale, vertexPaint)
                }
            }
            selectionBox.set(lassoBounds)
            selectionBox.offset(lassoDx, lassoDy)
            val pad = LASSO_PADDING / scale
            selectionBox.inset(-pad, -pad)
            overlay.color = 0xFF3B7DDD.toInt()
            overlay.strokeWidth = 2f / scale
            overlay.pathEffect = lassoDashes(scale)
            canvas.drawRect(selectionBox, overlay)
            overlay.pathEffect = null
            if (movingLasso && (!guideX.isNaN() || !guideY.isNaN())) {
                val page = document.pages.getOrNull(lassoPage) ?: return
                overlay.color = 0xFFE91E63.toInt()
                overlay.strokeWidth = 1f / scale
                if (!guideX.isNaN()) canvas.drawLine(guideX, 0f, guideX, page.height, overlay)
                if (!guideY.isNaN()) canvas.drawLine(0f, guideY, page.width, guideY, overlay)
            }
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
                // Tape off screen is not drawn; its box is cheap beside its mesh.
                val box = mask.stroke.shape.computeBoundingBox()
                if (box != null && (box.xMax < pageCull.left || box.xMin > pageCull.right ||
                        box.yMax < pageCull.top || box.yMin > pageCull.bottom)) continue
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
            val kind = shapeKind
            if (kind == null && rulerEdge == null) return
            val points = if (rulerEdge != null) rulerPoints else shapePoints(kind!!, shapeStart, shapeEnd)
            if (points.size < 2) return
            // Squared up: a red guide round the box says the sides are now equal.
            if (kind != null && rulerEdge == null) squaredShapeEnd(kind, shapeStart, shapeEnd)?.let { end ->
                overlay.color = 0xFFE53935.toInt()
                overlay.strokeWidth = 1.5f / currentScale()
                overlay.pathEffect = lassoDashes(currentScale())
                canvas.drawRect(min(shapeStart[0], end[0]), min(shapeStart[1], end[1]),
                    max(shapeStart[0], end[0]), max(shapeStart[1], end[1]), overlay)
                overlay.pathEffect = null
            }
            // At the target layer's opacity, as the shape will be; the tone comes with the page.
            overlay.color = previewArgb(colorArgb)
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
            // Draw only visible pattern cells, retaining their original page
            // coordinates and a margin for antialiasing at the viewport edges.
            val left = viewport[0] - document.leftOf(index)
            val top = viewport[1] - document.topOf(index)
            val right = minOf(page.width, viewport[2] - document.leftOf(index) + 4f)
            val bottom = minOf(page.height, viewport[3] - document.topOf(index) + 4f)
            when (page.background) {
                PageBackground.BLANK -> Unit
                PageBackground.INFINITE -> Unit
                PageBackground.LINED -> {
                    var y = firstVisiblePaperRule(top, RULE_SPACING)
                    while (y < bottom) {
                        canvas.drawLine(0f, y, page.width, y, rule)
                        y += RULE_SPACING
                    }
                }

                PageBackground.GRID -> {
                    var y = firstVisiblePaperRule(top, RULE_SPACING)
                    while (y < bottom) {
                        canvas.drawLine(0f, y, page.width, y, rule)
                        y += RULE_SPACING
                    }
                    var x = firstVisiblePaperRule(left, RULE_SPACING)
                    while (x < right) {
                        canvas.drawLine(x, 0f, x, page.height, rule)
                        x += RULE_SPACING
                    }
                }

                PageBackground.DOT -> {
                    var y = firstVisiblePaperRule(top, RULE_SPACING)
                    while (y < bottom) {
                        var x = firstVisiblePaperRule(left, RULE_SPACING)
                        while (x < right) {
                            canvas.drawCircle(x, y, 2.5f, rule)
                            x += RULE_SPACING
                        }
                        y += RULE_SPACING
                    }
                }

                PageBackground.NARROW_LINED -> {
                    var y = firstVisiblePaperRule(top, RULE_SPACING / 2f)
                    while (y < bottom) {
                        canvas.drawLine(0f, y, page.width, y, rule)
                        y += RULE_SPACING / 2f
                    }
                }

                PageBackground.CORNELL -> {
                    canvas.drawLine(page.width * 0.30f, 0f,
                        page.width * 0.30f, page.height * 0.82f, rule)
                    canvas.drawLine(0f, page.height * 0.82f,
                        page.width, page.height * 0.82f, rule)
                    var y = firstVisiblePaperRule(top, RULE_SPACING)
                    while (y < minOf(bottom, page.height * 0.82f)) {
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

        /** PDF pages on screen this frame: they share the page cache. */
        private var visiblePdfPages = 1

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
            val base = source.bitmap(
                page.pdfPageIndex,
                (page.width * scale).toInt(),
                render,
                sharing = visiblePdfPages,
                aspect = if (page.width > 0f) page.height / page.width else 1.414f,
            )
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
                        sharing = visiblePdfPages,
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
            layoutViewport(out)
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
        /** The zoom, as a multiple of the fit, past which a pinch adds rather than multiplies. See [pinchedScale]. */
        private const val LINEAR_ZOOM_FROM = 2.5f
        // 페이지 폭과 태블릿의 실제 콘텐츠 폭을 1:1로 맞춥니다.
        const val FIT_MARGIN = 1f
        const val HIGHLIGHT_ALPHA = 0x66000000
        const val RULE_SPACING = 60f
        const val PAGE_TOP_MARGIN_PX = 24f
        const val REFINE_DEBOUNCE_MS = 200L
        private const val INK_CACHE_IDLE_MS = 700L
        const val ZOOM_REPORT_INTERVAL_NS = 33_000_000L
        const val REFINE_MARGIN_PX = 160f
        const val MAX_UNDO_EDITS = 1000
        const val LASSO_DUPLICATE_OFFSET = 24f
        const val LINK_TAP_MS = 350L
        const val PLAYBACK_HIGHLIGHT_PAD = 6f
        /** Pages whose ink recordings are kept; the rest re-record when they come back. */
        const val MAX_INK_NODES = 6
        /** Strokes drawn live on top of a recording before it is re-recorded anyway. */
        const val MAX_NODE_APPENDED = 48
        const val BASE_TESSELLATION_SCALE = 1f
        /** The finest zoom a page is first read in at; see refineVisiblePages. */
        const val LOAD_TESSELLATION_SCALE = 2f
        const val MAX_SCREEN_BRUSHES = 256
        /** Page units round a picture still drawn when just off screen: shadows, handles. */
        const val IMAGE_CULL_MARGIN = 24f
        const val EPSILON_SLOP = 0.00001f
        const val PREFETCH_SETTLE_MS = 280L
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
        const val RULER_GAP_PX = 1.5f
        const val SNAP_ALIGN_PX = 10f
        const val PULL_FOR_PAGE_PX = 90f
        const val VERTEX_HANDLE_PX = 9f
        const val MAX_EDITABLE_INPUTS = 24
        const val EDGE_EXCLUSION_DP = 32f
        const val DOUBLE_TAP_ZOOM = 2f
        const val STRIKE_HEIGHT = 0.08f
        // Page units are A4 at 150dpi, so a real millimetre of page is this many of them.
        const val A4_MM_WIDTH = 210f
        const val IMAGE_INSERT_FRACTION = 0.5f
        const val IMAGE_HANDLE_PX = 22f
        const val IMAGE_MIN_SIZE = 32f
        /** The long edge of the picture the capture dialog shows. */
        const val CAPTURE_PREVIEW_PX = 1280f
        /** How long a found search result stays marked, and how long a page without a known place flashes. */
        const val SEARCH_HIT_MS = 1800L
        const val SEARCH_FLASH_MS = 450L
        /** Screen pixels round a marked search result. */
        const val SEARCH_HIT_PAD_PX = 4f
        const val CAPTURE_MAX_SCALE = 4f
        const val LASSO_MIN_POINTS = 6
        const val LASSO_PADDING = 10f
        const val LASSO_SAMPLE_DISTANCE_PX = 2f
        /** How long the pen rests at the end of a loop before it becomes a lasso. */
        const val CIRCLE_HOLD_MS = 500L
        /** Of touch slop: a resting pen still shakes this much. */
        const val HOLD_SLOP_FRACTION = 0.5f
        /** On screen: a loop smaller than this both ways is a letter o, not a lasso. */
        const val CIRCLE_MIN_EXTENT_PX = 48f
        const val WHOLE_CHECK_SAMPLES = 32
        const val KEY_ZOOM_STEP = 1.25f
        /** The reading-mode trail and tap ring: the selection's blue. */
        /** How often the lasso's live catch is re-collected while it is drawn. */
        private const val LASSO_PREVIEW_MS = 50L
        /** The wash's margin round each caught stroke, in screen pixels. */
        private const val LASSO_PREVIEW_PAD_PX = 3f
        /** Degrees three fingers must turn before the note follows. */
        private const val ROTATE_START_DEGREES = 12f
        private const val MAX_TURN_POINTERS = 10
        private const val PI_F = Math.PI.toFloat()
        private const val THUMBNAIL_CACHE_COUNT = 12
        const val GESTURE_TRAIL_COLOR = 0xFF3B7DDD.toInt()
        const val MEASURED_LATENCY_REFRESH_MS = 500L
        /** How far outside a checkbox, in screen pixels, a tap still ticks it. */
        const val CHECKBOX_TOUCH_SLOP_PX = 14f
        /** ln(1.15): a wheel notch zooms by about 15%. */
        const val WHEEL_ZOOM_PER_NOTCH = 0.1398f
        const val KEY_PAN_FRACTION = 0.15f
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
        const val TRACE_LAYER_SETTLE = "Notesis layers/settle"
        /** How long one "this layer is locked" message holds for the same layer. */
        const val LOCKED_NOTICE_MS = 6_000L
        const val TRACE_REFINE_APPLY = "Notesis refine/apply"

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

/**
 * Samsung's S Pen actions for a touch made with the side button held:
 * 211 down, 212 up, 213 move, 214 cancel. Returns the standard action, or null
 * for any other action.
 */
internal fun samsungSidePenAction(actionMasked: Int): Int? = when (actionMasked) {
    SAMSUNG_ACTION_PEN_DOWN -> MotionEvent.ACTION_DOWN
    SAMSUNG_ACTION_PEN_UP -> MotionEvent.ACTION_UP
    SAMSUNG_ACTION_PEN_MOVE -> MotionEvent.ACTION_MOVE
    SAMSUNG_ACTION_PEN_CANCEL -> MotionEvent.ACTION_CANCEL
    else -> null
}

private const val SAMSUNG_ACTION_PEN_DOWN = 211
private const val SAMSUNG_ACTION_PEN_UP = 212
private const val SAMSUNG_ACTION_PEN_MOVE = 213
private const val SAMSUNG_ACTION_PEN_CANCEL = 214

/** True while the barrel button is held, or the pen is flipped to its eraser end. */
private fun MotionEvent.isEraserGesture(): Boolean =
    getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER ||
        buttonState and
        (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0

/**
 * Recorded ink tiles: 24MB on a low-RAM device, 48MB as before on most, and
 * 96MB with 7GB or more, where scrolling back over a dense page should find
 * its ink still recorded.
 */
private fun inkTileBudget(context: Context): Int {
    val manager = context.getSystemService(android.app.ActivityManager::class.java)
    if (manager?.isLowRamDevice == true) return 24 * 1024 * 1024
    val info = android.app.ActivityManager.MemoryInfo()
    val total = runCatching { manager?.getMemoryInfo(info); info.totalMem }.getOrDefault(0L)
    return if (total >= 7L * 1024 * 1024 * 1024) 96 * 1024 * 1024 else 48 * 1024 * 1024
}
