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

    @Test fun `fit width is cached between touch samples and follows layout invalidation`() {
        var reads = 0
        val backing = MutableList(200) { Page(width = 100f, height = 200f) }
        val counted = object : AbstractMutableList<Page>() {
            override val size get() = backing.size
            override fun get(index: Int): Page { reads++; return backing[index] }
            override fun add(index: Int, element: Page) { backing.add(index, element) }
            override fun removeAt(index: Int) = backing.removeAt(index)
            override fun set(index: Int, element: Page) = backing.set(index, element)
        }
        val doc = Document(counted)
        assertEquals(100f, doc.fitWidth())
        reads = 0
        repeat(240) { assertEquals(100f, doc.fitWidth()) }
        assertEquals(0, reads)
        backing[100].width = 300f
        doc.invalidateLayout()
        assertEquals(300f, doc.fitWidth())
        doc.layoutMode = PageLayoutMode.SPREAD_2X1
        assertEquals(600f + Document.PAGE_GAP, doc.fitWidth())
    }

    @Test fun `infinite paper uses a sheet for fitting rather than its expanded width`() {
        val page = Page(width = 10000f, height = 200f, background = PageBackground.INFINITE_ALL)
        val doc = Document(mutableListOf(page))
        assertEquals(Page.A4_WIDTH, doc.fitWidth())
        page.background = PageBackground.BLANK
        doc.invalidateLayout()
        assertEquals(10000f, doc.fitWidth())
    }
}
