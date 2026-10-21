package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a frame is missed only past one and a half refresh periods`() {
        val period = 16_666_667L
        assertFalse(isMissedFrame(period, period))
        assertFalse(isMissedFrame(period * 3 / 2, period))
        assertTrue(isMissedFrame(period * 3 / 2 + 2, period))
        assertTrue(isMissedFrame(period * 2, period))
        // At 120 Hz a 60 Hz-length frame is a miss.
        assertTrue(isMissedFrame(period, 8_333_333L))
        assertFalse(isMissedFrame(period, 0L))
    }

    @Test
    fun `missed frames are counted against the refresh period and reset`() {
        val stats = LatencyStats()
        stats.setEnabled(true)
        stats.refreshPeriodNanos = 16_666_667L
        repeat(97) { stats.addFrame(16_666_667L) }
        repeat(3) { stats.addFrame(33_333_333L) }

        val extras = stats.extras()
        assertEquals(100L, extras.framesCounted)
        assertEquals(3L, extras.framesMissed)
        assertTrue(stats.render(0).contains("missed 3/100 (3.0%)"))

        stats.reset()
        assertEquals(0L, stats.extras().framesCounted)
        assertEquals(0L, stats.extras().framesMissed)
        assertNull(stats.extras().frame)
    }

    @Test
    fun `hidden diagnostics count no frames`() {
        val stats = LatencyStats()
        stats.addFrame(50_000_000L)
        assertEquals(0L, stats.extras().framesCounted)
    }

    @Test
    fun `submission timings are labelled as submitted, never as photons`() {
        val stats = LatencyStats()
        stats.setEnabled(true)
        val report = stats.render(0)
        assertTrue(report.contains("first ink (submitted)"))
        assertTrue(report.contains("wet ink (submitted)"))
        assertTrue(report.contains("input handled"))
        assertTrue(report.contains("page apply"))
        assertTrue(report.contains("save snapshot"))
        assertFalse(report.contains("photon"))
    }

    @Test
    fun `missed percentage text`() {
        assertEquals("-", missedPercentText(0L, 0L))
        assertEquals("0.0%", missedPercentText(0L, 10L))
        assertEquals("12.5%", missedPercentText(1L, 8L))
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
