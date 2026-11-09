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
