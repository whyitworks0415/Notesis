package com.notesis

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The end of a list as Mornye has it, and iOS: pulled past its end, the list
 * follows the finger less and less (see [rubberBand]) and springs back when
 * let go; a fling that reaches the end runs on a little past it and comes
 * back. Android's stretch stays where motion is off.
 *
 * A parent of the scrollable in the nested scroll chain, so what it takes is
 * only what the list could not use: the list scrolls as it always did, and the
 * stretch, which comes after its parents, is left nothing to stretch with.
 */
@Stable
internal class EdgeBounce(private val vertical: Boolean, private val maxThrowPx: Float) : NestedScrollConnection {
    /** How far the content is drawn past its end, in px; positive is down or towards the end edge. */
    var shown by mutableFloatStateOf(0f)
        private set

    /** The list's own length along its axis, which the pull is measured against. */
    var extent = 0f

    /** Whether the end is pulled or still coming back: the page behind is held for it as for a scroll. */
    val active: Boolean get() = shown != 0f || settling

    // The finger's own travel past the end; [shown] is what the rubber band makes of it.
    private var pull = 0f
    private var settling = false

    private fun along(o: Offset) = if (vertical) o.y else o.x
    private fun along(v: Velocity) = if (vertical) v.y else v.x
    private fun offsetOf(d: Float) = if (vertical) Offset(0f, d) else Offset(d, 0f)

    private fun setPull(raw: Float) {
        pull = raw
        shown = edgeStretch(raw, extent)
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        // Caught on its way back: the finger takes it from where it is.
        if (settling) pull = edgePull(shown, extent)
        val d = along(available)
        if (pull == 0f || d == 0f || sign(d) == sign(pull)) return Offset.Zero
        // Back towards the content: the stretch gives way first, and only
        // what is left over scrolls the list.
        val next = pull + d
        return if (sign(next) != sign(pull)) {
            val used = -pull
            setPull(0f)
            offsetOf(used)
        } else {
            setPull(next)
            offsetOf(d)
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val d = along(available)
        if (d == 0f) return Offset.Zero
        setPull(pull + d)
        return offsetOf(d)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (shown == 0f) return Velocity.Zero
        // Let go while pulled: back to the end, with the finger's speed.
        settle(along(available))
        return available
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val v = along(available)
        if (shown != 0f) settle(0f)
        // A fling that dies out mid-list arrives here too, with the little
        // speed it had left; only one cut off by the end still has real speed.
        if (abs(v) < EDGE_MIN_THROW_VELOCITY) return Velocity.Zero
        // A fling that met the end: what speed it had left carries it past.
        settle(v.coerceIn(-maxThrowPx, maxThrowPx))
        return available
    }

    private suspend fun settle(velocity: Float) {
        settling = true
        try {
            animate(shown, 0f, velocity, spring<Float>(dampingRatio = 1f, stiffness = EDGE_BOUNCE_STIFFNESS).paced()) { value, _ ->
                shown = value
            }
        } finally {
            settling = false
            pull = edgePull(shown, extent)
        }
    }
}

/** The spring an end comes back on: critically damped, about a third of a second, as iOS's does. */
internal const val EDGE_BOUNCE_STIFFNESS = 220f

/** The least speed, px a second, a fling must still have at the end to carry past it. */
internal const val EDGE_MIN_THROW_VELOCITY = 400f

/** How far a fling may carry the content past its end. */
private val EDGE_THROW = 72.dp

/** How far [pull] of finger travel past the end of a list [extent] long is drawn. */
internal fun edgeStretch(pull: Float, extent: Float): Float =
    if (pull == 0f || extent <= 0f) 0f else sign(pull) * rubberBand(abs(pull), extent)

/** The finger travel that draws the content [shown] past its end: [edgeStretch] backwards. */
internal fun edgePull(shown: Float, extent: Float): Float {
    if (shown == 0f || extent <= 0f) return 0f
    val y = abs(shown).coerceAtMost(extent * 0.999f)
    return sign(shown) * (extent / RUBBER_BAND_C) * (y / (extent - y))
}

/** [rubberBand]'s default stiffness, iOS's. */
private const val RUBBER_BAND_C = 0.55f

/**
 * The speed a fling carries past the end at most, for a throw of [throwPx]:
 * a critically damped spring from rest at the end with speed v reaches
 * v / (ω e) before it comes back.
 */
internal fun edgeMaxVelocity(throwPx: Float, stiffness: Float = EDGE_BOUNCE_STIFFNESS): Float =
    throwPx * sqrt(stiffness) * E.toFloat()

/** An [EdgeBounce] for one list, or null where motion is off and the system's stretch stays. */
@Composable
internal fun rememberEdgeBounce(vertical: Boolean = true): EdgeBounce? {
    val moves = rememberLiquidGlassEffectsAllowed() && !LocalSkinSettings.current.highContrast
    val throwPx = with(LocalDensity.current) { EDGE_THROW.toPx() }
    return remember(moves, vertical, throwPx) { if (moves) EdgeBounce(vertical, edgeMaxVelocity(throwPx)) else null }
}

/** Put before the scrollable it belongs to: draws it [EdgeBounce.shown] past its end. */
internal fun Modifier.edgeBounce(bounce: EdgeBounce?, vertical: Boolean = true): Modifier =
    if (bounce == null) this else this
        .onSizeChanged { bounce.extent = (if (vertical) it.height else it.width).toFloat() }
        .graphicsLayer { if (vertical) translationY = bounce.shown else translationX = bounce.shown }
        .nestedScroll(bounce)
