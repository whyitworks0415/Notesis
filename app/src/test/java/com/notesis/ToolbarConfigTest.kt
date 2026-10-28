package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarConfigTest {
    @Test fun `defaults hold every tool at the two larger sizes`() {
        val config = ToolbarConfig()
        assertEquals(SpotiToolbarTool.entries.size, config.tools(0).size)
        assertEquals(config.tools(0), config.tools(1))
        assertEquals(listOf(EditMode.READ, EditMode.PEN, EditMode.HIGHLIGHTER, EditMode.MASK),
            config.tools(2).map { it.mode })
        assertTrue(config.tools(3).isEmpty())
    }

    @Test fun `encode and decode round trip`() {
        val config = ToolbarConfig()
            .withTools(1, listOf(SpotiToolbarTool.PEN, SpotiToolbarTool.ERASE))
            .copy(topRow = listOf(TopRowItem.TITLE, TopRowItem.UNDO), penRow = listOf(PenRowItem.WIDTH))
        assertEquals(config, ToolbarConfig.decode(config.encode()))
    }

    @Test fun `nothing stored decodes to null and junk falls back to defaults`() {
        assertNull(ToolbarConfig.decode(null))
        assertNull(ToolbarConfig.decode(""))
        val junk = ToolbarConfig.decode("s0=NOPE;top=;pen=WIDTH,WIDTH")!!
        assertEquals(ToolbarConfig.DEFAULT_STAGES[0], junk.tools(0))
        assertEquals(listOf(TopRowItem.TITLE), junk.topRow)
        assertEquals(listOf(PenRowItem.WIDTH), junk.penRow)
    }

    @Test fun `essentials survive normalizing`() {
        val config = ToolbarConfig(
            stages = listOf(emptyList(), listOf(SpotiToolbarTool.PEN, SpotiToolbarTool.PEN),
                emptyList(), listOf(SpotiToolbarTool.PEN)),
            topRow = listOf(TopRowItem.BACK),
        ).normalized()
        assertTrue(config.tools(0).isNotEmpty())
        assertEquals(listOf(SpotiToolbarTool.PEN), config.tools(1))
        assertTrue(config.tools(2).isNotEmpty())
        assertTrue(config.tools(3).isEmpty())
        assertTrue(TopRowItem.TITLE in config.topRow)
    }

    @Test fun `legacy list fills the two full sizes`() {
        val legacy = ToolbarConfig.parseTools("PEN,LASSO,BOGUS")
        val config = ToolbarConfig.fromLegacy(legacy)
        assertEquals(listOf(SpotiToolbarTool.PEN, SpotiToolbarTool.LASSO), config.tools(0))
        assertEquals(config.tools(0), config.tools(1))
        assertEquals(ToolbarConfig.DEFAULT_STAGES[2], config.tools(2))
    }

    @Test fun `shortcuts follow the size in use and skip tools without a mode`() {
        val config = ToolbarConfig().withTools(2, listOf(SpotiToolbarTool.AI, SpotiToolbarTool.ERASE))
        assertEquals(listOf(EditMode.ERASE), config.shortcutModes(2))
        assertEquals(config.tools(0).mapNotNull { it.mode }, config.shortcutModes(3))
    }

    @Test fun `sidebar back stack keeps the root under every screen`() {
        val nav = SidebarNav.at(SettingsPage.PEN)
        assertTrue(nav.canGoBack)
        assertEquals(SettingsPage.ROOT, nav.back().current)
        assertFalse(nav.back().canGoBack)
        val deeper = nav.open(SettingsPage.TOOLBAR)
        assertEquals(SettingsPage.PEN, deeper.open(SettingsPage.PEN).current)
        assertEquals(2, deeper.open(SettingsPage.PEN).stack.size)
        assertEquals(SidebarNav(), deeper.open(SettingsPage.ROOT))
    }

    @Test fun `sidebar width is clamped and fills narrow screens`() {
        assertEquals(400f, settingsSidebarWidth(1000f, 400f))
        assertEquals(SETTINGS_SIDEBAR_MIN_DP, settingsSidebarWidth(10f, 1200f))
        assertEquals(SETTINGS_SIDEBAR_MAX_DP, settingsSidebarWidth(5000f, 2000f))
        assertEquals(700f - 48f, settingsSidebarWidth(5000f, 700f))
        assertEquals(SETTINGS_SIDEBAR_DEFAULT_DP, settingsSidebarWidth(Float.NaN, 1200f))
    }

    @Test fun `a panel pulled well past its narrowest is put away, a little past springs back`() {
        assertFalse(pulledToClose(0f))
        assertFalse(pulledToClose(PANEL_PULL_TO_CLOSE_DP / 2))
        assertTrue(pulledToClose(PANEL_PULL_TO_CLOSE_DP + 1f))
    }

    @Test fun `a fresh bar has the chosen rows`() {
        val fresh = ToolbarConfig()
        assertEquals(listOf(TopRowItem.BACK, TopRowItem.TITLE, TopRowItem.UNDO, TopRowItem.REDO, TopRowItem.MIC, TopRowItem.PAGES),
            fresh.topRow)
        assertEquals(listOf(PenRowItem.WIDTH, PenRowItem.PALETTE, PenRowItem.PAGE_LABEL), fresh.penRow)
        // Every tool is on the full bar, the masking tape just before AI.
        assertEquals(SpotiToolbarTool.entries.toSet(), fresh.tools(0).toSet())
        assertEquals(listOf(SpotiToolbarTool.CAPTURE, SpotiToolbarTool.MASK, SpotiToolbarTool.AI), fresh.tools(0).takeLast(3))
    }
}
