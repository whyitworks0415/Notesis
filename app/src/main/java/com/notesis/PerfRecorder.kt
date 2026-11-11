package com.notesis

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** What the app was doing when a frame was drawn, most specific first. */
internal enum class PerfScene(val label: String) {
    WRITING("Writing"),
    PAGE("Note page moving"),
    CHROME("Popup or panel moving"),
    HOME_SCROLL("Library scrolling"),
    TOUCHING("Other touch"),
    IDLE("Idle (no touch for 1 s)"),
}

/**
 * Which [PerfScene] a frame belongs to. Pure, so the table can be tested:
 * the pen first, then the page under fingers or a fling, then chrome in
 * motion, then the library scrolling; what is left is a touch of some other
 * kind if one was recent, and idle otherwise.
 */
internal fun perfScene(
    interaction: Interaction, chromeMoving: Boolean, homeScrolling: Boolean, sinceTouchMs: Long,
): PerfScene = when {
    interaction == Interaction.WRITING -> PerfScene.WRITING
    interaction == Interaction.MOVING -> PerfScene.PAGE
    chromeMoving -> PerfScene.CHROME
    homeScrolling -> PerfScene.HOME_SCROLL
    sinceTouchMs < PERF_IDLE_AFTER_MS -> PerfScene.TOUCHING
    else -> PerfScene.IDLE
}

internal const val PERF_IDLE_AFTER_MS = 1000L

/** One scene's frames: every total kept for the percentiles, and where the late ones spent their time. */
internal class PerfBucket(private val capacity: Int = 16_384) {
    private val totalsMicros = IntArray(capacity)
    var frames = 0
        private set
    var late = 0
        private set
    /** Sums over the late frames, in nanoseconds: UI thread, sync and draw commands, GPU. */
    var lateUiNanos = 0L
        private set
    var lateRenderNanos = 0L
        private set
    var lateGpuNanos = 0L
        private set

    fun add(totalNanos: Long, isLate: Boolean, uiNanos: Long, renderNanos: Long, gpuNanos: Long) {
        if (frames < capacity) totalsMicros[frames] = (totalNanos / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        frames++
        if (isLate) {
            late++
            lateUiNanos += uiNanos
            lateRenderNanos += renderNanos
            lateGpuNanos += gpuNanos
        }
    }

    /** The [fraction] percentile of the totals kept, in milliseconds; 0 with no frames. */
    fun percentileMs(fraction: Double): Double {
        val n = minOf(frames, capacity)
        if (n == 0) return 0.0
        val sorted = totalsMicros.copyOf(n).also { it.sort() }
        return sorted[percentileIndex(n, fraction)] / 1000.0
    }

    val kept: Int get() = minOf(frames, capacity)
}

/** The index of the [fraction] percentile in [count] sorted samples (nearest rank). */
internal fun percentileIndex(count: Int, fraction: Double): Int {
    if (count <= 0) return 0
    val rank = kotlin.math.ceil(fraction.coerceIn(0.0, 1.0) * count).toInt()
    return (rank - 1).coerceIn(0, count - 1)
}

/**
 * One line of the report for a scene. Kept apart from the recorder so the
 * wording can be tested: a scene with no frames says so rather than showing
 * zeros that read as a measurement.
 */
internal fun perfLine(scene: PerfScene, bucket: PerfBucket, idleSeconds: Double): String {
    if (bucket.frames == 0) return "${scene.label}: no frames"
    val share = bucket.late * 100.0 / bucket.frames
    val head = String.format(Locale.US, "%s: %d frames, %d late (%.1f%%) · p50 %.1f · p95 %.1f · p99 %.1f · max %.1f ms",
        scene.label, bucket.frames, bucket.late, share,
        bucket.percentileMs(0.50), bucket.percentileMs(0.95), bucket.percentileMs(0.99), bucket.percentileMs(1.0))
    // Frames drawn with nobody touching anything are what keeps a tablet warm.
    val rate = if (scene == PerfScene.IDLE && idleSeconds >= 1.0) {
        String.format(Locale.US, " · %.1f frames a second over %.0f s idle", bucket.frames / idleSeconds, idleSeconds)
    } else ""
    val where = if (bucket.late > 0) {
        val n = bucket.late.toDouble()
        String.format(Locale.US, "\n    late frames, on average: UI thread %.1f ms · sync and draw commands %.1f ms · GPU %s",
            bucket.lateUiNanos / n / 1e6, bucket.lateRenderNanos / n / 1e6,
            if (bucket.lateGpuNanos > 0L) String.format(Locale.US, "%.1f ms", bucket.lateGpuNanos / n / 1e6) else "not reported")
    } else ""
    return head + rate + where
}

/**
 * Records the frames the app's main window draws while the person uses it,
 * sorted by what was going on, and writes them up as text to copy or share.
 * A measurement on the tablet in hand, so a change is judged by numbers
 * rather than by reading the code.
 *
 * What it is and is not: the frames of this window as the system times them
 * (FrameMetrics) - input handling to the buffer handed over, and the GPU's
 * part from Android 12. Not the time from there to light, not what the touch
 * hardware costs before the event arrives, and not the frames of a popup's
 * own window. Nothing of the notes is recorded.
 */
internal object PerfRecorder {
    var recording by mutableStateOf(false)
        private set
    var report by mutableStateOf<String?>(null)
        private set

    /** Set by the library while its grid scrolls. */
    @Volatile var homeScrolling = false
    @Volatile private var lastTouchMs = 0L

    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private val main = Handler(android.os.Looper.getMainLooper())
    private var buckets = emptyArray<PerfBucket>()
    /** How long the app has been idle while recording, sampled off the UI thread: what idle frames are counted against. */
    @Volatile private var idleMs = 0L
    private var sampler: Handler? = null
    private val sampleIdle = object : Runnable {
        override fun run() {
            val scene = perfScene(InteractionGovernor.state, ChromeMotion.busy, homeScrolling,
                SystemClock.uptimeMillis() - lastTouchMs)
            if (scene == PerfScene.IDLE) idleMs += IDLE_SAMPLE_MS
            sampler?.postDelayed(this, IDLE_SAMPLE_MS)
        }
    }
    private var startedAtMs = 0L
    private var header = ""
    private val autoStop = Runnable { owner?.let { stop(it) } }
    private var owner: Activity? = null

    /** Every touch, from the activity: cheap, one write. */
    fun touched() { lastTouchMs = SystemClock.uptimeMillis() }

    fun start(activity: Activity, conditions: String) {
        if (listener != null) return
        val handlerThread = thread ?: HandlerThread("perf-frames").also { it.start(); thread = it }
        val hz = DisplayRate.refreshRate(activity)
        val interval = (1_000_000_000L / hz).toLong()
        val scenes = PerfScene.entries
        val fresh = Array(scenes.size) { PerfBucket() }
        buckets = fresh
        idleMs = 0L
        startedAtMs = SystemClock.uptimeMillis()
        header = conditions
        report = null
        val watch = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            // Late by the system's own deadline where it tells one (Android 12),
            // else past one and a half frames.
            val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else 0L
            val isLate = if (deadline > 0L) total > deadline else total > interval * 3 / 2
            val ui = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                metrics.getMetric(FrameMetrics.DRAW_DURATION)
            val render = metrics.getMetric(FrameMetrics.SYNC_DURATION) +
                metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) +
                metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
            val gpu = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION).coerceAtLeast(0L) else 0L
            val now = SystemClock.uptimeMillis()
            val scene = perfScene(InteractionGovernor.state, ChromeMotion.busy, homeScrolling, now - lastTouchMs)
            fresh[scene.ordinal].add(total, isLate, ui, render, gpu)
        }
        runCatching { activity.window.addOnFrameMetricsAvailableListener(watch, Handler(handlerThread.looper)) }
            .onSuccess {
                listener = watch
                owner = activity
                recording = true
                main.postDelayed(autoStop, MAX_RECORDING_MS)
                sampler = Handler(handlerThread.looper).also { it.postDelayed(sampleIdle, IDLE_SAMPLE_MS) }
            }
    }

    /** Stops and writes the report. */
    fun stop(activity: Activity) {
        val watch = listener ?: return
        main.removeCallbacks(autoStop)
        sampler?.removeCallbacks(sampleIdle)
        sampler = null
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(watch) }
        listener = null
        owner = null
        recording = false
        val seconds = (SystemClock.uptimeMillis() - startedAtMs) / 1000.0
        val taken = buckets
        report = buildString {
            appendLine("Notesis performance recording")
            appendLine(header)
            appendLine(String.format(Locale.US, "Recorded for %.0f s. Frames of the app's main window as the system times them; " +
                "not time to light, and not a popup's own window.", seconds))
            appendLine()
            for (scene in PerfScene.entries) appendLine(perfLine(scene, taken[scene.ordinal], idleMs / 1000.0))
        }.trimEnd()
    }

    fun clear() { report = null }

    /** A recording left running stops by itself: it would otherwise count for ever. */
    private const val MAX_RECORDING_MS = 10 * 60 * 1000L
    private const val IDLE_SAMPLE_MS = 250L
}
