package com.notesis

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * The blur drawn along something moving at [vx], [vy] px a second, at the
 * strength set in Motion (see [motionBlurPx]) when [part] takes it, or none.
 * None at all - not a blur of nothing - whenever it would be under half a
 * pixel, on Android 11 and older and in Light mode: a layer with no effect is
 * drawn straight, and a layer with one is drawn offscreen first. One axis at
 * a time where only one moves, which is one pass, not two.
 */
internal fun motionBlurEffect(part: BlurPart, vx: Float, vy: Float, maxPx: Float): RenderEffect? {
    val (bx, by) = motionBlurRadii(part, vx, vy, maxPx) ?: return null
    return BlurEffect(bx, by, TileMode.Decal)
}

/** The two radii [motionBlurEffect] would blur by, or null for none. */
private fun motionBlurRadii(part: BlurPart, vx: Float, vy: Float, maxPx: Float): Pair<Float, Float>? {
    if (Build.VERSION.SDK_INT < 31 || !MotionPrefs.blurs(part) || InteractionGovernor.lightMode) return null
    val hz = MotionPrefs.refreshHz
    val strength = MotionPrefs.blurStrength
    val bx = motionBlurPx(vx, hz, maxPx, strength)
    val by = motionBlurPx(vy, hz, maxPx, strength)
    if (bx <= 0f && by <= 0f) return null
    return bx.coerceAtLeast(0.01f) to by.coerceAtLeast(0.01f)
}

/**
 * The same blur for a View (the note's page), kept between frames while its
 * radii round to the same half pixel, so a steady scroll makes one effect
 * rather than one a frame.
 */
@android.annotation.SuppressLint("NewApi") // Every path to an effect checks for Android 12 first.
internal class ViewMotionBlur {
    private var effect: android.graphics.RenderEffect? = null
    private var keyX = -1
    private var keyY = -1

    fun effectFor(part: BlurPart, vx: Float, vy: Float, maxPx: Float): android.graphics.RenderEffect? {
        val radii = motionBlurRadii(part, vx, vy, maxPx) ?: return null
        val kx = (radii.first * 2f).toInt()
        val ky = (radii.second * 2f).toInt()
        if (kx != keyX || ky != keyY || effect == null) {
            keyX = kx; keyY = ky
            if (Build.VERSION.SDK_INT >= 31) effect = android.graphics.RenderEffect.createBlurEffect(
                (kx / 2f).coerceAtLeast(0.01f), (ky / 2f).coerceAtLeast(0.01f), android.graphics.Shader.TileMode.CLAMP)
        }
        return effect
    }
}

/** How far a list's blur may run at most, whatever its speed. */
private val SCROLL_BLUR_MAX = 6.dp

/**
 * How fast a list is scrolling, read from what it scrolls by: a parent of the
 * scrollable in the nested scroll chain that takes nothing, only watches.
 */
@Stable
internal class ScrollSpeed : NestedScrollConnection {
    /** Px a second along the list, smoothed; 0 at rest. */
    var velocity by mutableFloatStateOf(0f)
        private set

    internal var lastEventNanos = 0L
        private set
    private var sampleStart = 0L
    private var travelled = 0f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val now = System.nanoTime()
        val moved = consumed.y
        if (now - lastEventNanos > STALE_NANOS) {
            // A new movement: measured from here, not from the last one.
            sampleStart = now
            travelled = 0f
        }
        lastEventNanos = now
        travelled += moved
        val dt = (now - sampleStart) / 1e9f
        // Input can come more often than frames; a speed is measured over
        // at least a frame or so, so two events close together are not a spike.
        if (dt >= SAMPLE_SECONDS) {
            velocity = speedSample(velocity, travelled / dt)
            sampleStart = now
            travelled = 0f
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        stop()
        return Velocity.Zero
    }

    /** At rest: nothing has moved it for a while, or the fling is over. */
    fun stop() {
        velocity = 0f
        travelled = 0f
    }

    internal companion object {
        const val SAMPLE_SECONDS = 0.008f
        const val STALE_NANOS = 60_000_000L
    }
}

/** The next smoothed speed from the last [smoothed] one and a new [sample]: half of each. */
internal fun speedSample(smoothed: Float, sample: Float): Float =
    if (!sample.isFinite()) smoothed else smoothed * 0.5f + sample * 0.5f

/** A [ScrollSpeed] that settles to rest by itself when a held finger stops moving the list. */
@Composable
internal fun rememberScrollSpeed(): ScrollSpeed {
    val speed = remember { ScrollSpeed() }
    LaunchedEffect(speed) {
        // Only while it moves: a frame loop that ran at rest would keep the
        // screen awake for nothing.
        snapshotFlow { speed.velocity != 0f }.collect { moving ->
            while (moving && speed.velocity != 0f) {
                withFrameNanos { }
                if (System.nanoTime() - speed.lastEventNanos > ScrollSpeed.STALE_NANOS) speed.stop()
            }
        }
    }
    return speed
}

/**
 * A trace of blur along the list while it scrolls fast (see
 * [motionBlurEffect]). Put before the scrollable it belongs to. Read in the
 * layer only, so a change of speed redraws nothing inside the list.
 */
internal fun Modifier.scrollBlur(speed: ScrollSpeed): Modifier = this
    .graphicsLayer { renderEffect = motionBlurEffect(BlurPart.LISTS, 0f, speed.velocity, SCROLL_BLUR_MAX.toPx()) }
    .nestedScroll(speed)
