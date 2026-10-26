package com.notesis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

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
    /** The pen set down at the corner of the mark. */
    val PEN_IN = 1500L..1750L
    /** The icon's rounded square, drawn roughly by hand. */
    val FRAME = 1700L..2500L
    /** The pen held still at the end: a ring closing round it. */
    val HOLD = 2500L..2900L
    /** What the hold does, in words. */
    val CAPTION = 2500L..2900L
    /** The rough square becoming a clean one - the app's own trick. */
    val SNAP = 2900L..3150L
    /** The square turning to white paper. */
    val PAPER = 3050L..3400L
    /** The pen lifted across to where ">" starts. */
    val MOVE = 3150L..3350L
    /** ">" written, roughly. */
    val CHEVRON = 3350L..3950L
    /** ">" coming clean as the pen leaves it. */
    val CHEVRON_SNAP = 3950L..4200L
    /** The pen lifted off and dropped where "_" goes. */
    val DROP = 3950L..4500L
    /** Its little bounce as it lands. */
    val SETTLE = 4500L..4900L
    /** The page folding into the guide note's card. */
    val EXIT = 5400L..6000L
    const val TOTAL = 6000L

    /** 0 before [window], 1 after it, linear in between. */
    fun progress(window: LongRange, t: Float): Float {
        val start = window.first.toFloat()
        val end = window.last.toFloat()
        if (end <= start) return if (t >= end) 1f else 0f
        return ((t - start) / (end - start)).coerceIn(0f, 1f)
    }
}

/**
 * The app's mark as the welcome draws it, on the launcher icon's 1024 grid:
 * a rounded square, ">" inside it, and the pen lying where "_" goes. Both
 * are drawn roughly first, as a hand draws them, and then come clean.
 */
internal object WelcomeMark {
    const val SIZE = 1024f
    const val CORNER = 236f
    /** ">": top end, point, bottom end. */
    val CHEVRON = listOf(292f to 330f, 470f to 480f, 292f to 630f)
    const val CHEVRON_WIDTH = 92f
    /** Where the pen comes to rest: its tip, and the way its body points. */
    const val REST_X = 520f
    const val REST_Y = 668f
    const val REST_ANGLE = -6f
    /** The pen's angle while it writes, held as a right hand holds it. */
    const val WRITING_ANGLE = -72f
    /** How far a hand's line strays from the clean one, on this grid. */
    private const val WOBBLE = 22f

    /** How far round the square it is. */
    val perimeter: Float get() = 4f * (SIZE - 2f * CORNER) + 2f * PI.toFloat() * CORNER

    /**
     * The point [distance] round the clean square, clockwise from the middle
     * of the top left corner's left edge - where a hand starts a box.
     */
    fun framePoint(distance: Float): Pair<Float, Float> {
        val r = CORNER
        val straight = SIZE - 2f * r
        val quarter = PI.toFloat() * r / 2f
        var d = ((distance % perimeter) + perimeter) % perimeter
        fun arc(cx: Float, cy: Float, from: Float): Pair<Float, Float> {
            val a = Math.toRadians((from + 90f * d / quarter).toDouble())
            return (cx + r * cos(a)).toFloat() to (cy + r * sin(a)).toFloat()
        }
        if (d < quarter) return arc(r, r, 180f)
        d -= quarter
        if (d < straight) return (r + d) to 0f
        d -= straight
        if (d < quarter) return arc(SIZE - r, r, 270f)
        d -= quarter
        if (d < straight) return SIZE to (r + d)
        d -= straight
        if (d < quarter) return arc(SIZE - r, SIZE - r, 0f)
        d -= quarter
        if (d < straight) return (SIZE - r - d) to SIZE
        d -= straight
        if (d < quarter) return arc(r, SIZE - r, 90f)
        d -= quarter
        return 0f to (SIZE - r - d)
    }

    /**
     * The square as a hand draws it, [along] of the way round (0 to 1):
     * wandering a little and running on past where it began. [clean] moves
     * it onto the exact square, so 1 is the shape it snaps to.
     */
    fun roughFramePoint(along: Float, clean: Float): Pair<Float, Float> {
        val (ex, ey) = framePoint(along * perimeter)
        val (rx, ry) = framePoint(along * 1.04f * perimeter)
        val turn = along * 2f * PI.toFloat()
        val drift = WOBBLE * (sin(turn * 3f) + 0.6f * sin(turn * 7.3f + 1.1f))
        val x = rx + cos(turn) * drift
        val y = ry + sin(turn * 1.7f) * drift
        return (x + (ex - x) * clean) to (y + (ey - y) * clean)
    }

    /** The point [fraction] of the way along the clean ">", both strokes the same length. */
    fun chevronPoint(fraction: Float): Pair<Float, Float> {
        val f = fraction.coerceIn(0f, 1f) * 2f
        val i = min(f.toInt(), 1)
        val (ax, ay) = CHEVRON[i]
        val (bx, by) = CHEVRON[i + 1]
        val u = f - i
        return (ax + (bx - ax) * u) to (ay + (by - ay) * u)
    }

    /** ">" as a hand writes it, [fraction] of the way along; [clean] as for [roughFramePoint]. */
    fun roughChevronPoint(fraction: Float, clean: Float): Pair<Float, Float> {
        val (ex, ey) = chevronPoint(fraction)
        val turn = fraction * 2f * PI.toFloat()
        val x = ex + WOBBLE * 0.6f * sin(turn * 1.5f + 0.4f)
        val y = ey + WOBBLE * 0.5f * sin(turn * 2.2f + 0.9f)
        return (x + (ex - x) * clean) to (y + (ey - y) * clean)
    }

    /** How far round the square the pen has gone at [t], 0 to 1. */
    fun frameDrawn(t: Float) = easeInOutQuad(WelcomeTimeline.progress(WelcomeTimeline.FRAME, t))

    /** How far the square has come clean at [t], 0 to 1. */
    fun frameClean(t: Float) = easeOut(WelcomeTimeline.progress(WelcomeTimeline.SNAP, t))

    /** How much of ">" is written at [t], 0 to 1. */
    fun chevronDrawn(t: Float) = easeInOutQuad(WelcomeTimeline.progress(WelcomeTimeline.CHEVRON, t))

    /** How far ">" has come clean at [t], 0 to 1. */
    fun chevronClean(t: Float) = easeOut(WelcomeTimeline.progress(WelcomeTimeline.CHEVRON_SNAP, t))

    /** How far the square has turned to paper at [t], 0 to 1. */
    fun paper(t: Float) = easeOut(WelcomeTimeline.progress(WelcomeTimeline.PAPER, t))

    /** Where the pen is at [t], or null before it comes in. */
    fun pen(t: Float): WelcomePen? {
        val tl = WelcomeTimeline
        if (t < tl.PEN_IN.first) return null
        if (t < tl.FRAME.first) {
            // Set down from a little above, at the square's starting corner.
            val e = easeOut(tl.progress(tl.PEN_IN, t))
            val (x, y) = roughFramePoint(0f, 0f)
            return WelcomePen(x, y - 90f * (1f - e), WRITING_ANGLE, 1.2f - 0.2f * e, lift = 1f - e, alpha = e)
        }
        if (t < tl.MOVE.first) {
            // Going round, then held still at the end while the square snaps under it.
            val (x, y) = roughFramePoint(frameDrawn(t), frameClean(t))
            return WelcomePen(x, y, WRITING_ANGLE)
        }
        if (t < tl.CHEVRON.first) {
            val p = tl.progress(tl.MOVE, t)
            val e = easeInOutQuad(p)
            val (ax, ay) = roughFramePoint(1f, 1f)
            val (bx, by) = roughChevronPoint(0f, 0f)
            val lift = sin(p * PI.toFloat())
            return WelcomePen(ax + (bx - ax) * e, ay + (by - ay) * e - 50f * lift, WRITING_ANGLE, 1f + 0.08f * lift, lift)
        }
        if (t < tl.DROP.first) {
            val (x, y) = roughChevronPoint(chevronDrawn(t), 0f)
            return WelcomePen(x, y, WRITING_ANGLE)
        }
        if (t < tl.SETTLE.first) {
            // Lifted off the end of ">" to just above its place, then let go:
            // it falls, gathering speed, and tips over to lie as it lands.
            val p = tl.progress(tl.DROP, t)
            val (ax, ay) = roughChevronPoint(1f, 0f)
            val hoverX = REST_X
            val hoverY = REST_Y - DROP_HEIGHT
            if (p < LIFT_SHARE) {
                val e = easeOut(p / LIFT_SHARE)
                return WelcomePen(ax + (hoverX - ax) * e, ay + (hoverY - ay) * e, WRITING_ANGLE, 1f + 0.08f * e, lift = e)
            }
            val f = (p - LIFT_SHARE) / (1f - LIFT_SHARE)
            val fall = f * f
            val tip = easeInOutQuad(f)
            return WelcomePen(
                hoverX,
                hoverY + DROP_HEIGHT * fall,
                WRITING_ANGLE + (REST_ANGLE - WRITING_ANGLE) * tip,
                1.08f - 0.08f * fall,
                lift = 1f - fall,
            )
        }
        // Landed: a bounce and a small rock that die away.
        val p = tl.progress(tl.SETTLE, t)
        val decay = exp(-4f * p)
        val bounce = 26f * decay * abs(sin(p * 3f * PI.toFloat()))
        return WelcomePen(REST_X, REST_Y - bounce, REST_ANGLE + 4f * decay * sin(p * 4f * PI.toFloat()), lift = bounce / DROP_HEIGHT)
    }

    /** How far above its place the pen is let go. */
    private const val DROP_HEIGHT = 220f
    /** The part of the drop spent lifting it there. */
    private const val LIFT_SHARE = 0.35f
}

/**
 * The pen's pose: its tip at ([x], [y]) on the mark's grid, its body pointing
 * [angle] degrees round from the right, [scale] larger when it is nearer the
 * eye, [lift] how far off the paper (0 on it), [alpha] how far faded in.
 */
internal data class WelcomePen(
    val x: Float,
    val y: Float,
    val angle: Float,
    val scale: Float = 1f,
    val lift: Float = 0f,
    val alpha: Float = 1f,
)

/** Ease out: quick away, slow to land. */
internal fun easeOut(x: Float): Float {
    val t = 1f - x.coerceIn(0f, 1f)
    return 1f - t * t * t
}
