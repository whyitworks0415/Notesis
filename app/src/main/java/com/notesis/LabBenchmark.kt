package com.notesis

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Lab's benchmark: the same script on any build, so two builds - or one
 * build with a switch on and off - can be compared on the tablet rather than
 * by feel.
 *
 * It writes [WRITE_STROKES] lines of loops on the page in view by sending the
 * canvas the pen events a hand would, at a pen's rate, and takes them all back
 * again; then lets the page glide down and up as a flick does. While it runs
 * the canvas's own timings are collected, and the report is those timings -
 * the stages of wet ink, the frames, the times notes took to open - with the
 * device and the Lab's switches.
 *
 * The pen is the script's, not a hand's: the events come from this thread, not
 * the digitizer, so what the touch pipeline costs before an event reaches the
 * app is not in the numbers, and nor is the time from a posted frame to light.
 */
internal class LabBenchmark(private val view: InkCanvasView) {
    private val main = Handler(Looper.getMainLooper())
    private val properties = arrayOf(MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_STYLUS
    })
    private val coordinates = arrayOf(MotionEvent.PointerCoords())
    private var cancelled = false

    /** Runs the whole script and hands [onDone] the report. The UI thread only. */
    fun run(zoomPercent: Int, onDone: (String) -> Unit) {
        if (view.width <= 0 || view.height <= 0) {
            onDone("The page is not laid out yet; open a note and try again.")
            return
        }
        val wasMeasuring = view.latencyMonitoringEnabled
        view.latencyMonitoringEnabled = true
        view.latency.reset()
        // A selection left on the page would be picked up and moved by the first stroke.
        view.clearLassoSelection()
        val undoBefore = view.undoDepth
        view.benchWriting = true
        writeStrokes(0) {
            // The last stroke is committed a moment after the pen lifts.
            main.postDelayed({
                Measurements.sample(view)
                val writing = view.latency.render(view.strokeCount())
                val written = (view.undoDepth - undoBefore).coerceIn(0, WRITE_STROKES)
                repeat(written) { if (view.canUndo()) view.undo() }
                view.benchWriting = false
                view.latency.reset()
                glide(-GLIDE_PX_PER_S) {
                    glide(GLIDE_PX_PER_S) {
                        main.postDelayed({
                            Measurements.sample(view)
                            val scrolling = view.latency.render(view.strokeCount())
                            val report = benchmarkReport(
                                header = Measurements.conditions(view) + " · zoom $zoomPercent%",
                                device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android " +
                                    "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                                app = appVersion(),
                                switches = "performance hint ${view.writingBoostStatus} · own buffers ${view.nativePresentStatus} · " +
                                    "efficient glass ${if (LabPerformance.efficientGlass) "on" else "off"}",
                                written = written, writing = writing, scrolling = scrolling,
                                opens = NoteOpenClock.render(),
                            )
                            view.latencyMonitoringEnabled = wasMeasuring
                            if (!cancelled) onDone(report)
                        }, SETTLE_MS)
                    }
                }
            }, COMMIT_WAIT_MS)
        }
    }

    /** Stops handing anything on; a stroke in flight still finishes and is taken back by the host's undo. */
    fun cancel() {
        cancelled = true
        main.removeCallbacksAndMessages(null)
        view.benchWriting = false
    }

    private fun appVersion(): String = runCatching {
        view.context.packageManager.getPackageInfo(view.context.packageName, 0)
            .let { "${it.versionName} (${it.longVersionCode})" }
    }.getOrDefault("unknown")

    /** The digitizer's id where there is one, so the canvas treats the pressure as a pen's own. */
    private val stylusDevice: Int = InputDevice.getDeviceIds().firstOrNull { id ->
        InputDevice.getDevice(id)?.let { it.supportsSource(InputDevice.SOURCE_STYLUS) && !it.isVirtual } == true
    } ?: 0

    private fun writeStrokes(index: Int, done: () -> Unit) {
        if (cancelled) return
        if (index >= WRITE_STROKES) {
            done()
            return
        }
        val line = benchStrokeLine(index, view.width.toFloat(), view.height.toFloat())
        val downTime = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, downTime, downTime, line.x(0f), line.y(0f), 0f)
        var step = 1
        val steps = (STROKE_MS / SAMPLE_MS).toInt()
        val tick = object : Runnable {
            override fun run() {
                if (cancelled) return
                val t = step / steps.toFloat()
                val now = SystemClock.uptimeMillis()
                if (step >= steps) {
                    send(MotionEvent.ACTION_UP, downTime, now, line.x(1f), line.y(1f), 1f)
                    main.postDelayed({ writeStrokes(index + 1, done) }, BETWEEN_STROKES_MS)
                    return
                }
                send(MotionEvent.ACTION_MOVE, downTime, now, line.x(t), line.y(t), t)
                step++
                main.postDelayed(this, SAMPLE_MS)
            }
        }
        main.postDelayed(tick, SAMPLE_MS)
    }

    private fun send(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float, t: Float) {
        coordinates[0].apply {
            this.x = x
            this.y = y
            // Pressing and easing off along the stroke, as writing does.
            pressure = 0.35f + 0.4f * sin(t * PI.toFloat())
            size = 0.01f
        }
        val event = MotionEvent.obtain(downTime, eventTime, action, 1, properties, coordinates, 0, 0, 1f, 1f,
            stylusDevice, 0, InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /** Lets the page go and waits for it to come to rest, or for long enough. */
    private fun glide(velocity: Float, then: () -> Unit) {
        if (cancelled) return
        view.benchFling(velocity)
        val started = SystemClock.uptimeMillis()
        val poll = object : Runnable {
            override fun run() {
                if (cancelled) return
                if (view.gliding && SystemClock.uptimeMillis() - started < GLIDE_LIMIT_MS) {
                    main.postDelayed(this, 50L)
                } else main.postDelayed(then, SETTLE_MS)
            }
        }
        main.postDelayed(poll, 100L)
    }

    companion object {
        const val WRITE_STROKES = 30
        /** A pen's rate: 250 samples a second. */
        private const val SAMPLE_MS = 4L
        private const val STROKE_MS = 480L
        private const val BETWEEN_STROKES_MS = 140L
        /** Past the pause after writing, so the stroke has been committed and the page drawn. */
        private const val COMMIT_WAIT_MS = 1500L
        private const val SETTLE_MS = 600L
        private const val GLIDE_PX_PER_S = 9000f
        private const val GLIDE_LIMIT_MS = 4000L
    }
}

/** One replayed stroke: a row of loops across the page, as a line of handwriting runs. */
internal class BenchLine(private val left: Float, private val right: Float, private val baseline: Float, private val height: Float) {
    fun x(t: Float): Float = left + (right - left) * t + height * 0.35f * sin(t * LOOPS * 2f * PI.toFloat())
    fun y(t: Float): Float = baseline - height * 0.5f * (1f - kotlin.math.cos(t * LOOPS * 2f * PI.toFloat()))
    private companion object { const val LOOPS = 9f }
}

/**
 * Where stroke [index] of the script goes in a view [width] by [height]: lines
 * down the middle three fifths of the view, clear of its edges and the bars.
 */
internal fun benchStrokeLine(index: Int, width: Float, height: Float): BenchLine {
    val row = index % BENCH_ROWS
    val top = height * 0.25f
    val gap = height * 0.5f / BENCH_ROWS
    return BenchLine(width * 0.2f, width * 0.8f, top + gap * (row + 1), gap * 0.6f)
}

internal const val BENCH_ROWS = 10

/** The report, as text to paste beside another run's. */
internal fun benchmarkReport(
    header: String, device: String, app: String, switches: String,
    written: Int, writing: String, scrolling: String, opens: String,
): String = buildString {
    appendLine("Notesis Lab benchmark · $app")
    appendLine(device)
    appendLine(header)
    appendLine(switches)
    appendLine("Scripted pen and glide, sent by the app: not the digitizer's path, and not time to light.")
    appendLine()
    appendLine("== Writing: $written of ${LabBenchmark.WRITE_STROKES} strokes written and taken back")
    appendLine(writing)
    appendLine()
    appendLine("== Scrolling: one glide down, one up")
    appendLine(scrolling)
    appendLine()
    appendLine("== Notes opened since the app started")
    append(opens)
}
