package com.notesis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DabOutlinesTest {
    /**
     * A dab stroke as Ink grows one: round dabs laid along a path, the last
     * few volatile (replaced on every update, as a prediction is), and the
     * region it reports covering whatever it added, changed or took back.
     */
    private class FakeStroke(coats: Int) : OutlineSource {
        val outlines = List(coats) { ArrayList<FloatArray>() }
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        override fun coatCount() = outlines.size
        override fun outlineCount(coat: Int) = outlines[coat].size
        override fun vertexCount(coat: Int, outline: Int) = outlines[coat][outline].size / 2
        override fun position(coat: Int, outline: Int, vertex: Int, into: FloatArray, at: Int) {
            into[at] = outlines[coat][outline][vertex * 2]
            into[at + 1] = outlines[coat][outline][vertex * 2 + 1]
        }

        private fun touch(points: FloatArray) {
            for (i in points.indices step 2) {
                minX = minOf(minX, points[i]); maxX = maxOf(maxX, points[i])
                minY = minOf(minY, points[i + 1]); maxY = maxOf(maxY, points[i + 1])
            }
        }

        fun add(coat: Int, dab: FloatArray) {
            outlines[coat] += dab
            touch(dab)
        }

        fun takeBack(coat: Int, count: Int) {
            repeat(minOf(count, outlines[coat].size)) { touch(outlines[coat].removeAt(outlines[coat].size - 1)) }
        }

        /** Syncs [cache] with the region since the last call, then forgets it, as the view does. */
        fun syncInto(cache: DabOutlineCache) {
            cache.sync(this, minX, minY, maxX, maxY)
            minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY
            maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY
        }
    }

    private fun dab(x: Float, y: Float, r: Float, sides: Int = 16) = FloatArray(sides * 2) { i ->
        val angle = (i / 2) * 2.0 * Math.PI / sides
        if (i % 2 == 0) x + r * cos(angle).toFloat() else y + r * sin(angle).toFloat()
    }

    private fun held(cache: DabOutlineCache, coat: Int,
        left: Float = Float.NEGATIVE_INFINITY, top: Float = Float.NEGATIVE_INFINITY,
        right: Float = Float.POSITIVE_INFINITY, bottom: Float = Float.POSITIVE_INFINITY): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        cache.forEachMeeting(coat, left, top, right, bottom) { points, count -> out += points.copyOf(count * 2) }
        return out
    }

    private fun assertSame(stroke: FakeStroke, cache: DabOutlineCache) {
        for (coat in 0 until stroke.coatCount()) {
            val expected = stroke.outlines[coat]
            val actual = held(cache, coat)
            assertEquals(expected.size, actual.size)
            for (i in expected.indices) assertArrayEquals(expected[i], actual[i], 0f)
        }
    }

    /** One frame of writing: the volatile end replaced, [fresh] new dabs laid along a loop. */
    private fun write(stroke: FakeStroke, at: IntArray, fresh: Int, volatile: Int = 3) {
        for (coat in 0 until stroke.coatCount()) stroke.takeBack(coat, volatile)
        val next = at[0] + fresh
        for (i in (at[0] - volatile).coerceAtLeast(0) until next + volatile) {
            // A loop that keeps crossing its own earlier dabs, as handwriting does.
            val t = i * 0.05f
            val x = 200f + 150f * cos(t) + i * 0.3f
            val y = 200f + 150f * sin(t * 1.7f)
            stroke.add(0, dab(x, y, 6f))
            if (stroke.coatCount() > 1) stroke.add(1, dab(x + 3f, y - 2f, 1.5f, sides = 6))
        }
        at[0] = next
    }

    @Test fun `the cache holds exactly the stroke's outlines through every frame`() {
        val stroke = FakeStroke(coats = 2)
        val cache = DabOutlineCache()
        val at = intArrayOf(0)
        repeat(400) {
            write(stroke, at, fresh = 1 + it % 5)
            stroke.syncInto(cache)
            assertSame(stroke, cache)
        }
    }

    @Test fun `a frame reads back what changed, not the whole stroke`() {
        val stroke = FakeStroke(coats = 1)
        val cache = DabOutlineCache()
        val at = intArrayOf(0)
        // A straight run, so the region never crosses earlier dabs.
        fun straight(fresh: Int) {
            stroke.takeBack(0, 3)
            val next = at[0] + fresh
            for (i in (at[0] - 3).coerceAtLeast(0) until next + 3) stroke.add(0, dab(i * 2f, 50f, 6f))
            at[0] = next
        }
        straight(2000)
        stroke.syncInto(cache)
        assertEquals(stroke.outlineCount(0).toLong(), cache.reads)
        var worst = 0L
        repeat(200) {
            val before = cache.reads
            straight(4)
            stroke.syncInto(cache)
            worst = maxOf(worst, cache.reads - before)
        }
        assertSame(stroke, cache)
        // Four new dabs, three replaced, the neighbours their region
        // overlaps and the tail - not the 2000-odd a full rebuild reads.
        assertTrue("read $worst", worst <= 32)
    }

    @Test fun `nothing changed reads nothing`() {
        val stroke = FakeStroke(coats = 1)
        val cache = DabOutlineCache()
        write(stroke, intArrayOf(0), fresh = 50)
        stroke.syncInto(cache)
        val before = cache.reads
        stroke.syncInto(cache)
        assertEquals(before, cache.reads)
    }

    @Test fun `an unknown change reads everything again`() {
        val stroke = FakeStroke(coats = 1)
        val cache = DabOutlineCache()
        write(stroke, intArrayOf(0), fresh = 50)
        stroke.syncInto(cache)
        // Changed behind the region's back: only a full read can see it.
        stroke.outlines[0][10] = dab(-500f, -500f, 6f)
        cache.syncAll(stroke)
        assertSame(stroke, cache)
    }

    @Test fun `a clipped draw gets every outline that reaches the clip and no others`() {
        val stroke = FakeStroke(coats = 1)
        val cache = DabOutlineCache()
        write(stroke, intArrayOf(0), fresh = 300)
        stroke.syncInto(cache)
        val left = 150f; val top = 120f; val right = 190f; val bottom = 160f
        val got = held(cache, 0, left, top, right, bottom)
        val expected = stroke.outlines[0].filter { points ->
            var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
            for (i in points.indices step 2) {
                minX = minOf(minX, points[i]); maxX = maxOf(maxX, points[i])
                minY = minOf(minY, points[i + 1]); maxY = maxOf(maxY, points[i + 1])
            }
            minX <= right && maxX >= left && minY <= bottom && maxY >= top
        }
        assertTrue(expected.isNotEmpty())
        assertTrue(expected.size < stroke.outlines[0].size)
        assertEquals(expected.size, got.size)
        for (i in expected.indices) assertArrayEquals(expected[i], got[i], 0f)
    }

    @Test fun `a cleared cache starts the next stroke from nothing`() {
        val stroke = FakeStroke(coats = 2)
        val cache = DabOutlineCache()
        write(stroke, intArrayOf(0), fresh = 40)
        stroke.syncInto(cache)
        cache.clear()
        assertEquals(0, cache.outlineCount(0))
        val next = FakeStroke(coats = 2)
        write(next, intArrayOf(0), fresh = 7)
        next.syncInto(cache)
        assertSame(next, cache)
    }

    @Test fun `a dab stroke weighs what its dabs add up to, a line one stroke`() {
        // A pen or graphite line: one outline.
        assertEquals(1, dabDrawWeight(1))
        assertEquals(1, dabDrawWeight(0))
        // Stamp dabs come about 14 to a brush width of travel: a 400-unit
        // stroke at the default width of 24 has some 240 of them.
        assertEquals(15, dabDrawWeight(240))
        // Soft pencil at width 3 lays about 19 per width, two coats: 2600 in
        // the same 400 units, worth a page of ordinary writing on its own.
        assertEquals(162, dabDrawWeight(2600))
    }
}
