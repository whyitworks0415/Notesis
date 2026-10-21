package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishCountsTest {
    @Test
    fun singularOnlyForOne() {
        assertEquals("1 page", countNoun(1, "page"))
        assertEquals("0 pages", countNoun(0, "page"))
        assertEquals("3 pages", countNoun(3, "page"))
    }

    @Test
    fun irregularPlural() {
        assertEquals("2 boxes", countNoun(2, "box", "boxes"))
        assertEquals("1 box", countNoun(1, "box", "boxes"))
    }
}
