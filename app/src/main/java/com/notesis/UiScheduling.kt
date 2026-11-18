package com.notesis

/**
 * When the note screen's own background work runs: the autosave snapshot
 * and the diagnostics readout. Pure, so the timing can be
 * tested off the device; the screen feeds it the clock and
 * [InteractionGovernor]'s state.
 */
internal object UiScheduling {
    /** A pause this long after the last edit is a natural place to save. */
    const val AUTOSAVE_QUIET_MS = 1200L

    /**
     * The longest an edit waits for that pause. Writing without one used to
     * restart the quiet timer stroke after stroke, so a page written in one
     * go was not saved until the hand stopped.
     */
    const val AUTOSAVE_MAX_DELAY_MS = 8000L

    /**
     * Past the longest wait the save takes the next gap between strokes; this
     * much later still it is taken regardless, so a pen that never lifts (or
     * a state that never clears) cannot starve it.
     */
    const val AUTOSAVE_HARD_GRACE_MS = 4000L

    /**
     * How long after a lift the overdue save waits, so the stroke that just
     * ended is finished and committed before the snapshot is taken.
     */
    const val AUTOSAVE_AFTER_LIFT_MS = 100L

    /** The readout refreshes at most this often... */
    const val HUD_REFRESH_MS = 500L

    /** ...and half as often while a pen writes, so measuring perturbs the stroke less. */
    const val HUD_WRITING_REFRESH_MS = 1000L

    /**
     * How long until the pending edits should be saved; 0 or less means now.
     *
     * [sinceFirstEdit] is the age of the oldest unsaved edit, [sinceLastEdit]
     * of the newest, [quietMs] the time since the last pen lift or finger
     * release (0 while one is down). A save waits for a pause - neither
     * writing nor moving the page - and is never put off past
     * [AUTOSAVE_MAX_DELAY_MS] except by a stroke still being drawn, and never
     * past [AUTOSAVE_MAX_DELAY_MS] + [AUTOSAVE_HARD_GRACE_MS] at all.
     */
    fun autosaveWaitMs(sinceFirstEdit: Long, sinceLastEdit: Long, state: Interaction, quietMs: Long): Long {
        val hard = AUTOSAVE_MAX_DELAY_MS + AUTOSAVE_HARD_GRACE_MS - sinceFirstEdit
        if (hard <= 0L) return 0L
        val busy = state == Interaction.WRITING || state == Interaction.MOVING
        if (busy) {
            // A pen lift or a release changes the state and asks again; until
            // then only the hard limit can make this due.
            return hard
        }
        val quiet = AUTOSAVE_QUIET_MS - sinceLastEdit
        if (quiet <= 0L) return 0L
        val overdue = AUTOSAVE_MAX_DELAY_MS - sinceFirstEdit
        if (overdue <= 0L) {
            val lift = AUTOSAVE_AFTER_LIFT_MS - quietMs
            if (lift <= 0L) return 0L
            return minOf(lift, quiet, hard)
        }
        return minOf(quiet, overdue, hard)
    }

    /** Whether the readout is due a refresh, [sinceLastMs] after the previous one. */
    fun hudRefreshDue(sinceLastMs: Long, writing: Boolean): Boolean =
        sinceLastMs >= if (writing) HUD_WRITING_REFRESH_MS else HUD_REFRESH_MS
}
