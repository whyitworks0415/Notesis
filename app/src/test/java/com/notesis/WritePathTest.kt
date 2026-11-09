package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritePathTest {
    @Test fun `the hint's target is a share of the display's frame, within sane rates`() {
        assertEquals(1e9 / 60.0 * HINT_TARGET_SHARE, hintTargetNanos(60f).toDouble(), 2.0)
        assertTrue(hintTargetNanos(120f) < hintTargetNanos(60f))
        // An unknown or absurd rate never gives a target of nothing or of a second.
        assertEquals(hintTargetNanos(30f), hintTargetNanos(0f))
        assertEquals(hintTargetNanos(240f), hintTargetNanos(10_000f))
        assertTrue(HINT_TARGET_SHARE in 0.1..0.9)
    }

    @Test fun `the new write-path switches start where the Lab means them to`() {
        // The hint is on until it is compared; the own-buffer experiment is off until it is tried.
        assertTrue(LabPerformance.writingBoost)
        assertFalse(LabPerformance.nativePresent)
    }

    @Test fun `the own-buffer states are named for the diagnostics`() {
        assertEquals(4, OwnBufferState.entries.size)
        assertTrue(OwnBufferState.entries.all { it.label.isNotBlank() })
        assertEquals("off", OwnBufferState.OFF.label)
    }

    @Test fun `the pressure rewrite is a metric of its own`() {
        val stats = LatencyStats()
        stats.setEnabled(true)
        stats.record(PerformanceMetric.PRESSURE_CURVE, 40_000L)
        assertTrue(stats.render(0).contains("pressure rewrite"))
    }
}
