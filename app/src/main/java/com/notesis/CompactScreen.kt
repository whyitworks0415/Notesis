package com.notesis

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
