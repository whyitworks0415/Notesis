package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyStatsTest {
    @Test
    fun `disabled diagnostics do not retain hot path samples`() {
        val stats = LatencyStats()

        stats.add(12_000_000L)

        assertNull(stats.snapshot(PerformanceMetric.WET_INK))
    }

    @Test
    fun `rolling metrics report p50 p95 and p99`() {
        val stats = LatencyStats(128)
        stats.setEnabled(true)

        for (millis in 1L..100L) stats.add(millis * 1_000_000L)

        val snapshot = stats.snapshot(PerformanceMetric.WET_INK)!!
        assertEquals(100, snapshot.count)
        assertEquals(50.0, snapshot.p50, 0.001)
        assertEquals(95.0, snapshot.p95, 0.001)
        assertEquals(99.0, snapshot.p99, 0.001)
        assertEquals(100.0, snapshot.max, 0.001)
    }

    @Test
    fun `dense draw is classified without losing the general draw sample`() {
        val stats = LatencyStats()
        stats.setEnabled(true)

        stats.addDraw(4_000_000L, LatencyStats.DENSE_PAGE_STROKES - 1)
        stats.addDraw(9_000_000L, LatencyStats.DENSE_PAGE_STROKES)

        assertEquals(2, stats.snapshot(PerformanceMetric.CANVAS_DRAW)?.count)
        assertEquals(1, stats.snapshot(PerformanceMetric.DENSE_DRAW)?.count)
    }

    @Test
    fun `enabling a new diagnostic session clears stale samples`() {
        val stats = LatencyStats()
        stats.setEnabled(true)
        stats.addPenFinalize(3_000_000L)
        stats.setEnabled(false)
        stats.setEnabled(true)

        assertNull(stats.snapshot(PerformanceMetric.PEN_FINALIZE))
        assertTrue(stats.render(0).contains("p99").not())
    }

    @Test
    fun `allocation rate is rendered in mebibytes per second`() {
        val stats = LatencyStats()
        stats.setEnabled(true)
        stats.addRuntimeSample(0L, 0L, 1_000_000_000L)
        stats.addRuntimeSample(2L * 1024 * 1024, 1L, 2_000_000_000L)

        val report = stats.render(0)

        assertTrue(report.contains("allocation p50 2.0MB/s"))
        assertTrue(report.contains("GC +1"))
    }
}
