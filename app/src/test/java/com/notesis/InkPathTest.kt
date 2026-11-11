package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class InkPathTest {
    @Test fun `left to the app, an older tablet takes the plainest path and a newer one the fastest`() {
        assertEquals(INK_PATH_WINDOW, inkPathApplied(INK_PATH_AUTO, model = "SM-X700", sdk = 29))
        assertEquals(INK_PATH_WINDOW, inkPathApplied(INK_PATH_AUTO, model = "SM-X700", sdk = 32))
        assertEquals(INK_PATH_FRONT, inkPathApplied(INK_PATH_AUTO, model = "SM-X700", sdk = 33))
        assertEquals(INK_PATH_FRONT, inkPathApplied(INK_PATH_AUTO, model = "SM-X910", sdk = 36))
    }

    @Test fun `a choice is kept, except the fastest where the front buffer is known to fail`() {
        for (choice in listOf(INK_PATH_FRONT, INK_PATH_COMPAT, INK_PATH_WINDOW)) {
            assertEquals(choice, inkPathApplied(choice, model = "SM-X700", sdk = 30))
        }
        // The S6 Lite: its own layer as before, the window if asked, never the front buffer.
        assertEquals(INK_PATH_COMPAT, inkPathApplied(INK_PATH_AUTO, model = "SM-P613", sdk = 33))
        assertEquals(INK_PATH_COMPAT, inkPathApplied(INK_PATH_FRONT, model = "SM-P620", sdk = 34))
        assertEquals(INK_PATH_WINDOW, inkPathApplied(INK_PATH_WINDOW, model = "SM-P613", sdk = 33))
    }

    @Test fun `an older version's two switches become the choice`() {
        assertEquals(INK_PATH_AUTO, inkPathFromLegacy(compat = null, inWindow = null))
        assertEquals(INK_PATH_AUTO, inkPathFromLegacy(compat = null, inWindow = true))
        assertEquals(INK_PATH_FRONT, inkPathFromLegacy(compat = false, inWindow = null))
        assertEquals(INK_PATH_COMPAT, inkPathFromLegacy(compat = true, inWindow = false))
        assertEquals(INK_PATH_WINDOW, inkPathFromLegacy(compat = true, inWindow = true))
    }
}
