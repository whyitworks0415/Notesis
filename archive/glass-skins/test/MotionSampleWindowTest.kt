package com.notesis

import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionSampleWindowTest {
    @Test
    fun `primitive window matches original animation through growth wrap and pauses`() {
        val window = MotionSampleWindow()
        val samples = ArrayDeque<Pair<Double, Double>>()
        var now = 0.0
        repeat(2000) { i ->
            now += when {
                i == 900 -> 1.0
                i % 53 == 0 -> 0.0
                else -> 1.0 / 240
            }
            val position = sin(now * 8) * 80
            samples.addLast(position to now)
            while (samples.isNotEmpty() && samples.first().second < now - 0.3) samples.removeFirst()
            val velocities = samples.zipWithNext().mapNotNull { (a, b) ->
                val delta = b.second - a.second
                if (delta > 0) ((b.first - a.first) / delta) to ((a.second + b.second) / 2) else null
            }
            val accelerations = velocities.zipWithNext().mapNotNull { (a, b) ->
                val delta = b.second - a.second
                if (delta > 0) (b.first - a.first) / delta else null
            }
            val expected = if (accelerations.isEmpty()) 0.0 else accelerations.average()
            window.add(position, now)
            assertEquals(expected, window.acceleration(), 0.00000001)
        }
        window.clear()
        assertEquals(0.0, window.acceleration(), 0.0)
        window.add(1.0, now)
        assertEquals(0.0, window.acceleration(), 0.0)
    }
}
