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
        LiquidStyle.IOS -> {
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
        LiquidStyle.IOS -> PressRecipe(
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
    val scaleOf: (progress: Float, start: Float) -> Float = { p, s -> s + (1f - s) * p },
    /** How long the width waits behind the height on the way out, so it runs down before it spreads. */
    val widthDelayMs: Int = 0,
    /** The width's own close, when it folds ahead of the height; null is [close]. */
    val closeWidth: AnimationSpec<Float>? = null,
)

private val EaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)
private val LinearToEaseOut = CubicBezierEasing(0.35f, 0.91f, 0.33f, 0.97f)

/** The opening under the recipe in use, or null for the app's own. */
internal fun SkinSettings.openProfile(): OpenProfile? {
    if (projectMotion?.open != true) return null
    return when (liquidStyle) {
        // GlassMorphController: open on stiffness 120, damping 16 (ζ ≈ 0.73),
        // close on 200 / 21 thrown back at −3.2. The size follows
        // linearToEaseOut of the clamped value; past 1 the body swells by a
        // tenth of the overshoot, below 0 it squeezes by 0.55 of it.
        LiquidStyle.IOS -> OpenProfile(
            open = spring(dampingRatio = (16.0 / (2.0 * sqrt(120.0))).toFloat(), stiffness = 120f),
            close = spring(dampingRatio = (21.0 / (2.0 * sqrt(200.0))).toFloat(), stiffness = 200f),
            startScale = POPUP_MORPH_START,
            closeScale = POPUP_MORPH_START,
            alpha = { p -> (p / 0.3f).coerceIn(0f, 1f) },
            closeVelocity = -3.2f,
            scaleOf = { p, s ->
                val clamped = p.coerceIn(0f, 1f)
                val under = if (p < 0f) p else 0f
                val sizeT = LinearToEaseOut.transform(clamped) + under
                val container = when {
                    p > 1f -> 1f + (p - 1f) * 0.10f
                    p < 0f -> 1f + p * 0.55f
                    else -> 1f
                }
                ((s + (1f - s) * sizeT) * container).coerceAtLeast(0.01f)
            },
        )
        // Mornye's context menu: poured out of the button (see [PourOpen]).
        LiquidStyle.MORNYE -> PourOpen
        LiquidStyle.NOTESIS -> null
    }
}

/** How small a morphing menu starts: the trigger's size against the menu's, about. */
private const val POPUP_MORPH_START = 0.06f

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

/** Mornye's pouring spring: damping ratio 0.62 at 15 radians a second. */
internal const val POUR_DAMPING_RATIO = 0.62f
internal const val POUR_STIFFNESS = 15f * 15f

private val EaseInCubic = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

/**
 * Mornye's context menu, as the glass looks open every menu and panel: a drop
 * at the button that runs down - the height first, on an underdamped spring -
 * and spreads 50 ms later to its width, landing with a small wobble; it is
 * seen from the start, as glass is. It goes back into the button in 240 ms,
 * the width folding first.
 */
internal val PourOpen = OpenProfile(
    open = spring(dampingRatio = POUR_DAMPING_RATIO, stiffness = POUR_STIFFNESS),
    close = tween(240, easing = EaseInCubic),
    startScale = POUR_START,
    closeScale = POUR_START,
    alpha = { p -> (p / 0.3f).coerceIn(0f, 1f) },
    scaleOf = ::pourScale,
    widthDelayMs = 50,
    closeWidth = tween(200, easing = EaseInCubic),
)

/** A 44-point drop against a panel of about 360: where a poured menu starts. */
private const val POUR_START = 0.12f

/**
 * Material's: out of the button from 0.8, as its menus grow, on Material 3
 * Expressive's slow spatial spring with no overshoot - Mornye keeps a sheet or
 * menu from overshooting its own size - and fading in on the way; back in
 * 200 ms on the emphasised accelerate curve.
 */
internal val MaterialOpen = OpenProfile(
    open = spring(dampingRatio = 1f, stiffness = 200f),
    close = tween(200, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)),
    startScale = 0.8f,
    closeScale = 0.8f,
    alpha = { p -> (p / 0.45f).coerceIn(0f, 1f) },
)
