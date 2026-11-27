package com.notesis

import androidx.compose.ui.unit.IntSize
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

    @Test fun `frosted glass saves half resolution by default, and one of three`() {
        assertEquals(0.5f, IosGlass().resolution)
        assertEquals(0.25f, iosResolution(0.3f))
        assertEquals(1f, iosResolution(2f))
        assertEquals(0.5f, iosResolution(Float.NaN))
        val quarter = IosGlass(resolution = 0.25f)
        assertEquals(0.25f, IosGlass.fromJson(quarter.toJson()).resolution)
        // Saved before there was a choice, the glass takes the default.
        val old = IosGlass().toJson().apply { remove("resolution") }
        assertEquals(0.5f, IosGlass.fromJson(old).resolution)
    }

    @Test fun `clear glass keeps native resolution and frosted only glass can render smaller`() {
        assertEquals(1f, iosGlassRenderScale(0.25f, clearLayer = true, fullResolution = false))
        assertEquals(1f, iosGlassRenderScale(0.5f, clearLayer = false, fullResolution = true))
        assertEquals(0.5f, iosGlassRenderScale(0.5f, clearLayer = false, fullResolution = false))
        assertEquals(0.25f, iosGlassRenderScale(0.25f, clearLayer = false, fullResolution = false))
    }

    @Test fun `paused capture keeps a valid recording and fills the first or resized frame`() {
        val portrait = IntSize(800, 1200)
        val landscape = IntSize(1200, 800)
        assertTrue(shouldRecordGlassBackdrop(IntSize.Zero, portrait, paused = true))
        assertFalse(shouldRecordGlassBackdrop(portrait, portrait, paused = true))
        assertTrue(shouldRecordGlassBackdrop(portrait, landscape, paused = true))
        assertTrue(shouldRecordGlassBackdrop(portrait, portrait, paused = false))
        assertFalse(shouldRecordGlassBackdrop(portrait, IntSize.Zero, paused = false))
    }

    @Test fun `a held backdrop defers even live glass updates`() {
        for (state in Interaction.entries) {
            assertFalse(glassFollowsPage(state, followsScroll = true, ink = GLASS_INK_LIVE, holdBackdrop = true))
        }
    }
}
