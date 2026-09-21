package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfCachePolicyTest {
    @Test
    fun `role budgets add up to the exact byte ceiling`() {
        val budget = splitPdfCacheBudget(101)
        assertEquals(101, budget.totalBytes)
        assertTrue(budget.previewBytes < budget.fullPageBytes)
        assertTrue(budget.tileBytes > 0)
    }

    @Test
    fun `cache budget is bounded independently of document page count`() {
        assertEquals(PDF_CACHE_MIN_BYTES, pdfCacheBytesForMemoryClass(128, lowRam = true))
        assertEquals(PDF_CACHE_MAX_BYTES, pdfCacheBytesForMemoryClass(4096, lowRam = false))
        assertEquals(512 * 1024 * 1024 / 8, pdfCacheBytesForMemoryClass(512, lowRam = false))
    }

    @Test
    fun `only visible pages receive the requested full width`() {
        val full = setOf(50, 51)
        assertEquals(2048, pdfRenderWidthForPage(50, full, 2048, 1024))
        assertEquals(1024, pdfRenderWidthForPage(49, full, 2048, 1024))
        assertEquals(768, pdfRenderWidthForPage(49, full, 768, 1024))
    }

    @Test
    fun `a thousand page document still has only viewport full resolution pages`() {
        val visible = setOf(499, 500)
        val widths = (0 until 1_000).map {
            pdfRenderWidthForPage(it, visible, requestedWidth = 2048, previewWidth = 1024)
        }
        assertEquals(2, widths.count { it == 2048 })
        assertEquals(998, widths.count { it == 1024 })
    }

    @Test
    fun `page visibility and zoom bucket each advance cache generation`() {
        val pages = setOf(9, 10, 11)
        assertTrue(pdfViewportCacheChanged(pages, setOf(10, 11, 12), setOf(10), setOf(10), 1024, 1024))
        assertTrue(pdfViewportCacheChanged(pages, pages, setOf(10), setOf(10, 11), 1024, 1024))
        assertTrue(pdfViewportCacheChanged(pages, pages, setOf(10), setOf(10), 1024, 2048))
        assertFalse(pdfViewportCacheChanged(pages, pages, setOf(10), setOf(10), 1024, 1024))
    }
}
