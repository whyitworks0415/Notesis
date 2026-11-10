package com.notesis

import org.junit.Assert.*
import org.junit.Test

class BenchmarkInkTest {
    @Test fun `ending a run clears all temporary ink`() {
        val page = Any(); val test = Any()
        val ink = BenchmarkInk<Any, Any>()
        ink.begin()
        assertTrue(ink.add(ink.session, page, test))
        assertEquals(listOf(test), ink.onPage(page))
        ink.end()
        assertFalse(ink.active)
        assertEquals(0, ink.size)
        assertTrue(ink.onPage(page).isEmpty())
    }

    @Test fun `callbacks after cancellation or from a previous run cannot add ink`() {
        val ink = BenchmarkInk<Any, Any>(); val page = Any()
        ink.begin(); val first = ink.session
        ink.end()
        assertFalse(ink.add(first, page, Any()))
        ink.begin()
        assertFalse(ink.add(first, page, Any()))
        assertFalse(ink.add(0L, page, Any()))
        assertTrue(ink.add(ink.session, page, Any()))
        assertEquals(1, ink.size)
    }

    @Test fun `temporary strokes stay on their own pages and all clear together`() {
        data class EqualPage(val id: String)
        val ink = BenchmarkInk<Any, Any>(); val first = EqualPage("same"); val second = EqualPage("same")
        ink.begin()
        repeat(30) { ink.add(ink.session, if (it % 2 == 0) first else second, Any()) }
        assertEquals(15, ink.onPage(first).size)
        assertEquals(15, ink.onPage(second).size)
        assertEquals(30, ink.size)
        assertTrue(ink.onPage(EqualPage("same")).isEmpty())
        ink.end()
        assertTrue(ink.onPage(first).isEmpty())
        assertTrue(ink.onPage(second).isEmpty())
    }
}
