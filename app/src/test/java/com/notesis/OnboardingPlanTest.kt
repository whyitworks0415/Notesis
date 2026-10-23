package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPlanTest {
    @Test fun `a first run goes welcome, look, home marks, then nothing`() {
        assertEquals(OnboardingStep.WELCOME, onboardingStep(false, false, false, animations = true))
        assertEquals(OnboardingStep.SKIN, onboardingStep(true, false, false, animations = true))
        assertEquals(OnboardingStep.COACH, onboardingStep(true, true, false, animations = true))
        assertEquals(OnboardingStep.NONE, onboardingStep(true, true, true, animations = true))
    }

    @Test fun `without animations the welcome is left out`() {
        assertEquals(OnboardingStep.SKIN, onboardingStep(false, false, false, animations = false))
        assertEquals(OnboardingStep.NONE, onboardingStep(false, true, true, animations = false))
    }

    @Test fun `tips come with writing, one at a time`() {
        val long = NOTE_TIP_SPACING_MS
        assertNull(dueNoteTip(emptySet(), strokes = 0, onLastPage = false, sinceLastTipMs = long))
        assertEquals(NoteTip.PEN_SETTINGS, dueNoteTip(emptySet(), 1, false, long))
        // Too soon after the last one: nothing, however much is due.
        assertNull(dueNoteTip(emptySet(), 50, false, long - 1))
        assertEquals(NoteTip.FUNCTIONS, dueNoteTip(setOf(NoteTip.PEN_SETTINGS), 6, false, long))
        assertNull(dueNoteTip(setOf(NoteTip.PEN_SETTINGS), 5, false, long))
        val allButLast = setOf(NoteTip.PEN_SETTINGS, NoteTip.FUNCTIONS, NoteTip.HOLD_SHAPE)
        assertEquals(NoteTip.GESTURES, dueNoteTip(allButLast, 20, false, long))
        assertNull(dueNoteTip(NoteTip.entries.toSet(), 1000, true, long))
    }

    @Test fun `the new page tip waits for the last page, then goes first`() {
        val long = NOTE_TIP_SPACING_MS
        // Never on another page, whatever has been written.
        assertTrue(dueNoteTip(emptySet(), 100, onLastPage = false, sinceLastTipMs = long) != NoteTip.NEW_PAGE)
        assertEquals(NoteTip.NEW_PAGE, dueNoteTip(emptySet(), 3, onLastPage = true, sinceLastTipMs = long))
        // Not before a little has been written there.
        assertEquals(NoteTip.PEN_SETTINGS, dueNoteTip(emptySet(), 2, onLastPage = true, sinceLastTipMs = long))
    }

    @Test fun `a coach bubble sits below its target when there is room, else above`() {
        assertEquals(212f, coachBubbleTop(100f, 200f, 120f, 1000f, 12f), 0f)
        // A target at the bottom: above it.
        assertEquals(768f, coachBubbleTop(900f, 980f, 120f, 1000f, 12f), 0f)
        // No room either way: kept on screen.
        assertEquals(12f, coachBubbleTop(20f, 990f, 200f, 1000f, 12f), 0f)
    }

    @Test fun `a coach bubble is centred on its target within the margins`() {
        assertEquals(350f, coachBubbleLeft(500f, 300f, 1000f, 16f), 0f)
        assertEquals(16f, coachBubbleLeft(20f, 300f, 1000f, 16f), 0f)
        assertEquals(684f, coachBubbleLeft(990f, 300f, 1000f, 16f), 0f)
    }

    @Test fun `the welcome's windows run in order and end with the clock`() {
        val t = WelcomeTimeline
        assertEquals(0f, t.progress(t.WRITE, 0f), 0f)
        assertEquals(0.5f, t.progress(t.WRITE, 750f), 0.0001f)
        assertEquals(1f, t.progress(t.WRITE, 2000f), 0f)
        assertTrue(t.SHAPE.first < t.HOLD.first && t.HOLD.last <= t.SNAP.first)
        assertTrue(t.SNAP.last < t.EXIT.first)
        assertEquals(t.TOTAL, t.EXIT.last)
        for (window in listOf(t.WRITE, t.FILL, t.TAGLINE, t.SHAPE, t.HOLD, t.SNAP, t.CAPTION, t.EXIT)) {
            assertEquals(1f, t.progress(window, t.TOTAL.toFloat()), 0f)
        }
    }

    @Test fun `the ease out leaves quickly and lands on one`() {
        assertEquals(0f, easeOut(0f), 0f)
        assertEquals(1f, easeOut(1f), 0f)
        assertTrue(easeOut(0.3f) > 0.3f)
    }
}
