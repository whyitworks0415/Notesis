package com.notesis

import kotlin.math.abs
import kotlin.math.exp

/*
 * The arithmetic behind moving the page under the fingers, and the policies
 * that decide how much background work may follow it. Kept free of Android so
 * the numbers the canvas relies on can be tested on their own.
 */

/**
 * One pinch sample applied to a viewport held as [state] = scale, translate x,
 * translate y (document to screen, no rotation or skew): scaled by [factor]
 * about ([fromX], [fromY]), the centroid the fingers were at, then carried so
 * that the same document point lands at ([toX], [toY]), where they are now.
 *
 * Done as one step rather than a scale about the new centroid followed by a
 * move: that order left the point under the fingers drifting a little on
 * every moving pinch sample. Floats throughout, no rounding, and a delta of a
 * hundredth of a pixel moves the page a hundredth of a pixel.
 */
internal fun pinchStep(state: FloatArray, factor: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
    state[0] *= factor
    state[1] = toX + (state[1] - fromX) * factor
    state[2] = toY + (state[2] - fromY) * factor
}

/**
 * Whether a hand lifting off the page may fling it: not after three fingers,
 * which never move the page, and not after a pinch that changed the zoom
 * ([startScale] to [endScale]). By the last lift the scale detector has
 * already ended, so its own check let the last finger's speed through - and
 * a pinch closing to zoom out slid the page off sideways as the hand let go.
 */
internal fun flingsOnLift(maxPointers: Int, startScale: Float, endScale: Float): Boolean {
    if (maxPointers >= 3) return false
    if (!(startScale > 0f) || !endScale.isFinite()) return true
    return abs(endScale / startScale - 1f) <= PINCH_NO_FLING
}

/** A hundredth of the zoom: less than this is two fingers panning, not a pinch. */
private const val PINCH_NO_FLING = 0.01f

/** The longest step a fling takes in one frame; a stall past this is not made up in one jump. */
internal const val MAX_FLING_STEP_S = 0.05f

/** Seconds between two frame times, clamped so a long frame does not throw the page. */
internal fun flingDt(frameNanos: Long, lastNanos: Long): Float =
    ((frameNanos - lastNanos) / 1e9f).coerceIn(0f, MAX_FLING_STEP_S)

/**
 * A fling frame's step in seconds: [flingDt] from the last frame, or, on the
 * first frame ([lastNanos] 0), one whole frame of a [hz] screen. The first
 * was once timed from the lift, read after the vsync the frame is stamped
 * with, so it came out nothing: the page stood still for a frame just as
 * the finger let go, a hitch at the start of every fling on 60 Hz.
 */
internal fun flingStepSeconds(frameNanos: Long, lastNanos: Long, hz: Float): Float =
    if (lastNanos == 0L) (1f / hz.coerceAtLeast(20f)).coerceAtMost(MAX_FLING_STEP_S) else flingDt(frameNanos, lastNanos)

/**
 * How far a fling going at [velocity] travels in [dt] seconds under
 * exponential [friction]: the exact integral of v·e^(-friction·t), so the
 * glide covers the same distance at 60Hz, at 120Hz, and across a dropped
 * frame. Stepping v·dt and then decaying travelled a few percent further at
 * 60Hz than at 120Hz.
 */
internal fun flingDistance(velocity: Float, dt: Float, friction: Float): Float =
    if (friction <= 0f) velocity * dt else velocity * (1f - exp(-friction * dt)) / friction

internal fun flingVelocityAfter(velocity: Float, dt: Float, friction: Float): Float =
    velocity * exp(-friction * dt)

/**
 * Whether one axis of a fling has run into the document's edge: the clamp
 * kept most of the step from happening. That axis's speed is then spent;
 * left in, it kept the fling alive pressing on the edge for frames on end.
 */
internal fun flingAxisBlocked(intended: Float, actual: Float): Boolean =
    abs(intended) > FLING_BLOCK_MIN_PX && abs(actual) < abs(intended) * 0.5f

private const val FLING_BLOCK_MIN_PX = 0.5f

/**
 * Pages read ahead of the screen once it stops: [ahead] in the direction the
 * last move went, [behind] the other way. Empty is none.
 */
internal data class PrefetchSpan(val ahead: Int, val behind: Int) {
    val isEmpty: Boolean get() = ahead <= 0 && behind <= 0
    val radius: Int get() = maxOf(ahead, behind)

    companion object {
        val NONE = PrefetchSpan(0, 0)
        /** A full halo, a little longer in the direction of travel. */
        val FULL = PrefetchSpan(3, 2)
        /** Battery saver: the next page each way and nothing further. */
        val SAVING = PrefetchSpan(1, 1)
    }
}

/**
 * How far round the screen to read pages in, or null for "not now - ask again
 * when the hands are off". Heat skips it outright: the pages come in when the
 * screen reaches them, as they would without a prefetch.
 */
internal fun prefetchSpan(state: Interaction, powerSave: Boolean, thermalStatus: Int): PrefetchSpan? = when {
    thermalStatus >= THERMAL_MODERATE -> PrefetchSpan.NONE
    state == Interaction.WRITING || state == Interaction.MOVING -> null
    powerSave -> PrefetchSpan.SAVING
    else -> PrefetchSpan.FULL
}

/**
 * PDF pages around the current one to queue while the page is still moving:
 * none while it is going fast or the device is hot (each would be passed
 * before it lands), else the neighbours.
 */
internal fun movingPdfRadius(fast: Boolean, thermalStatus: Int): Int =
    if (fast || thermalStatus >= THERMAL_MODERATE) 0 else 1

/**
 * Page indices for a [span] round [center], nearest first, alternating with
 * the direction of travel ([direction] = 1 forward, -1 back) leading.
 */
internal fun nearbyPages(center: Int, pageCount: Int, span: PrefetchSpan, direction: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val at = center.coerceIn(0, pageCount - 1)
    val step = if (direction < 0) -1 else 1
    val result = ArrayList<Int>(1 + span.ahead.coerceAtLeast(0) + span.behind.coerceAtLeast(0))
    result += at
    for (distance in 1..span.radius) {
        val ahead = at + distance * step
        val behind = at - distance * step
        if (distance <= span.ahead && ahead in 0 until pageCount) result += ahead
        if (distance <= span.behind && behind in 0 until pageCount) result += behind
    }
    return result
}

/**
 * The share of one frame the ink tile cache may spend recording tiles, in
 * nanoseconds. Past it, tiles not yet recorded wait for the next frame and
 * the picture from before the change stands in for them; one tile always
 * goes ahead, so the backlog always drains. Smallest while a pen writes,
 * whose input shares the UI thread; largest once nothing moves.
 */
internal fun tileRecordBudgetNanos(frameMs: Float, state: Interaction): Long {
    val share = when (state) {
        Interaction.WRITING -> 0.15f
        Interaction.MOVING -> 0.25f
        Interaction.SETTLING, Interaction.IDLE -> 0.35f
    }
    return (frameMs.coerceIn(4f, 50f) * share * 1_000_000f).toLong()
}

/**
 * Threads for rebuilding one page's stroke meshes as it is read in, out of a
 * pool of [threads]. [budget] is what [InteractionGovernor.budget] allows
 * background work right now; a page the screen is waiting on ([onScreen])
 * keeps at least [floor] of them even while the page moves or the pen writes,
 * since it is the one thing on screen still missing.
 */
internal fun inkBuildWidth(threads: Int, budget: Int, onScreen: Boolean, floor: Int): Int {
    val all = threads.coerceAtLeast(1)
    val allowed = budget.coerceIn(1, all)
    return if (onScreen) maxOf(allowed, floor.coerceIn(1, all)) else allowed
}
