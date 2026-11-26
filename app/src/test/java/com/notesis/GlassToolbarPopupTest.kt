package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassToolbarPopupTest {
    @Test fun `shape and AI prepare a popup on the first tap`() {
        assertTrue(GlassToolbarTool.SHAPE.opensPopup(EditMode.PEN))
        assertTrue(GlassToolbarTool.AI.opensPopup(EditMode.PEN))
    }

    @Test fun `pen settings and text options prepare a popup only when tapped again`() {
        assertFalse(GlassToolbarTool.PEN.opensPopup(EditMode.ERASE))
        assertTrue(GlassToolbarTool.PEN.opensPopup(EditMode.PEN))
        assertFalse(GlassToolbarTool.TEXT.opensPopup(EditMode.PEN))
        assertTrue(GlassToolbarTool.TEXT.opensPopup(EditMode.TEXT))
        assertFalse(GlassToolbarTool.LASSO.opensPopup(EditMode.LASSO))
    }
}
