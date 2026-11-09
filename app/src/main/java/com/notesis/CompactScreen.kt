package com.notesis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density

/**
 * The largest font scale the app's own controls are laid out for. The
 * system's font size is followed up to here, which is already past
 * Android's "Large"; beyond it, bars, chips and panel rows that keep their
 * height cut their words off, so the app stops there.
 */
internal const val MAX_FONT_SCALE = 1.3f

/** [system] as it is, or with its font scale brought down to [MAX_FONT_SCALE]. */
internal fun cappedFontScale(system: Density): Density =
    if (system.fontScale <= MAX_FONT_SCALE) system else Density(system.density, MAX_FONT_SCALE)

/**
 * How big the whole app's chrome and its words are drawn, as the reader sets
 * it: 1 is the system's own size. Every dp and sp is scaled together, so the
 * layout keeps its proportions; the page itself is drawn by the canvas and
 * keeps its own zoom. Kept between runs.
 */
internal object UiScale {
    private var prefs: android.content.SharedPreferences? = null

    const val MIN = 0.5f
    const val MAX = 1.5f
    const val STEP = 0.05f
    const val DEFAULT = 1f

    var scale by androidx.compose.runtime.mutableFloatStateOf(DEFAULT)
        private set

    fun init(context: android.content.Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("pens", android.content.Context.MODE_PRIVATE)
        prefs = p
        scale = clampUiScale(p.getFloat(KEY, DEFAULT))
    }

    fun choose(value: Float) {
        scale = clampUiScale(value)
        prefs?.edit()?.putFloat(KEY, scale)?.apply()
    }

    private const val KEY = "uiScale"
}

/** [value] in whole [UiScale.STEP]s within the slider's range; anything unreadable is the default. */
internal fun clampUiScale(value: Float): Float =
    if (!value.isFinite()) UiScale.DEFAULT
    else (kotlin.math.round(value / UiScale.STEP) * UiScale.STEP).coerceIn(UiScale.MIN, UiScale.MAX)

/** [system], its font scale capped (see [cappedFontScale]), drawn at [scale]: dp and sp grow and shrink together. */
internal fun scaledDensity(system: Density, scale: Float): Density {
    val capped = cappedFontScale(system)
    val s = scale.coerceIn(UiScale.MIN, UiScale.MAX)
    return if (s == 1f) capped else Density(capped.density * s, capped.fontScale)
}
