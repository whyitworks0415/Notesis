package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Scribble-out erases ink, so what is not a scribble matters as much as what is. */
class InkGesturesTest {

    private fun path(points: List<Pair<Float, Float>>): Pair<FloatArray, FloatArray> =
        FloatArray(points.size) { points[it].first } to FloatArray(points.size) { points[it].second }

    /** [passes] straight strokes across [width], drifting down a little, densely sampled. */
    private fun zigzag(passes: Int, width: Float = 200f): Pair<FloatArray, FloatArray> {
        val points = ArrayList<Pair<Float, Float>>()
        for (pass in 0 until passes) {
            for (step in 0..20) {
                val t = step / 20f
                val x = if (pass % 2 == 0) t * width else width - t * width
                points += x to (pass * 4f + t * 4f)
            }
        }
        return path(points)
    }

    @Test
    fun `back and forth over a word is a scribble`() {
        val (xs, ys) = zigzag(5)
        assertTrue(isScribble(xs, ys, xs.size))
        val (vx, vy) = zigzag(6).let { (x, y) -> y to x } // the same, vertically
        assertTrue(isScribble(vx, vy, vx.size))
    }

    @Test
    fun `two passes are a tick mark, not a scribble`() {
        val (xs, ys) = zigzag(2)
        assertFalse(isScribble(xs, ys, xs.size))
    }

    @Test
    fun `a loop drawn three times is a circle, not a scribble`() {
        val points = (0..360).map {
            val t = it / 120.0 * 2 * Math.PI
            (100f + 80f * cos(t).toFloat()) to (100f + 80f * sin(t).toFloat())
        }
        val (xs, ys) = path(points)
        assertFalse(isScribble(xs, ys, xs.size))
    }

    @Test
    fun `cursive humps moving forward are writing`() {
        // m m m m: up and down, but always moving right along the longer side.
        val points = (0..400).map { val t = it / 100f; t * 60f to (if ((it / 25) % 2 == 0) (it % 25) * 2f else 50f - (it % 25) * 2f) }
        val (xs, ys) = path(points)
        assertFalse(isScribble(xs, ys, xs.size))
    }

    @Test
    fun `a straight line is not a scribble`() {
        val (xs, ys) = path((0..50).map { it * 4f to 0f })
        assertFalse(isScribble(xs, ys, xs.size))
    }

    @Test
    fun `a loop closes when the pen comes back near where it started`() {
        assertTrue(isLassoLoop(gap = 20f, width = 200f, height = 150f, minExtent = 30f))
        assertFalse(isLassoLoop(gap = 150f, width = 200f, height = 150f, minExtent = 30f))
        // A flat line that happens to end near its start is not a loop.
        assertFalse(isLassoLoop(gap = 5f, width = 200f, height = 4f, minExtent = 30f))
    }

    @Test
    fun alignTakesTheNearestTargetWithinReach() {
        // Middle at 98 is 2 from the page middle at 100; the left edge is 7 from 0.
        val snap = alignOffset(floatArrayOf(7f, 98f, 189f), floatArrayOf(0f, 100f, 200f), reach = 10f)
        assertEquals(2f to 100f, snap)
        assertEquals(null, alignOffset(floatArrayOf(30f), floatArrayOf(0f, 100f), reach = 10f))
    }

    @Test
    fun `guides follow the switches`() {
        val pic = floatArrayOf(100f, 100f, 300f, 200f)
        val pageOnly = snapGuides(1000f, 2000f, pic, FloatArray(0), SnapTo.PAGE)
        assertEquals(listOf(0f, 500f, 1000f), pageOnly.xs.toList())
        assertEquals(0, pageOnly.boxes.size)
        val all = snapGuides(1000f, 2000f, pic, FloatArray(0), SnapTo.ALL)
        assertTrue(65f in all.xs.toList() && 935f in all.xs.toList())
        assertTrue(200f in all.xs.toList() && 150f in all.ys.toList())
        assertEquals(pic.toList(), all.boxes.toList())
    }

    @Test
    fun `strokes near each other make one block`() {
        val letters = floatArrayOf(0f, 0f, 10f, 20f, 14f, 2f, 24f, 20f, 300f, 0f, 310f, 20f)
        val blocks = inkBlocks(letters, gap = 8f)
        assertEquals(listOf(0f, 0f, 24f, 20f, 300f, 0f, 310f, 20f), blocks.toList())
        // A stroke joining two blocks makes them one.
        assertEquals(4, inkBlocks(floatArrayOf(0f, 0f, 10f, 10f, 30f, 0f, 40f, 10f, 9f, 0f, 31f, 10f), 2f).size)
    }

    @Test
    fun `a selection centres between its neighbours`() {
        // Boxes at 0..100 and 300..400; the selection 160..210 is 60 from one and 90 from the other.
        val shift = evenGapShift(160f, 210f, floatArrayOf(0f, 300f), floatArrayOf(100f, 400f), reach = 20f)!!
        assertEquals(15f, shift[0], 0.001f)
        assertEquals(100f, shift[1]); assertEquals(300f, shift[2])
    }

    @Test
    fun `a selection keeps the gap its neighbours keep`() {
        // 0..100 and 130..230 stand 30 apart; the selection after them, at 268..300, catches 30 from 230.
        val shift = evenGapShift(268f, 300f, floatArrayOf(0f, 130f), floatArrayOf(100f, 230f), reach = 10f)!!
        assertEquals(-8f, shift[0], 0.001f)
        assertEquals(null, evenGapShift(400f, 450f, floatArrayOf(0f, 130f), floatArrayOf(100f, 230f), reach = 10f))
    }
}
