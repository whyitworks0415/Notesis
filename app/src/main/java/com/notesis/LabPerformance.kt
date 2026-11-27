package com.notesis

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize

internal const val GLASS_INK_LIVE = 0
internal const val GLASS_INK_AFTER_STROKE = 1
internal const val GLASS_INK_AFTER_PAUSE = 2

/**
 * Whether the page under the glass is recorded where the glass sees it, for an
 * interaction [state]: while a finger moves the page if [followsScroll], while
 * the pen is down only for [GLASS_INK_LIVE], and once the stroke has ended
 * unless the glass waits for a pause.
 */
internal fun glassFollowsPage(state: Interaction, followsScroll: Boolean, ink: Int,
    holdBackdrop: Boolean = false,
): Boolean = !holdBackdrop && when (state) {
        Interaction.MOVING -> followsScroll
        Interaction.WRITING -> ink == GLASS_INK_LIVE
        Interaction.SETTLING -> ink != GLASS_INK_AFTER_PAUSE
        Interaction.IDLE -> false
    }

/** A pause can reuse a recording only if it exists at the current size. */
internal fun shouldRecordGlassBackdrop(recorded: IntSize, requested: IntSize, paused: Boolean): Boolean =
    requested.width > 0 && requested.height > 0 && (!paused || recorded != requested)

/** A visible quality tradeoff, saved only inside this separate installation. */
internal object LabPerformance {
    var efficientGlass by mutableStateOf(false)
        private set

    /**
     * Whether the glass is drawn the cheaper way now: chosen, or the tablet
     * is running warm. One sampling pass instead of two, and a smaller blur;
     * it goes back by itself once the tablet has cooled (see [ThermalHold]).
     */
    val efficientNow: Boolean get() = efficientGlass || InteractionGovernor.heatStep >= 1

    /**
     * Whether the glass follows the page while a finger moves it. Off, it
     * keeps the picture it had until the page comes to rest, as it always does
     * while the pen writes: no glass is drawn again for each frame of a scroll.
     * Read on the UI thread as the page is drawn, so a plain field.
     */
    @Volatile var glassFollowsScroll = true
        private set
    var glassFollowsScrollShown by mutableStateOf(true)
        private set

    fun setGlassFollowsScroll(context: Context, enabled: Boolean) {
        glassFollowsScroll = enabled
        glassFollowsScrollShown = enabled
        context.getSharedPreferences("lab", Context.MODE_PRIVATE).edit().putBoolean("glassFollowsScroll", enabled).apply()
    }

    /**
     * When the glass shows ink just written or erased: [GLASS_INK_LIVE] as the
     * pen goes (the glass drawn again for every change, the heaviest),
     * [GLASS_INK_AFTER_STROKE] once each stroke or erase ends, or
     * [GLASS_INK_AFTER_PAUSE] once the pen has rested. Read as the page is drawn.
     */
    @Volatile var glassInk = GLASS_INK_AFTER_STROKE
        private set
    var glassInkShown by mutableStateOf(GLASS_INK_AFTER_STROKE)
        private set

    fun setGlassInk(context: Context, mode: Int) {
        val m = mode.coerceIn(GLASS_INK_LIVE, GLASS_INK_AFTER_PAUSE)
        glassInk = m
        glassInkShown = m
        context.getSharedPreferences("lab", Context.MODE_PRIVATE).edit().putInt("glassInk", m).apply()
    }

    /** Hint the system about the pen's threads while writing (see [WritingBoost]); on until compared. */
    var writingBoost by mutableStateOf(true)
        private set

    /**
     * Show the wet ink through buffers of our own and a surface transaction
     * instead of waiting for the surface's canvas (see [NativePresenter]).
     * An experiment: off until it is tried on the tablet.
     */
    var nativePresent by mutableStateOf(false)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("lab", Context.MODE_PRIVATE)
        // Earlier Labs enabled simplified optics by default. Restore the standard appearance once;
        // a reader can explicitly opt back into the cheaper renderer after this upgrade.
        if (!prefs.getBoolean("originalChromeV1", false)) {
            prefs.edit().putBoolean("efficientGlass", false).putBoolean("originalChromeV1", true).apply()
        }
        efficientGlass = prefs.getBoolean("efficientGlass", false)
        glassFollowsScroll = prefs.getBoolean("glassFollowsScroll", true)
        glassInk = prefs.getInt("glassInk", GLASS_INK_AFTER_STROKE).coerceIn(GLASS_INK_LIVE, GLASS_INK_AFTER_PAUSE)
        glassInkShown = glassInk
        glassFollowsScrollShown = glassFollowsScroll
        writingBoost = prefs.getBoolean("writingBoost", true)
        nativePresent = prefs.getBoolean("nativePresent", false)
    }

    fun setWritingBoost(context: Context, enabled: Boolean) {
        writingBoost = enabled
        context.getSharedPreferences("lab", Context.MODE_PRIVATE).edit().putBoolean("writingBoost", enabled).apply()
    }

    fun setNativePresent(context: Context, enabled: Boolean) {
        nativePresent = enabled
        context.getSharedPreferences("lab", Context.MODE_PRIVATE).edit().putBoolean("nativePresent", enabled).apply()
    }

    fun setEfficientGlass(context: Context, enabled: Boolean) {
        efficientGlass = enabled
        context.getSharedPreferences("lab", Context.MODE_PRIVATE).edit().putBoolean("efficientGlass", enabled).apply()
    }
}

/**
 * Keeps the Lab's native code from taking the app down every time it starts.
 *
 * A fault in native code ends the process with nothing caught. The app notes,
 * synchronously, when it comes to the front and when it leaves cleanly; a
 * start that finds the last run never left cleanly counts one, and after
 * [UNCLEAN_LIMIT] in a row the native paths - the packed search tree, the
 * erase geometry, the own-buffer display - are left unloaded and the Kotlin
 * ones do the work, until the reader switches them back on in the diagnostics.
 * A crash anywhere counts, native or not: this is a safe mode, not a verdict.
 */
internal object LabGuard {
    private val run = LabRunGuard()
    val uncleanRuns: Int get() = run.uncleanRuns
    val nativeOff: Boolean get() = run.nativeOff
    fun load(context: Context) = run.load(context)
    fun entered(context: Context) = run.entered(context)
    fun left(context: Context) = run.left(context)
    fun reset(context: Context) = run.reset(context)
}

/** One instance per process; recreating an Activity is not another process start. */
internal class LabRunGuard {
    private var loaded = false
    var uncleanRuns by mutableStateOf(0)
        private set

    /** Native code is left out for this run. */
    private var heldForRun by mutableStateOf(false)
    val nativeOff: Boolean get() = heldForRun

    private fun prefs(context: Context) = context.getSharedPreferences("lab", Context.MODE_PRIVATE)

    /** The app is starting: read how the last runs ended. Before any native library is loaded. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        uncleanRuns = nextUncleanRuns(p.getInt(KEY_UNCLEAN, 0), p.getBoolean(KEY_OPEN, false))
        heldForRun = nativeHeldBack(uncleanRuns)
        // Written before anything else can go wrong; the count stands if this run dies too.
        p.edit().putInt(KEY_UNCLEAN, uncleanRuns).putBoolean(KEY_OPEN, true).commit()
    }

    /** In front of the reader: a run that ends from here without [left] did not end cleanly. */
    fun entered(context: Context) {
        prefs(context).edit().putBoolean(KEY_OPEN, true).commit()
    }

    /** Stopped as it should be. A run that got this far also clears the count. */
    fun left(context: Context) {
        val p = prefs(context).edit().putBoolean(KEY_OPEN, false)
        if (!nativeOff) {
            uncleanRuns = 0
            p.putInt(KEY_UNCLEAN, 0)
        }
        p.apply()
    }

    /** The reader asks for the native paths again; they load at the next start. */
    fun reset(context: Context) {
        uncleanRuns = 0
        prefs(context).edit().putInt(KEY_UNCLEAN, 0).putBoolean(KEY_OPEN, false).apply()
    }

    private companion object {
        const val KEY_OPEN = "sessionOpen"
        const val KEY_UNCLEAN = "uncleanRuns"
    }
}

/** How many runs in a row ended without leaving cleanly, given the count before and how the last one ended. */
internal fun nextUncleanRuns(before: Int, lastRunLeftOpen: Boolean): Int =
    if (lastRunLeftOpen) (before.coerceIn(0, 99) + 1).coerceAtMost(99) else before.coerceIn(0, 99)

/** Whether native code stays unloaded after [uncleanRuns] such runs. */
internal fun nativeHeldBack(uncleanRuns: Int): Boolean = uncleanRuns >= UNCLEAN_LIMIT

internal const val UNCLEAN_LIMIT = 2

/** This is the separate Lab installation, whose testers are the ones the diagnostics are for. */
internal fun isLabInstall(context: Context): Boolean = context.packageName.endsWith(".lab")

internal fun labBlurDp(requested: Float, efficient: Boolean): Float =
    if (efficient) requested.coerceIn(0f, 12f) else requested

/** A suffix, not just a prefix: the original app accepts any tag ending in a numeric code. */
internal fun isLabReleaseTag(tag: String): Boolean =
    Regex("lab-v.+-\\d+-preview").matches(tag)

/** Stop abandoned geometry jobs between strokes without interrupting the native library. */
internal fun <T, R : Any> mapWhileWanted(items: List<T>, wanted: () -> Boolean, build: (T) -> R?): List<R> {
    val result = ArrayList<R>(items.size)
    for (item in items) {
        if (!wanted()) break
        build(item)?.let(result::add)
    }
    return result
}
