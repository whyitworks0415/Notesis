package com.notesis

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PenPanelAndChromeTest {
    @Test fun `the width is shown, stepped and rounded to the tenth`() {
        assertEquals("2.0", widthTenths(2f))
        assertEquals("0.3", widthTenths(0.25f + 0.04f))
        assertEquals(1.3f, roundTenth(1.26f), 0.0001f)
        assertEquals(2.1f, stepWidth(2f, 1, 0.1f..10f), 0.0001f)
        assertEquals(1.9f, stepWidth(2f, -1, 0.1f..10f), 0.0001f)
        // Never past the ends.
        assertEquals(10f, stepWidth(10f, 1, 0.1f..10f), 0f)
        assertEquals(0.1f, stepWidth(0.1f, -1, 0.1f..10f), 0.0001f)
        // Presets inside the range, six at most.
        val presets = defaultWidthPresets(0.5f..12f)
        assertTrue(presets.all { it in 0.5f..12f })
        assertTrue(presets.size <= 6)
    }

    @Test fun `each kind of pen has its own extra row`() {
        assertEquals(PenExtraRow.LINE_TYPE, penExtraRow(EditMode.PEN, Tool.PEN, Tool.PEN))
        assertEquals(PenExtraRow.LINE_TYPE, penExtraRow(EditMode.PEN, Tool.VANISHING_PEN, Tool.VANISHING_PEN))
        assertEquals(PenExtraRow.NIB_ANGLE, penExtraRow(EditMode.PEN, Tool.FOUNTAIN, Tool.FOUNTAIN))
        assertEquals(PenExtraRow.NIB_ANGLE, penExtraRow(EditMode.PEN, Tool.CALLIGRAPHY, Tool.CALLIGRAPHY))
        assertEquals(PenExtraRow.DENSITY, penExtraRow(EditMode.PENCIL, Tool.PENCIL, Tool.PENCIL))
        assertEquals(PenExtraRow.NONE, penExtraRow(EditMode.HIGHLIGHTER, Tool.PEN, Tool.HIGHLIGHTER))
        // The speed fountain pen shows as the fountain pen; the four types are the four segments.
        assertEquals(Tool.FOUNTAIN, penTypeShown(Tool.SPEED_FOUNTAIN))
        assertEquals(listOf(Tool.PEN, Tool.FOUNTAIN, Tool.CALLIGRAPHY, Tool.VANISHING_PEN), PEN_TYPES.map { it.first })
    }

    @Test fun `the pencil no longer offers the bubble`() {
        assertFalse(PENCIL_NIBS.any { it.first == Tool.BUBBLE })
        assertEquals(Tool.PENCIL, PenPreset(Tool.PENCIL, 0, 5f, nib = Tool.BUBBLE).drawingTool())
    }

    @Test fun `the colour views are four, and the old ones open as their nearest`() {
        assertEquals(listOf(ColorPickerMode.GRID, ColorPickerMode.SQUARE, ColorPickerMode.BARS, ColorPickerMode.RING), COLOR_VIEWS)
        assertEquals(ColorPickerMode.BARS, colorViewOf(ColorPickerMode.BARS.ordinal))
        assertEquals(ColorPickerMode.BARS, colorViewOf(ColorPickerMode.RGB.ordinal))
        assertEquals(ColorPickerMode.RING, colorViewOf(ColorPickerMode.DISC.ordinal))
        assertEquals(ColorPickerMode.GRID, colorViewOf(ColorPickerMode.GRID.ordinal))
        assertEquals(ColorPickerMode.SQUARE, colorViewOf(99))
        // The stored indices of the older views did not move.
        assertEquals(0, ColorPickerMode.SQUARE.ordinal)
        assertEquals(4, ColorPickerMode.DISC.ordinal)
    }

    @Test fun `a bar kept to the bottom or the right grows away from that edge`() {
        val room = IntSize(1000, 800)
        // Moved to the bottom while 100 tall: grown to 200, its bottom stays put.
        val low = anchoredBarOffset(Offset(100f, 700f), IntSize(200, 100), IntSize(200, 200), room)
        assertEquals(600f, low.y, 0.01f)
        // At the top it grows down from where it is.
        val high = anchoredBarOffset(Offset(100f, 0f), IntSize(200, 100), IntSize(200, 200), room)
        assertEquals(0f, high.y, 0.01f)
        // At the right its right edge stays; in the middle its middle.
        val right = anchoredBarOffset(Offset(800f, 0f), IntSize(200, 100), IntSize(400, 100), room)
        assertEquals(600f, right.x, 0.01f)
        val middle = anchoredBarOffset(Offset(400f, 0f), IntSize(200, 100), IntSize(400, 100), room)
        assertEquals(300f, middle.x, 0.01f)
        // Not moved yet: as it is.
        assertEquals(Offset(5f, 5f), anchoredBarOffset(Offset(5f, 5f), IntSize.Zero, IntSize(10, 10), room))
    }

    @Test fun `chrome in the lower half goes away downwards`() {
        assertEquals(-(80f + 24f), awayShift(10f, 80f, 1000f, 24f), 0.01f)
        assertEquals(1000f - 900f + 24f, awayShift(900f, 980f, 1000f, 24f), 0.01f)
        // An unknown window height: up, as before.
        assertEquals(-(980f + 24f), awayShift(900f, 980f, 0f, 24f), 0.01f)
    }

    @Test fun `a line from the recogniser starts where the pen landed`() {
        val fit = RecognizedShape(ShapeKind.LINE, 12f, 10f, 112f, 10f)
        val moved = startingAt(fit, 10f, 11f)
        assertEquals(10f, moved.fromX, 0f)
        assertEquals(11f, moved.fromY, 0f)
        // Its length and direction are kept.
        assertEquals(110f, moved.toX, 0.001f)
        assertEquals(11f, moved.toY, 0.001f)
        // Fitted the other way round, it is turned to start at the pen.
        val reversed = startingAt(RecognizedShape(ShapeKind.ARROW, 112f, 10f, 12f, 10f), 10f, 10f)
        assertEquals(10f, reversed.fromX, 0f)
        assertEquals(110f, reversed.toX, 0.001f)
        // A rectangle is left alone.
        val rect = RecognizedShape(ShapeKind.RECT, 0f, 0f, 50f, 50f)
        assertEquals(rect, startingAt(rect, 3f, 3f))
    }

    @Test fun `cells keep their middles to the fraction of a pixel`() {
        // 7 cells in 100 px: each 14.2857 wide, drawn 15 wide about the same middle.
        val cell = 100f / 7f
        for (i in 0 until 7) {
            val middle = evenCellStart(i, cell, 15) + 7.5f
            assertEquals(i * cell + cell / 2f, middle, 0.0001f)
        }
    }

    @Test fun `the interface size scales dp and sp together, within its range`() {
        val system = Density(2f, 1f)
        val big = scaledDensity(system, 1.5f)
        assertEquals(3f, big.density, 0f)
        assertEquals(1f, big.fontScale, 0f)
        assertEquals(1f, scaledDensity(system, 0.1f).density, 0.0001f)
        // The system's own font scale is still capped.
        assertEquals(MAX_FONT_SCALE, scaledDensity(Density(2f, 2f), 1f).fontScale, 0f)
        assertEquals(1.05f, clampUiScale(1.04f), 0.0001f)
        assertEquals(UiScale.MAX, clampUiScale(9f), 0f)
        assertEquals(UiScale.DEFAULT, clampUiScale(Float.NaN), 0f)
    }

    @Test fun `a capture goes to the AI site last used, known by its host`() {
        val sites = listOf("ChatGPT" to "https://chatgpt.com/", "Claude" to "https://claude.ai/new")
        assertTrue(isAiAddress("https://claude.ai/chat/123", sites))
        assertTrue(isAiAddress("https://www.chatgpt.com", sites))
        assertFalse(isAiAddress("https://www.google.com/search?q=x", sites))
        assertEquals("claude.ai", siteHost("https://Claude.ai:443/new?x=1"))
        assertNull(siteHost("https:///path"))
    }

    @Test fun `diagnostics is listed for beta testers only`() {
        assertFalse(SettingsPage.DIAGNOSTICS in settingsSections(beta = false))
        assertTrue(SettingsPage.DIAGNOSTICS in settingsSections(beta = true))
        assertEquals(SettingsPage.PEN, settingsSections(false).first())
    }

    @Test fun `the selected tool's pill is the same grey whatever the accent`() {
        val ink = androidx.compose.ui.graphics.Color(0xFF202020)
        val light = selectionPillFill(ink, dark = false)
        val dark = selectionPillFill(ink, dark = true)
        assertEquals(ink.red, light.red, 0.005f)
        assertEquals(0.08f, light.alpha, 0.01f)
        assertEquals(0.12f, dark.alpha, 0.01f)
    }
}
