package com.notesis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
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
    /** A square of white paper, the icon's shape, laid down below the name. */
    val PAPER = 1600L..1950L
    /** The pen set down where ">" starts. */
    val PEN_IN = 1750L..2000L
    /** ">" written, roughly. */
    val CHEVRON = 2000L..2600L
    /** ">" coming clean as the pen leaves it - the app's own trick. */
    val CHEVRON_SNAP = 2600L..2850L
    /** The pen lifted off ">" and held over its place. */
    val LIFT = 2600L..2900L
    /** Let go: it falls, strikes, rocks and settles. See [WelcomeDrop]. */
    val FALL = 2900L..3550L
    /** The page folding into the guide note's card. */
    val EXIT = 4100L..4700L
    const val TOTAL = 4700L

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
 * a rounded square of paper, ">" written on it roughly and coming clean, and
 * the pen dropped to lie where "_" goes.
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

    /** The point [fraction] of the way along the clean ">", both strokes the same length. */
    fun chevronPoint(fraction: Float): Pair<Float, Float> {
        val f = fraction.coerceIn(0f, 1f) * 2f
        val i = min(f.toInt(), 1)
        val (ax, ay) = CHEVRON[i]
        val (bx, by) = CHEVRON[i + 1]
        val u = f - i
        return (ax + (bx - ax) * u) to (ay + (by - ay) * u)
    }

    /**
     * ">" as a hand writes it, [fraction] of the way along: wandering a
     * little. [clean] moves it onto the exact ">", so 1 is the shape it snaps to.
     */
    fun roughChevronPoint(fraction: Float, clean: Float): Pair<Float, Float> {
        val (ex, ey) = chevronPoint(fraction)
        val turn = fraction * 2f * PI.toFloat()
        val x = ex + WOBBLE * 0.6f * sin(turn * 1.5f + 0.4f)
        val y = ey + WOBBLE * 0.5f * sin(turn * 2.2f + 0.9f)
        return (x + (ex - x) * clean) to (y + (ey - y) * clean)
    }

    /** How much of ">" is written at [t], 0 to 1. */
    fun chevronDrawn(t: Float) = easeInOutQuad(WelcomeTimeline.progress(WelcomeTimeline.CHEVRON, t))

    /** How far ">" has come clean at [t], 0 to 1. */
    fun chevronClean(t: Float) = easeOut(WelcomeTimeline.progress(WelcomeTimeline.CHEVRON_SNAP, t))

    /** How far the paper has come in at [t], 0 to 1. */
    fun paper(t: Float) = easeOut(WelcomeTimeline.progress(WelcomeTimeline.PAPER, t))

    /** Where the pen is at [t], or null before it comes in. */
    fun pen(t: Float): WelcomePen? {
        val tl = WelcomeTimeline
        if (t < tl.PEN_IN.first) return null
        if (t < tl.CHEVRON.first) {
            // Set down from a little above, where ">" starts.
            val e = easeOut(tl.progress(tl.PEN_IN, t))
            val (x, y) = roughChevronPoint(0f, 0f)
            return WelcomePen(x, y - 90f * (1f - e), WRITING_ANGLE, 1.2f - 0.2f * e, lift = 1f - e, alpha = e)
        }
        if (t < tl.LIFT.first) {
            val (x, y) = roughChevronPoint(chevronDrawn(t), 0f)
            return WelcomePen(x, y, WRITING_ANGLE)
        }
        if (t < tl.FALL.first) {
            // Lifted off the end of ">" and carried over to where it is let go.
            val p = easeInOutQuad(tl.progress(tl.LIFT, t))
            val (ax, ay) = roughChevronPoint(1f, 0f)
            val to = onPage(WelcomeDrop.release)
            val arc = 60f * sin(p * PI.toFloat())
            return WelcomePen(ax + (to.x - ax) * p, ay + (to.y - ay) * p - arc,
                WRITING_ANGLE + (to.angle - WRITING_ANGLE) * p, to.scale * p + (1f - p), lift = to.lift * p)
        }
        return onPage(WelcomeDrop.at(t - tl.FALL.first))
    }

    /**
     * A pose of the fall, which is worked out lying along the pen's resting
     * line, as the page has it: tip on the rest point, "up" off that line.
     */
    private fun onPage(pose: DropPose): WelcomePen {
        val a = Math.toRadians(REST_ANGLE.toDouble())
        val ux = cos(a).toFloat()
        val uy = sin(a).toFloat()
        // Up off the resting line, on a page whose y runs down.
        val nx = uy
        val ny = -ux
        val height = pose.tipHeight + WelcomeDrop.LENGTH / 2f * sin(Math.toRadians(pose.angle.toDouble())).toFloat()
        val lift = (height / WelcomeDrop.LIFTED).coerceIn(0f, 1f)
        return WelcomePen(
            REST_X + pose.tipX * ux + pose.tipHeight * nx,
            REST_Y + pose.tipX * uy + pose.tipHeight * ny,
            REST_ANGLE - pose.angle,
            1f + 0.05f * lift,
            lift = lift,
        )
    }
}

/**
 * A moment of the pen's fall: its tip [tipX] along the line it comes to
 * rest on and [tipHeight] above it, and [angle] degrees between the pen and
 * that line - positive with the cap end up.
 */
internal data class DropPose(val tipX: Float, val tipHeight: Float, val angle: Float)

/**
 * The pen's fall, as a rod dropped onto a floor: let go tip-down and a
 * little tilted, the tip strikes first and the pen pivots on it, the cap end
 * slaps down and kicks back, and it rocks to rest. Worked out once, step by
 * step, with gravity and the knock at each end (bounce and friction), then
 * moved along so it comes to rest exactly where the icon has it.
 */
internal object WelcomeDrop {
    /** Tip to the end of the cap, on the mark's grid. */
    const val LENGTH = 362f
    /** The height counted as fully lifted, for the shadow. */
    const val LIFTED = 260f
    private const val GRAVITY = 5200f
    private const val RESTITUTION = 0.38f
    private const val FRICTION = 0.6f
    /** Below this closing speed a contact rests rather than bounces. */
    private const val RESTING_SPEED = 40f
    private const val RELEASE_ANGLE = 34f
    private const val RELEASE_HEIGHT = 150f
    private const val SAMPLE_MS = 4
    /** The last part of the fall, eased onto the exact resting pose. */
    private const val SETTLE_MS = 120f

    private val duration = (WelcomeTimeline.FALL.last - WelcomeTimeline.FALL.first).toFloat()
    private val samples: FloatArray by lazy { simulate() }

    /** Where it is let go. */
    val release: DropPose get() = sample(0)

    /** The pose [ms] after it is let go; at rest from the end of the fall on. */
    fun at(ms: Float): DropPose {
        if (ms <= 0f) return release
        if (ms >= duration) return DropPose(0f, 0f, 0f)
        val f = ms / SAMPLE_MS
        val i = f.toInt()
        val a = sample(i)
        val b = sample(i + 1)
        val u = f - i
        var pose = DropPose(a.tipX + (b.tipX - a.tipX) * u, a.tipHeight + (b.tipHeight - a.tipHeight) * u,
            a.angle + (b.angle - a.angle) * u)
        val settle = ((ms - (duration - SETTLE_MS)) / SETTLE_MS).coerceIn(0f, 1f)
        if (settle > 0f) {
            val k = 1f - easeInOutQuad(settle)
            pose = DropPose(pose.tipX * k, pose.tipHeight * k, pose.angle * k)
        }
        return pose
    }

    private fun sample(i: Int): DropPose {
        val j = i.coerceIn(0, samples.size / 3 - 1) * 3
        return DropPose(samples[j], samples[j + 1], samples[j + 2])
    }

    private fun simulate(): FloatArray {
        val half = LENGTH / 2f
        val mass = 1f
        val inertia = mass * LENGTH * LENGTH / 12f
        var angle = Math.toRadians(RELEASE_ANGLE.toDouble()).toFloat()
        // The centre, from the tip let go at x 0, RELEASE_HEIGHT up.
        var cx = half * cos(angle)
        var cy = RELEASE_HEIGHT + half * sin(angle)
        var vx = 0f
        var vy = 0f
        var spin = -0.4f
        val count = (duration / SAMPLE_MS).toInt() + 1
        val out = FloatArray(count * 3)
        val dt = 0.0005f
        val perSample = ((SAMPLE_MS / 1000f) / dt).roundToInt()
        for (s in 0 until count) {
            out[s * 3] = cx - half * cos(angle)
            out[s * 3 + 1] = cy - half * sin(angle)
            out[s * 3 + 2] = Math.toDegrees(angle.toDouble()).toFloat()
            repeat(perSample) {
                vy -= GRAVITY * dt
                cx += vx * dt
                cy += vy * dt
                angle += spin * dt
                // Each end against the floor: pushed back out, and knocked.
                for (end in intArrayOf(-1, 1)) {
                    val rx = end * half * cos(angle)
                    val ry = end * half * sin(angle)
                    val py = cy + ry
                    if (py >= 0f) continue
                    cy -= py
                    val pvx = vx - spin * ry
                    val pvy = vy + spin * rx
                    if (pvy >= 0f) continue
                    val bounce = if (-pvy < RESTING_SPEED) 0f else RESTITUTION
                    val jn = -(1f + bounce) * pvy / (1f / mass + rx * rx / inertia)
                    var jt = -pvx / (1f / mass + ry * ry / inertia)
                    jt = jt.coerceIn(-FRICTION * jn, FRICTION * jn)
                    vx += jt / mass
                    vy += jn / mass
                    spin += (rx * jn - ry * jt) / inertia
                }
                // Lying on it, it slows: what a soft page does to a pen.
                if (cy < half * 0.2f) {
                    vx *= 1f - 4f * dt
                    spin *= 1f - 4f * dt
                }
            }
        }
        // Moved along so it comes to rest with its tip on the rest point.
        val endX = out[(count - 1) * 3]
        for (s in 0 until count) out[s * 3] -= endX
        return out
    }
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
