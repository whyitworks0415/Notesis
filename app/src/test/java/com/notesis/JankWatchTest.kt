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
    fun `asked once, and never while light mode is on`() {
        assertTrue(shouldSuggestLightMode(0.3f, hot = false, lightMode = false, asked = false))
        assertTrue(shouldSuggestLightMode(0f, hot = true, lightMode = false, asked = false))
        assertFalse(shouldSuggestLightMode(0.1f, hot = false, lightMode = false, asked = false))
        assertFalse(shouldSuggestLightMode(0.9f, hot = true, lightMode = true, asked = false))
        assertFalse(shouldSuggestLightMode(0.9f, hot = true, lightMode = false, asked = true))
    }
}
