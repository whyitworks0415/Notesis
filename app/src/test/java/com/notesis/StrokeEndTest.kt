package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeEndTest {
    @Test
    fun `short contact reversal is a spur`() {
        assertTrue(isContactSpur(0f, 0f, 3f, 0f, 0.4f, 0.1f, 16L, 5f))
    }

    @Test
    fun `intentional turn and slow backtrack are preserved`() {
        assertFalse(isContactSpur(0f, 0f, 5f, 0f, 5f, 5f, 16L, 5f))
        assertFalse(isContactSpur(0f, 0f, 3f, 0f, 0.4f, 0f, 90L, 5f))
        assertFalse(isContactSpur(0f, 0f, 12f, 0f, 0.4f, 0f, 16L, 5f))
    }
}
