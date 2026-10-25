package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebTintTest {
    @Test fun `rgb as the page hands it back`() {
        assertEquals(0xFF202124.toInt(), parseCssColor("\"rgb(32, 33, 36)\""))
        assertEquals(0xFFFFFFFF.toInt(), parseCssColor("\"rgb(255,255,255)\""))
        // The modern space-separated form.
        assertEquals(0xFF0A141E.toInt(), parseCssColor("\"rgb(10 20 30)\""))
    }

    @Test fun `see-through colours are no colour`() {
        assertNull(parseCssColor("\"rgba(0, 0, 0, 0)\""))
        assertNull(parseCssColor("\"rgba(255, 255, 255, 0.2)\""))
        assertEquals(0xFF112233.toInt(), parseCssColor("\"rgba(17, 34, 51, 0.9)\""))
    }

    @Test fun `hex and names`() {
        assertEquals(0xFF112233.toInt(), parseCssColor("\"#112233\""))
        assertEquals(0xFFFFFFFF.toInt(), parseCssColor("\"#fff\""))
        assertNull(parseCssColor("\"#11223300\""))
        assertEquals(0xFF000000.toInt(), parseCssColor("\"black\""))
    }

    @Test fun `nothing comes back as nothing`() {
        assertNull(parseCssColor(null))
        assertNull(parseCssColor("null"))
        assertNull(parseCssColor("\"\""))
        assertNull(parseCssColor("\"transparent\""))
        assertNull(parseCssColor("\"#zzzzzz\""))
    }
}
