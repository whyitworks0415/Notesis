package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentPagingTest {
    private fun document(count: Int = 100): Document = Document(
        MutableList(count) { Page(width = 100f, height = 200f) },
    )

    @Test
    fun `page lookup reaches a long document without changing boundary behavior`() {
        val document = document()
        val pitch = 200f + Document.PAGE_GAP

        assertEquals(0, document.pageIndexAt(0f))
        assertEquals(50, document.pageIndexAt(50 * pitch + 10f))
        assertEquals(99, document.pageIndexAt(Float.MAX_VALUE))
    }

    @Test
    fun `visible range contains only nearby pages`() {
        val document = document()
        val pitch = 200f + Document.PAGE_GAP

        assertEquals(40..42, document.pagesIntersecting(40 * pitch + 20f, 42 * pitch + 10f))
    }

    @Test
    fun `ink in a page gap is rejected`() {
        val document = document(2)

        assertEquals(-1, document.pageAt(50f, 220f))
        assertEquals(-1, document.pageAt(50f, 230f))
        assertEquals(1, document.pageAt(50f, 250f))
    }

    @Test
    fun `horizontal and grid layouts place pages in expected cells`() {
        val horizontal = document(3).apply { layoutMode = PageLayoutMode.HORIZONTAL }
        assertEquals(0f, horizontal.topOf(2))
        assertEquals(2 * (100f + Document.PAGE_GAP), horizontal.leftOf(2))
        assertEquals(2, horizontal.pageAt(horizontal.leftOf(2) + 10f, 10f))

        val grid = document(4).apply { layoutMode = PageLayoutMode.GRID_2X2 }
        assertEquals(100f + Document.PAGE_GAP, grid.leftOf(1))
        assertEquals(200f + Document.PAGE_GAP, grid.topOf(2))
        assertEquals(2, grid.pageAt(10f, grid.topOf(2) + 10f))
    }
}
