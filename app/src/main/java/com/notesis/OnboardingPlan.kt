package com.notesis

/**
 * What a first run shows, in order: the welcome, then a choice of look, then
 * a few marks on the home screen pointing at where things are. Each is shown
 * once; [NONE] is a library like any other day's.
 */
internal enum class OnboardingStep { WELCOME, SKIN, COACH, NONE }

/**
 * The step to show now, from what has already been seen. Without animations
 * there is no welcome to watch, so it goes straight to the choice of look.
 */
internal fun onboardingStep(welcomeDone: Boolean, skinChosen: Boolean, homeTipsDone: Boolean, animations: Boolean): OnboardingStep =
    when {
        !welcomeDone && animations -> OnboardingStep.WELCOME
        !skinChosen -> OnboardingStep.SKIN
        !homeTipsDone -> OnboardingStep.COACH
        else -> OnboardingStep.NONE
    }

/**
 * A tip shown inside a note, once, when it is of use: after the pen has
 * written something and come off the page, never in the middle of a stroke.
 * [afterStrokes] is how much writing comes first, so the first tips are about
 * the pen in hand and the later ones about what else there is.
 */
internal enum class NoteTip(val afterStrokes: Int, val text: String) {
    PEN_SETTINGS(1, "Tap the pen you're holding again for its colour, width and nib."),
    FUNCTIONS(6, "The round button on the bar is Functions: pictures, layers, text, recording and Settings."),
    HOLD_SHAPE(12, "Draw a line, a box or a circle and keep the pen still for a moment: it becomes the shape."),
    GESTURES(20, "Pinch to zoom. Two fingers double-tapped undo; three fingers redo."),
    // Not counted in strokes: it is due when the last page is reached.
    NEW_PAGE(3, "On the last page, pull up past its end to add a new one."),
}

/**
 * The tip due now, or null. One at a time and never two close together:
 * [sinceLastTipMs] is how long ago the last one went. The new-page tip comes
 * first when it fits, because it is about the page in front of the person;
 * the rest come in the order they are listed.
 */
internal fun dueNoteTip(seen: Set<NoteTip>, strokes: Int, onLastPage: Boolean, sinceLastTipMs: Long): NoteTip? {
    if (sinceLastTipMs < NOTE_TIP_SPACING_MS) return null
    if (NoteTip.NEW_PAGE !in seen && onLastPage && strokes >= NoteTip.NEW_PAGE.afterStrokes) return NoteTip.NEW_PAGE
    return NoteTip.entries.firstOrNull { it != NoteTip.NEW_PAGE && it !in seen && strokes >= it.afterStrokes }
}

/** At least this long between two tips, so they never read as a stream of interruptions. */
internal const val NOTE_TIP_SPACING_MS = 40_000L

/** How long the hands have to be off the page before a tip may appear. */
internal const val NOTE_TIP_QUIET_MS = 900L

/** How long a tip stays if it is left alone. */
internal const val NOTE_TIP_SHOWN_MS = 9_000L

/**
 * Where a coach mark's bubble goes for a highlighted [holeTop]..[holeBottom]:
 * below it when it fits there, else above, and never off the screen.
 */
internal fun coachBubbleTop(holeTop: Float, holeBottom: Float, bubbleHeight: Float, screenHeight: Float, gap: Float): Float {
    val below = holeBottom + gap
    if (below + bubbleHeight <= screenHeight - gap) return below
    val above = holeTop - gap - bubbleHeight
    return above.coerceIn(gap, (screenHeight - gap - bubbleHeight).coerceAtLeast(gap))
}

/** The bubble centred on the highlight, as far as the screen's edges allow. */
internal fun coachBubbleLeft(holeCenterX: Float, bubbleWidth: Float, screenWidth: Float, margin: Float): Float =
    (holeCenterX - bubbleWidth / 2f).coerceIn(margin, (screenWidth - margin - bubbleWidth).coerceAtLeast(margin))

/**
 * The welcome, as one clock in milliseconds. Each part of it is a window on
 * that clock, so skipping is moving the clock, and every frame is a pure
 * function of one number.
 */
internal object WelcomeTimeline {
    /** The name written out, outline first. */
    val WRITE = 0L..1500L
    /** Ink filling the letters as the pen finishes them. */
    val FILL = 1100L..1600L
    /** The line under the name. */
    val TAGLINE = 1300L..1800L
    /** A rough box drawn by hand. */
    val SHAPE = 1700L..2500L
    /** The pen held still: a ring closing round it. */
    val HOLD = 2500L..3000L
    /** The rough box becoming a clean one. */
    val SNAP = 3000L..3250L
    /** What just happened, in words. */
    val CAPTION = 2500L..2900L
    /** The page folding into the guide note's card. */
    val EXIT = 3800L..4400L
    const val TOTAL = 4400L

    /** 0 before [window], 1 after it, linear in between. */
    fun progress(window: LongRange, t: Float): Float {
        val start = window.first.toFloat()
        val end = window.last.toFloat()
        if (end <= start) return if (t >= end) 1f else 0f
        return ((t - start) / (end - start)).coerceIn(0f, 1f)
    }
}

/** Ease out: quick away, slow to land. */
internal fun easeOut(x: Float): Float {
    val t = 1f - x.coerceIn(0f, 1f)
    return 1f - t * t * t
}
