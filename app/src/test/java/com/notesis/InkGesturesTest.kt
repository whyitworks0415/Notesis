package com.notesis

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
}
