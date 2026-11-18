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
        /**
         * Long enough to span the gap between two handwritten strokes, and
         * between two words. At 350 the pause between words ran out: the
         * page was recorded again for the glass and the deferred work woke,
         * the heaviest frames there are, just as the next word began.
         */
        const val SETTLE_MS = 700L
        /** In power saving the pause is longer, so deferred work trickles back rather than bursts. */
        const val SETTLE_POWER_SAVE_MS = 1000L
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

/**
 * What heat takes away, from PowerManager's thermal status: 0 nothing; 1 the
 * decorations (motion blur, the lens's travel); 2 the measures of saving
 * power as well (plain glass, no fastest refresh rate asked for, less work).
 */
internal fun thermalStep(status: Int): Int = when {
    status >= THERMAL_SEVERE -> 2
    status >= THERMAL_MODERATE -> 1
    else -> 0
}

/**
 * Holds a [thermalStep] once reached until the tablet has reported cooler for
 * [cooldownMs]: up at once, down only after the cooldown, so the quality does
 * not swing on and off round a threshold.
 */
internal class ThermalHold(private val cooldownMs: Long = 30_000L) {
    var step = 0
        private set
    private var coolerSince = -1L

    /** The step in force at [nowMs] for a reported [status]. */
    fun update(status: Int, nowMs: Long): Int {
        val wanted = thermalStep(status)
        if (wanted >= step) {
            step = wanted
            coolerSince = -1L
        } else {
            if (coolerSince < 0L) coolerSince = nowMs
            if (nowMs - coolerSince >= cooldownMs) {
                step = wanted
                coolerSince = -1L
            }
        }
        return step
    }

    /** How long until a held step may come down, or 0 when nothing is held. */
    fun remainingMs(nowMs: Long): Long =
        if (coolerSince < 0L) 0L else (cooldownMs - (nowMs - coolerSince)).coerceAtLeast(0L)
}

internal const val THERMAL_SEVERE = 3

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
            highRefresh = it.getBoolean(KEY_HIGH_REFRESH, true)
            slowDevice = DeviceClass.isSlow(app)
            lightMode = it.getBoolean(KEY_LIGHT_MODE, slowDevice)
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
                followHeat()
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

    /** The heat step in force (see [thermalStep]), held through a cooldown. */
    var heatStep by mutableStateOf(0)
        private set
    private val heat = ThermalHold()
    private val heatCheck = Runnable { followHeat() }

    private fun followHeat() {
        val now = SystemClock.uptimeMillis()
        val step = heat.update(thermalStatus, now)
        main.removeCallbacks(heatCheck)
        // Held through its cooldown: looked at again when that runs out.
        heat.remainingMs(now).takeIf { it > 0L }?.let { main.postDelayed(heatCheck, it + 50L) }
        if (step == heatStep) return
        heatStep = step
        followPowerSave()
        publish()
    }

    /**
     * Whether the decorations - motion blur and the like - may be drawn: not
     * while saving power, running light, or warm. What the person chose in
     * the settings is kept; this is only what is applied now.
     */
    val decorationsAllowed: Boolean get() = !powerSave && heatStep == 0

    /** Why the decorations are off for now, in words for the settings, or null when they are allowed. */
    val decorationsHeldBy: String? get() = when {
        lightMode -> "Light mode"
        systemPowerSave && batteryOptimization -> "battery saver"
        heatStep > 0 -> "the tablet running warm"
        else -> null
    }

    private fun followPowerSave() {
        powerSave = (systemPowerSave && batteryOptimization) || lightMode || heatStep >= 2
        observedPowerSave = powerSave
        applySettle()
    }

    private const val KEY_BATTERY_OPTIMIZATION = "batteryOptimization"
    private const val KEY_HIGH_REFRESH = "highRefresh"
    private const val KEY_LIGHT_MODE = "lightMode"

    /** Whether this device was found to be a slower one; see [DeviceClass]. */
    var slowDevice = false
        private set

    /**
     * Running light all the time, whatever battery saver says: the same
     * measures as saving power - plain solid glass, a still lens, fewer
     * pages read ahead, less background work - for a tablet that cannot
     * keep the full effects and the pen at speed together. On by default
     * where [slowDevice].
     */
    var lightMode by mutableStateOf(false)
        private set

    fun updateLightMode(on: Boolean) {
        if (lightMode == on) return
        lightMode = on
        prefs?.edit()?.putBoolean(KEY_LIGHT_MODE, on)?.apply()
        followPowerSave()
        publish()
    }

    /** Whether the app asks the display for its fastest refresh rate; see [DisplayRate]. */
    var highRefresh by mutableStateOf(true)
        private set

    fun updateHighRefresh(on: Boolean) {
        if (highRefresh == on) return
        highRefresh = on
        prefs?.edit()?.putBoolean(KEY_HIGH_REFRESH, on)?.apply()
    }

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
