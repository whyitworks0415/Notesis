package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownCalloutTest {
    @Test fun aPlainQuoteIsNotACallout() {
        assertNull(calloutOf("Just a quote\nover two lines"))
    }

    @Test fun theTypeTitleAndBodyAreRead() {
        val c = calloutOf("[!WARNING] Mind the step\nIt is steep.\nVery.")!!
        assertEquals("warning", c.type)
        assertEquals("Mind the step", c.title)
        assertEquals("It is steep.\nVery.", c.body)
        assertNull(c.startsOpen)
    }

    @Test fun withoutATitleTheTypeIsTheTitle() {
        assertEquals("Tip", calloutOf("[!tip]\nbody")!!.title)
    }

    @Test fun plusAndMinusFold() {
        assertEquals(true, calloutOf("[!note]+ Open")!!.startsOpen)
        assertEquals(false, calloutOf("[!note]- Shut\nhidden")!!.startsOpen)
    }
}
