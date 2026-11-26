package com.notesis

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Lets the frame that first composes and draws something new go by before
 * anything is timed from it. It waited for the frame after as well, which on
 * a 60 Hz screen held every popup and panel still for a frame more than
 * needed: the beat between a tap and the first movement.
 */
internal suspend fun awaitFirstFrames() {
    androidx.compose.runtime.withFrameNanos { }
}

/**
 * Two frames: the one a popup's window is added in and the one it is first
 * laid out and drawn in. Something moving that starts after them does not
 * stall halfway for the popup.
 */
internal suspend fun awaitPopupFrames() {
    androidx.compose.runtime.withFrameNanos { }
    androidx.compose.runtime.withFrameNanos { }
}

/**
 * How far a menu, panel or dialog has come out of the button that opened it:
 * 0 shut, 1 open, its width and height each on Material's container spring
 * out and its exit spring back (see [MaterialMotion]). What is drawn from it
 * is in PopupReveal.kt.
 */
internal class PopupGrowth(shown: Boolean) {
    val width = Animatable(if (shown) 1f else 0f)
    val height = Animatable(if (shown) 1f else 0f)
    // Animatable clears velocity when its coroutine is cancelled. Keep the
    // last frame's sample so a reversal starts with the motion it interrupted.
    private val velocities = floatArrayOf(0f, 0f)

    /** Whether the last move was a close, so the rows fade as a closing popup's do. */
    var closing = false
        private set

    /** Whether the popup is anywhere but fully open: on its way, or shut. */
    fun moving(w: Float, h: Float): Boolean = w < 1f || h < 1f || width.isRunning || height.isRunning

    suspend fun open(animate: Boolean) {
        closing = false
        // Opening from nothing: the popup's first frame - composing, measuring
        // and drawing all of it - is the slow one, and a curve started on it
        // lost its first steps to that frame and jumped. It goes out unseen;
        // the curve starts on the frame after.
        val firstFrame = width.value == 0f && height.value == 0f
        // Opened again while closing: on from where it is, at the speed it has.
        move(1f, animate, POPUP_OPEN, if (midFlight()) Float.NaN else POPUP_OPEN_VELOCITY, firstFrame)
    }

    /** At rest and shut. */
    fun isShut(): Boolean = width.value == 0f && height.value == 0f && !width.isRunning && !height.isRunning

    /** Neither shut nor fully open: a move was caught on its way. */
    private fun midFlight(): Boolean =
        width.isRunning || height.isRunning || width.value in 0.001f..0.999f || height.value in 0.001f..0.999f

    suspend fun close(animate: Boolean) {
        closing = true
        // Already shut: nothing to move. Every note card's menu is composed
        // shut, and each one ran a whole close from 0 to 0 as its card
        // scrolled in, frames counted as chrome motion for nothing.
        if (isShut()) return
        // Caught while still opening, it turns round at the speed it has.
        move(0f, animate, POPUP_CLOSE, if (midFlight()) Float.NaN else 0f)
    }

    private suspend fun move(target: Float, animate: Boolean, spec: AnimationSpec<Float>, velocity: Float,
        firstFrame: Boolean = false,
    ) {
        if (!animate) {
            velocities.fill(0f)
            width.snapTo(target)
            height.snapTo(target)
            return
        }
        // The same curve on both, so the shape keeps its proportion on the way.
        // NaN velocity: each axis keeps the speed it is already moving at.
        val widthStart = if (velocity.isNaN()) velocities[0] else velocity
        val heightStart = if (velocity.isNaN()) velocities[1] else velocity
        ChromeMotion.during {
            // The note can keep its existing glass recording while the
            // popup is prepared, just as it does during the visible reveal.
            if (firstFrame) awaitFirstFrames()
            coroutineScope {
                launch {
                    width.animateTo(target, spec.paced(), initialVelocity = widthStart) { velocities[0] = this.velocity }
                    velocities[0] = 0f
                }
                launch {
                    height.animateTo(target, spec.paced(), initialVelocity = heightStart) { velocities[1] = this.velocity }
                    velocities[1] = 0f
                }
            }
        }
    }
}

/** A popup's growth, kept for as long as the popup is composed. */
@Composable
internal fun rememberPopupGrowth(shown: Boolean): PopupGrowth = remember { PopupGrowth(shown) }

/** Out of the button: Material's container spring, which lands without running past. */
internal val POPUP_OPEN: AnimationSpec<Float> = MaterialMotion.rawContainer()

/** Back into it: Material's exit spring. */
internal val POPUP_CLOSE: AnimationSpec<Float> = MaterialMotion.rawExit()

/**
 * The progress per second an opening starts with. From rest a spring's first
 * frames hardly move, and a popup that answered a tap two frames late and
 * then crept out read as a beat behind the finger. Under the spring's own
 * 14 radians a second, so it still lands without overshoot.
 */
internal const val POPUP_OPEN_VELOCITY = 3f

// Material's emphasized decelerate: arrives quickly and lands softly.
internal val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
