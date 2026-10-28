package com.notesis

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

class SpotiGlassToolbarTest {
    @Test fun `narrow full toolbar shows every tool without scrolling and preserves touch areas`() {
        val tools = spotiToolbarTools(0)
        for (width in listOf(110f, 176f, 264f, 400f, 532f, 820f)) {
            val rows = spotiToolRows(tools, width)
            assertEquals(tools, rows.flatten())
            assertTrue(rows.all { it.isNotEmpty() })
            assertTrue(rows.all { it.size * 44 + 12 <= width })
        }
        assertTrue(spotiToolRows(emptyList(), 300f).isEmpty())
    }

    @Test fun `quick mode has exactly the four sketched tools`() {
        assertEquals(listOf(EditMode.READ, EditMode.PEN, EditMode.HIGHLIGHTER, EditMode.MASK),
            spotiToolbarTools(2).map { it.mode })
    }

    @Test fun `single row preserves capture lasso text and AI while minimal mode has no tool row`() {
        val tools = spotiToolbarTools(1)
        assertTrue(tools.contains(SpotiToolbarTool.CAPTURE))
        assertTrue(tools.contains(SpotiToolbarTool.LASSO))
        assertTrue(tools.contains(SpotiToolbarTool.TEXT))
        assertTrue(tools.contains(SpotiToolbarTool.AI))
        assertEquals(tools.size, tools.distinct().size)
        assertEquals(spotiToolbarTools(0), tools)
        assertTrue(spotiToolbarTools(3).isEmpty())
    }

    @Test fun `each mode reserves less space while leaving a reachable minimum button`() {
        val widths = (0..3).map { spotiToolbarWidth(it).value }
        assertTrue(widths.zipWithNext().all { (a, b) -> a > b })
        assertTrue(widths.last() >= 44f + 24f)
    }

    @Test fun `menus stay on screen when the minimal handle is dragged to a corner`() {
        val position = SpotiToolbarPopupPosition(false, 8)
        val window = IntSize(800, 600)
        val menu = IntSize(340, 400)
        for (anchor in listOf(IntRect(0, 0, 44, 44), IntRect(756, 556, 800, 600))) {
            for (direction in LayoutDirection.entries) {
                val placed = position.calculatePosition(anchor, window, direction, menu)
                assertTrue(placed.x >= 8 && placed.x + menu.width <= window.width - 8)
                assertTrue(placed.y >= 8 && placed.y + menu.height <= window.height - 8)
            }
        }
    }

    @Test fun `size menu follows the size trigger and mirrors for RTL`() {
        val position = SpotiToolbarPopupPosition(true, 8)
        val anchor = IntRect(100, 40, 700, 120)
        assertEquals(100, position.calculatePosition(anchor, IntSize(800, 600), LayoutDirection.Ltr, IntSize(340, 180)).x)
        assertEquals(360, position.calculatePosition(anchor, IntSize(800, 600), LayoutDirection.Rtl, IntSize(340, 180)).x)
    }

    @Test fun `a bar that would fold onto a second row is the one that turns down the side`() {
        val tools = SpotiToolbarTool.entries.take(8)
        val dp = { v: Float -> androidx.compose.ui.unit.Dp(v) }
        // Room for the 2/4 bar: one row, stays across.
        assertFalse(spotiBarWraps(tools, dp(900f), dp(632f), 1f))
        // Too narrow for it: two rows, turns.
        assertTrue(spotiBarWraps(tools, dp(300f), dp(632f), 1f))
    }

    @Test fun `the 3-4 bar is two rows by design and stays across where it fits`() {
        val tools = SpotiToolbarTool.entries.take(8)
        val dp = { v: Float -> androidx.compose.ui.unit.Dp(v) }
        assertTrue(spotiToolRows(tools, 204f).size > 1)
        assertFalse(spotiBarWraps(tools, dp(900f), dp(304f), 1f))
        // Only a window narrower than the bar itself folds it further.
        assertTrue(spotiBarWraps(tools, dp(150f), dp(304f), 1f))
    }

    @Test fun `the smaller bars hold their tools on one row at every chrome scale`() {
        for (scale in listOf(0.7f, 0.8f, 0.9f, 1f, 1.2f)) for (level in 1..2) for (count in 1..11) {
            val tools = SpotiToolbarTool.entries.take(count)
            val width = spotiToolbarWidth(level, scale, count)
            val rows = spotiToolRows(tools, spotiToolWidth(width, scale), 44f * scale, 12f * scale)
            assertEquals("level $level, $count tools at $scale", 1, rows.size)
        }
    }

    @Test fun `a widened bar folds only where its own width would have`() {
        val tools = SpotiToolbarTool.entries.take(8)
        val dp = { v: Float -> androidx.compose.ui.unit.Dp(v) }
        val wide = spotiToolbarWidth(2, 1f, tools.size)
        assertTrue(wide > spotiToolbarWidth(2, 1f))
        assertFalse(spotiBarWraps(tools, dp(320f), wide, 1f, base = spotiToolbarWidth(2, 1f)))
        assertTrue(spotiBarWraps(tools, dp(150f), wide, 1f, base = spotiToolbarWidth(2, 1f)))
    }

    @Test fun `dragging the Functions button steps sizes from where it came down`() {
        // Right is longer, toward the whole bar; left shorter, toward the button alone.
        assertEquals(2, functionsDragLevel(2, 30f, 56f, 0))
        assertEquals(1, functionsDragLevel(2, 60f, 56f, 0))
        assertEquals(0, functionsDragLevel(2, 500f, 56f, 0))
        assertEquals(3, functionsDragLevel(2, -60f, 56f, 0))
        assertEquals(3, functionsDragLevel(0, -500f, 56f, 0))
        // Down the side the whole bar is not a size.
        assertEquals(1, functionsDragLevel(2, 500f, 56f, 1))
    }

    @Test fun `a side bar let go across the screen lands on a side or back across the top`() {
        assertEquals(FunctionsDropZone.LEFT, functionsDropZone(100f, 1000f))
        assertEquals(FunctionsDropZone.MIDDLE, functionsDropZone(500f, 1000f))
        assertEquals(FunctionsDropZone.RIGHT, functionsDropZone(900f, 1000f))
    }

    @Test fun `a dragged bar follows the finger and settles on the nearest size`() {
        // Centred, the bar grows twice what the finger travels, so the button stays under it.
        assertEquals(400f, liveBarWidth(300f, 50f, 0.5f, 80f, 900f), 0.001f)
        // From a fixed start, as far as the finger.
        assertEquals(350f, liveBarWidth(300f, 50f, 1f, 80f, 900f), 0.001f)
        // Never past the narrowest or the widest size.
        assertEquals(80f, liveBarWidth(300f, -500f, 0.5f, 80f, 900f), 0.001f)
        assertEquals(900f, liveBarWidth(300f, 500f, 0.5f, 80f, 900f), 0.001f)
        val widths = listOf(846f, 569f, 274f, 83f)
        assertEquals(2, nearestBarSize(300f, 0..3) { widths[it] })
        assertEquals(1, nearestBarSize(430f, 0..3) { widths[it] })
        assertEquals(3, nearestBarSize(100f, 0..3) { widths[it] })
        // Down the side the whole bar is not a size.
        assertEquals(1, nearestBarSize(846f, 1..3) { widths[it] })
    }
}
