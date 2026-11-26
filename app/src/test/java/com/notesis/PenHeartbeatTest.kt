package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenHeartbeatTest {
    @Test fun `only the front buffer with nothing else drawing each pen frame needs it`() {
        assertTrue(penHeartbeatWanted(compatWetInk = false, streakActive = false))
        // The compatible surface draws its own frame for every pen frame.
        assertFalse(penHeartbeatWanted(compatWetInk = true, streakActive = false))
        // A streak already redraws the window on every pen frame.
        assertFalse(penHeartbeatWanted(compatWetInk = false, streakActive = true))
    }

    @Test fun `the streak that kept 60 Hz screens drawing is still off at 120 Hz`() {
        assertTrue(autoInkStreak(blurOn = true, displayHz = 60f))
        assertFalse(autoInkStreak(blurOn = true, displayHz = 120f))
    }
}
