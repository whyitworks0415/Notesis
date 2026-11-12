package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupDropTest {
    @Test fun `it starts as a round drop at the button, kept inside the panel`() {
        for ((x, y) in listOf(100f to -20f, -50f to 100f, 500f to 100f, 100f to 900f, Float.NaN to Float.NaN)) {
            val start = popupDrop(x, y, 300f, 400f, 44f, 28f, 0f, 0f)
            assertEquals(44f, start.width, 0f)
            assertEquals(44f, start.height, 0f)
            assertEquals(22f, start.radius, 0f)
            assertTrue(start.left >= 0f && start.top >= 0f)
            assertTrue(start.left + start.width <= 300.001f && start.top + start.height <= 400.001f)
        }
        // Under a button above its top right: it comes out at the top, under the button.
        val under = popupDrop(250f, -20f, 300f, 400f, 44f, 28f, 0f, 0f)
        assertEquals(228f, under.left, 0.001f)
        assertEquals(0f, under.top, 0.001f)
    }

    @Test fun `it runs down before it spreads, and lands on the panel with its own corners`() {
        // The height ahead of the width, as the width waits behind it.
        val running = popupDrop(250f, -20f, 300f, 400f, 44f, 28f, 0.2f, 0.7f)
        assertTrue(running.height > running.width)
        // Still round while it is narrow.
        assertEquals(running.width / 2f, running.radius, 1f)
        val open = popupDrop(250f, -20f, 300f, 400f, 44f, 28f, 1f, 1f)
        assertEquals(PopupDrop(0f, 0f, 300f, 400f, 28f), open)
        // Every frame on the way stays inside the panel and rounded.
        for (step in 0..100) {
            val p = step / 100f
            val f = popupDrop(250f, -20f, 300f, 400f, 44f, 28f, p * p, p)
            assertTrue(f.left >= -0.001f && f.top >= -0.001f)
            assertTrue(f.left + f.width <= 300.001f && f.top + f.height <= 400.001f)
            assertTrue(f.radius > 0f && f.radius <= minOf(f.width, f.height) / 2f + 0.001f)
        }
    }

    @Test fun `a progress past either end is held at that end`() {
        val over = popupDrop(150f, -20f, 300f, 400f, 44f, 28f, 1.08f, 1.08f)
        assertEquals(300f, over.width, 0f)
        assertEquals(400f, over.height, 0f)
        assertEquals(1f, unitProgress(1.2f), 0f)
        assertEquals(0f, unitProgress(-0.1f), 0f)
        assertEquals(0f, unitProgress(Float.NaN), 0f)
    }

    @Test fun `tiny or unmeasured panels give no outline to invert`() {
        assertEquals(0f, popupDrop(0f, 0f, 0f, 0f, 44f, 28f, 0f, 0f).width, 0f)
        val tiny = popupDrop(Float.NaN, Float.NaN, 20f, 10f, 44f, 28f, 0f, 0f)
        assertEquals(10f, tiny.width, 0f)
        assertTrue(tiny.radius <= 5f)
    }

    @Test fun `rows come up once the drop has run half way and go first on the way back`() {
        assertEquals(0f, popupContentAlpha(0.3f, closing = false), 0f)
        assertTrue(popupContentAlpha(0.7f, closing = false) in 0.1f..0.95f)
        assertEquals(1f, popupContentAlpha(0.98f, closing = false), 0f)
        assertEquals(1f, popupContentAlpha(1.07f, closing = false), 0f)
        // Closing, they are gone while the glass is still nearly its size.
        assertEquals(1f, popupContentAlpha(1f, closing = true), 0f)
        assertEquals(0f, popupContentAlpha(0.85f, closing = true), 0f)
    }
}
