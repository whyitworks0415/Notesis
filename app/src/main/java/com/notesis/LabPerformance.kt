package com.notesis

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A visible quality tradeoff, saved only inside this separate installation. */
internal object LabPerformance {
    var efficientGlass by mutableStateOf(true)
        private set
    fun load(context: Context) {
        efficientGlass = context.getSharedPreferences("lab", Context.MODE_PRIVATE).getBoolean("efficientGlass", true)
    }
    fun setEfficientGlass(context: Context, enabled: Boolean) {
        efficientGlass = enabled
        context.getSharedPreferences("lab", Context.MODE_PRIVATE).edit().putBoolean("efficientGlass", enabled).apply()
    }
}

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
