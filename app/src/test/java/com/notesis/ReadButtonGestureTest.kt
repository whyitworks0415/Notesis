package com.notesis

import org.junit.Assert.*
import org.junit.Test

class ReadButtonGestureTest {
    private val first = ReadTextPoint(0, 10f, 20f)
    private val second = ReadTextPoint(0, 80f, 120f)

    @Test fun `two taps select between endpoints then the next tap starts a new range`() {
        val gesture = ReadButtonGesture(8f)
        gesture.begin(first, 100f, 200f)
        gesture.move(103f, 202f)
        assertEquals(ReadButtonGesture.Result.Select(first, first), gesture.finish())
        gesture.begin(second, 800f, 1200f)
        assertEquals(ReadButtonGesture.Result.Select(first, second), gesture.finish())
        gesture.begin(second, 800f, 1200f)
        assertEquals(ReadButtonGesture.Result.Select(second, second), gesture.finish())
    }

    @Test fun `drag captures and forgets a previous tap even when it returns to its start`() {
        val gesture = ReadButtonGesture(8f)
        gesture.begin(first, 100f, 200f)
        gesture.finish()
        gesture.begin(second, 800f, 1200f)
        gesture.move(820f, 1220f)
        gesture.move(800f, 1200f)
        assertTrue(gesture.dragging)
        assertEquals(ReadButtonGesture.Result.Capture, gesture.finish())
        gesture.begin(second, 800f, 1200f)
        assertEquals(ReadButtonGesture.Result.Select(second, second), gesture.finish())
    }

    @Test fun `tap on another page starts a new range`() {
        val gesture = ReadButtonGesture(8f)
        gesture.begin(first, 100f, 200f)
        gesture.finish()
        val otherPage = second.copy(page = 1)
        gesture.begin(otherPage, 800f, 1200f)
        assertEquals(ReadButtonGesture.Result.Select(otherPage, otherPage), gesture.finish())
    }

    @Test fun `cancellation or selection dismissal clears pending endpoints`() {
        val gesture = ReadButtonGesture(8f)
        gesture.begin(first, 100f, 200f)
        gesture.finish()
        gesture.begin(second, 800f, 1200f)
        gesture.reset()
        assertNull(gesture.finish())
        gesture.begin(second, 800f, 1200f)
        assertEquals(ReadButtonGesture.Result.Select(second, second), gesture.finish())
    }

    @Test fun `either direction of dragging uses the same threshold`() {
        for (offset in listOf(-20f, 20f)) {
            val gesture = ReadButtonGesture(8f)
            gesture.begin(first, 100f, 200f)
            gesture.move(100f + offset, 200f + offset)
            assertEquals(ReadButtonGesture.Result.Capture, gesture.finish())
        }
    }
}
