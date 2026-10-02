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
}
