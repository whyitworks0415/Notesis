package com.notesis

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticesTest {
    @Test fun `a notice comes out of the touch along a line to where it rests`() {
        val rest = Offset(500f, 1800f)
        val touch = Offset(100f, 200f)
        assertEquals(touch - rest, noticeOffset(touch, rest, 0f))
        assertEquals(Offset.Zero, noticeOffset(touch, rest, 1f))
        assertEquals(Offset(-200f, -800f), noticeOffset(touch, rest, 0.5f))
        // With no touch it grows where it rests.
        assertEquals(Offset.Zero, noticeOffset(null, rest, 0f))
    }

    @Test fun `it grows from a fifth of its size`() {
        assertEquals(0.2f, noticeScale(0f), 0f)
        assertEquals(1f, noticeScale(1f), 0f)
        assertEquals(1f, noticeScale(Float.NaN), 0f)
    }

    @Test fun `one with something to do on it stays longer`() {
        assertTrue(noticeShownMs(long = true) > noticeShownMs(long = false))
    }
}
