package com.notesis

import org.junit.Assert.*
import org.junit.Test

class CustomizationTest {
    @Test fun `numeric width rejects invalid and out of range input`() {
        val range = 0.25f..12f
        listOf("", "NaN", "Infinity", "-1", "0", "12.01", "abc").forEach {
            assertNull(it, parseWidth(it, range))
        }
        assertEquals(0.25f, parseWidth("0,25", range)!!, 0f)
        assertEquals(12f, parseWidth("12", range)!!, 0f)
        assertEquals("0.25", widthLabel(0.25f))
    }

    @Test fun `colors accept only complete RGB codes`() {
        assertEquals(0xFFAABBCC.toInt(), parseColor("#aabbcc"))
        listOf("xyz123", "fff", "1234567", "").forEach { assertNull(parseColor(it)) }
    }

    @Test fun `pen lift filters hooks and jumps but keeps normal continuation`() {
        assertTrue(unstableLift(0f, 3f, 3f, 0f))
        assertTrue(unstableLift(-2f, 0f, 3f, 0f))
        assertTrue(unstableLift(20f, 0f, 3f, 0f))
        assertFalse(unstableLift(2f, 0.5f, 3f, 0f))
        assertFalse(unstableLift(0f, 0f, 0f, 0f))
    }

    @Test fun `fine stroke caps stay round across zoom refinement`() {
        assertEquals(0.25f / 64f, strokeEpsilon(0.25f, 0.1f), 0f)
        assertEquals(0.00625f, strokeEpsilon(5f, 0.00625f), 0f)
        assertTrue(strokeEpsilon(5f, 0.1f) < 0.1f)
    }
}
