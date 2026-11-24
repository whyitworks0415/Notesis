package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The end edge holds one panel: opening another puts the one there away. */
class SideSheetSlotTest {
    @Test fun `the panel just opened replaces the one shown`() {
        assertEquals(SideSheetSlot.AGENT, nextSideSheet(SideSheetSlot.SETTINGS, listOf(SideSheetSlot.SETTINGS, SideSheetSlot.AGENT)))
        assertEquals(SideSheetSlot.PAGES, nextSideSheet(SideSheetSlot.LAYERS, listOf(SideSheetSlot.PAGES, SideSheetSlot.LAYERS)))
    }

    @Test fun `alone, a panel stays, and none is none`() {
        assertEquals(SideSheetSlot.PAGES, nextSideSheet(null, listOf(SideSheetSlot.PAGES)))
        assertEquals(SideSheetSlot.PAGES, nextSideSheet(SideSheetSlot.PAGES, listOf(SideSheetSlot.PAGES)))
        assertNull(nextSideSheet(SideSheetSlot.PAGES, emptyList()))
    }
}
