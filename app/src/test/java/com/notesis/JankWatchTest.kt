package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telling a tablet that cannot keep up from one that stumbled once. */
class JankWatchTest {
    private val frame = 16_666_667L

    @Test
    fun `nothing is judged before the window is full`() {
        val window = JankWindow(capacity = 10)
        repeat(9) { window.record(frame * 5, frame) }
        assertEquals(0f, window.lateShare(), 0f)
    }

    @Test
    fun `the share of late frames is of the newest ones only`() {
        val window = JankWindow(capacity = 10)
        repeat(10) { window.record(frame * 3, frame) }
        assertEquals(1f, window.lateShare(), 0f)
        repeat(10) { window.record(frame, frame) }
        assertEquals(0f, window.lateShare(), 0f)
        repeat(3) { window.record(frame * 3, frame) }
        assertEquals(0.3f, window.lateShare(), 0.001f)
    }

    @Test
    fun `a frame up to twice the frame time is not late`() {
        val window = JankWindow(capacity = 2)
        window.record(frame * 2, frame)
        window.record(frame * 2 + 1, frame)
        assertEquals(0.5f, window.lateShare(), 0f)
    }

    @Test
    fun `a frame is as long as its own work, not its wait in the queue`() {
        val ms = 1_000_000L
        // A smooth 120 Hz frame: 3 ms of work, 2.5 ms on the GPU.
        assertEquals(3 * ms, frameWorkNanos(longArrayOf(ms / 2, ms, ms, ms / 2), 5 * ms / 2))
        // GPU-bound: the GPU's time is the frame's.
        assertEquals(20 * ms, frameWorkNanos(longArrayOf(ms, ms), 20 * ms))
        // A stage the platform could not time reads as -1, and is left out.
        assertEquals(2 * ms, frameWorkNanos(longArrayOf(-1L, 2 * ms), 0L))
    }

    @Test
    fun `asked once, and never while light mode is on`() {
        assertTrue(shouldSuggestLightMode(0.3f, hot = false, lightMode = false, asked = false))
        assertTrue(shouldSuggestLightMode(0f, hot = true, lightMode = false, asked = false))
        assertFalse(shouldSuggestLightMode(0.1f, hot = false, lightMode = false, asked = false))
        assertFalse(shouldSuggestLightMode(0.9f, hot = true, lightMode = true, asked = false))
        assertFalse(shouldSuggestLightMode(0.9f, hot = true, lightMode = false, asked = true))
    }
}
