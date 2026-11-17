package com.notesis

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import java.util.Locale

/**
 * The share of its usual GPU tile work a frame may take, after a frame whose
 * render thread and GPU took [renderNanos] against a frame of [intervalNanos]:
 * halved after one that ran over (never below [MIN_RENDER_SHARE]), and given
 * back a tenth at a time after each one that did not.
 */
internal fun nextRenderShare(current: Float, renderNanos: Long, intervalNanos: Long): Float =
    if (renderNanos > intervalNanos) (current * 0.5f).coerceAtLeast(MIN_RENDER_SHARE)
    else (current + 0.1f).coerceAtMost(1f)

internal const val MIN_RENDER_SHARE = 0.1f

/** Workers' threads while the render thread is behind: half of them, at least one. */
internal fun yieldToRender(width: Int, behind: Boolean): Int =
    if (behind) (width / 2).coerceAtLeast(1) else width

/**
 * Where each frame's time went, rolling over the last [capacity] frames: the
 * UI thread (input, animation, layout, drawing the lists), the sync between
 * the threads, the render thread issuing commands, and the GPU.
 */
internal class FrameSplit(private val capacity: Int = 240) {
    private val ui = LongArray(capacity)
    private val sync = LongArray(capacity)
    private val render = LongArray(capacity)
    private val gpu = LongArray(capacity)
    private val total = LongArray(capacity)
    private var at = 0
    var count = 0
        private set

    fun record(uiNanos: Long, syncNanos: Long, renderNanos: Long, gpuNanos: Long, totalNanos: Long) {
        ui[at] = uiNanos; sync[at] = syncNanos; render[at] = renderNanos; gpu[at] = gpuNanos; total[at] = totalNanos
        at = (at + 1) % capacity
        if (count < capacity) count++
    }

    private fun average(of: LongArray): Double = if (count == 0) 0.0 else (0 until count).sumOf { of[it] } / count.toDouble() / 1e6
    private fun worst(of: LongArray): Double = if (count == 0) 0.0 else (0 until count).maxOf { of[it] } / 1e6

    /** One line for the diagnostics: averages, then the worst frame's parts. */
    fun render(intervalNanos: Long): String {
        if (count == 0) return "Frames: none drawn yet"
        val late = (0 until count).count { total[it] > intervalNanos * 2 }
        return String.format(Locale.US,
            "Frames (last %d): ui %.1f / sync %.1f / render %.1f / gpu %.1f ms avg · worst ui %.1f, render %.1f, gpu %.1f, total %.1f ms · %d late",
            count, average(ui), average(sync), average(render), average(gpu),
            worst(ui), worst(render), worst(gpu), worst(total), late)
    }
}

/**
 * How the render thread is keeping up, frame by frame, from Android's own
 * frame timings. The ink tile cache takes [share] of its usual GPU work, and
 * the workers that read pages in and paint tiles give way while [behind]: a
 * frame budget that saw only the UI thread let a dense page bury the render
 * thread, which is where a page coming on screen spent its time.
 */
internal object RenderLoad {
    @Volatile var share = 1f
        private set
    @Volatile private var behindUntil = 0L
    /** A frame's render thread and GPU ran over within the last moment. */
    val behind: Boolean get() = SystemClock.uptimeMillis() < behindUntil

    private val split = FrameSplit()
    private var interval = 16_666_667L
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    fun start(activity: Activity) {
        if (listener != null) return
        val handlerThread = thread ?: HandlerThread("render-load").also { it.start(); thread = it }
        interval = (1_000_000_000L / DisplayRate.refreshRate(activity)).toLong()
        val watch = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            fun part(id: Int) = metrics.getMetric(id).coerceAtLeast(0L)
            val ui = part(FrameMetrics.INPUT_HANDLING_DURATION) + part(FrameMetrics.ANIMATION_DURATION) +
                part(FrameMetrics.LAYOUT_MEASURE_DURATION) + part(FrameMetrics.DRAW_DURATION)
            val sync = part(FrameMetrics.SYNC_DURATION)
            val render = part(FrameMetrics.COMMAND_ISSUE_DURATION) + part(FrameMetrics.SWAP_BUFFERS_DURATION)
            val gpu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) part(FrameMetrics.GPU_DURATION) else 0L
            split.record(ui, sync, render, gpu, part(FrameMetrics.TOTAL_DURATION))
            val renderSide = sync + render + gpu
            share = nextRenderShare(share, renderSide, interval)
            if (renderSide > interval) behindUntil = SystemClock.uptimeMillis() + BEHIND_HOLD_MS
        }
        runCatching { activity.window.addOnFrameMetricsAvailableListener(watch, Handler(handlerThread.looper)) }
            .onSuccess { listener = watch }
    }

    fun stop(activity: Activity) {
        val watch = listener ?: return
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(watch) }
        listener = null
        share = 1f
        behindUntil = 0L
    }

    fun report(): String = split.render(interval) +
        String.format(Locale.US, " · GPU tile share %.0f%%%s", share * 100f, if (behind) " · render behind" else "")

    private const val BEHIND_HOLD_MS = 300L
}
