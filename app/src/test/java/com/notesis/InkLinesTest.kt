package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A recogniser handed a whole page returns a whole page of nonsense, so the
 * lines have to be recovered from the geometry first. This is that.
 */
class InkLinesTest {

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        InkBox(left, top, right, bottom)

    @Test
    fun `strokes on the same line group together, left to right`() {
        val boxes = listOf(
            box(300f, 100f, 340f, 130f),
            box(100f, 102f, 140f, 132f),
            box(200f, 98f, 240f, 128f),
        )
        assertEquals(listOf(listOf(1, 2, 0)), groupIntoLines(boxes))
    }

    @Test
    fun `a stroke well below starts a new line`() {
        val boxes = listOf(
            box(100f, 100f, 140f, 130f),
            box(100f, 300f, 140f, 330f),
        )
        assertEquals(listOf(listOf(0), listOf(1)), groupIntoLines(boxes))
    }

    @Test
    fun `lines come back in reading order`() {
        val boxes = listOf(
            box(100f, 300f, 140f, 330f),
            box(100f, 100f, 140f, 130f),
        )
        assertEquals(listOf(listOf(1), listOf(0)), groupIntoLines(boxes))
    }

    @Test
    fun `a tall stroke does not swallow the line below it`() {
        // A bracket down the margin overlaps two lines; it joins the first and
        // must not drag the second into it.
        val boxes = listOf(
            box(60f, 100f, 70f, 200f),
            box(100f, 100f, 140f, 130f),
            box(100f, 400f, 140f, 430f),
        )
        val lines = groupIntoLines(boxes)
        assertEquals(2, lines.size)
        assertEquals(listOf(2), lines[1])
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList<List<Int>>(), groupIntoLines(emptyList()))
    }
}
