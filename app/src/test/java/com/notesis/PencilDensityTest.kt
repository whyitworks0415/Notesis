package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PencilDensityTest {
    private fun coverage(level: Int): Double {
        var sum = 0L
        for (y in 0 until 64) for (x in 0 until 64) sum += graphiteToothAlpha(x, y, level)
        return sum / (64.0 * 64.0)
    }

    @Test fun theDefaultLevelIsTheToothAsItAlwaysWas() {
        for (y in 0 until 32) for (x in 0 until 32) {
            assertEquals(graphiteToothAlpha(x, y), graphiteToothAlpha(x, y, GRAPHITE_DENSITY_DEFAULT))
        }
    }

    @Test fun aDenserPencilFillsMoreOfTheTooth() {
        var previous = -1.0
        for (level in GRAPHITE_DENSITY_MIN..GRAPHITE_DENSITY_MAX) {
            val now = coverage(level)
            assertTrue("level $level ($now) should cover more than ${level - 1} ($previous)", now > previous)
            previous = now
        }
    }

    @Test fun outOfRangeLevelsAreHeldToTheEnds() {
        assertEquals(coverage(GRAPHITE_DENSITY_MAX), coverage(99), 0.0)
        assertEquals(coverage(GRAPHITE_DENSITY_MIN), coverage(-3), 0.0)
    }
}
