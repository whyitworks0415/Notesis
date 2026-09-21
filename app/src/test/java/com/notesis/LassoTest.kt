package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the loop caught depends entirely on this, so it is worth pinning down. */
class LassoTest {

    /** A 100x100 square, drawn as a lasso would be: open, not closed. */
    private val square = listOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)

    @Test
    fun `catches what is inside`() {
        assertTrue(insidePolygon(square, 50f, 50f))
        assertTrue(insidePolygon(square, 1f, 99f))
    }

    @Test
    fun `leaves what is outside`() {
        assertFalse(insidePolygon(square, 150f, 50f))
        assertFalse(insidePolygon(square, -1f, 50f))
        assertFalse(insidePolygon(square, 50f, 200f))
    }

    @Test
    fun `a concave loop does not catch the bite taken out of it`() {
        // A C shape: the gap in the middle of the right side is outside it.
        val c = listOf(
            0f, 0f, 100f, 0f, 100f, 30f, 40f, 30f,
            40f, 70f, 100f, 70f, 100f, 100f, 0f, 100f,
        )
        assertTrue(insidePolygon(c, 20f, 50f))
        assertFalse(insidePolygon(c, 80f, 50f))
    }

    @Test
    fun `too few points catch nothing`() {
        assertFalse(insidePolygon(listOf(0f, 0f, 10f, 10f), 5f, 5f))
        assertFalse(insidePolygon(emptyList(), 0f, 0f))
    }

    @Test
    fun `primitive path drops nearby samples without changing its last point`() {
        val path = FloatPointBuffer(2)
        assertTrue(path.addIfFarEnough(0f, 0f, 2f))
        assertFalse(path.addIfFarEnough(1f, 0f, 2f))
        assertTrue(path.addIfFarEnough(2f, 0f, 2f))

        assertEquals(4, path.size)
        assertEquals(2f, path[2])
    }

    @Test
    fun `primitive path keeps polygon selection semantics`() {
        val path = FloatPointBuffer(2)
        path.add(0f, 0f)
        path.add(100f, 0f)
        path.add(100f, 100f)
        path.add(0f, 100f)

        assertTrue(path.contains(50f, 50f))
        assertFalse(path.contains(150f, 50f))
    }

    @Test
    fun `eraser skips covered sub-tip moves but always accepts the final sample`() {
        assertFalse(shouldProcessEraserMove(1f, 0f, eraserWidth = 24f, force = false))
        assertTrue(shouldProcessEraserMove(2f, 0f, eraserWidth = 24f, force = false))
        assertTrue(shouldProcessEraserMove(0f, 0f, eraserWidth = 24f, force = true))
    }
}
