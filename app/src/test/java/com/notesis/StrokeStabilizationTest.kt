package com.notesis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeStabilizationTest {
    @Test fun `zero percent preserves every coordinate`() {
        val x = floatArrayOf(0f, 1f, 2f, 3f)
        val y = floatArrayOf(0f, 3f, -2f, 1f)

        val (resultX, resultY) = stabilizedCoordinates(x, y, 0)

        assertArrayEquals(x, resultX, 0f)
        assertArrayEquals(y, resultY, 0f)
    }

    @Test fun `strong stabilization reduces jitter and preserves endpoints`() {
        val x = FloatArray(9) { it.toFloat() }
        val y = floatArrayOf(0f, 1f, -1f, 2f, -3f, 2f, -1f, 1f, 0f)

        val (resultX, resultY) = stabilizedCoordinates(x, y, 100)

        assertArrayEquals(floatArrayOf(x.first(), x.last()), floatArrayOf(resultX.first(), resultX.last()), 0f)
        assertArrayEquals(floatArrayOf(y.first(), y.last()), floatArrayOf(resultY.first(), resultY.last()), 0f)
        assertTrue(kotlin.math.abs(resultY[4]) < kotlin.math.abs(y[4]))
    }

    @Test fun `percentage is clamped to its public range`() {
        val x = floatArrayOf(0f, 1f, 2f, 3f)
        val y = floatArrayOf(0f, 2f, -1f, 0f)

        val below = stabilizedCoordinates(x, y, -20)
        val zero = stabilizedCoordinates(x, y, 0)
        val above = stabilizedCoordinates(x, y, 140)
        val full = stabilizedCoordinates(x, y, 100)

        assertArrayEquals(zero.first, below.first, 0f)
        assertArrayEquals(zero.second, below.second, 0f)
        assertArrayEquals(full.first, above.first, 0f)
        assertArrayEquals(full.second, above.second, 0f)
    }
}
