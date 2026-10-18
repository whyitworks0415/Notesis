package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTest {
    @Test
    fun `speeds step up and wrap`() {
        assertEquals(1.25f, nextPlaybackSpeed(1f), 0f)
        assertEquals(0.5f, nextPlaybackSpeed(2f), 0f)
        assertEquals(0.75f, nextPlaybackSpeed(0.5f), 0f)
    }

    @Test
    fun `speeds print without trailing zeros`() {
        assertEquals("1", formatSpeed(1f))
        assertEquals("1.25", formatSpeed(1.25f))
        assertEquals("0.5", formatSpeed(0.5f))
    }
}
