package com.notesis

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The last [capacity] frames' lengths, and how many of them ran late: over
 * twice the display's frame time, which the eye sees as a stutter.
 */
internal class JankWindow(private val capacity: Int = 300) {
    private val late = BooleanArray(capacity)
    private var at = 0
    var count = 0
        private set
    private var lateCount = 0

    fun record(frameNanos: Long, intervalNanos: Long) {
        val isLate = frameNanos > intervalNanos * 2
        if (count == capacity) {
            if (late[at]) lateCount--
        } else count++
        late[at] = isLate
        if (isLate) lateCount++
        at = (at + 1) % capacity
    }

    /** The share of the frames held that ran late, once the window is full; 0 before. */
    fun lateShare(): Float = if (count < capacity) 0f else lateCount.toFloat() / capacity

    fun clear() {
        late.fill(false); at = 0; count = 0; lateCount = 0
    }
}

/**
 * Whether to suggest light mode: a full window of frames in which at least
 * [threshold] ran late, or the tablet running hot, while light mode is off
 * and the question has not been asked on this tablet before.
 */
internal fun shouldSuggestLightMode(lateShare: Float, hot: Boolean, lightMode: Boolean, asked: Boolean,
                                    threshold: Float = 0.25f): Boolean =
    !lightMode && !asked && (lateShare >= threshold || hot)

/**
 * Watches the frames the app draws - only those it draws, so an idle screen
 * costs nothing - and, if the tablet cannot keep up, asks once whether to
 * run light. A device the app does not know as slow is found this way.
 */
internal object JankWatch {
    /** Set when the question should be put; the screen shows it and calls [answered]. */
    var suggest by mutableStateOf(false)
        private set

    private val window = JankWindow()
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    /** Frames after coming to the front that are not counted: the first ones build the screen. */
    private var skip = 0
    private var sinceCheck = 0

    private fun prefs(context: Context) = context.getSharedPreferences("pens", Context.MODE_PRIVATE)

    fun start(activity: Activity) {
        if (listener != null) return
        if (InteractionGovernor.lightMode || prefs(activity).getBoolean(KEY_ASKED, false)) return
        val handlerThread = thread ?: HandlerThread("frame-watch").also { it.start(); thread = it }
        val interval = (1_000_000_000L / DisplayRate.refreshRate(activity)).toLong()
        window.clear()
        skip = SETTLE_FRAMES
        val main = Handler(activity.mainLooper)
        val power = activity.getSystemService(PowerManager::class.java)
        val watch = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (skip > 0) { skip--; return@OnFrameMetricsAvailableListener }
            window.record(metrics.getMetric(FrameMetrics.TOTAL_DURATION), interval)
            // Looked at every second or so of drawing, once the window is full.
            if (++sinceCheck < CHECK_EVERY || window.lateShare() == 0f) return@OnFrameMetricsAvailableListener
            sinceCheck = 0
            val hot = (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE
            if (shouldSuggestLightMode(window.lateShare(), hot, InteractionGovernor.lightMode, false)) {
                main.post { if (!suggest) suggest = true }
            }
        }
        runCatching { activity.window.addOnFrameMetricsAvailableListener(watch, Handler(handlerThread.looper)) }
            .onSuccess { listener = watch }
    }

    fun stop(activity: Activity) {
        val watch = listener ?: return
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(watch) }
        listener = null
    }

    /** The question was answered, either way: it is not asked again on this tablet. */
    fun answered(activity: Activity, turnOn: Boolean) {
        suggest = false
        prefs(activity).edit().putBoolean(KEY_ASKED, true).apply()
        if (turnOn) InteractionGovernor.updateLightMode(true)
        stop(activity)
    }

    private const val KEY_ASKED = "jankAsked"
    private const val SETTLE_FRAMES = 120
    private const val CHECK_EVERY = 60
}
