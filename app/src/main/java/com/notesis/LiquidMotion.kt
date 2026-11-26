package com.notesis

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Each recipe's own motion, used where its [ProjectMotion] switch is on;
 * where it is off, or under this app's own glass, nothing here applies and the
 * motion is the app's as before.
 *
 * Springs given by a duration and a bounce are Flutter's
 * SpringDescription.withDurationAndBounce, as liquid_glass_widgets' GlassSpring
 * uses it: stiffness 4π² / duration², damping ratio 1 − bounce.
 */
private fun stiffnessFor(durationMs: Int): Double = 4.0 * Math.PI * Math.PI / (durationMs / 1000.0).pow(2)

/** Damping per unit mass for [ratio] at [stiffness]: c = 2ζ√k. */
private fun dampingFor(ratio: Double, stiffness: Double): Double = 2.0 * ratio * sqrt(stiffness)

/**
 * How the toolbar's selection travels: [follow] is the spring it chases a
 * dragging finger on (null: it keeps to the finger as before), [travel] the
 * one it lands on after a tap or a release, [lift] the one its lens swells
 * and settles on, across and down. Stiffness and damping per unit mass.
 */
internal class SlideProfile(
    val follow: Pair<Double, Double>?,
    val travel: Pair<Double, Double>,
    val liftX: Pair<Double, Double>,
    val liftY: Pair<Double, Double>,
)

/** The selection's motion under the recipe in use, or null for the app's own. */
internal fun SkinSettings.slideProfile(): SlideProfile? {
    if (projectMotion?.slide != true) return null
    return when (liquidStyle) {
        // GlassSpring.interactive() while the finger drags it, snappy(350 ms)
        // when it is let go; the lens lifts and settles on snappy(300 ms).
        LiquidStyle.IOS, LiquidStyle.IOS_LITE -> {
            val drag = stiffnessFor(150)
            val land = stiffnessFor(350)
            val lift = stiffnessFor(300)
            SlideProfile(
                follow = drag to dampingFor(1.0 - 0.14, drag),
                travel = land to dampingFor(1.0 - 0.15, land),
                liftX = lift to dampingFor(1.0 - 0.15, lift),
                liftY = lift to dampingFor(1.0 - 0.15, lift),
            )
        }
        // Its selection pill slides on 220 ms of ease-out-cubic; as a spring, the
        // critically damped one that settles in that time. The lens under the
        // finger lifts on its press spring: stiffness 420, damping 24.
        LiquidStyle.MORNYE -> {
            val land = stiffnessFor(240)
            val lift = MORNYE_PRESS_STIFFNESS.toDouble() to MORNYE_PRESS_DAMPING.toDouble()
            SlideProfile(follow = null, travel = land to dampingFor(1.0, land), liftX = lift, liftY = lift)
        }
        LiquidStyle.NOTESIS -> null
    }
}

/**
 * How a glass button answers a press: [scale] it grows (or shrinks) to for a
 * button [width] by [height] pixels, and the animations into and out of it.
 */
internal class PressRecipe(
    val scale: (width: Float, height: Float, density: Float) -> Float,
    val press: AnimationSpec<Float>,
    val release: AnimationSpec<Float>,
)

/** Android's DecelerateInterpolator(factor): 1 − (1 − t)^(2 × factor). */
internal fun decelerate(factor: Float): Easing = Easing { t -> 1f - (1f - t).pow(2f * factor) }

/** Android's AccelerateInterpolator(factor): t^(2 × factor). */
internal fun accelerate(factor: Float): Easing = Easing { t -> t.pow(2f * factor) }

/** The press under the recipe in use, or null for the app's own. */
internal fun SkinSettings.pressRecipe(): PressRecipe? {
    if (projectMotion?.press != true) return null
    return when (liquidStyle) {
        // LiquidStretch.nativePressGrowth: ~17 pt along the longest side, on
        // snappy(250 ms, +0.1) into the press and bouncy(280 ms, +0.15) out.
        LiquidStyle.IOS, LiquidStyle.IOS_LITE -> PressRecipe(
            scale = { w, h, d -> 1f + 17f * d / max(max(w, h), 1f) },
            press = spring(dampingRatio = 1f - 0.25f, stiffness = stiffnessFor(250).toFloat()),
            release = spring(dampingRatio = 1f - 0.45f, stiffness = stiffnessFor(280).toFloat()),
        )
        // MornyeGlassPress: a fixed swell of a tenth of the short side, held
        // between 2 and 5 points, so a wide bar moves no more than a button,
        // on an underdamped spring (stiffness 420, damping 24) both ways.
        LiquidStyle.MORNYE -> {
            val spring = spring<Float>(dampingRatio = MORNYE_PRESS_RATIO, stiffness = MORNYE_PRESS_STIFFNESS)
            PressRecipe(
                scale = { w, h, d -> 1f + mornyeSwell(w, h, d) / max(max(w, h), 1f) },
                press = spring,
                release = spring,
            )
        }
        LiquidStyle.NOTESIS -> null
    }
}

/**
 * How a menu or panel comes out of the button that opened it and goes back:
 * the animations of its 0-to-1 progress, the [startScale] it grows from, how
 * opaque it is for a progress ([alpha]) and how far it drops into place
 * ([dropDp], from above), and the velocity a close is thrown with.
 * [scaleOf] turns a progress - which a spring may carry past 1 or below 0 -
 * into the scale drawn.
 */
internal class OpenProfile(
    val open: AnimationSpec<Float>,
    val close: AnimationSpec<Float>,
    val startScale: Float,
    val closeScale: Float,
    val alpha: (Float) -> Float,
    val dropDp: Float = 0f,
    val closeVelocity: Float = 0f,
    /**
     * The progress per second an opening starts with. From rest, a spring's
     * first frames hardly move, and a popup that answered a tap two frames
     * late then crept out read as a beat behind the finger.
     */
    val openVelocity: Float = 0f,
    val scaleOf: (progress: Float, start: Float) -> Float = { p, s -> s + (1f - s) * p },
    /** How long the width waits behind the height on the way out, so it runs down before it spreads. */
    val widthDelayMs: Int = 0,
    /** The width's own close, when it folds ahead of the height; null is [close]. */
    val closeWidth: AnimationSpec<Float>? = null,
    /**
     * The rounded window and its cached contents morph together (see PopupReveal.kt).
     */
    val reveal: Boolean = false,
)

private val EaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)

/** The opening under the recipe in use, or null for the app's own. */
internal fun SkinSettings.openProfile(): OpenProfile? {
    if (projectMotion?.open != true) return null
    return when (liquidStyle) {
        // iOS and Mornye together: its menus pour out of the button as
        // Mornye's do, the glass growing round content that stays its size.
        LiquidStyle.IOS -> PourOpen
        // Mornye's context menu: poured out of the button (see [PourOpen]).
        LiquidStyle.MORNYE -> PourOpen
        // The light one opens as Liquid glass does: a pour reshapes the glass on every
        // frame of the opening, and every frame of it worked the blur out again.
        LiquidStyle.IOS_LITE, LiquidStyle.NOTESIS -> null
    }
}

/** Mornye's press spring: stiffness 420, damping 24, so a release overshoots a little and settles. */
internal const val MORNYE_PRESS_STIFFNESS = 420f
internal const val MORNYE_PRESS_DAMPING = 24f
internal val MORNYE_PRESS_RATIO: Float = (MORNYE_PRESS_DAMPING / (2.0 * sqrt(MORNYE_PRESS_STIFFNESS.toDouble()))).toFloat()

/** How far a Mornye control swells under a finger, in px: a tenth of its short side, 2 to 5 points. */
internal fun mornyeSwell(width: Float, height: Float, density: Float): Float =
    (minOf(width, height) * 0.10f).coerceIn(2f * density, 5f * density)

/**
 * The scale a poured menu is drawn at for a spring [progress]: out of [start]
 * to full, and past full by only 0.35 of the spring's overshoot, so the
 * glass runs just past its size and settles rather than bouncing.
 */
internal fun pourScale(progress: Float, start: Float): Float = when {
    progress <= 0f -> start
    progress <= 1f -> start + (1f - start) * progress
    else -> 1f + (progress - 1f) * POUR_OVERSHOOT_KEPT
}

/** How much of the spring's overshoot a poured menu keeps. */
internal const val POUR_OVERSHOOT_KEPT = 0.35f

/**
 * The pouring spring: Mornye's own, a damping ratio of 0.62 at 15 radians a
 * second. Paced by the motion speed (1.3 by default) it runs at about 19,
 * which a 60 Hz screen shows moving on the first frame after the tap.
 */
internal const val POUR_DAMPING_RATIO = 0.62f
internal const val POUR_STIFFNESS = 15f * 15f

private val EaseInCubic = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

/**
 * Mornye's context menu, as the glass looks open every menu and panel: a drop
 * at the button runs down - the height first, on an underdamped spring - and
 * spreads 50 ms later to its width, landing with a small wobble; the glass is
 * seen from the start and the rows fade up on it (see PopupReveal.kt). It goes
 * back into the button in 240 ms, the width folding first, in 200.
 */
internal val PourOpen = OpenProfile(
    open = spring(dampingRatio = POUR_DAMPING_RATIO, stiffness = POUR_STIFFNESS),
    close = tween(240, easing = EaseInCubic),
    startScale = POUR_START,
    closeScale = POUR_START,
    alpha = { p -> (p / 0.08f).coerceIn(0f, 1f) },
    scaleOf = ::pourScale,
    widthDelayMs = POUR_WIDTH_DELAY_MS,
    // From rest a spring's first frames hardly move; a little speed to start
    // with, so the drop is seen leaving the button on the frame after the tap.
    openVelocity = 4f,
    closeWidth = tween(200, easing = EaseInCubic),
    reveal = true,
)

/** How long the width waits behind the height, as Mornye's does, before the global speed. */
internal const val POUR_WIDTH_DELAY_MS = 50

/** A 44-point drop against a panel of about 360: where a poured menu starts. */
private const val POUR_START = 0.12f

/** Material surfaces share one restrained, source-anchored spatial spring. */
internal val MaterialOpen = OpenProfile(
    open = MaterialMotion.rawContainer(),
    close = MaterialMotion.rawExit(),
    startScale = MaterialMotion.START_SCALE,
    closeScale = MaterialMotion.START_SCALE,
    alpha = { p -> (p / 0.45f).coerceIn(0f, 1f) },
    // Under the spring's own 14 rad/s, so it still lands without overshoot.
    openVelocity = 3f,
    reveal = true,
)
