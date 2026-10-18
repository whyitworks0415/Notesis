package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeSplitTest {
    private fun line(count: Int, step: Float = 1f): Pair<FloatArray, FloatArray> =
        FloatArray(count) { it * step } to FloatArray(count)

    @Test fun `an eraser that misses leaves the stroke alone`() {
        val (xs, ys) = line(20)
        assertNull(keptRuns(xs, ys, 20, 5f, 10f, 15f, 10f, 2f))
    }

    @Test fun `erasing the middle leaves two pieces`() {
        val (xs, ys) = line(21)
        // Vertical swipe through x = 10 with half-width 2 drops inputs 8..12.
        val runs = keptRuns(xs, ys, 21, 10f, -5f, 10f, 5f, 2f)!!
        assertEquals(listOf(0 until 8, 13 until 21), runs)
    }

    @Test fun `erasing an end leaves one piece`() {
        val (xs, ys) = line(21)
        val runs = keptRuns(xs, ys, 21, 0f, -5f, 0f, 5f, 2.5f)!!
        assertEquals(listOf(3 until 21), runs)
    }

    @Test fun `a lone input left behind is dropped`() {
        val (xs, ys) = line(9)
        // Drops 1..3; input 0 is left alone and goes with them.
        val first = keptRuns(xs, ys, 9, 2f, -5f, 2f, 5f, 1f)!!
        assertEquals(listOf(4 until 9), first)
        val (xs2, ys2) = line(5)
        assertEquals(emptyList<IntRange>(), keptRuns(xs2, ys2, 5, 2f, -5f, 2f, 5f, 1.5f))
    }

    @Test fun `sparse samples are cut where the line between them is touched`() {
        // Two inputs 100 apart: no input is near x = 50, but the line is.
        val xs = floatArrayOf(0f, 100f, 200f)
        val ys = floatArrayOf(0f, 0f, 0f)
        val runs = keptRuns(xs, ys, 3, 50f, -10f, 50f, 10f, 3f)!!
        assertEquals(listOf(1 until 3), runs)
    }

    @Test fun `segment distance`() {
        assertEquals(0f, segmentSegmentDistance(0f, 0f, 10f, 0f, 5f, -5f, 5f, 5f), 1e-4f)
        assertEquals(3f, segmentSegmentDistance(0f, 0f, 10f, 0f, 5f, 3f, 5f, 8f), 1e-4f)
        assertEquals(5f, pointSegmentDistance(0f, 5f, -10f, 0f, 10f, 0f), 1e-4f)
    }

    @Test
    fun `a kept piece runs on to the eraser's edge`() {
        // From x=0 toward x=10 along y=0; a vertical sweep at x=8 reaching 2 is met at x=6.
        val t = firstReachFraction(0f, 0f, 10f, 0f, 8f, -5f, 8f, 5f, 2f)
        assertEquals(0.6f, t, 1e-3f)
        assertTrue(t <= 0.6f)
    }

    @Test
    fun `the edge is found in the middle when both ends are outside`() {
        // The sweep at x=5 reaching 1 touches only the middle of 0..10.
        assertEquals(0.4f, firstReachFraction(0f, 0f, 10f, 0f, 5f, -5f, 5f, 5f, 1f), 1e-3f)
    }

    @Test
    fun `never reached is the whole way`() {
        assertEquals(1f, firstReachFraction(0f, 0f, 10f, 0f, 5f, 3f, 5f, 8f, 1f), 0f)
    }
}
