package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSliderTest {
    @Test fun `the ends of the slider are one minute and three hours`() {
        assertEquals(1L, timerSliderMinutes(0f))
        assertEquals(TIMER_SLIDER_MAX_MINUTES, timerSliderMinutes(1f))
    }

    @Test fun `the first quarter of the track goes minute by minute`() {
        val seen = (0..250).map { timerSliderMinutes(it / 1000f) }.toSet()
        // Every whole minute up to about a quarter of an hour can be stopped on.
        for (m in 1L..11L) assertTrue("$m min", m in seen)
    }

    @Test fun `past the hour it moves in fives`() {
        for (i in 0..1000) {
            val m = timerSliderMinutes(i / 1000f)
            if (m > 60) assertEquals(0L, m % 5)
        }
    }

    @Test fun `a length goes back to where it is on the track`() {
        for (m in listOf(1L, 5L, 25L, 45L, 90L, 180L)) {
            assertEquals(m, timerSliderMinutes(timerSliderPosition(m)))
        }
    }
}
