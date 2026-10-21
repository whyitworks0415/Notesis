package com.notesis

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusPressureTest {
    @Test
    fun `a pen tip with a pressure axis is real pressure`() {
        assertTrue(reportsRealPressure(MotionEvent.TOOL_TYPE_STYLUS, true, 0.12f))
        assertTrue(reportsRealPressure(MotionEvent.TOOL_TYPE_ERASER, true, 0.3f))
        // A device that cannot be asked is given the benefit of the doubt.
        assertTrue(reportsRealPressure(MotionEvent.TOOL_TYPE_STYLUS, null, 0.2f))
    }

    @Test
    fun `fingers, mice and pens without a sensor are not`() {
        assertFalse(reportsRealPressure(MotionEvent.TOOL_TYPE_FINGER, true, 0.4f))
        assertFalse(reportsRealPressure(MotionEvent.TOOL_TYPE_MOUSE, true, 1f))
        assertFalse(reportsRealPressure(MotionEvent.TOOL_TYPE_UNKNOWN, null, 0.5f))
        assertFalse(reportsRealPressure(MotionEvent.TOOL_TYPE_STYLUS, false, 0.4f))
    }

    @Test
    fun `a pen landing at exactly full force is a switch`() {
        assertFalse(reportsRealPressure(MotionEvent.TOOL_TYPE_STYLUS, true, 1f))
    }

    @Test
    fun `without real pressure every sample gets the same width`() {
        val samples = listOf(0f, 0.07f, 0.93f, 1f, 1.4f, Float.NaN)
        for (raw in samples) assertEquals(NEUTRAL_PRESSURE, normalizedPressure(raw, false), 0f)
    }

    @Test
    fun `real pressure is kept and clamped`() {
        assertEquals(0.42f, normalizedPressure(0.42f, true), 0f)
        assertEquals(1f, normalizedPressure(1.3f, true), 0f)
        assertEquals(0f, normalizedPressure(-0.2f, true), 0f)
        assertEquals(NEUTRAL_PRESSURE, normalizedPressure(Float.NaN, true), 0f)
    }
}
