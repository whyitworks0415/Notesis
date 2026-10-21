package com.notesis

import com.notesis.UiScheduling.AUTOSAVE_AFTER_LIFT_MS
import com.notesis.UiScheduling.AUTOSAVE_HARD_GRACE_MS
import com.notesis.UiScheduling.AUTOSAVE_MAX_DELAY_MS
import com.notesis.UiScheduling.AUTOSAVE_QUIET_MS
import com.notesis.UiScheduling.INK_INDEX_AFTER_SAVE_MS
import com.notesis.UiScheduling.INK_INDEX_IDLE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSchedulingTest {
    private fun wait(sinceFirst: Long, sinceLast: Long, state: Interaction, quiet: Long = 10_000L) =
        UiScheduling.autosaveWaitMs(sinceFirst, sinceLast, state, quiet)

    @Test
    fun `a pause after the last edit saves`() {
        assertTrue(wait(AUTOSAVE_QUIET_MS, AUTOSAVE_QUIET_MS, Interaction.IDLE) <= 0L)
        assertTrue(wait(5_000L, AUTOSAVE_QUIET_MS + 1, Interaction.SETTLING, quiet = 0L) <= 0L)
    }

    @Test
    fun `before the pause it waits for exactly what is left of it`() {
        assertEquals(AUTOSAVE_QUIET_MS - 300L, wait(300L, 300L, Interaction.IDLE))
    }

    @Test
    fun `never in the middle of a stroke before the hard limit`() {
        // Well past the quiet period and the longest wait, but the pen is down.
        val waiting = wait(AUTOSAVE_MAX_DELAY_MS + 1_000L, AUTOSAVE_QUIET_MS * 3, Interaction.WRITING, quiet = 0L)
        assertTrue(waiting > 0L)
        assertEquals(AUTOSAVE_HARD_GRACE_MS - 1_000L, waiting)
        assertTrue(wait(AUTOSAVE_MAX_DELAY_MS + 1_000L, 0L, Interaction.MOVING, quiet = 0L) > 0L)
    }

    @Test
    fun `continuous writing cannot postpone the save past the longest wait`() {
        // A stroke every 600 ms - 400 down, 200 lifted - and an edit at every
        // lift: the quiet period never comes. The old timer never fired.
        val period = 600L
        val down = 400L
        val firstAt = down
        var savedAt = -1L
        var savedWhile: Interaction? = null
        var t = firstAt
        while (t < 60_000L && savedAt < 0L) {
            val writing = t % period < down
            val lastLift = (t - down).floorDiv(period) * period + down
            val state = if (writing) Interaction.WRITING else Interaction.SETTLING
            val quiet = if (writing) 0L else t - lastLift
            if (wait(t - firstAt, t - lastLift, state, quiet) <= 0L) {
                savedAt = t
                savedWhile = state
            }
            t += 4L
        }
        assertTrue("saved at all", savedAt >= 0L)
        assertEquals("in a gap, not mid-stroke", Interaction.SETTLING, savedWhile)
        assertTrue("not before the longest wait", savedAt - firstAt >= AUTOSAVE_MAX_DELAY_MS)
        assertTrue("in the first gap after it", savedAt - firstAt < AUTOSAVE_MAX_DELAY_MS + period)
    }

    @Test
    fun `overdue save waits a moment after the lift`() {
        val overdue = AUTOSAVE_MAX_DELAY_MS + 10L
        assertEquals(AUTOSAVE_AFTER_LIFT_MS - 30L, wait(overdue, 50L, Interaction.SETTLING, quiet = 30L))
        assertTrue(wait(overdue, 50L, Interaction.SETTLING, quiet = AUTOSAVE_AFTER_LIFT_MS) <= 0L)
    }

    @Test
    fun `the hard limit saves whatever the state`() {
        val past = AUTOSAVE_MAX_DELAY_MS + AUTOSAVE_HARD_GRACE_MS
        for (state in Interaction.entries) assertTrue(state.name, wait(past, 0L, state, 0L) <= 0L)
    }

    @Test
    fun `the wait is never longer than the nearest limit`() {
        for (state in Interaction.entries) {
            for (first in longArrayOf(0L, 500L, 4_000L, AUTOSAVE_MAX_DELAY_MS - 1, AUTOSAVE_MAX_DELAY_MS + 50L)) {
                val w = wait(first, minOf(first, 200L), state, quiet = 0L)
                assertTrue("$state $first", w <= AUTOSAVE_MAX_DELAY_MS + AUTOSAVE_HARD_GRACE_MS - first)
            }
        }
    }

    @Test
    fun `handwriting waits for an idle screen left alone`() {
        assertTrue(UiScheduling.inkIndexWaitMs(Interaction.IDLE, INK_INDEX_IDLE_MS, Long.MAX_VALUE / 2) <= 0L)
        assertEquals(1_000L, UiScheduling.inkIndexWaitMs(Interaction.IDLE, INK_INDEX_IDLE_MS - 1_000L, Long.MAX_VALUE / 2))
        for (busy in listOf(Interaction.WRITING, Interaction.MOVING, Interaction.SETTLING)) {
            assertTrue(busy.name, UiScheduling.inkIndexWaitMs(busy, 60_000L, 60_000L) >= INK_INDEX_IDLE_MS)
        }
    }

    @Test
    fun `handwriting keeps clear of a save that just started`() {
        assertEquals(INK_INDEX_AFTER_SAVE_MS - 500L,
            UiScheduling.inkIndexWaitMs(Interaction.IDLE, INK_INDEX_IDLE_MS * 2, 500L))
    }

    @Test
    fun `readout refreshes at most twice a second and once a second while writing`() {
        assertFalse(UiScheduling.hudRefreshDue(UiScheduling.HUD_REFRESH_MS - 1, writing = false))
        assertTrue(UiScheduling.hudRefreshDue(UiScheduling.HUD_REFRESH_MS, writing = false))
        assertFalse(UiScheduling.hudRefreshDue(UiScheduling.HUD_REFRESH_MS, writing = true))
        assertTrue(UiScheduling.hudRefreshDue(UiScheduling.HUD_WRITING_REFRESH_MS, writing = true))
        // A loop that polls every 16 ms for ten seconds refreshes at most 20 times.
        var last = Long.MIN_VALUE / 2
        var refreshes = 0
        for (t in 0L until 10_000L step 16L) {
            if (UiScheduling.hudRefreshDue(t - last, writing = false)) { last = t; refreshes++ }
        }
        assertTrue("$refreshes", refreshes in 19..20)
    }
}
