package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionBlurTest {
    @Test fun `the blur follows its strength and stays off at 0 and on fast screens`() {
        // 600 px a second at 60 Hz is a 10 px step between frames.
        assertEquals(2f, motionBlurPx(600f, 60f, 6f, strength = 0.5f), 0.001f)
        assertEquals(4f, motionBlurPx(600f, 60f, 6f, strength = 1f), 0.001f)
        assertEquals(0f, motionBlurPx(600f, 60f, 6f, strength = 0f), 0f)
        assertEquals(0f, motionBlurPx(600f, 60f, 6f, strength = Float.NaN), 0f)
        // Never past its cap, never at 120 Hz, never under half a pixel.
        assertEquals(6f, motionBlurPx(20000f, 60f, 6f, strength = 1f), 0f)
        assertEquals(0f, motionBlurPx(20000f, 120f, 6f, strength = 1f), 0f)
        assertEquals(0f, motionBlurPx(50f, 60f, 6f, strength = 1f), 0f)
    }

    @Test fun `a list's speed is smoothed, and a bad sample is left out`() {
        assertEquals(500f, speedSample(0f, 1000f), 0f)
        assertEquals(750f, speedSample(500f, 1000f), 0f)
        assertEquals(500f, speedSample(500f, Float.NaN), 0f)
        assertEquals(500f, speedSample(500f, Float.POSITIVE_INFINITY), 0f)
    }

    @Test fun `a selection moves eased in and out and lands without a bounce`() {
        var last = 0f
        for (step in 0..100) {
            val v = SelectionEasing.transform(step / 100f)
            assertTrue(v >= last - 1e-4f && v <= 1f + 1e-4f)
            last = v
        }
        // Slow at both ends, quicker in the middle.
        assertTrue(SelectionEasing.transform(0.1f) < 0.1f)
        assertTrue(SelectionEasing.transform(0.9f) > 0.9f)
        assertEquals(1f, SelectionEasing.transform(1f), 1e-4f)
    }
}
