package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The append path is the only place that writes over a file people's work is
 * already in, so the decision to take it is pinned down here.
 */
class AppendStrokesTest {

    @Test
    fun `appends when the file holds a shorter prefix`() {
        assertTrue(canAppendStrokes(onDisk = 10, total = 11, fileExists = true))
        assertTrue(canAppendStrokes(onDisk = 1, total = 900, fileExists = true))
    }

    @Test
    fun `rewrites when strokes were removed`() {
        // An erase or an undo leaves fewer than the file holds; appending then
        // would keep the erased strokes on disk forever.
        assertFalse(canAppendStrokes(onDisk = 10, total = 9, fileExists = true))
        assertFalse(canAppendStrokes(onDisk = 10, total = 0, fileExists = true))
    }

    @Test
    fun `rewrites when nothing changed`() {
        assertFalse(canAppendStrokes(onDisk = 10, total = 10, fileExists = true))
    }

    @Test
    fun `rewrites a page that has never been written`() {
        assertFalse(canAppendStrokes(onDisk = 0, total = 5, fileExists = true))
        assertFalse(canAppendStrokes(onDisk = 3, total = 5, fileExists = false))
    }
}
