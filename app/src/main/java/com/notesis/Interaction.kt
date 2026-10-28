package com.notesis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the person is doing with the canvases right now. Background work reads
 * it to know how much room it has: ink first while a pen writes, the picture
 * moving under the fingers while they scroll or pinch, then a short pause
 * before anything waiting is let back in, then idle.
 */
internal enum class Interaction { WRITING, MOVING, SETTLING, IDLE }

/**
 * The bookkeeping behind [InteractionGovernor], kept free of Android so it can
 * be tested. Every canvas (the note, the reference panel, a lasso preview) is
 * its own owner: one canvas lifting its pen, or being detached, never ends the
 * stroke another one is still drawing.
 */
internal class InteractionTracker(
    /** How long after the last pen lift or finger release the state stays [Interaction.SETTLING]. */
    var settleMs: Long = SETTLE_MS,
) {
    private val writers = HashSet<Any>()
    private val movers = HashSet<Any>()
    private var lastEnd = Long.MIN_VALUE / 2

    fun begin(owner: Any, writing: Boolean) {
        if (writing) writers += owner else movers += owner
    }

    /** Ends what [owner] was doing; true if that was the last one, so the pause starts now. */
    fun end(owner: Any, writing: Boolean, now: Long): Boolean {
        val removed = if (writing) writers.remove(owner) else movers.remove(owner)
        if (!removed) return false
        if (writers.isEmpty() && movers.isEmpty()) {
            lastEnd = now
            return true
        }
        return false
    }

    /** [owner] went away mid-gesture: whatever it held is let go, nobody else's. */
    fun forget(owner: Any, now: Long) {
        val held = writers.remove(owner) or movers.remove(owner)
        if (held && writers.isEmpty() && movers.isEmpty()) lastEnd = now
    }

    val writing: Boolean get() = writers.isNotEmpty()
    val moving: Boolean get() = movers.isNotEmpty()

    fun state(now: Long): Interaction = when {
        writers.isNotEmpty() -> Interaction.WRITING
        movers.isNotEmpty() -> Interaction.MOVING
        now - lastEnd < settleMs -> Interaction.SETTLING
        else -> Interaction.IDLE
    }

    /** How long nothing has been touched, or 0 while something is. */
    fun quietFor(now: Long): Long =
        if (writers.isNotEmpty() || movers.isNotEmpty()) 0L else (now - lastEnd).coerceAtLeast(0L)

    companion object {
        /** Long enough to span the gap between two handwritten strokes. */
        const val SETTLE_MS = 350L
        /** In power saving the pause is longer, so deferred work trickles back rather than bursts. */
        const val SETTLE_POWER_SAVE_MS = 600L
    }
}

/**
 * How many threads a kind of background work may use, given what is going on.
 * [cores] is what the work would take with the device to itself. Pure, so the
 * table can be tested.
 */
internal fun backgroundBudget(cores: Int, state: Interaction, powerSave: Boolean, thermalStatus: Int): Int {
    val full = cores.coerceAtLeast(1)
    val hot = thermalStatus >= THERMAL_MODERATE
    val cap = when {
        state == Interaction.WRITING || state == Interaction.MOVING -> 1
        hot -> 1
        powerSave -> 2
        state == Interaction.SETTLING -> 2
        else -> full
    }
    return minOf(full, cap)
}

/** PowerManager.THERMAL_STATUS_MODERATE, kept here so the pure budget needs no Android. */
internal const val THERMAL_MODERATE = 2

/**
 * One place for what every background job asks before it runs: is a pen
 * writing, are fingers moving the page, is the device in battery saver or hot,
 * how fast is the screen. Main-thread writes, any-thread reads.
 *
 * It never changes a setting the person chose. Power saving and heat only
 * narrow how much background work runs at once; when they go away the work
 * simply has its room back.
 */
internal object InteractionGovernor {
    private val tracker = InteractionTracker()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Interaction) -> Unit>()

    /** The last state handed out, readable from any thread. */
    @Volatile var state: Interaction = Interaction.IDLE
        private set

    /**
     * Battery saver as the app follows it: on only while the system's is on
     * and [batteryOptimization] lets the app save with it.
     */
    @Volatile var powerSave: Boolean = false
        private set

    /** The system's battery saver, whatever the app makes of it. */
    var systemPowerSave by mutableStateOf(false)
        private set

    /**
     * Whether the app saves power with the system's battery saver: plainer
     * glass, no lens motion, fewer pages read ahead. Off, battery saver
     * changes nothing in the app; the system's own limits still apply.
     */
    var batteryOptimization by mutableStateOf(true)
        private set

    private var prefs: android.content.SharedPreferences? = null

    /** PowerManager's thermal status, NONE (0) until it reports. */
    @Volatile var thermalStatus: Int = 0
        private set

    /** The display's refresh rate as last seen by a canvas; 60 until one says otherwise. */
    @Volatile var refreshHz: Float = 60f
        private set

    /** For Compose: the state as snapshot state, for the diagnostics readout and decorative effects. */
    var observed by mutableStateOf(Interaction.IDLE)
        private set
    var observedPowerSave by mutableStateOf(false)
        private set

    private var initialized = false

    /** Hooks up the battery saver and thermal reports once; safe to call from every canvas. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        prefs = app.getSharedPreferences("pens", Context.MODE_PRIVATE).also {
            batteryOptimization = it.getBoolean(KEY_BATTERY_OPTIMIZATION, true)
        }
        systemPowerSave = power?.isPowerSaveMode == true
        followPowerSave()
        runCatching {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    systemPowerSave = power?.isPowerSaveMode == true
                    followPowerSave()
                    publish()
                }
            }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        }
        runCatching {
            power?.addThermalStatusListener(app.mainExecutor) { status ->
                thermalStatus = status
                StrokeBuilder.thermalThrottle = status >= THERMAL_MODERATE
                publish()
            }
        }
    }

    /** Lets the app save power with battery saver, or keeps it as it is whatever battery saver says. */
    fun updateBatteryOptimization(on: Boolean) {
        if (batteryOptimization == on) return
        batteryOptimization = on
        prefs?.edit()?.putBoolean(KEY_BATTERY_OPTIMIZATION, on)?.apply()
        followPowerSave()
        publish()
    }

    private fun followPowerSave() {
        powerSave = systemPowerSave && batteryOptimization
        observedPowerSave = powerSave
        applySettle()
    }

    private const val KEY_BATTERY_OPTIMIZATION = "batteryOptimization"

    fun noteRefreshRate(hz: Float?) {
        if (hz != null && hz.isFinite() && hz >= 20f) refreshHz = hz
    }

    /** One frame at the current refresh rate, in milliseconds. */
    val frameMs: Float get() = 1000f / refreshHz

    fun beginWriting(owner: Any) = change { tracker.begin(owner, writing = true) }
    fun endWriting(owner: Any) = change { tracker.end(owner, writing = true, SystemClock.uptimeMillis()) }
    fun beginMoving(owner: Any) = change { tracker.begin(owner, writing = false) }
    fun endMoving(owner: Any) = change { tracker.end(owner, writing = false, SystemClock.uptimeMillis()) }

    /** [owner] is going away; only what it held is released. */
    fun forget(owner: Any) = change { tracker.forget(owner, SystemClock.uptimeMillis()) }

    val writing: Boolean get() = state == Interaction.WRITING

    /** Milliseconds since the last pen lift or finger release; 0 while one is down. */
    fun quietForMs(): Long = synchronized(tracker) { tracker.quietFor(SystemClock.uptimeMillis()) }

    /** Threads a job that would use [cores] on an idle device may use now. */
    fun budget(cores: Int): Int = backgroundBudget(cores, state, powerSave, thermalStatus)

    /**
     * Whether optional work - prefetch of pages around, mesh refinement of
     * what is already drawn, handwriting recognition, thumbnails - may start
     * now. [quietMs] is how long the hands must have been off for it.
     */
    fun mayRunOptional(quietMs: Long = 0L): Boolean =
        state == Interaction.IDLE && quietForMs() >= quietMs

    /** Called on the main thread on every change of [state]. */
    fun addListener(listener: (Interaction) -> Unit) { listeners += listener }
    fun removeListener(listener: (Interaction) -> Unit) { listeners -= listener }

    private val settleCheck = Runnable { publish() }

    private inline fun change(block: () -> Unit) {
        synchronized(tracker) { block() }
        if (Looper.myLooper() == Looper.getMainLooper()) publish() else main.post { publish() }
    }

    private fun applySettle() {
        synchronized(tracker) {
            tracker.settleMs = if (powerSave) InteractionTracker.SETTLE_POWER_SAVE_MS else InteractionTracker.SETTLE_MS
        }
    }

    private fun publish() {
        val now = SystemClock.uptimeMillis()
        val next = synchronized(tracker) { tracker.state(now) }
        StrokeBuilder.penActive = next == Interaction.WRITING
        main.removeCallbacks(settleCheck)
        if (next == Interaction.SETTLING) {
            val left = synchronized(tracker) { tracker.settleMs - tracker.quietFor(now) }
            main.postDelayed(settleCheck, left.coerceAtLeast(1L))
        }
        if (next == state) return
        state = next
        observed = next
        for (listener in listeners) runCatching { listener(next) }
    }
}
