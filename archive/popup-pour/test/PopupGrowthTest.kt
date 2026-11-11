package com.notesis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupGrowthTest {
    @Test fun `a popup composed shut is not closed again`() {
        val growth = PopupGrowth(shown = false)
        assertTrue(growth.isShut())
        // No frame clock here: a close that animated would throw for want of
        // one. A shut popup returns at once, counting no chrome motion.
        runBlocking { growth.close(animate = true) }
        assertTrue(growth.isShut())
        assertEquals(0, ChromeMotion.moving)
    }
}
