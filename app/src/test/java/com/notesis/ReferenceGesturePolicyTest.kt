package com.notesis

import org.junit.Assert.*
import org.junit.Test

class ReferenceGesturePolicyTest {
    @Test fun `held fingers do not prevent an already armed downward dismiss`() {
        val gesture = ReferenceDismissGesture(12f)
        assertFalse(gesture.tracks(0f, 2f, 1f))
        assertTrue(gesture.tracks(4f, 24f, 1.03f))
        assertTrue(gesture.tracks(10f, 100f, 1.18f))
        gesture.reset()
        assertFalse(gesture.tracks(0f, 0f, 1f))
    }

    @Test fun `intentional expansion and horizontal movement remain panel gestures`() {
        val resize = ReferenceDismissGesture(12f)
        assertFalse(resize.tracks(0f, 30f, 1.3f))
        assertFalse(resize.tracks(90f, 30f, 1f))
        assertFalse(resize.tracks(0f, -80f, 1f))
    }

    @Test fun `a free resize moves each axis on its own`() {
        assertEquals(Pair(400f, 300f), freeResize(300f, 300f, 100f, 0f, 220f, 1000f, 1000f))
        assertEquals(Pair(300f, 260f), freeResize(300f, 300f, 0f, -40f, 220f, 1000f, 1000f))
    }

    @Test fun `a free resize stays within the minimum and the container`() {
        assertEquals(Pair(220f, 900f), freeResize(300f, 300f, -500f, 900f, 220f, 1000f, 900f))
        // Past the limit and back: the edge is picked up where the finger is.
        assertEquals(Pair(1000f, 300f), freeResize(300f, 300f, 2000f, 0f, 220f, 1000f, 900f))
        assertEquals(Pair(500f, 300f), freeResize(300f, 300f, 200f, 0f, 220f, 1000f, 900f))
        // A container smaller than the minimum still allows the minimum.
        assertEquals(Pair(220f, 220f), freeResize(300f, 300f, 0f, 0f, 220f, 100f, 100f))
    }

    @Test fun `dragging past the bottom edge is counted and comes back with the hand`() {
        val (top, over) = panelTopWithOvershoot(top = 450f, overshoot = 0f, dy = 120f, maxTop = 500f)
        assertEquals(500f, top, 0f)
        assertEquals(70f, over, 0f)
        val (backTop, backOver) = panelTopWithOvershoot(top, over, dy = -100f, maxTop = 500f)
        assertEquals(470f, backTop, 0f)
        assertEquals(0f, backOver, 0f)
        val (upTop, upOver) = panelTopWithOvershoot(10f, 0f, dy = -50f, maxTop = 500f)
        assertEquals(0f, upTop, 0f)
        assertEquals(0f, upOver, 0f)
    }
}
