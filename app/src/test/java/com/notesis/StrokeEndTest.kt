package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeEndTest {
    @Test
    fun `short contact reversal is a spur`() {
        assertTrue(isContactSpur(0f, 0f, 3f, 0f, 0.4f, 0.1f, 16L, 5f))
    }

    @Test
    fun `intentional turn and slow backtrack are preserved`() {
        assertFalse(isContactSpur(0f, 0f, 5f, 0f, 5f, 5f, 16L, 5f))
        assertFalse(isContactSpur(0f, 0f, 3f, 0f, 0.4f, 0f, 90L, 5f))
        assertFalse(isContactSpur(0f, 0f, 12f, 0f, 0.4f, 0f, 16L, 5f))
    }

    // The first stroke of a reported note: an 11-wide pen whose contact sample
    // landed 0.35 ahead of the three after it. The old fixed 0.3 threshold took
    // the contact sample's neighbour as the first movement and kept the cluster.
    private val reportedX = floatArrayOf(658.52f, 658.17f, 658.24f, 658.35f, 658.82f, 659.02f,
        659.65f, 659.90f, 660.65f, 661.23f, 661.80f, 662.37f)
    private val reportedY = floatArrayOf(769.29f, 769.29f, 769.29f, 769.29f, 769.29f, 769.25f,
        769.16f, 769.04f, 768.90f, 768.78f, 768.60f, 768.41f)
    private val reportedT = longArrayOf(0, 10, 16, 19, 20, 23, 25, 27, 29, 31, 33, 35)

    @Test
    fun `contact sample ahead of the cluster still drops the cluster`() {
        val first = firstMovementIndex(
            reportedX.size, stationaryStartThreshold(11f),
            { reportedX[it] }, { reportedY[it] }, { reportedT[it] },
        )
        assertEquals(9, first)
    }

    @Test
    fun `a pen that never leaves the contact point keeps every sample`() {
        val first = firstMovementIndex(
            4, stationaryStartThreshold(11f),
            { reportedX[it] }, { reportedY[it] }, { reportedT[it] },
        )
        assertEquals(-1, first)
    }

    @Test
    fun `fine pens keep a floor on the threshold`() {
        assertEquals(0.3f, stationaryStartThreshold(0.5f), 0f)
        assertEquals(2.75f, stationaryStartThreshold(11f), 1e-6f)
    }
}
