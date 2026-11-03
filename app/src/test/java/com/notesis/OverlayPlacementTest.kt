package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlacementTest {
    private fun place(top: Float, bottom: Float, left: Float = 400f, right: Float = 600f) =
        placeMenu(left, top, right, bottom, menuWidth = 300, menuHeight = 60,
            areaWidth = 1000, areaTop = 100, areaBottom = 1500, margin = 16, gap = 12)

    @Test fun goesAboveWhenThereIsRoom() {
        val p = place(top = 600f, bottom = 800f)
        assertEquals(MenuPlacement.Side.ABOVE, p.side)
        assertEquals(600 - 12 - 60, p.y)
        assertEquals(350, p.x)
    }

    @Test fun goesBelowNearTheTop() {
        val p = place(top = 150f, bottom = 300f)
        assertEquals(MenuPlacement.Side.BELOW, p.side)
        assertEquals(312, p.y)
    }

    @Test fun goesToTheBottomWhenTheSelectionFillsTheScreen() {
        val p = place(top = 110f, bottom = 1490f)
        assertEquals(MenuPlacement.Side.BOTTOM, p.side)
        assertEquals(1500 - 16 - 60, p.y)
    }

    @Test fun staysInsideSideways() {
        assertEquals(16, place(600f, 800f, left = -200f, right = 0f).x)
        assertEquals(1000 - 16 - 300, place(600f, 800f, left = 950f, right = 1200f).x)
    }

    @Test fun aWiderMenuThanTheScreenStartsAtTheMargin() {
        val p = placeMenu(0f, 600f, 100f, 700f, menuWidth = 2000, menuHeight = 60,
            areaWidth = 1000, areaTop = 0, areaBottom = 1500, margin = 16, gap = 12)
        assertEquals(16, p.x)
    }

    @Test fun aSelectionScrolledOffTheTopPutsTheMenuBelowOrAtTheBottom() {
        val p = place(top = -800f, bottom = -600f)
        assertEquals(MenuPlacement.Side.BOTTOM, p.side)
    }
}
