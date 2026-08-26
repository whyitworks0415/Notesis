package com.notesis

import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.authoring.ExperimentalLatencyDataApi
import androidx.ink.authoring.latency.LatencyData
import androidx.ink.authoring.latency.LatencyDataCallback
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.rendering.android.view.ViewStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor

/**
 * P0 spike: prove the latency target on a real Galaxy Tab before anything else
 * gets built. Draws with androidx.ink and reports its own motion-to-photon
 * numbers, so the go/no-go call is a number on screen rather than a feeling.
 */
@OptIn(ExperimentalLatencyDataApi::class)
class MainActivity : AppCompatActivity(), InProgressStrokesFinishedListener {

    private lateinit var wet: InProgressStrokesView
    private lateinit var dry: DryStrokesView
    private lateinit var hud: TextView
    private lateinit var predictor: MotionEventPredictor

    private val brush = Brush.createWithColorIntArgb(
        family = StockBrushes.pressurePen(),
        colorIntArgb = 0xFF1A1A1A.toInt(),
        size = 5f,
        epsilon = 0.1f,
    )

    private val pointerToStroke = mutableMapOf<Int, InProgressStrokeId>()
    private val stats = LatencyStats()

    // ponytail: P0 lets a finger draw so the spike is testable without a pen in
    // hand. Flip to false in P1 - the real rule is pen draws, finger pans.
    private val drawWithFinger = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        wet = findViewById(R.id.wet)
        dry = findViewById(R.id.dry)
        hud = findViewById(R.id.hud)

        // Builds the render thread and native surfaces now, so the very first
        // stroke of the session is not the slow one.
        wet.eagerInit()
        wet.addFinishedStrokesListener(this)
        wet.setLatencyDataCallback(object : LatencyDataCallback {
            // Called off the UI thread, and the LatencyData instance is pooled
            // and reset once this returns - read the fields, never keep it.
            override fun onLatencyData(latencyData: LatencyData) {
                if (latencyData.eventAction != LatencyData.EventAction.MOVE) return
                if (!latencyData.isOsDetectsEventSet) return
                stats.add(latencyData.estimatedPixelPresentationTime - latencyData.osDetectsEvent)
            }
        })

        predictor = MotionEventPredictor.newInstance(dry)
        dry.setOnTouchListener { view, event -> onTouch(view, event) }

        hud.postDelayed(object : Runnable {
            override fun run() {
                hud.text = stats.render(dry.strokeCount)
                hud.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        if (!drawWithFinger && event.getToolType(event.actionIndex) != MotionEvent.TOOL_TYPE_STYLUS) {
            return false
        }
        predictor.record(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Without this the OS batches input to the display frame rate and
                // throws away most of the samples the S Pen actually reports.
                view.requestUnbufferedDispatch(event)
                val pointerId = event.getPointerId(event.actionIndex)
                pointerToStroke[pointerId] = wet.startStroke(event, pointerId, brush)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                stats.addSamples(1 + event.historySize)
                val predicted = predictor.predict()
                try {
                    for (i in 0 until event.pointerCount) {
                        val pointerId = event.getPointerId(i)
                        val strokeId = pointerToStroke[pointerId] ?: continue
                        wet.addToStroke(event, pointerId, strokeId, predicted)
                    }
                } finally {
                    predicted?.recycle()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                val strokeId = pointerToStroke.remove(pointerId) ?: return false
                wet.finishStroke(event, pointerId, strokeId)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                val pointerId = event.getPointerId(event.actionIndex)
                val strokeId = pointerToStroke.remove(pointerId) ?: return false
                wet.cancelStroke(strokeId, event)
                return true
            }
        }
        return false
    }

    /** Wet ink has dried: hand it to the dry layer, then let the wet layer drop it. */
    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        dry.addStrokes(strokes.values)
        wet.removeFinishedStrokes(strokes.keys)
    }
}

/** Everything already committed. Redrawn wholesale for now - see the note below. */
class DryStrokesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val renderer = ViewStrokeRenderer(CanvasStrokeRenderer.create(), this)
    private val strokes = mutableListOf<Stroke>()

    val strokeCount: Int get() = strokes.size

    fun addStrokes(new: Collection<Stroke>) {
        strokes += new
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // ponytail: redraws every stroke every frame. Fine up to ~1k strokes,
        // which is all P0 needs. P2 adds viewport culling off each bounding box,
        // and a tile cache only if culling alone still misses 60fps.
        renderer.drawWithStrokes(canvas) { _, scope ->
            for (stroke in strokes) scope.drawStroke(stroke)
        }
    }
}

/**
 * Rolling window of motion-to-photon samples. Written from the ink render
 * thread, read from the UI thread, so every access is inside the lock.
 */
class LatencyStats(private val capacity: Int = 512) {
    private val nanos = LongArray(capacity)
    private var count = 0
    private var next = 0
    private var samples = 0
    private var samplesSince = System.nanoTime()
    private var lastRateHz = 0.0

    @Synchronized
    fun add(latencyNanos: Long) {
        if (latencyNanos <= 0) return
        nanos[next] = latencyNanos
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized
    fun addSamples(n: Int) {
        samples += n
        val elapsed = System.nanoTime() - samplesSince
        if (elapsed > 500_000_000L) {
            lastRateHz = samples * 1e9 / elapsed
            samples = 0
            samplesSince = System.nanoTime()
        }
    }

    @Synchronized
    fun render(strokeCount: Int): String {
        if (count == 0) return "draw to measure"
        val sorted = nanos.copyOf(count).apply { sort() }
        val median = sorted[count / 2] / 1e6
        val p95 = sorted[(count * 95 / 100).coerceAtMost(count - 1)] / 1e6
        val worst = sorted[count - 1] / 1e6
        return ("motion-to-photon  median %.1fms  p95 %.1fms  max %.1fms" +
            "\ninput %.0fHz   strokes %d   n=%d")
            .format(median, p95, worst, lastRateHz, strokeCount, count)
    }
}
