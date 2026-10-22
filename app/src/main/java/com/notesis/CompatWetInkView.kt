package com.notesis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.BoxAccumulator
import androidx.ink.geometry.MutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Galaxy Tab S6 Lite models. Ink's front-buffer layer accepts every update on
 * them but the panel only shows it once HWUI draws again - at pen-up - so the
 * stroke appears all at once when the pen lifts. androidx.ink itself carries a
 * workaround for this tablet (b/365131024), which is not enough here.
 */
internal fun frontBufferInkUnreliable(model: String = Build.MODEL ?: ""): Boolean =
    Regex("^SM-P6(1[0-9]|2[0-9])[A-Z]*$").matches(model.trim().uppercase())

/** Converts MotionEvent.AXIS_ORIENTATION ([-PI, PI], 0 = up) to Ink's [0, 2PI), 0 = left. */
internal fun inkOrientationRadians(axisOrientation: Float): Float {
    val full = (2 * Math.PI).toFloat()
    return ((axisOrientation + 2.5f * Math.PI.toFloat()) % full + full) % full
}

/**
 * Wet ink for tablets where [frontBufferInkUnreliable], plus the eraser cursor
 * on every tablet.
 *
 * It mirrors the stroke that InProgressStrokesView is building from the same
 * events - same brush, same page transform, same timing - so what shows while
 * writing is what lands at pen-up. InProgressStrokesView still produces the
 * committed stroke; this only makes it visible on the way.
 *
 * The ink is drawn into its own SurfaceView whenever it can be. Drawn as part
 * of the window instead, every pen frame redrew the whole window: the page
 * tiles, the toolbar's blurred backdrop (which records the page), a docked web
 * panel and the reference popup. With a web page, a popup note or a second app
 * on screen that frame no longer fit in 16.7ms on the S6 Lite and the ink fell
 * behind the pen. The surface is rasterised on the CPU and only around the
 * stroke, so a pen frame touches neither the window, the RenderThread nor the
 * GPU the other content is competing for. A rotated note cannot place a
 * surface, so it falls back to drawing in this View.
 */
internal class CompatWetInkView(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    /** One stroke being written or waiting for the page to draw it. Reused. */
    private class Wet {
        var id: Any = Unit
        val stroke = InProgressStroke()
        val real = MutableStrokeInputBatch()
        val predicted = MutableStrokeInputBatch()
        val screenToPage = Matrix()
        val pageToScreen = Matrix()
        private val values = FloatArray(9)
        var startTime = 0L
        var margin = 0f
        var lastTime = 0L
        var lastX = Float.NaN
        var lastY = Float.NaN
        var hasTilt = false
        var hasOrientation = false
        var pending = false
        var changed = true
        var finished = false
        var finishedAt = 0L
        /** Its first frame has been handed on; see [onFirstShown]. */
        var shown = false

        /** Every input position seen so far, in screen pixels. */
        val inputs = RectF()
        var hasInputs = false

        /** What this stroke covers on the surface right now. */
        val drawn = RectF()

        /**
         * A pencil dab look, drawn from [outlines] rather than by Ink; see
         * [drawDabs]. Cleared for good if reading the outlines ever fails.
         */
        var dabs = false
        var color = 0
        val outlines = DabOutlineCache()
        val outlineSource = InProgressOutlines(stroke)

        fun begin(id: Any, brush: Brush, transform: Matrix, startTime: Long) {
            this.id = id
            stroke.start(brush)
            dabs = PencilBrushes.isDab(brush.family)
            color = brush.colorIntArgb
            outlines.clear()
            real.clear()
            predicted.clear()
            screenToPage.set(transform)
            if (!screenToPage.invert(pageToScreen)) pageToScreen.reset()
            // Screen-space reach of the brush around every input: the tip,
            // pressure growth up to twice the size, and antialiasing.
            pageToScreen.getValues(values)
            val scale = sqrt(kotlin.math.abs(
                values[Matrix.MSCALE_X] * values[Matrix.MSCALE_Y] -
                    values[Matrix.MSKEW_X] * values[Matrix.MSKEW_Y]))
            margin = brush.size * scale + 4f
            this.startTime = startTime
            lastTime = startTime
            lastX = Float.NaN
            lastY = Float.NaN
            hasTilt = false
            hasOrientation = false
            pending = false
            changed = true
            finished = false
            finishedAt = 0L
            shown = false
            inputs.setEmpty()
            hasInputs = false
            drawn.setEmpty()
        }
    }

    private val renderer = CanvasStrokeRenderer.create(PencilTextureStore)

    // For [drawDabs]: the same paint Ink's path renderer gives a dab coat,
    // which has no texture and no colour function.
    private val dabPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dabPath = Path()
    private val dabClip = RectF()
    private val dabSink = OutlineSink { points, count ->
        dabPath.moveTo(points[0], points[1])
        for (v in 1 until count) dabPath.lineTo(points[v * 2], points[v * 2 + 1])
        dabPath.close()
    }
    private val strokes = ArrayList<Wet>()
    private val spare = ArrayList<Wet>()
    private val point = FloatArray(2)
    private val emptyBatch = MutableStrokeInputBatch()

    private var surface: SurfaceView? = null
    private var surfaceReady = false
    private var surfaceHasContent = false
    private var framePosted = false
    private val pendingClear = RectF()
    private val dirtyF = RectF()
    private val extent = RectF()
    private val updated = BoxAccumulator()
    private val region = RectF()
    private val dirty = Rect()
    private val clipF = RectF()
    private var viewHasContent = false

    private var cursorVisible = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorRadius = 0f
    private var cursorChanged = false
    private val cursorDrawn = RectF()
    private val density = resources.displayMetrics.density
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ERASER_CURSOR_COLOR
    }
    private val cursorEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ERASER_CURSOR_COLOR
    }

    /**
     * Called with the time from the newest pen event in a surface frame to
     * that frame being posted, in nanoseconds.
     */
    var onFramePosted: ((Long) -> Unit)? = null
    private var newestUnpostedEventMillis = Long.MIN_VALUE

    /**
     * Called once per stroke, with its pen-down event time, when the frame
     * that first shows it is handed on: posted to the surface, or drawn by
     * the window on the in-window path.
     */
    var onFirstShown: ((Long) -> Unit)? = null

    /**
     * The eraser cursor while ink is drawn in the window: a View of its own,
     * kept to the cursor's box, so a hover or an eraser frame redraws the
     * cursor rather than the whole window under it. On the surface the
     * cursor is drawn there instead.
     */
    private val cursorView = object : android.view.View(context) {
        init {
            setWillNotDraw(false)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            if (!cursorVisible || usingSurface()) return
            overlayOrigin(canvas)
            canvas.drawCircle(cursorX, cursorY, cursorRadius, cursorFill)
            canvas.drawCircle(cursorX, cursorY, cursorRadius, cursorEdge)
        }
    }
    private val cursorBox = IntArray(4)

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        framePosted = false
        lastVsyncNanos = frameTimeNanos
        renderSurface()
    }

    /** When the last frame this view drew for began: the vsync grid its ink is shown on. */
    var lastVsyncNanos: Long = 0L
        private set

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(cursorView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * Whether wet ink may use its own surface. A surface follows its View's
     * position and scale, never a rotation; the canvas keeps this view in the
     * screen's frame (as it does Ink's own front buffer) so a turned note can
     * still use it, and this is only for a host that turns the view itself.
     */
    var surfaceAllowed: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            modeChanged()
        }

    /** Wet ink is drawn here at all; the eraser cursor works either way. */
    var inkEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) attachSurface() else {
                clearInk()
                detachSurface()
            }
        }

    private fun attachSurface() {
        if (surface != null) return
        val view = SurfaceView(context).apply {
            // Above the window like Ink's own front buffer, transparent where
            // there is no ink, and never a hole punched through the page.
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            holder.addCallback(this@CompatWetInkView)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        surface = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun detachSurface() {
        val view = surface ?: return
        view.holder.removeCallback(this)
        surface = null
        surfaceReady = false
        surfaceHasContent = false
        removeView(view)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        // Undefined until drawn; the first lock covers the whole surface.
        surfaceHasContent = true
        markAllChanged()
        modeChanged()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceHasContent = true
        markAllChanged()
        modeChanged()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        surfaceHasContent = false
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            framePosted = false
        }
        modeChanged()
    }

    private fun usingSurface(): Boolean = surface != null && surfaceReady && surfaceAllowed

    private fun markAllChanged() {
        for (wet in strokes) wet.changed = true
        cursorChanged = true
    }

    private fun modeChanged() {
        markAllChanged()
        if (usingSurface()) {
            pendingClear.set(0f, 0f, width.toFloat(), height.toFloat())
        }
        requestRender()
        // Whichever side is not drawing drops what it last showed.
        invalidate()
        placeCursor()
        cursorView.invalidate()
    }

    fun start(id: Any, event: MotionEvent, pointerId: Int, brush: Brush, transform: Matrix) {
        if (!inkEnabled) return
        cancel(id)
        // A finished stroke whose commit never arrived must not stay forever.
        val now = SystemClock.uptimeMillis()
        val stale = strokes.filter { it.finished && now - it.finishedAt > STALE_FINISHED_MS }
        if (stale.isNotEmpty()) remove(stale)
        val wet = spare.removeLastOrNull() ?: Wet()
        if (runCatching { wet.begin(id, brush, transform, event.eventTime) }.isFailure) {
            spare += wet
            return
        }
        val index = event.findPointerIndex(pointerId)
        val device = event.device
        if (index >= 0 && event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS && device != null) {
            wet.hasTilt = device.getMotionRange(MotionEvent.AXIS_TILT, event.source) != null
            wet.hasOrientation = device.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source) != null
        }
        strokes += wet
        enqueue(wet, event, pointerId, null)
        requestRender()
    }

    fun add(id: Any, event: MotionEvent, pointerId: Int, prediction: MotionEvent?) {
        val wet = find(id) ?: return
        if (wet.finished) return
        enqueue(wet, event, pointerId, prediction)
        requestRender()
    }

    fun finish(id: Any, event: MotionEvent, pointerId: Int) {
        val wet = find(id) ?: return
        if (wet.finished) return
        enqueue(wet, event, pointerId, null)
        wet.predicted.clear()
        flush(wet, finishing = true)
        wet.finished = true
        wet.finishedAt = SystemClock.uptimeMillis()
        // Stays on screen until the committed stroke is drawn underneath.
        requestRender()
    }

    /** The stroke was cancelled: gone at once. */
    fun cancel(id: Any?) {
        if (id == null) return
        val wet = find(id) ?: return
        remove(listOf(wet))
    }

    /**
     * The stroke was committed. In this View it goes in the same frame as the
     * page that now draws it. On the surface it stays until the window has
     * submitted that frame and one more has passed, because the two are
     * composited separately: clearing first would blink the stroke out.
     */
    fun handOff(ids: Collection<Any>) {
        val done = strokes.filter { it.id in ids }
        if (done.isEmpty()) return
        if (!usingSurface() || !isAttachedToWindow) {
            remove(done)
            return
        }
        var retired = false
        val retire = Runnable {
            if (retired) return@Runnable
            retired = true
            remove(done)
        }
        viewTreeObserver.registerFrameCommitCallback {
            Choreographer.getInstance().postFrameCallback { retire.run() }
        }
        postDelayed(retire, HANDOFF_TIMEOUT_MS)
    }

    fun clearInk() {
        if (strokes.isEmpty()) return
        remove(strokes.toList())
    }

    fun showCursor(x: Float, y: Float, radius: Float) {
        if (cursorVisible && x == cursorX && y == cursorY && radius == cursorRadius) return
        cursorVisible = true
        cursorX = x
        cursorY = y
        cursorRadius = radius
        cursorChanged = true
        renderCursor()
    }

    fun hideCursor() {
        if (!cursorVisible) return
        cursorVisible = false
        cursorChanged = true
        renderCursor()
    }

    private fun renderCursor() {
        if (usingSurface()) {
            requestRender()
            return
        }
        // Only the cursor's own box: its View is moved, the window is not redrawn.
        placeCursor()
        cursorView.invalidate()
    }

    private fun placeCursor() {
        if (!cursorVisible || width <= 0) return
        val reach = cursorRadius + cursorEdge.strokeWidth + 2f
        if (overlayBounds(cursorX - reach, cursorY - reach, cursorX + reach, cursorY + reach, 0f,
                width, height, cursorBox)) cursorView.placeOver(cursorBox)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Layout puts every child back to full size; the cursor goes back to its box.
        placeCursor()
    }

    private fun remove(done: List<Wet>) {
        for (wet in done) {
            if (!strokes.remove(wet)) continue
            pendingClear.union(wet.drawn)
            wet.id = Unit
            if (spare.size < SPARE_STROKES) spare += wet
        }
        requestRender()
    }

    private fun find(id: Any): Wet? {
        for (i in strokes.indices.reversed()) if (strokes[i].id == id) return strokes[i]
        return null
    }

    private fun requestRender() {
        if (usingSurface()) {
            if (!framePosted) {
                framePosted = true
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
            if (viewHasContent) invalidate()
        } else {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Collects the inputs of one event. The geometry is updated once per frame
     * in [flush] instead: a 240Hz pen otherwise rebuilt the stroke four times
     * for every frame anyone could see.
     */
    private fun enqueue(wet: Wet, event: MotionEvent, pointerId: Int, prediction: MotionEvent?) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        for (h in 0 until event.historySize) {
            addSample(wet, wet.real, event.getHistoricalX(index, h), event.getHistoricalY(index, h),
                event.getHistoricalEventTime(h), event.getHistoricalPressure(index, h),
                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h),
                event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, h), track = true)
        }
        addSample(wet, wet.real, event.getX(index), event.getY(index), event.eventTime,
            event.getPressure(index), event.getAxisValue(MotionEvent.AXIS_TILT, index),
            event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index), track = true)
        wet.predicted.clear()
        if (prediction != null) {
            val p = prediction.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
            if (p < prediction.pointerCount) {
                addSample(wet, wet.predicted, prediction.getX(p), prediction.getY(p),
                    maxOf(prediction.eventTime, wet.lastTime), prediction.getPressure(p),
                    prediction.getAxisValue(MotionEvent.AXIS_TILT, p),
                    prediction.getAxisValue(MotionEvent.AXIS_ORIENTATION, p), track = false)
            }
        }
        wet.pending = true
        wet.changed = true
        if (event.eventTime > newestUnpostedEventMillis) newestUnpostedEventMillis = event.eventTime
    }

    private fun addSample(
        wet: Wet,
        batch: MutableStrokeInputBatch,
        x: Float,
        y: Float,
        time: Long,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        track: Boolean,
    ) {
        if (time < wet.lastTime) return
        if (!x.isFinite() || !y.isFinite()) return
        point[0] = x
        point[1] = y
        wet.screenToPage.mapPoints(point)
        if (point[0] == wet.lastX && point[1] == wet.lastY) return
        if (!point[0].isFinite() || !point[1].isFinite()) return
        runCatching {
            batch.add(
                type = InputToolType.STYLUS,
                x = point[0],
                y = point[1],
                elapsedTimeMillis = time - wet.startTime,
                pressure = pressure.coerceIn(0f, 1f),
                tiltRadians = if (wet.hasTilt && tilt.isFinite()) {
                    tilt.coerceIn(0f, HALF_PI)
                } else StrokeInput.NO_TILT,
                orientationRadians = if (wet.hasOrientation && orientation.isFinite()) {
                    inkOrientationRadians(orientation)
                } else StrokeInput.NO_ORIENTATION,
            )
        }.onSuccess {
            if (!wet.hasInputs) {
                wet.hasInputs = true
                wet.inputs.set(x, y, x, y)
            } else wet.inputs.union(x, y)
            if (track) {
                wet.lastTime = time
                wet.lastX = point[0]
                wet.lastY = point[1]
            }
        }
    }

    /** Brings the stroke's geometry up to the inputs collected since the last frame. */
    private fun flush(wet: Wet, finishing: Boolean = false) {
        if (!wet.pending && !finishing) return
        wet.pending = false
        val queued = runCatching { wet.stroke.enqueueInputs(wet.real, wet.predicted) }.isSuccess
        // A prediction Ink refuses must not take the real inputs down with it.
        if (!queued) runCatching { wet.stroke.enqueueInputs(wet.real, emptyBatch) }
        wet.real.clear()
        wet.predicted.clear()
        runCatching {
            if (finishing) {
                wet.stroke.finishInput()
                wet.stroke.updateShape(Long.MAX_VALUE)
            } else {
                wet.stroke.updateShape(wet.lastTime - wet.startTime)
            }
        }
        wet.changed = true
    }

    private fun extentOf(wet: Wet, out: RectF) {
        if (!wet.hasInputs) {
            out.setEmpty()
            return
        }
        out.set(wet.inputs)
        out.inset(-wet.margin, -wet.margin)
    }

    private fun renderSurface() {
        val view = surface
        if (view == null || !usingSurface()) return
        dirtyF.set(pendingClear)
        pendingClear.setEmpty()
        for (wet in strokes) {
            flush(wet)
            if (!wet.changed) continue
            wet.changed = false
            extentOf(wet, extent)
            // Only what Ink re-meshed since the last frame needs repainting;
            // the rest of the stroke is still on the surface. Repainting the
            // whole extent re-rasterised every dab of a long pencil scatter
            // on the CPU every frame, slower the longer the stroke got.
            // Asked every frame the stroke changed, first one included: it
            // also brings a dab stroke's outlines up to date.
            val known = updatedRegion(wet, region)
            if (!wet.drawn.isEmpty && known) {
                dirtyF.union(region)
            } else {
                dirtyF.union(wet.drawn)
                dirtyF.union(extent)
            }
            wet.drawn.set(extent)
        }
        if (cursorChanged) {
            cursorChanged = false
            dirtyF.union(cursorDrawn)
            if (cursorVisible) {
                val reach = cursorRadius + cursorEdge.strokeWidth + 2f
                cursorDrawn.set(cursorX - reach, cursorY - reach, cursorX + reach, cursorY + reach)
                dirtyF.union(cursorDrawn)
            } else {
                cursorDrawn.setEmpty()
            }
        }
        if (surfaceHasContent && strokes.isEmpty() && !cursorVisible) {
            // Nothing left to show: make sure nothing is left behind either.
            dirtyF.set(0f, 0f, width.toFloat(), height.toFloat())
        }
        if (dirtyF.isEmpty) return
        dirty.set(floor(dirtyF.left).toInt() - 1, floor(dirtyF.top).toInt() - 1,
            ceil(dirtyF.right).toInt() + 1, ceil(dirtyF.bottom).toInt() + 1)
        if (!dirty.intersect(0, 0, width, height)) return
        val holder = view.holder
        // The dirty rectangle may grow, for instance to the whole surface on
        // its first frame; everything inside what comes back is redrawn.
        val canvas = try { holder.lockCanvas(dirty) } catch (_: Exception) { null } ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawContent(canvas, dirty)
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
        surfaceHasContent = strokes.isNotEmpty() || cursorVisible
        val newest = newestUnpostedEventMillis
        newestUnpostedEventMillis = Long.MIN_VALUE
        // Event times and System.nanoTime share the monotonic clock.
        if (newest != Long.MIN_VALUE) onFramePosted?.invoke(System.nanoTime() - newest * 1_000_000L)
        reportFirstShown()
    }

    /**
     * The screen area whose geometry Ink added, changed or removed - a
     * replaced prediction included - since this was last asked. False when
     * Ink cannot say, and the caller falls back to the whole stroke.
     */
    private fun updatedRegion(wet: Wet, out: RectF): Boolean {
        val box = runCatching {
            wet.stroke.populateUpdatedRegion(updated.reset())
            wet.stroke.resetUpdatedRegion()
            updated.box
        }.getOrElse {
            if (wet.dabs) syncOutlines(wet) { wet.outlines.syncAll(wet.outlineSource) }
            return false
        }
        if (wet.dabs) syncOutlines(wet) {
            if (box == null) {
                wet.outlines.sync(wet.outlineSource, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            } else {
                wet.outlines.sync(wet.outlineSource, box.xMin, box.yMin, box.xMax, box.yMax)
            }
        }
        if (box == null) {
            out.setEmpty()
            return true
        }
        out.set(box.xMin, box.yMin, box.xMax, box.yMax)
        wet.pageToScreen.mapRect(out)
        out.inset(-REGION_PAD_PX, -REGION_PAD_PX)
        return true
    }

    /** A failed read of the outlines hands the stroke back to Ink's own renderer. */
    private inline fun syncOutlines(wet: Wet, sync: () -> Unit) {
        if (runCatching(sync).isFailure) wet.dabs = false
    }

    private fun drawContent(canvas: Canvas, clip: Rect?) {
        if (clip != null) clipF.set(clip)
        for (wet in strokes) {
            if (clip != null && !wet.drawn.isEmpty && !RectF.intersects(wet.drawn, clipF)) continue
            val saved = canvas.save()
            try {
                // The renderer's matrix describes screen resolution for its
                // shader; the canvas still needs the page-to-screen placement.
                canvas.concat(wet.pageToScreen)
                if (wet.dabs) {
                    runCatching { drawDabs(canvas, wet, if (clip != null) clipF else null) }
                } else {
                    runCatching { renderer.draw(canvas, wet.stroke, wet.pageToScreen) }
                }
            } finally {
                canvas.restoreToCount(saved)
            }
        }
        // In the window the cursor has a View of its own; see [cursorView].
        if (cursorVisible && clip != null) {
            canvas.drawCircle(cursorX, cursorY, cursorRadius, cursorFill)
            canvas.drawCircle(cursorX, cursorY, cursorRadius, cursorEdge)
        }
    }

    /**
     * A dab stroke as Ink's own renderer draws it on this CPU canvas - its
     * outlines filled as one path, nonzero, in the stroke's colour - but only
     * the outlines that reach [screenClip], from [Wet.outlines]. Ink rebuilt
     * the whole stroke's path every frame, which is what made a long pencil
     * line slow down as it grew; this is the dabs near the pen. The canvas
     * is already in page units.
     */
    private fun drawDabs(canvas: Canvas, wet: Wet, screenClip: RectF?) {
        if (screenClip != null) {
            dabClip.set(screenClip)
            dabClip.inset(-REGION_PAD_PX, -REGION_PAD_PX)
            wet.screenToPage.mapRect(dabClip)
        } else {
            dabClip.set(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        }
        dabPaint.color = wet.color
        for (coat in 0 until wet.outlines.coatCount) {
            dabPath.rewind()
            wet.outlines.forEachMeeting(coat, dabClip.left, dabClip.top, dabClip.right, dabClip.bottom, dabSink)
            if (!dabPath.isEmpty) canvas.drawPath(dabPath, dabPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (usingSurface()) {
            viewHasContent = false
            return
        }
        for (wet in strokes) {
            flush(wet)
            // Keeps a dab stroke's outlines current; the whole view is drawn here.
            if (wet.changed) updatedRegion(wet, region)
            wet.changed = false
        }
        drawContent(canvas, null)
        viewHasContent = strokes.isNotEmpty()
        newestUnpostedEventMillis = Long.MIN_VALUE
        reportFirstShown()
    }

    /** [onFirstShown] for each stroke whose first frame was just handed on. */
    private fun reportFirstShown() {
        for (wet in strokes) {
            if (wet.shown || !wet.hasInputs) continue
            wet.shown = true
            onFirstShown?.invoke(wet.startTime)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            framePosted = false
        }
    }

    /** [OutlineSource] over an in-progress stroke's outlines. */
    private class InProgressOutlines(private val stroke: InProgressStroke) : OutlineSource {
        private val vec = MutableVec()
        override fun coatCount(): Int = stroke.getBrushCoatCount()
        override fun outlineCount(coat: Int): Int = stroke.getOutlineCount(coat)
        override fun vertexCount(coat: Int, outline: Int): Int = stroke.getOutlineVertexCount(coat, outline)
        override fun position(coat: Int, outline: Int, vertex: Int, into: FloatArray, at: Int) {
            stroke.populateOutlinePosition(coat, outline, vertex, vec)
            into[at] = vec.x
            into[at + 1] = vec.y
        }
    }

    private companion object {
        const val HALF_PI = (Math.PI / 2).toFloat()
        const val HANDOFF_TIMEOUT_MS = 250L
        /** Antialiasing reaches a pixel or two past the mesh. */
        const val REGION_PAD_PX = 4f
        const val STALE_FINISHED_MS = 1_500L
        const val SPARE_STROKES = 4

        // One colour, translucent, for both the inside and the rim. The rim is
        // the same paint drawn once more, so it reads as a slightly denser
        // edge of the same disc rather than a second outline colour.
        const val ERASER_CURSOR_COLOR = 0x5C8E8E93
    }
}
