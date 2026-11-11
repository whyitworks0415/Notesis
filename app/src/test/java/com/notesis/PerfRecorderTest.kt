package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfRecorderTest {
    @Test fun `a frame is filed under the most specific thing going on`() {
        assertEquals(PerfScene.WRITING, perfScene(Interaction.WRITING, chromeMoving = true, homeScrolling = true, sinceTouchMs = 0L))
        assertEquals(PerfScene.PAGE, perfScene(Interaction.MOVING, chromeMoving = true, homeScrolling = false, sinceTouchMs = 0L))
        assertEquals(PerfScene.CHROME, perfScene(Interaction.IDLE, chromeMoving = true, homeScrolling = true, sinceTouchMs = 5_000L))
        assertEquals(PerfScene.HOME_SCROLL, perfScene(Interaction.SETTLING, chromeMoving = false, homeScrolling = true, sinceTouchMs = 5_000L))
        assertEquals(PerfScene.TOUCHING, perfScene(Interaction.IDLE, chromeMoving = false, homeScrolling = false, sinceTouchMs = 200L))
        // Idle only once nothing has been touched for a second.
        assertEquals(PerfScene.IDLE, perfScene(Interaction.IDLE, chromeMoving = false, homeScrolling = false, sinceTouchMs = PERF_IDLE_AFTER_MS))
    }

    @Test fun `percentiles are the nearest rank of the frames kept`() {
        assertEquals(0, percentileIndex(1, 0.99))
        assertEquals(49, percentileIndex(100, 0.50))
        assertEquals(94, percentileIndex(100, 0.95))
        assertEquals(98, percentileIndex(100, 0.99))
        assertEquals(99, percentileIndex(100, 1.0))
        assertEquals(0, percentileIndex(0, 0.5))
        val bucket = PerfBucket(capacity = 8)
        for (ms in 1..10) bucket.add(ms * 1_000_000L, isLate = ms > 8, uiNanos = 2_000_000L, renderNanos = 1_000_000L, gpuNanos = 0L)
        // Ten frames counted, eight kept for the percentiles.
        assertEquals(10, bucket.frames)
        assertEquals(8, bucket.kept)
        assertEquals(2, bucket.late)
        assertEquals(4.0, bucket.percentileMs(0.50), 0.001)
        assertEquals(8.0, bucket.percentileMs(1.0), 0.001)
    }

    @Test fun `a scene with no frames says so, and idle frames are given as a rate`() {
        assertEquals("Writing: no frames", perfLine(PerfScene.WRITING, PerfBucket(), 0.0))
        val idle = PerfBucket()
        repeat(120) { idle.add(8_000_000L, isLate = false, uiNanos = 0L, renderNanos = 0L, gpuNanos = 0L) }
        val line = perfLine(PerfScene.IDLE, idle, idleSeconds = 60.0)
        assertTrue(line, line.contains("120 frames, 0 late"))
        assertTrue(line, line.contains("2.0 frames a second over 60 s idle"))
        val late = PerfBucket()
        late.add(40_000_000L, isLate = true, uiNanos = 30_000_000L, renderNanos = 6_000_000L, gpuNanos = 0L)
        val where = perfLine(PerfScene.CHROME, late, 0.0)
        assertTrue(where, where.contains("UI thread 30.0 ms"))
        assertTrue(where, where.contains("GPU not reported"))
    }
}
