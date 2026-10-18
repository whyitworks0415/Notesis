package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapBudgetTest {
    @Test
    fun `small bitmaps are left alone`() {
        assertEquals(1f, bitmapBudgetScale(1000, 1000, 64L shl 20, 4096), 0f)
    }

    @Test
    fun `memory budget scales both sides by the same root`() {
        // 4000 x 4000 x 4 = 64,000,000 bytes against a 16,000,000 budget: half each side.
        assertEquals(0.5f, bitmapBudgetScale(4000, 4000, 16_000_000L, 8192), 1e-4f)
    }

    @Test
    fun `the longest side is held to the limit`() {
        assertEquals(0.5f, bitmapBudgetScale(8192, 100, 1L shl 40, 4096), 1e-6f)
    }
}
