package com.notesis

import android.app.Activity
import android.os.Build
import android.view.Display

/**
 * Asks the display for its fastest refresh rate while the app is in front.
 *
 * Some phones run an app at 60 Hz unless the app asks for more: the system
 * leaves the choice to a list of apps it knows, and an app not on it gets
 * the slow mode however fast the panel is. Scrolling then looks like 60 Hz
 * on a 120 Hz screen. Naming the fastest mode at the current resolution in
 * the window's attributes is the request every such system honours.
 *
 * Off with [InteractionGovernor.highRefresh], and while the app saves power
 * with battery saver, the window asks for nothing and the system picks.
 */
internal object DisplayRate {
    fun apply(activity: Activity) {
        val window = activity.window ?: return
        val display = displayOf(activity) ?: return
        val wanted = if (InteractionGovernor.highRefresh && !InteractionGovernor.powerSave) fastestMode(display) else 0
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId == wanted) return
        attributes.preferredDisplayModeId = wanted
        window.attributes = attributes
    }

    /** The fastest mode at the resolution in use, or 0 when there is no choice to make. */
    private fun fastestMode(display: Display): Int = runCatching {
        val current = display.mode
        display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }?.modeId ?: 0
    }.getOrDefault(0)

    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) runCatching { activity.display }.getOrNull()
        else activity.windowManager.defaultDisplay
}
