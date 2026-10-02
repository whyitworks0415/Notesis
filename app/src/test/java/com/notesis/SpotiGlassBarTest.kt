package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class SpotiGlassBarTest {
    @Test fun `slide selects the slot under the pointer in either direction`() {
        for (index in 0..3) {
            val x = 50f + index * 100f
            assertEquals(index, spotiGlassPosition(x, 400f, 4, false).roundToInt())
            assertEquals(3 - index, spotiGlassPosition(x, 400f, 4, true).roundToInt())
        }
    }

    @Test fun `lens follows intermediate positions and clamps outside the bar`() {
        assertEquals(0.5f, spotiGlassPosition(100f, 400f, 4, false), 0f)
        assertEquals(2.5f, spotiGlassPosition(100f, 400f, 4, true), 0f)
        assertEquals(0f, spotiGlassPosition(-200f, 400f, 4, false), 0f)
        assertEquals(3f, spotiGlassPosition(900f, 400f, 4, false), 0f)
        assertEquals(0f, spotiGlassPosition(900f, 400f, 4, true), 0f)
        assertEquals(0f, spotiGlassPosition(50f, 0f, 4, false), 0f)
    }
}
