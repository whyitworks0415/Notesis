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
        // DampedDragAnimation: the value on spring(1, 1000) whether dragged or
        // sent; the indicator's scale on spring(0.6, 250) across, (0.7, 250) down.
        LiquidStyle.KYANT -> SlideProfile(
            follow = 1000.0 to dampingFor(1.0, 1000.0),
            travel = 1000.0 to dampingFor(1.0, 1000.0),
            liftX = 250.0 to dampingFor(0.6, 250.0),
            liftY = 250.0 to dampingFor(0.7, 250.0),
        )
        // LiquidGlassTabBar's droplet: 380 ms on OvershootInterpolator(1.1),
        // as the spring that settles in that time with that overshoot -
        // withDurationAndBounce(380 ms, 0.25). It stays under the finger while dragged.
        LiquidStyle.QWEA -> {
            val k = stiffnessFor(380)
            val pair = k to dampingFor(0.75, k)
            SlideProfile(follow = null, travel = pair, liftX = pair, liftY = pair)
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
        // LiquidButton: lerp(1, 1 + 4 dp / height, pressProgress), the progress on spring(0.5, 300).
        LiquidStyle.KYANT -> PressRecipe(
            scale = { _, h, d -> 1f + 4f * d / max(h, 1f) },
            press = spring(dampingRatio = 0.5f, stiffness = 300f),
            release = spring(dampingRatio = 0.5f, stiffness = 300f),
        )
        // LiquidGlassView.animatePressScale: to pressScale and back over 200 ms, decelerating.
        LiquidStyle.QWEA -> {
            val to = qwea.pressScale
            PressRecipe(
                scale = { _, _, _ -> to },
                press = tween(200, easing = decelerate(1f)),
                release = tween(200, easing = decelerate(1f)),
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
        // ControlCenterContent: down 48 dp into place, fading in on EaseIn,
        // on spring(0.5, 300) opening and spring(1, 300) closing.
        LiquidStyle.KYANT -> OpenProfile(
            open = spring(dampingRatio = 0.5f, stiffness = 300f),
            close = spring(dampingRatio = 1f, stiffness = 300f),
            startScale = 1f,
            closeScale = 1f,
            alpha = { p -> EaseIn.transform(p.coerceIn(0f, 1f)) },
            dropDp = 48f,
        )
        // LiquidGlassDialogBuilder: from 0.86 and transparent over 250 ms on
        // DecelerateInterpolator(1.6); to 0.88 over 150 ms on AccelerateInterpolator(1.4).
        LiquidStyle.QWEA -> OpenProfile(
            open = tween(250, easing = decelerate(1.6f)),
            close = tween(150, easing = accelerate(1.4f)),
            startScale = 0.86f,
            closeScale = 0.88f,
            alpha = { p -> p.coerceIn(0f, 1f) },
        )
        LiquidStyle.NOTESIS -> null
    }
}

/** How small a morphing menu starts: the trigger's size against the menu's, about. */
private const val POPUP_MORPH_START = 0.06f
