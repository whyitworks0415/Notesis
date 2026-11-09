package com.notesis

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class LabStrokeProcessingTest {
    @Test fun `packed smoothing keeps the exact reference coordinates and endpoints`() {
        val random = Random(584)
        for (size in listOf(0, 1, 2, 3, 128, 500)) for (passes in listOf(0, 1, 2, 12)) {
            val points = List(size) { floatArrayOf(random.nextFloat() * 500, random.nextFloat() * 500) }
            var reference = points
            repeat(passes) {
                reference = reference.indices.map { i ->
                    if (i == 0 || i == reference.lastIndex) reference[i] else floatArrayOf(
                        (reference[i - 1][0] + 2 * reference[i][0] + reference[i + 1][0]) / 4f,
                        (reference[i - 1][1] + 2 * reference[i][1] + reference[i + 1][1]) / 4f)
                }
            }
            val actual = smoothed(points, passes)
            for (i in points.indices) assertArrayEquals(reference[i], actual[i], 0f)
            if (size > 0) { assertSame(points.first(), actual.first()); assertSame(points.last(), actual.last()) }
        }
    }

    @Test fun `nested and oversized tidying cannot overwrite a held workspace`() {
        val outer = TidySamples.acquire(256)
        try {
            outer.xs[0] = 73f
            val nested = TidySamples.acquire(256)
            assertNotSame(outer, nested)
            nested.xs[0] = 9f; TidySamples.release(nested)
            val large = TidySamples.acquire(20000)
            assertNotSame(outer, large); TidySamples.release(large)
            assertEquals(73f, outer.xs[0], 0f)
        } finally { TidySamples.release(outer) }
        val again = TidySamples.acquire(64)
        try { assertSame(outer, again) } finally { TidySamples.release(again) }
    }

    @Test fun `tidying uses logical sample count even when reused arrays have a dirty tail`() {
        val random = Random(777)
        repeat(200) {
            val count = random.nextInt(3, 300)
            val x = FloatArray(count) { i -> if (i < 4) 0f else i + random.nextFloat() }
            val y = FloatArray(count) { random.nextFloat() }
            val times = LongArray(count) { it * 4L }
            val scratch = TidySamples.acquire(count + 512)
            try {
                scratch.xs.fill(Float.NaN); scratch.ys.fill(Float.NaN); scratch.times.fill(Long.MAX_VALUE)
                x.copyInto(scratch.xs); y.copyInto(scratch.ys); times.copyInto(scratch.times)
                assertEquals(tidiedPenKeepMask(count, 2f, x, y, times)?.toList(),
                    tidiedPenKeepMask(count, 2f, scratch.xs, scratch.ys, scratch.times)?.toList())
                assertEquals(spurAndDwellKeepMask(count, 2f, x, y, times)?.toList(),
                    spurAndDwellKeepMask(count, 2f, scratch.xs, scratch.ys, scratch.times)?.toList())
            } finally { TidySamples.release(scratch) }
        }
    }

    @Test fun `callback timestamps cannot manufacture first ink from unset or inverted spans`() {
        assertNull(inkRenderLatencyNanos(Long.MIN_VALUE, 100))
        assertNull(inkRenderLatencyNanos(100, Long.MIN_VALUE))
        assertNull(inkRenderLatencyNanos(100, 99))
        assertNull(inkRenderLatencyNanos(100, 100))
        assertEquals(30L, inkRenderLatencyNanos(100, 130))
        val stats = LatencyStats(); stats.setEnabled(true)
        stats.record(PerformanceMetric.PEN_START_HANDLED, 1_000_000)
        assertNull(stats.snapshot(PerformanceMetric.FIRST_INK))
        assertNotNull(stats.snapshot(PerformanceMetric.PEN_START_HANDLED))
    }
}
