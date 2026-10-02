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
}
