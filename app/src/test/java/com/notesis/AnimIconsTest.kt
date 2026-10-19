package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimIconsTest {
    @Test
    fun `tracks start and end on their keyframes`() {
        val t = track(0f, 30f, -6f, 0f, duration = 0.9f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f))
        assertEquals(0f, t.at(0f), 1e-4f)
        assertEquals(30f, t.at(0.36f), 1e-3f)
        assertEquals(-6f, t.at(0.675f), 1e-3f)
        assertEquals(0f, t.at(5f), 1e-4f)
    }

    @Test
    fun `delay holds the first value`() {
        val t = track(1f, 2f, duration = 1f, delay = 0.5f)
        assertEquals(1f, t.at(0.25f), 1e-4f)
        assertEquals(1.5f, t.at(1f), 1e-2f)
        assertEquals(1.5f + 0.5f, t.at(1.5f), 1e-4f)
    }

    @Test
    fun `eases are monotone and symmetric`() {
        assertEquals(0.5f, IconEase.IN_OUT.at(0.5f), 1e-3f)
        assertEquals(0.25f, IconEase.LINEAR.at(0.25f), 0f)
        var last = 0f
        for (i in 0..20) {
            val v = IconEase.OUT.at(i / 20f)
            assert(v >= last)
            last = v
        }
    }
}
