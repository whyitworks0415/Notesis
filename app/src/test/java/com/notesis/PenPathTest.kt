package com.notesis

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure pieces of the pen path: prediction horizon, overlay damage, lasso hits, brush reuse. */
class PenPathTest {

    // ---- prediction horizon ------------------------------------------------

    @Test
    fun `a sample handled at once keeps the frame-based horizon`() {
        assertEquals(8, predictionLeadForRefresh(0, 60f, handlingDelayMs = 0.0))
        assertEquals(8, predictionLeadForRefresh(0, 60f, handlingDelayMs = HANDLING_SLACK_MS))
        assertEquals(4, predictionLeadForRefresh(0, 120f, handlingDelayMs = 1.5))
    }

    @Test
    fun `a sample that waited is predicted a little further, never past the cap`() {
        // Half of what is over the slack: 6ms late at 60Hz adds 2ms.
        assertEquals(10, predictionLeadForRefresh(0, 60f, handlingDelayMs = 6.0))
        assertEquals(6, predictionLeadForRefresh(0, 120f, handlingDelayMs = 6.0))
        // A long stall reaches the settings' 12ms and no further.
        assertEquals(VSYNC_BOUND_LEAD_MS, predictionLeadForRefresh(0, 60f, handlingDelayMs = 80.0))
        assertEquals(VSYNC_BOUND_LEAD_MS, predictionLeadForRefresh(0, 60f, vsyncBoundInk = true, handlingDelayMs = 30.0))
    }

    @Test
    fun `the horizon counts a 60Hz frame as a 60Hz frame`() {
        // Not a 120Hz horizon stretched: each rate gets its own period's share.
        assertEquals(8, autoPredictionLeadMs(60f))
        assertEquals(4, autoPredictionLeadMs(120f))
        assertTrue(autoPredictionLeadMs(60f, handlingDelayMs = 4.0) < autoPredictionLeadMs(60f, vsyncBoundInk = true))
    }

    @Test
    fun `a measured latency is not added to twice`() {
        // The measurement already holds the queueing; the delay is then ignored.
        assertEquals(
            predictionLeadForRefresh(0, 120f, measuredLatencyMs = 16.0),
            predictionLeadForRefresh(0, 120f, measuredLatencyMs = 16.0, handlingDelayMs = 20.0),
        )
    }

    @Test
    fun `fixed leads ignore the delay and bad delays are ignored`() {
        assertEquals(4, predictionLeadForRefresh(4, 60f, handlingDelayMs = 50.0))
        assertEquals(8, predictionLeadForRefresh(0, 60f, handlingDelayMs = Double.NaN))
        assertEquals(8, predictionLeadForRefresh(0, 60f, handlingDelayMs = -5.0))
    }

    // ---- overlay damage ------------------------------------------------------

    @Test
    fun `overlay box covers the mark and its pad`() {
        val out = IntArray(4)
        assertTrue(overlayBounds(10.5f, 20f, 4f, 30.2f, 2f, 1000, 1000, out))
        assertArrayEquals(intArrayOf(2, 18, 13, 33), out)
    }

    @Test
    fun `overlay box is kept inside the parent`() {
        val out = IntArray(4)
        assertTrue(overlayBounds(-5f, -5f, 10f, 10f, 3f, 8, 9, out))
        assertArrayEquals(intArrayOf(0, 0, 8, 9), out)
    }

    @Test
    fun `nothing on screen or not a number is no box`() {
        val out = intArrayOf(7, 7, 7, 7)
        assertFalse(overlayBounds(-50f, 5f, -40f, 6f, 2f, 100, 100, out))
        assertFalse(overlayBounds(Float.NaN, 5f, 10f, 6f, 2f, 100, 100, out))
        assertFalse(overlayBounds(1f, 5f, 10f, 6f, Float.POSITIVE_INFINITY, 100, 100, out))
        // Left as it was when there is no answer.
        assertArrayEquals(intArrayOf(7, 7, 7, 7), out)
    }

    @Test
    fun `a dot gets the box of its pad, and nothing without one`() {
        val out = IntArray(4)
        assertTrue(overlayBounds(50f, 50f, 50f, 50f, 1f, 100, 100, out))
        assertArrayEquals(intArrayOf(49, 49, 51, 51), out)
        assertFalse(overlayBounds(50f, 50f, 50f, 50f, 0f, 100, 100, out))
    }

    @Test
    fun `overlay square steps up so a growing mark rarely resizes it`() {
        assertEquals(64, overlaySide(0, 0))
        assertEquals(64, overlaySide(10, 64))
        assertEquals(128, overlaySide(65, 3))
        assertEquals(128, overlaySide(100, 128))
        // Square: a mark turning from flat to tall keeps its size.
        assertEquals(overlaySide(90, 4), overlaySide(4, 90))
    }

    // ---- lasso hits ------------------------------------------------------------

    @Test
    fun `growing loop answers exactly as a fresh test would`() {
        val random = Random(7)
        repeat(20) {
            val loop = FloatPointBuffer(2)
            val cache = LassoHitCache()
            val probes = List(60) { floatArrayOf(random.nextFloat() * 200f - 100f, random.nextFloat() * 200f - 100f) }
            // A wandering spiral: concave, self-crossing, the hard cases.
            var angle = 0.0
            var radius = 10.0
            repeat(120) { step ->
                angle += 0.3 + random.nextDouble() * 0.4
                radius += random.nextDouble() * 1.5 - 0.3
                loop.add((radius * kotlin.math.cos(angle)).toFloat(), (radius * kotlin.math.sin(angle)).toFloat())
                // Asked only now and then, as the preview is.
                if (step % 3 == 0) {
                    probes.forEachIndexed { index, p ->
                        assertEquals(loop.contains(p[0], p[1]), cache.contains(index, p[0], p[1], loop))
                    }
                }
            }
        }
    }

    @Test
    fun `asking again needs no position and knows nothing new`() {
        val loop = FloatPointBuffer(2)
        listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f).forEach { (x, y) -> loop.add(x, y) }
        val cache = LassoHitCache()
        val key = Any()
        assertNull(cache.containsAgain(key, loop))
        assertTrue(cache.contains(key, 50f, 50f, loop))
        assertEquals(true, cache.containsAgain(key, loop))
        loop.add(50f, 40f)
        assertEquals(loop.contains(50f, 50f), cache.containsAgain(key, loop))
    }

    @Test
    fun `a loop that starts over is not mistaken for the old one`() {
        val loop = FloatPointBuffer(2)
        val cache = LassoHitCache()
        listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f).forEach { (x, y) -> loop.add(x, y) }
        assertTrue(cache.contains("a", 50f, 50f, loop))
        loop.clear()
        listOf(200f to 200f, 300f to 200f, 300f to 300f).forEach { (x, y) -> loop.add(x, y) }
        assertFalse(cache.contains("a", 50f, 50f, loop))
        // A different point under the same key starts over too.
        assertTrue(cache.contains("a", 280f, 210f, loop))
    }

    @Test
    fun `too few points catch nothing`() {
        val loop = FloatPointBuffer(2)
        loop.add(0f, 0f)
        loop.add(10f, 10f)
        assertFalse(LassoHitCache().contains("a", 5f, 5f, loop))
    }

    @Test
    fun `erased strokes leave the list as a full filter would leave it`() {
        val random = Random(3)
        repeat(200) {
            val items = List(random.nextInt(0, 40)) { Any() }
            val gone = items.filter { random.nextInt(4) == 0 }.shuffled(random)
            val goneSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>()).apply { addAll(gone) }
            val expected = items.filterNot { it in goneSet }
            val list = ArrayList(items)
            removeByIdentity(list, gone, goneSet::contains)
            assertEquals(expected.size, list.size)
            expected.forEachIndexed { i, item -> assertSame(item, list[i]) }
        }
    }

    @Test
    fun `an item not in the list is passed over`() {
        val a = Any()
        val b = Any()
        val list = arrayListOf(a, b)
        removeByIdentity(list, listOf(Any()), { false })
        assertEquals(listOf(a, b), list)
    }

    // ---- brush reuse -------------------------------------------------------------

    @Test
    fun `brush keys are equal exactly when everything that makes the brush is`() {
        val key = BrushKey(tool = 0, colorArgb = 0xFF000000.toInt(), size = 5f, epsilon = 0.1f, pattern = 0)
        assertEquals(key, BrushKey(0, 0xFF000000.toInt(), 5f, 0.1f, 0))
        assertEquals(key.hashCode(), BrushKey(0, 0xFF000000.toInt(), 5f, 0.1f, 0).hashCode())
        assertFalse(key == key.copy(tool = 1))
        assertFalse(key == key.copy(colorArgb = 0x80000000.toInt()))
        assertFalse(key == key.copy(size = 5.0001f))
        assertFalse(key == key.copy(epsilon = 0.05f))
        assertFalse(key == key.copy(pattern = 2))
    }

    @Test
    fun `recent cache keeps the last few and drops the least used`() {
        val cache = RecentCache<BrushKey, Any>(2)
        val pen = BrushKey(0, 1, 5f, 0.1f, 0)
        val marker = BrushKey(1, 1, 20f, 0.1f, 0)
        val zoomed = BrushKey(0, 1, 5f, 0.05f, 0)
        var made = 0
        val penBrush = cache.getOrPut(pen) { made++; Any() }
        val markerBrush = cache.getOrPut(marker) { made++; Any() }
        assertSame(penBrush, cache.getOrPut(pen) { made++; Any() })
        assertEquals(2, made)
        // The pen was used last, so the marker goes.
        cache.getOrPut(zoomed) { made++; Any() }
        assertEquals(2, cache.size)
        assertNull(cache[marker])
        assertSame(penBrush, cache[pen])
        assertNotSame(markerBrush, cache.getOrPut(marker) { made++; Any() })
        assertEquals(4, made)
    }
}
