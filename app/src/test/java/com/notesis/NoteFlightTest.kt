package com.notesis

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFlightTest {
    private val full = Rect(0f, 0f, 1000f, 2000f)
    private val card = Rect(100f, 300f, 300f, 600f)

    @Test fun `the picture runs from the card to the whole screen`() {
        assertEquals(card, flightRect(card, full, 0f))
        assertEquals(full, flightRect(card, full, 1f))
        assertEquals(Rect(50f, 150f, 650f, 1300f), flightRect(card, full, 0.5f))
        // Past either end it stays at that end.
        assertEquals(full, flightRect(card, full, 1.3f))
        assertEquals(card, flightRect(card, full, Float.NaN))
    }

    @Test fun `with no card it comes from or goes to the middle`() {
        val small = flightRect(null, full, 0f)
        assertEquals(full.center, small.center)
        assertEquals(400f, small.width, 0.001f)
    }

    @Test fun `a note flies out only from the card just tapped, and only where the chrome moves`() {
        NoteFlights.tapped("a", card, null)
        var opened = false
        assertFalse(NoteFlights.open("b", moves = true) { opened = true })
        assertNull(NoteFlights.current)
        NoteFlights.tapped("a", card, null)
        assertFalse(NoteFlights.open("a", moves = false) { opened = true })
        NoteFlights.tapped("a", null, null)
        assertFalse(NoteFlights.open("a", moves = true) { opened = true })
        NoteFlights.tapped("a", card, null)
        assertTrue(NoteFlights.open("a", moves = true) { opened = true })
        val flight = NoteFlights.current!!
        assertTrue(flight.opening)
        // The note is put in place at once, and the library kept under it while it grows.
        assertTrue(opened)
        assertTrue(NoteFlights.growing("a"))
        assertFalse(NoteFlights.growing("b"))
        flight.covering = false
        assertFalse(NoteFlights.growing("a"))
        NoteFlights.finished(flight)
        assertNull(NoteFlights.current)
    }

    @Test fun `a closing note lands on its card, or knows there is none`() {
        NoteFlights.close("a", null, moves = true)
        val flight = NoteFlights.current!!
        NoteFlights.landed("other", card)
        assertNull(flight.landing)
        NoteFlights.landed("a", card)
        assertEquals(card, flight.landing)
        NoteFlights.finished(flight)
        NoteFlights.close("a", null, moves = true)
        NoteFlights.landed("a", null)
        assertTrue(NoteFlights.current!!.noCard)
        NoteFlights.finished(NoteFlights.current!!)
        NoteFlights.close("a", null, moves = false)
        assertNull(NoteFlights.current)
    }

    @Test fun `a back gesture draws the note in to nine tenths at most`() {
        assertEquals(1f, backScale(0f), 0f)
        assertEquals(0.9f, backScale(1f), 0.0001f)
        assertEquals(0.95f, backScale(0.5f), 0.0001f)
        assertEquals(0.9f, backScale(2f), 0.0001f)
        assertEquals(1f, backScale(Float.NaN), 0f)
    }

    @Test fun `a back gesture let go of puts the note back`() {
        NoteBack.follow(0.6f, androidx.activity.BackEventCompat.EDGE_RIGHT)
        assertEquals(0.6f, NoteBack.progress, 0f)
        NoteBack.reset()
        assertEquals(0f, NoteBack.progress, 0f)
    }
}
