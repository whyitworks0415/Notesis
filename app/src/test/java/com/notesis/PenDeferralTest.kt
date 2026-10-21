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
    fun `a lift before the hands are off keeps prefetch waiting until quiet`() {
        val gate = PenDeferral<Long, String>()
        gate.setPen(true)
        assertTrue(gate.deferIfWriting(1L, "a"))
        assertTrue(gate.deferIfWriting(2L, "b"))
        // Lifted between strokes: nothing comes back yet, and a prefetch
        // reaching the worker in the gap waits too.
        assertTrue(gate.lift(quiet = false).isEmpty())
        assertFalse(gate.penDown)
        assertTrue(gate.waitingForQuiet)
        assertTrue(gate.deferIfWriting(3L, "c"))
        // A page on screen still never waits.
        assertNull(gate.markOnScreen(4L))
        assertFalse(gate.deferIfWriting(4L, "visible"))
        // The hands come off: everything, oldest first, and no more waiting.
        assertEquals(listOf("a", "b", "c"), gate.quiet())
        assertFalse(gate.waitingForQuiet)
        assertFalse(gate.deferIfWriting(5L, "later"))
    }

    @Test
    fun `the next stroke landing before quiet keeps the queue put aside`() {
        val gate = PenDeferral<Long, String>()
        gate.setPen(true)
        assertTrue(gate.deferIfWriting(1L, "a"))
        gate.lift(quiet = false)
        gate.setPen(true)
        // Quiet cannot be reached with a pen down; nothing is let out.
        assertTrue(gate.quiet().isEmpty())
        assertEquals(1, gate.deferredCount)
        gate.lift(quiet = false)
        assertEquals(listOf("a"), gate.quiet())
    }

    @Test
    fun `a lift when already quiet resumes at once`() {
        val gate = PenDeferral<Long, String>()
        gate.setPen(true)
        assertTrue(gate.deferIfWriting(1L, "a"))
        assertEquals(listOf("a"), gate.lift(quiet = true))
        assertFalse(gate.waitingForQuiet)
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
