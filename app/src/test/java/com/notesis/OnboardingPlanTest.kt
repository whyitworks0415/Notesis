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
        assertTrue(t.PAPER.first < t.PEN_IN.first && t.PEN_IN.last <= t.CHEVRON.first)
        assertTrue(t.CHEVRON.last <= t.LIFT.first && t.LIFT.last <= t.FALL.first && t.FALL.last < t.EXIT.first)
        assertEquals(t.TOTAL, t.EXIT.last)
        for (window in listOf(t.WRITE, t.FILL, t.TAGLINE, t.PAPER, t.PEN_IN, t.CHEVRON, t.CHEVRON_SNAP, t.LIFT, t.FALL, t.EXIT)) {
            assertEquals(1f, t.progress(window, t.TOTAL.toFloat()), 0f)
        }
    }

    @Test fun `the rough chevron wanders, and clean it is exact`() {
        val m = WelcomeMark
        var strayed = 0f
        for (i in 0..50) {
            val along = i / 50f
            val (hx, hy) = m.roughChevronPoint(along, 1f)
            val (gx, gy) = m.chevronPoint(along)
            assertEquals(gx, hx, 0.01f)
            assertEquals(gy, hy, 0.01f)
            val (rx, ry) = m.roughChevronPoint(along, 0f)
            strayed = maxOf(strayed, kotlin.math.hypot(rx - gx, ry - gy))
        }
        assertTrue(strayed in 5f..40f)
        assertEquals(470f to 480f, m.chevronPoint(0.5f))
    }

    @Test fun `the dropped pen strikes tip first, then cap, never goes through the page, and turns less than a right angle`() {
        val d = WelcomeDrop
        val fall = (WelcomeTimeline.FALL.last - WelcomeTimeline.FALL.first).toFloat()
        var tipFirstAt = -1f
        var capFirstAt = -1f
        var least = Float.MAX_VALUE
        var most = -Float.MAX_VALUE
        var ms = 0f
        while (ms <= fall) {
            val p = d.at(ms)
            val a = Math.toRadians(p.angle.toDouble())
            val capHeight = p.tipHeight + d.LENGTH * kotlin.math.sin(a).toFloat()
            assertTrue("tip under the page at $ms", p.tipHeight > -3f)
            assertTrue("cap under the page at $ms", capHeight > -3f)
            if (tipFirstAt < 0f && p.tipHeight < 1f) tipFirstAt = ms
            if (capFirstAt < 0f && capHeight < 1f) capFirstAt = ms
            least = minOf(least, p.angle)
            most = maxOf(most, p.angle)
            ms += 2f
        }
        assertTrue("the tip lands at $tipFirstAt, the cap at $capFirstAt", tipFirstAt in 0f..capFirstAt - 20f)
        // From the writing angle to lying down, and its rocking, inside a right angle.
        val writing = WelcomeMark.REST_ANGLE - WelcomeMark.WRITING_ANGLE
        assertTrue(maxOf(writing, most) - least < 90f)
        // At rest at the end, exactly.
        assertEquals(DropPose(0f, 0f, 0f), d.at(fall))
    }

    @Test fun `the pen writes the mark and comes to rest where the icon has it`() {
        val m = WelcomeMark
        val t = WelcomeTimeline
        assertNull(m.pen(t.PEN_IN.first - 1f))
        assertEquals(m.WRITING_ANGLE, m.pen((t.CHEVRON.first + t.CHEVRON.last) / 2f)!!.angle, 0f)
        // Lifted then let go with no jump.
        val held = m.pen(t.FALL.first - 0.01f)!!
        val dropped = m.pen(t.FALL.first.toFloat())!!
        assertEquals(dropped.x, held.x, 1f)
        assertEquals(dropped.y, held.y, 1f)
        assertEquals(dropped.angle, held.angle, 0.5f)
        val rest = m.pen(t.TOTAL.toFloat())!!
        assertEquals(m.REST_X, rest.x, 0.01f)
        assertEquals(m.REST_Y, rest.y, 0.01f)
        assertEquals(m.REST_ANGLE, rest.angle, 0.01f)
        assertEquals(1f, rest.scale, 0f)
        assertEquals(0f, rest.lift, 0f)
    }

    @Test fun `the paper comes before the pen, and the chevron is written and comes clean`() {
        val m = WelcomeMark
        val t = WelcomeTimeline
        assertEquals(1f, m.paper(t.CHEVRON.first.toFloat()), 0.05f)
        assertEquals(1f, m.chevronDrawn(t.TOTAL.toFloat()), 0f)
        assertEquals(0f, m.chevronClean(t.CHEVRON.last.toFloat()), 0f)
        assertEquals(1f, m.chevronClean(t.TOTAL.toFloat()), 0f)
    }

    @Test fun `the ease out leaves quickly and lands on one`() {
        assertEquals(0f, easeOut(0f), 0f)
        assertEquals(1f, easeOut(1f), 0f)
        assertTrue(easeOut(0.3f) > 0.3f)
    }
}
