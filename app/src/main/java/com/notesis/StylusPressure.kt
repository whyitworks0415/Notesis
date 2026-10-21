package com.notesis

import android.view.MotionEvent

/**
 * The pressure given to input that has none worth reading: a finger, a mouse,
 * or a pen without a pressure sensor. Mid-range, so a pressure brush draws a
 * steady line at about its set width instead of following contact area or a
 * switch that only knows touching from not.
 */
internal const val NEUTRAL_PRESSURE = 0.5f

/**
 * Whether a stroke starting with this down sample carries real pressure.
 *
 * Only a pen tip or its eraser end measures force. A finger's "pressure" on
 * most panels is its contact area, which swells and shrinks as the finger
 * rolls, and a pressure brush drew that as a line that throbbed. A mouse or a
 * basic stylus reports a flat 0 or 1. [hasPressureAxis] is what the input
 * device says about its pressure axis, null when the device cannot be asked.
 * A pen landing at exactly full force is a switch reporting on/off, not a
 * sensor: a real tip ramps up from almost nothing.
 */
internal fun reportsRealPressure(toolType: Int, hasPressureAxis: Boolean?, downPressure: Float): Boolean {
    if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) return false
    if (hasPressureAxis == false) return false
    return downPressure < 1f
}

/** [raw] as a pressure brush should see it: clamped when it is real, [NEUTRAL_PRESSURE] when it is not. */
internal fun normalizedPressure(raw: Float, realPressure: Boolean): Float =
    if (realPressure && !raw.isNaN()) raw.coerceIn(0f, 1f) else NEUTRAL_PRESSURE
