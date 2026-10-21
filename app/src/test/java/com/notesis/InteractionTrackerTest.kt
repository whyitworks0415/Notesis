package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionTrackerTest {
    @Test
    fun `one canvas lifting its pen does not end another canvas's stroke`() {
        val tracker = InteractionTracker(settleMs = 300)
        val note = Any()
        val panel = Any()
        tracker.begin(note, writing = true)
        tracker.begin(panel, writing = true)
        assertFalse(tracker.end(panel, writing = true, now = 100))
        assertEquals(Interaction.WRITING, tracker.state(110))
        tracker.forget(panel, now = 120)
        assertEquals(Interaction.WRITING, tracker.state(130))
        assertTrue(tracker.end(note, writing = true, now = 200))
        assertEquals(Interaction.SETTLING, tracker.state(250))
        assertEquals(Interaction.IDLE, tracker.state(500))
    }

    @Test
    fun `writing outranks moving and ending something never held changes nothing`() {
        val tracker = InteractionTracker(settleMs = 300)
        val note = Any()
        tracker.begin(note, writing = false)
        tracker.begin(note, writing = true)
        assertEquals(Interaction.WRITING, tracker.state(0))
        tracker.end(note, writing = true, now = 10)
        assertEquals(Interaction.MOVING, tracker.state(20))
        assertFalse(tracker.end(Any(), writing = false, now = 30))
        assertEquals(Interaction.MOVING, tracker.state(40))
        assertEquals(0L, tracker.quietFor(40))
        tracker.end(note, writing = false, now = 50)
        assertEquals(70L, tracker.quietFor(120))
    }

    @Test
    fun `idle before anything happened`() {
        assertEquals(Interaction.IDLE, InteractionTracker().state(0))
    }

    @Test
    fun `background budget narrows with the pen, the fingers, battery saver and heat`() {
        assertEquals(6, backgroundBudget(6, Interaction.IDLE, powerSave = false, thermalStatus = 0))
        assertEquals(1, backgroundBudget(6, Interaction.WRITING, powerSave = false, thermalStatus = 0))
        assertEquals(1, backgroundBudget(6, Interaction.MOVING, powerSave = false, thermalStatus = 0))
        assertEquals(2, backgroundBudget(6, Interaction.SETTLING, powerSave = false, thermalStatus = 0))
        assertEquals(2, backgroundBudget(6, Interaction.IDLE, powerSave = true, thermalStatus = 0))
        assertEquals(1, backgroundBudget(6, Interaction.IDLE, powerSave = false, thermalStatus = THERMAL_MODERATE))
        assertEquals(1, backgroundBudget(1, Interaction.IDLE, powerSave = false, thermalStatus = 0))
        assertEquals(1, backgroundBudget(0, Interaction.IDLE, powerSave = false, thermalStatus = 0))
    }
}
