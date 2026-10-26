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
        assertTrue(t.PEN_IN.first < t.FRAME.first && t.FRAME.last <= t.HOLD.first && t.HOLD.last <= t.SNAP.first)
        assertTrue(t.SNAP.last <= t.MOVE.last && t.MOVE.last <= t.CHEVRON.first && t.CHEVRON.last <= t.DROP.first)
        assertTrue(t.DROP.last <= t.SETTLE.first && t.SETTLE.last < t.EXIT.first)
        assertEquals(t.TOTAL, t.EXIT.last)
        for (window in listOf(t.WRITE, t.FILL, t.TAGLINE, t.PEN_IN, t.FRAME, t.HOLD, t.CAPTION, t.SNAP, t.PAPER,
            t.MOVE, t.CHEVRON, t.CHEVRON_SNAP, t.DROP, t.SETTLE, t.EXIT)) {
            assertEquals(1f, t.progress(window, t.TOTAL.toFloat()), 0f)
        }
    }

    @Test fun `the square is traced from its left edge all the way round`() {
        val m = WelcomeMark
        val start = m.framePoint(0f)
        assertEquals(0f, start.first, 0.01f)
        assertEquals(m.CORNER, start.second, 0.01f)
        // Every point on the edge of the rounded square, and the trace closes.
        for (i in 0..200) {
            val (x, y) = m.framePoint(m.perimeter * i / 200f)
            assertTrue(x in -0.01f..m.SIZE + 0.01f && y in -0.01f..m.SIZE + 0.01f)
            assertTrue(x < 0.01f || y < 0.01f || x > m.SIZE - 0.01f || y > m.SIZE - 0.01f ||
                cornerDistance(x, y) in m.CORNER - 0.5f..m.CORNER + 0.5f)
        }
        val end = m.framePoint(m.perimeter)
        assertEquals(start.first, end.first, 0.01f)
        assertEquals(start.second, end.second, 0.01f)
        // Half way round is the opposite side.
        assertEquals(m.SIZE, m.framePoint(m.perimeter / 2f).first, 0.01f)
    }

    private fun cornerDistance(x: Float, y: Float): Float {
        val r = WelcomeMark.CORNER
        val cx = if (x < WelcomeMark.SIZE / 2f) r else WelcomeMark.SIZE - r
        val cy = if (y < WelcomeMark.SIZE / 2f) r else WelcomeMark.SIZE - r
        return kotlin.math.hypot(x - cx, y - cy)
    }

    @Test fun `the rough square and chevron wander, and clean they are exact`() {
        val m = WelcomeMark
        var strayed = 0f
        for (i in 0..50) {
            val along = i / 50f
            val (rx, ry) = m.roughFramePoint(along, 0f)
            val (cx, cy) = m.roughFramePoint(along, 1f)
            val (ex, ey) = m.framePoint(along * m.perimeter)
            assertEquals(ex, cx, 0.01f)
            assertEquals(ey, cy, 0.01f)
            // How far off the square's line it is, wherever along it.
            val off = (0 until 400).minOf { j ->
                val (sx, sy) = m.framePoint(m.perimeter * j / 400f)
                kotlin.math.hypot(rx - sx, ry - sy)
            }
            strayed = maxOf(strayed, off)
            val (hx, hy) = m.roughChevronPoint(along, 1f)
            val (gx, gy) = m.chevronPoint(along)
            assertEquals(gx, hx, 0.01f)
            assertEquals(gy, hy, 0.01f)
        }
        // Rough enough to see, not so rough it leaves the square's neighbourhood.
        assertTrue(strayed in 10f..80f)
        assertEquals(470f to 480f, m.chevronPoint(0.5f))
    }

    @Test fun `the pen writes the mark, is dropped without a spin and comes to rest where the icon has it`() {
        val m = WelcomeMark
        val t = WelcomeTimeline
        assertNull(m.pen(t.PEN_IN.first - 1f))
        // Writing: held at the writing angle throughout.
        assertEquals(m.WRITING_ANGLE, m.pen((t.CHEVRON.first + t.CHEVRON.last) / 2f)!!.angle, 0f)
        // Never turned further than from writing to lying: no spin on the way down.
        var at = t.DROP.first.toFloat()
        while (at <= t.TOTAL) {
            val angle = m.pen(at)!!.angle
            assertTrue(angle in m.WRITING_ANGLE - 0.01f..m.REST_ANGLE + 5f)
            at += 10f
        }
        // Falling, it gathers speed: the second half of the fall covers more than the first.
        val drop = t.DROP
        val letGo = drop.first + (drop.last - drop.first) * 0.35f
        val y0 = m.pen(letGo + 1f)!!.y
        val yMid = m.pen((letGo + drop.last) / 2f)!!.y
        val y1 = m.pen(drop.last - 1f)!!.y
        assertTrue(y1 - yMid > yMid - y0)
        // At rest at the end, lying as on the icon, on the paper.
        val rest = m.pen(t.TOTAL.toFloat())!!
        assertEquals(m.REST_X, rest.x, 0.01f)
        assertEquals(m.REST_Y, rest.y, 0.5f)
        assertEquals(m.REST_ANGLE, rest.angle, 0.5f)
        assertEquals(1f, rest.scale, 0f)
        // Landed with no jump: the end of the drop is the start of the bounce.
        val landing = m.pen(t.SETTLE.first - 0.001f)!!
        val bounced = m.pen(t.SETTLE.first.toFloat())!!
        assertEquals(bounced.x, landing.x, 1f)
        assertEquals(bounced.y, landing.y, 2f)
        assertEquals(bounced.angle, landing.angle, 1f)
    }

    @Test fun `the square is drawn, snaps, turns to paper, then the chevron is written and comes clean`() {
        val m = WelcomeMark
        val t = WelcomeTimeline
        assertEquals(0f, m.frameDrawn(t.FRAME.first.toFloat()), 0f)
        assertEquals(1f, m.frameDrawn(t.FRAME.last.toFloat()), 0f)
        assertEquals(0f, m.frameClean(t.HOLD.last.toFloat()), 0f)
        assertEquals(1f, m.frameClean(t.SNAP.last.toFloat()), 0f)
        assertEquals(1f, m.paper(t.CHEVRON.first.toFloat()), 0.05f)
        assertEquals(1f, m.chevronDrawn(t.TOTAL.toFloat()), 0f)
        assertEquals(1f, m.chevronClean(t.TOTAL.toFloat()), 0f)
    }

    @Test fun `the ease out leaves quickly and lands on one`() {
        assertEquals(0f, easeOut(0f), 0f)
        assertEquals(1f, easeOut(1f), 0f)
        assertTrue(easeOut(0.3f) > 0.3f)
    }
}
