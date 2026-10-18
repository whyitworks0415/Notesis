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

    // The apex of a hairpin turn in a reported 1.4-wide stroke: the pen slows,
    // wobbles a hundredth of a unit back and forth, and comes back down.
    private val hairpinX = floatArrayOf(999.22f, 999.34f, 999.46f, 999.56f, 999.62f, 999.7f, 999.76f, 999.8f,
        999.84f, 999.85f, 999.87f, 999.87f, 999.86f, 999.83f, 999.77f, 999.67f, 999.56f, 999.42f, 999.26f,
        999.1f, 998.92f, 998.72f, 998.53f, 998.31f, 998.1f, 997.87f)
    private val hairpinY = floatArrayOf(26.5f, 26.34f, 26.19f, 26.07f, 25.98f, 25.89f, 25.82f, 25.77f,
        25.72f, 25.7f, 25.68f, 25.67f, 25.69f, 25.75f, 25.82f, 25.94f, 26.08f, 26.24f, 26.43f, 26.64f,
        26.88f, 27.15f, 27.43f, 27.73f, 28.04f, 28.36f)

    @Test
    fun `a wobbling hairpin apex folds to its furthest sample`() {
        val keep = dwellKeepMask(hairpinX.size, stationaryStartThreshold(1.4f), { hairpinX[it] }, { hairpinY[it] })!!
        assertEquals(listOf(5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16), keep.indices.filter { !keep[it] })
        // The kept apex is the one furthest up the incoming line.
        assertTrue(keep[11])
    }

    @Test
    fun `a smooth turn without doubling back is left alone`() {
        // A quarter circle of radius 3 sampled every 0.2: no step points back.
        val count = 24
        val xs = FloatArray(count) { 3f * kotlin.math.cos(it * 0.0683f) }
        val ys = FloatArray(count) { 3f * kotlin.math.sin(it * 0.0683f) }
        assertEquals(null, dwellKeepMask(count, stationaryStartThreshold(1.4f), { xs[it] }, { ys[it] }))
    }
}
