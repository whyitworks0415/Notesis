package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

/** The library's search, from a field in the middle to a round button at the right. */
class SearchMorphTest {

    @Test
    fun `open, the field is full width in the middle`() {
        assertEquals(140 to 720, searchMorphSpan(row = 1000, full = 720, side = 42, fold = 0f))
    }

    @Test
    fun `folded, a square at the right end`() {
        assertEquals(958 to 42, searchMorphSpan(row = 1000, full = 720, side = 42, fold = 1f))
    }

    @Test
    fun `half way, narrower and on its way right, never past the end`() {
        val (x, w) = searchMorphSpan(row = 1000, full = 720, side = 42, fold = 0.5f)
        assertEquals(381, w)
        assert(x in 141 until 958)
        assert(x + w <= 1000)
    }

    @Test
    fun `a row narrower than the field takes the whole row`() {
        assertEquals(0 to 400, searchMorphSpan(row = 400, full = 720, side = 42, fold = 0f))
    }

    @Test
    fun `a fold out of range is held to the ends`() {
        assertEquals(searchMorphSpan(1000, 720, 42, 1f), searchMorphSpan(1000, 720, 42, 3f))
        assertEquals(searchMorphSpan(1000, 720, 42, 0f), searchMorphSpan(1000, 720, 42, -1f))
    }
}
