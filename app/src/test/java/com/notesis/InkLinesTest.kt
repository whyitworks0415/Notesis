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

    @Test
    fun `a syllable stacked in its square is one line, not three`() {
        // 를: ㄹ, ㅡ and ㄹ one under another, none overlapping the next in height.
        val boxes = listOf(
            box(100f, 100f, 140f, 120f),
            box(98f, 126f, 142f, 129f),
            box(100f, 134f, 140f, 154f),
        )
        assertEquals(listOf(listOf(0, 1, 2)), groupIntoLines(boxes))
    }

    @Test
    fun `stacked syllables on two lines stay two lines`() {
        val first = listOf(box(100f, 100f, 140f, 120f), box(98f, 126f, 142f, 129f), box(100f, 134f, 140f, 154f))
        val second = first.map { box(it.left, it.top + 110f, it.right, it.bottom + 110f) }
        val lines = groupIntoLines(first + second)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), lines)
    }

    @Test
    fun `a sloping line is one line, and its tilt is measured`() {
        val boxes = (0 until 6).map { i -> box(100f + 50f * i, 100f + 12f * i, 130f + 50f * i, 130f + 12f * i) }
        val lines = inkLines(boxes)
        assertEquals(1, lines.size)
        assertEquals((0 until 6).toList(), lines[0].strokes)
        assertEquals(kotlin.math.atan(12.0 / 50.0).toFloat(), lines[0].tilt, 0.01f)
    }

    @Test
    fun `two sloping lines one under the other stay two lines`() {
        val first = (0 until 6).map { i -> box(100f + 50f * i, 100f + 12f * i, 130f + 50f * i, 130f + 12f * i) }
        val second = first.map { box(it.left, it.top + 75f, it.right, it.bottom + 75f) }
        val lines = groupIntoLines(first + second)
        assertEquals(listOf((0 until 6).toList(), (6 until 12).toList()), lines)
    }

    @Test
    fun `a level line has no tilt`() {
        val boxes = (0 until 6).map { i -> box(100f + 50f * i, 100f, 130f + 50f * i, 130f) }
        assertEquals(0f, inkLines(boxes)[0].tilt, 0f)
    }

    @Test
    fun `letters go left to right, each letter's strokes as written`() {
        // Written: the right-hand letter first, then the left one's lower
        // stroke, then its upper one.
        val boxes = listOf(
            box(200f, 100f, 240f, 140f),
            box(100f, 124f, 140f, 140f),
            box(100f, 100f, 140f, 118f),
        )
        assertEquals(listOf(listOf(1, 2, 0)), groupIntoLines(boxes))
    }

    @Test
    fun `words far apart on one line are still one line`() {
        val boxes = listOf(box(100f, 100f, 130f, 130f), box(600f, 102f, 630f, 132f))
        assertEquals(listOf(listOf(0, 1)), groupIntoLines(boxes))
    }

    @Test
    fun `a reading in its own script fits its language`() {
        assertEquals(1f, scriptFit("안녕하세요", "ko"), 0f)
        assertEquals(0f, scriptFit("hello", "ko"), 0f)
        assertEquals(1f, scriptFit("hello", "en"), 0f)
        assertEquals(0.5f, scriptFit("123 - 4", "en"), 0f)
        assertEquals(1f, scriptFit("日本", "zh-Hani-CN"), 0f)
    }
}
