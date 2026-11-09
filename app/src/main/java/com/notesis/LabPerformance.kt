package com.notesis

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A visible quality tradeoff, saved only inside this separate installation. */
internal object LabPerformance {
    var efficientGlass by mutableStateOf(true)
        private set

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
        efficientGlass = prefs.getBoolean("efficientGlass", true)
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
    var uncleanRuns by mutableStateOf(0)
        private set

    /** Native code is left out for this run. */
    val nativeOff: Boolean get() = nativeHeldBack(uncleanRuns)

    private fun prefs(context: Context) = context.getSharedPreferences("lab", Context.MODE_PRIVATE)

    /** The app is starting: read how the last runs ended. Before any native library is loaded. */
    fun load(context: Context) {
        val p = prefs(context)
        uncleanRuns = nextUncleanRuns(p.getInt(KEY_UNCLEAN, 0), p.getBoolean(KEY_OPEN, false))
        // Written before anything else can go wrong; the count stands if this run dies too.
        p.edit().putInt(KEY_UNCLEAN, uncleanRuns).putBoolean(KEY_OPEN, false).commit()
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

    private const val KEY_OPEN = "sessionOpen"
    private const val KEY_UNCLEAN = "uncleanRuns"
}

/** How many runs in a row ended without leaving cleanly, given the count before and how the last one ended. */
internal fun nextUncleanRuns(before: Int, lastRunLeftOpen: Boolean): Int =
    if (lastRunLeftOpen) (before.coerceAtLeast(0) + 1).coerceAtMost(99) else before.coerceAtLeast(0)

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
