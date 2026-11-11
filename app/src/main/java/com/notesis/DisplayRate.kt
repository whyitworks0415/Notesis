package com.notesis

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
        // Sharing the screen with another app and not the one in use - another
        // app's pop-up over it, say - it asks for nothing: the window in use
        // decides, and a fast mode held for a window nobody is touching only
        // made the one being used stutter.
        val idleBeside = activity.isInMultiWindowMode && !WindowFocus.focused
        val wanted = if (InteractionGovernor.highRefresh && !InteractionGovernor.powerSave && !idleBeside) fastestMode(display) else 0
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

    /**
     * The screen's refresh rate, or 60 when it cannot be read. Activity.display
     * is Android 11's: called bare it threw NoSuchMethodError on Android 10,
     * which took the app down from the frame watch and the motion settings.
     */
    fun refreshRate(activity: Activity): Float =
        displayOf(activity)?.refreshRate?.takeIf { it.isFinite() && it >= 1f } ?: 60f

    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) runCatching { activity.display }.getOrNull()
        else activity.windowManager.defaultDisplay
}

/** Whether the app's window has input focus: false while another app's window, a pop-up say, is the one in use. */
internal object WindowFocus {
    var focused by androidx.compose.runtime.mutableStateOf(true)
}
