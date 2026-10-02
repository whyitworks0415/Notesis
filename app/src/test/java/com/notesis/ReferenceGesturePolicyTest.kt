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
}
