package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSidebarPartsTest {
    @Test fun `the page panel keeps between its narrowest and most of the window`() {
        assertEquals(PAGE_SIDEBAR_MIN_DP, pageSidebarWidth(10f, 1200f))
        assertEquals(400f, pageSidebarWidth(400f, 1200f))
        // Three quarters of a 600 dp window, at most.
        assertEquals(450f, pageSidebarWidth(700f, 600f))
        assertEquals(PAGE_SIDEBAR_MAX_DP, pageSidebarWidth(5000f, 3000f))
        assertEquals(PAGE_SIDEBAR_DEFAULT_DP, pageSidebarWidth(Float.NaN, 1200f))
        // A window too narrow for three quarters still gets the narrowest panel.
        assertEquals(PAGE_SIDEBAR_MIN_DP, pageSidebarWidth(300f, 280f))
    }

    @Test fun `a strip of tape is placed in words by thirds of its page`() {
        assertEquals("Top left", maskPlace(10f, 10f, 300f, 300f))
        assertEquals("Top", maskPlace(150f, 10f, 300f, 300f))
        assertEquals("Middle", maskPlace(150f, 150f, 300f, 300f))
        assertEquals("Right", maskPlace(290f, 150f, 300f, 300f))
        assertEquals("Bottom right", maskPlace(290f, 299f, 300f, 300f))
        assertEquals("Bottom left", maskPlace(0f, 300f, 300f, 300f))
    }

    @Test fun `pictures are read at the largest step that still covers the width wanted`() {
        assertEquals(1, sampleSizeFor(400, 480))
        assertEquals(1, sampleSizeFor(959, 480))
        assertEquals(2, sampleSizeFor(960, 480))
        assertEquals(4, sampleSizeFor(2000, 480))
        assertEquals(1, sampleSizeFor(0, 480))
    }
}
