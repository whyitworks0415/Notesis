package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.exp
import kotlin.math.sqrt

class EdgeBounceTest {
    @Test fun `a pull past the end follows less and less and never reaches the list's length`() {
        val extent = 1000f
        var last = 0f
        for (pull in listOf(10f, 100f, 400f, 2000f, 20000f)) {
            val shown = edgeStretch(pull, extent)
            assertTrue(shown > last && shown < pull && shown < extent)
            last = shown
        }
        // Either way, and nothing for nothing.
        assertEquals(-edgeStretch(300f, extent), edgeStretch(-300f, extent), 0.0001f)
        assertEquals(0f, edgeStretch(0f, extent), 0f)
        assertEquals(0f, edgeStretch(50f, 0f), 0f)
    }

    @Test fun `the finger's travel is found again from what is shown`() {
        for (pull in listOf(-900f, -40f, 3f, 250f, 1600f)) {
            assertEquals(pull, edgePull(edgeStretch(pull, 1000f), 1000f), abs(pull) * 1e-3f + 0.01f)
        }
        assertEquals(0f, edgePull(0f, 1000f), 0f)
    }

    @Test fun `a fling at the end carries past it by at most the throw`() {
        val throwPx = 150f
        val v = edgeMaxVelocity(throwPx)
        // A critically damped spring from rest at the end peaks at 1 / ω of the way, at v / (ω e).
        val omega = sqrt(EDGE_BOUNCE_STIFFNESS)
        val t = 1f / omega
        assertEquals(throwPx, v * t * exp(-omega * t), 0.01f)
        assertEquals(throwPx * omega * E.toFloat(), v, 0.01f)
    }

    private fun abs(x: Float) = kotlin.math.abs(x)

    @Test fun `card pictures are scaled to the width asked, not left up to twice it`() {
        // 1600 px sampled by 2 is 800: brought down to the card's 480.
        assertEquals(480 to 660, fittedSize(800, 1100, 480))
        // Near enough, or narrower, is kept as it is.
        assertEquals(null, fittedSize(520, 700, 480))
        assertEquals(null, fittedSize(300, 400, 480))
        assertEquals(null, fittedSize(0, 400, 480))
        // A fling that dies out mid-list has nowhere near this much speed left.
        assertTrue(EDGE_MIN_THROW_VELOCITY >= 200f)
    }
}
