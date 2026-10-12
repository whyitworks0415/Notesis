package com.notesis

import org.junit.Assert.*
import org.junit.Test

class GraphiteGrainTest {
    @Test fun `graphite has gaps islands and repeatable density`() {
        var empty = 0
        var dark = 0
        var adjoining = 0
        for (y in 0 until 128) for (x in 0 until 128) {
            val alpha = graphiteGrainAlpha(x, y)
            assertEquals(alpha, graphiteGrainAlpha(x, y))
            assertTrue(alpha in 0..240)
            if (alpha == 0) empty++
            if (alpha >= 170) dark++
            if (alpha > 0 && x > 0 && graphiteGrainAlpha(x - 1, y) > 0) adjoining++
        }
        assertTrue(empty in 6500..11000)
        assertTrue(dark > 1000)
        assertTrue(adjoining > 1000)
    }

    @Test fun `pencil tooth covers most of the line with an uneven speckle and no holes`() {
        var total = 0L
        var dense = 0
        var light = 0
        for (y in 0 until GRAPHITE_TILE) for (x in 0 until GRAPHITE_TILE) {
            val alpha = graphiteToothAlpha(x, y)
            assertEquals(alpha, graphiteToothAlpha(x, y))
            // Never a hole: a light pencil line is still one line.
            assertTrue(alpha in 70..255)
            total += alpha
            if (alpha >= 200) dense++
            if (alpha <= 110) light++
        }
        val mean = total.toFloat() / (GRAPHITE_TILE * GRAPHITE_TILE)
        assertTrue(mean in 140f..200f)
        assertTrue(dense > 3000)
        assertTrue(light > 1500)
    }
}
