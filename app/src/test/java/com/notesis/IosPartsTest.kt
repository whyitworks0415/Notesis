package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IosPartsTest {
    @Test fun `every part is on by default and survives a round trip`() {
        val d = IosParts()
        assertTrue(d.clearLayer && d.frostLayer && d.lens && d.saturation && d.edgeAbsorption && d.rimLight &&
            d.rimShade && d.shadow && d.body && d.buttonGlass && d.controlGlass && d.clockGlass)
        val pared = IosParts(clearLayer = false, lens = false, buttonGlass = false, clockGlass = false)
        assertEquals(pared, IosParts.fromJson(pared.toJson()))
        assertEquals(d, IosParts.fromJson(null))
    }

    @Test fun `the parts are kept with the rest of iOS's glass`() {
        val g = IosGlass(parts = IosParts(frostLayer = false, shadow = false))
        assertEquals(g.parts, IosGlass.fromJson(g.toJson()).parts)
    }

    @Test fun `with both layers off the page is not looked at`() {
        assertFalse(IosParts(clearLayer = false, frostLayer = false).samplesPage)
        assertTrue(IosParts(clearLayer = false).samplesPage)
    }

    @Test fun `a part switched off only matters under iOS`() {
        val off = IosGlass(parts = IosParts(buttonGlass = false))
        val ios = SkinSettings(liquidStyle = LiquidStyle.IOS, ios = off)
        assertFalse(ios.iosPartOn(Skin.LIQUID_GLASS) { it.buttonGlass })
        assertTrue(ios.iosPartOn(Skin.MATERIAL) { it.buttonGlass })
        assertTrue(ios.copy(liquidStyle = LiquidStyle.MORNYE).iosPartOn(Skin.LIQUID_GLASS) { it.buttonGlass })
    }

    @Test fun `the glass takes new ink as the setting says`() {
        // While the pen is down only the live setting follows.
        assertTrue(glassFollowsPage(Interaction.WRITING, followsScroll = true, ink = GLASS_INK_LIVE))
        assertFalse(glassFollowsPage(Interaction.WRITING, followsScroll = true, ink = GLASS_INK_AFTER_STROKE))
        // Once the stroke ends it follows, unless it waits for a pause.
        assertTrue(glassFollowsPage(Interaction.SETTLING, followsScroll = false, ink = GLASS_INK_AFTER_STROKE))
        assertFalse(glassFollowsPage(Interaction.SETTLING, followsScroll = true, ink = GLASS_INK_AFTER_PAUSE))
        // A moving finger is the scroll setting's.
        assertTrue(glassFollowsPage(Interaction.MOVING, followsScroll = true, ink = GLASS_INK_AFTER_PAUSE))
        assertFalse(glassFollowsPage(Interaction.MOVING, followsScroll = false, ink = GLASS_INK_LIVE))
    }
}
