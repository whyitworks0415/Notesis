package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tap-to-reveal is only ever as good as the hit test behind it. */
class PageMaskTest {

    private val tape = PageMask(x = 10f, y = 20f, width = 100f, height = 30f)

    @Test
    fun `covers its own rectangle, edges included`() {
        assertTrue(tape.contains(60f, 35f))
        assertTrue(tape.contains(10f, 20f))
        assertTrue(tape.contains(110f, 50f))
    }

    @Test
    fun `does not reach past its edges`() {
        assertFalse(tape.contains(9f, 35f))
        assertFalse(tape.contains(111f, 35f))
        assertFalse(tape.contains(60f, 19f))
        assertFalse(tape.contains(60f, 51f))
    }

    @Test
    fun `starts face down`() {
        assertFalse(tape.revealed)
    }
}
