package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class PageNavigationTest {

    @Test
    fun `restored page stays inside a changed note`() {
        assertEquals(0, restoredPage(-3, 8))
        assertEquals(4, restoredPage(4, 8))
        assertEquals(7, restoredPage(20, 8))
        assertEquals(0, restoredPage(4, 0))
    }

    @Test
    fun `scrubber maps its usable travel from first page to last`() {
        assertEquals(0, scrubbedPage(25f, 500f, 50f, 10))
        assertEquals(5, scrubbedPage(275f, 500f, 50f, 10))
        assertEquals(9, scrubbedPage(475f, 500f, 50f, 10))
    }

    @Test
    fun `single page note never leaves its only page`() {
        assertEquals(0, scrubbedPage(400f, 500f, 50f, 1))
    }
}
