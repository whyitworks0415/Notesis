package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceGesturePolicyTest {
    @Test fun `a corner follows the hand and the opposite corner stays put`() {
        val start = PanelBounds(100f, 100f, 300f, 400f)
        // Bottom right: only the size changes.
        assertEquals(PanelBounds(100f, 100f, 350f, 460f),
            cornerResize(start, fromLeft = false, fromTop = false, 50f, 60f, 220f, 1000f, 1000f))
        // Top left: the edges held move, the right and bottom stay where they were.
        val topLeft = cornerResize(start, fromLeft = true, fromTop = true, -40f, -30f, 220f, 1000f, 1000f)
        assertEquals(PanelBounds(60f, 70f, 340f, 430f), topLeft)
        assertEquals(start.right, topLeft.right, 0f)
        assertEquals(start.bottom, topLeft.bottom, 0f)
        // Top right and bottom left mix the two.
        assertEquals(PanelBounds(100f, 80f, 330f, 420f),
            cornerResize(start, fromLeft = false, fromTop = true, 30f, -20f, 220f, 1000f, 1000f))
        assertEquals(PanelBounds(80f, 100f, 320f, 410f),
            cornerResize(start, fromLeft = true, fromTop = false, -20f, 10f, 220f, 1000f, 1000f))
    }

    @Test fun `a corner stops at the minimum and at the container's edges`() {
        val start = PanelBounds(100f, 100f, 300f, 400f)
        // Pushed in past the minimum: the moving edges stop the minimum short of the anchors.
        val shrunk = cornerResize(start, fromLeft = true, fromTop = true, 500f, 500f, 220f, 1000f, 1000f)
        assertEquals(PanelBounds(180f, 280f, 220f, 220f), shrunk)
        // Pulled out past the screen: the left and top edges stop at zero.
        assertEquals(PanelBounds(0f, 0f, 400f, 500f),
            cornerResize(start, fromLeft = true, fromTop = true, -900f, -900f, 220f, 1000f, 1000f))
        // And the right and bottom at the container's size.
        assertEquals(PanelBounds(100f, 100f, 900f, 700f),
            cornerResize(start, fromLeft = false, fromTop = false, 2000f, 2000f, 220f, 1000f, 800f))
    }

    @Test fun `a moved panel stays inside the container`() {
        assertEquals(Pair(150f, 120f), movePanel(100f, 100f, 300f, 400f, 50f, 20f, 1000f, 1000f))
        assertEquals(Pair(0f, 0f), movePanel(100f, 100f, 300f, 400f, -500f, -500f, 1000f, 1000f))
        assertEquals(Pair(700f, 600f), movePanel(100f, 100f, 300f, 400f, 2000f, 2000f, 1000f, 1000f))
        // Wider than the container: pinned to its left edge, so the top bar is still reachable.
        assertEquals(Pair(0f, 0f), movePanel(10f, 10f, 1200f, 1200f, 30f, 30f, 1000f, 1000f))
    }
}
