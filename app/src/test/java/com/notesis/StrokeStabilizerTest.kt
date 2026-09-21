package com.notesis

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeStabilizerTest {
    private fun run(
        strength: Int,
        points: List<Pair<Float, Float>>,
        stepMs: Long = 8L,
    ): List<Pair<Float, Float>> {
        val filter = AdaptiveStrokeStabilizer()
        filter.reset(strength, points.first().first, points.first().second, 0L)
        val output = mutableListOf(filter.x to filter.y)
        points.drop(1).forEachIndexed { index, point ->
            filter.add(point.first, point.second, (index + 1L) * stepMs)
            output += filter.x to filter.y
        }
        return output
    }

    private fun yEnergy(points: List<Pair<Float, Float>>): Float =
        sqrt(points.map { it.second * it.second }.average()).toFloat()

    @Test
    fun `zero percent is exact raw input`() {
        val raw = listOf(0f to 0f, 1f to 0.4f, 2f to -0.7f, 5f to 3f)
        assertEquals(raw, run(0, raw))
    }

    @Test
    fun `straight line stays straight and ordered`() {
        val output = run(30, (0..12).map { it * 2f to 0f })
        assertTrue(output.all { it.second == 0f })
        assertTrue(output.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    @Test
    fun `jitter reduction increases at 10 30 and 100 percent`() {
        val raw = (0..20).map { it * 2f to if (it == 0) 0f else if (it % 2 == 0) 1f else -1f }
        val e0 = yEnergy(run(0, raw))
        val e10 = yEnergy(run(10, raw))
        val e30 = yEnergy(run(30, raw))
        val e100 = yEnergy(run(100, raw))
        assertTrue(e10 < e0)
        assertTrue(e30 < e10)
        assertTrue(e100 < e30)
    }

    @Test
    fun `five through thirty percent have distinct useful strength`() {
        val raw = (0..28).map { it * 1.5f to if (it % 2 == 0) 0.9f else -0.9f }
        val e0 = yEnergy(run(0, raw))
        val e5 = yEnergy(run(5, raw))
        val e10 = yEnergy(run(10, raw))
        val e20 = yEnergy(run(20, raw))
        val e30 = yEnergy(run(30, raw))
        assertTrue(e5 < e0)
        assertTrue(e10 < e5)
        assertTrue(e20 < e10)
        assertTrue(e30 < e20)
    }

    @Test
    fun `curve direction and endpoint are retained`() {
        val raw = listOf(0f to 10f, 3f to 9.5f, 6f to 8f, 8f to 6f, 9.5f to 3f, 10f to 0f)
        val output = run(30, raw)
        assertTrue(output.zipWithNext().all { (a, b) -> b.first >= a.first && b.second <= a.second })
        assertTrue(output.last().first > 9f)
        assertTrue(output.last().second < 1f)
    }

    @Test
    fun `ninety degree corner and rapid reversal are preserved`() {
        val corner = run(100, listOf(0f to 0f, 5f to 0f, 10f to 0f, 10f to 5f, 10f to 10f))
        assertTrue(corner[3].first > 9f)
        assertTrue(corner[3].second > 4f)

        val reversal = run(100, listOf(0f to 0f, 6f to 0f, 12f to 0f, 7f to 0f, 2f to 0f))
        assertTrue(reversal[3].first < 8f)
        assertTrue(reversal.last().first < reversal[3].first)
    }

    @Test
    fun `dot and short stroke are not destroyed`() {
        val dot = run(100, listOf(4f to 7f))
        assertEquals(listOf(4f to 7f), dot)
        val short = run(30, listOf(0f to 0f, 1f to 0f, 2f to 0.2f))
        assertTrue(short.last().first > 1.5f)
        assertTrue(short.last().second >= 0f)
    }

    @Test
    fun `non finite driver sample cannot poison following input`() {
        val filter = AdaptiveStrokeStabilizer()
        filter.reset(30, 0f, 0f, 0L)
        filter.add(Float.NaN, Float.POSITIVE_INFINITY, 8L)
        assertEquals(0f, filter.x, 0f)
        assertEquals(0f, filter.y, 0f)
        filter.add(2f, 0f, 16L)
        assertTrue(filter.x.isFinite())
        assertTrue(filter.x > 1f)
    }

    @Test
    fun `start reverse spike is damped and returns promptly`() {
        val raw = listOf(0f to 0f, 6f to 0f, 1f to 0f, 2f to 0f, 3f to 0f)
        val output = run(100, raw)
        assertTrue(output[1].first < raw[1].first)
        assertTrue(output[2].first < 2f)
        assertTrue(output.last().first > output[2].first)
    }

    @Test
    fun `pen lift hook and jump are rejected but continuation is accepted`() {
        val filter = AdaptiveStrokeStabilizer()
        filter.reset(30, 0f, 0f, 0L)
        filter.add(3f, 0f, 8L)
        filter.add(6f, 0f, 16L)
        assertTrue(filter.shouldRejectLift(6f, 3f))
        assertTrue(filter.shouldRejectLift(30f, 0f))
        assertFalse(filter.shouldRejectLift(8f, 0.3f))
    }

    @Test
    fun `fast writing follows more closely than slow writing`() {
        val fast = run(100, listOf(0f to 0f, 10f to 0f), stepMs = 4L).last().first
        val slow = run(100, listOf(0f to 0f, 10f to 0f), stepMs = 24L).last().first
        assertTrue(fast > slow)
    }

    @Test
    fun `prediction waits for stable direction and pauses at cusp`() {
        val filter = AdaptiveStrokeStabilizer()
        filter.reset(30, 0f, 0f, 0L)
        filter.add(2f, 0f, 8L)
        assertFalse(filter.predictionAllowed)
        filter.add(4f, 0f, 16L)
        assertFalse(filter.predictionAllowed)
        filter.add(6f, 0f, 24L)
        assertTrue(filter.predictionAllowed)
        filter.add(6f, 3f, 32L)
        assertFalse(filter.predictionAllowed)
    }

    @Test
    fun `pressure smoothing is independent and zero percent is raw`() {
        val raw = listOf(0.45f, 0.56f, 0.44f, 0.55f, 0.43f)
        val bypass = AdaptivePressureStabilizer()
        bypass.reset(0, raw.first(), 0L)
        val bypassed = raw.drop(1).mapIndexed { index, value ->
            bypass.add(value, (index + 1L) * 8L)
        }
        assertEquals(raw.drop(1), bypassed)

        val smooth = AdaptivePressureStabilizer()
        smooth.reset(30, raw.first(), 0L)
        val filtered = raw.drop(1).mapIndexed { index, value ->
            smooth.add(value, (index + 1L) * 8L)
        }
        val rawVariation = raw.zipWithNext().sumOf { (a, b) ->
            kotlin.math.abs(b - a).toDouble()
        }
        val filteredWithStart = listOf(raw.first()) + filtered
        val filteredVariation = filteredWithStart.zipWithNext().sumOf { (a, b) ->
            kotlin.math.abs(b - a).toDouble()
        }
        assertTrue(filteredVariation < rawVariation)
    }

    @Test
    fun `intentional pressure change and final pressure are preserved`() {
        val filter = AdaptivePressureStabilizer()
        filter.reset(100, 0.2f, 0L)
        val p1 = filter.add(0.35f, 8L)
        val p2 = filter.add(0.55f, 16L)
        val p3 = filter.add(0.8f, 24L, finalSample = true)
        assertTrue(p1 > 0.2f)
        assertTrue(p2 > p1)
        assertTrue(p3 > p2)
        assertTrue(p3 > 0.72f)
    }

    @Test
    fun `invalid pressure cannot poison following samples`() {
        val filter = AdaptivePressureStabilizer()
        filter.reset(30, 0.5f, 0L)
        val invalid = filter.add(Float.NaN, 8L)
        assertTrue(invalid.isFinite())
        val next = filter.add(0.7f, 16L)
        assertTrue(next.isFinite())
        assertTrue(next > invalid)
    }
}
