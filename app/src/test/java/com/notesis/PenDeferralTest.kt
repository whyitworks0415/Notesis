package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PenDeferralTest {
    @Test
    fun `nothing waits while the pen is up`() {
        val gate = PenDeferral<Long, String>()
        assertFalse(gate.deferIfWriting(1L, "a"))
        assertEquals(0, gate.deferredCount)
        assertTrue(gate.setPen(false).isEmpty())
    }

    @Test
    fun `prefetch waits for the pen and resumes in order on lift`() {
        val gate = PenDeferral<Long, String>()
        assertTrue(gate.setPen(true).isEmpty())
        assertTrue(gate.penDown)
        assertTrue(gate.deferIfWriting(3L, "c"))
        assertTrue(gate.deferIfWriting(1L, "a"))
        assertTrue(gate.deferIfWriting(2L, "b"))
        assertEquals(listOf("c", "a", "b"), gate.setPen(false))
        assertFalse(gate.penDown)
        assertEquals(0, gate.deferredCount)
        // Lifting again has nothing left to hand back.
        assertTrue(gate.setPen(false).isEmpty())
    }

    @Test
    fun `a page on screen never waits`() {
        val gate = PenDeferral<Long, String>()
        gate.setPen(true)
        assertNull(gate.markOnScreen(7L))
        assertFalse(gate.deferIfWriting(7L, "visible"))
        assertEquals(0, gate.deferredCount)
    }

    @Test
    fun `a prefetch put aside comes back as soon as its page is drawn`() {
        val gate = PenDeferral<Long, String>()
        gate.setPen(true)
        assertTrue(gate.deferIfWriting(5L, "job"))
        assertEquals("job", gate.markOnScreen(5L))
        assertEquals(0, gate.deferredCount)
        // Run again from the queue, it is now on screen and goes ahead.
        assertFalse(gate.deferIfWriting(5L, "job"))
        assertTrue(gate.setPen(false).isEmpty())
    }

    @Test
    fun `finishing forgets the key so a later prefetch of it waits again`() {
        val gate = PenDeferral<Long, String>()
        gate.setPen(true)
        gate.markOnScreen(9L)
        gate.finished(9L)
        assertTrue(gate.deferIfWriting(9L, "later"))
    }

    @Test
    fun `clearing drops what was put aside and every on-screen mark`() {
        val gate = PenDeferral<Long, String>()
        gate.setPen(true)
        gate.markOnScreen(1L)
        assertTrue(gate.deferIfWriting(2L, "b"))
        gate.clear()
        assertEquals(0, gate.deferredCount)
        assertTrue(gate.deferIfWriting(1L, "a"))
        assertEquals(listOf("a"), gate.setPen(false))
    }
}
