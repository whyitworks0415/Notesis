package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGridSelectionTest {
    private val order = listOf("a", "b", "c", "d", "e", "f")

    @Test fun `a sweep takes in every page it passes, either way`() {
        assertEquals(listOf("b", "c", "d"), sweptSelection(order, emptySet(), 1, 3, adding = true))
        assertEquals(listOf("b", "c", "d"), sweptSelection(order, emptySet(), 3, 1, adding = true))
    }

    @Test fun `a sweep keeps what was chosen before it, in page order`() {
        assertEquals(listOf("a", "c", "d", "f"), sweptSelection(order, setOf("f", "a"), 2, 3, adding = true))
    }

    @Test fun `a sweep that starts on a chosen page takes pages out`() {
        assertEquals(listOf("a", "e"), sweptSelection(order, setOf("a", "b", "c", "e"), 1, 2, adding = false))
    }

    @Test fun `a sweep going back over its path gives back what it passed`() {
        val wide = sweptSelection(order, setOf("f"), 0, 3, adding = true)
        val back = sweptSelection(order, setOf("f"), 0, 1, adding = true)
        assertEquals(listOf("a", "b", "c", "d", "f"), wide)
        assertEquals(listOf("a", "b", "f"), back)
    }

    @Test fun `out of range ends are held to the pages there are`() {
        assertEquals(order, sweptSelection(order, emptySet(), -4, 99, adding = true))
        assertTrue(sweptSelection(emptyList(), emptySet(), 0, 3, adding = true).isEmpty())
    }

    @Test fun `a typed range is 1-based, either way round, and kept to the note`() {
        assertEquals(2..6, pageRange(3, 7, 10))
        assertEquals(2..6, pageRange(7, 3, 10))
        assertEquals(0..9, pageRange(0, 40, 10))
        assertTrue(pageRange(1, 2, 0).isEmpty())
    }

    @Test fun `thumbnails are drawn as wide as a card, in steps`() {
        // Two columns in a 380 dp panel at 2.5x: a card is about 162 dp, 405 px.
        val two = pageThumbnailWidthPx(380f, 2, 2.5f)
        assertEquals(0, two % 64)
        assertTrue(two in 405..470)
        // More columns, narrower pictures; never below the floor nor above the cap.
        assertTrue(pageThumbnailWidthPx(380f, 5, 2.5f) < two)
        assertEquals(192, pageThumbnailWidthPx(200f, 5, 1f))
        assertEquals(768, pageThumbnailWidthPx(900f, 1, 3f))
        // A small change of the panel's width keeps the same pictures.
        assertEquals(two, pageThumbnailWidthPx(384f, 2, 2.5f))
    }
}
