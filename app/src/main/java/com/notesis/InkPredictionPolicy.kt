package com.notesis

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Decides whether the platform predictor may draw, and how far ahead it may draw.
 *
 * This is deliberately independent from stroke stabilization. Prediction must
 * not start on the first MOVE just because stabilization is set to zero, and a
 * corner or a slow correction must turn prediction down for both raw and
 * stabilized ink.
 */
internal class InkPredictionPolicy {
    private var lastX = 0f
    private var lastY = 0f
    private var lastTimeMillis = 0L
    private var lastDx = 0f
    private var lastDy = 0f
    private var hasVector = false
    private var stableVectors = 0

    var speedPxPerMs: Float = 0f
        private set

    /**
     * The panel's pixels per millimetre. The thresholds below were tuned on a
     * 287dpi tablet; on a denser panel the same hand speed is more pixels per
     * millisecond, so they scale with it and a stroke is judged by how fast
     * the pen actually moves, whatever the screen.
     */
    var pxPerMm: Float = REFERENCE_PX_PER_MM
        set(value) {
            field = if (value.isFinite() && value > 0f) value else REFERENCE_PX_PER_MM
            densityScale = field / REFERENCE_PX_PER_MM
        }
    private var densityScale = 1f

    // The last few segments, so the speed that picks the horizon is the
    // stroke's, not one sample's: a 240Hz digitizer's single intervals jitter
    // enough to flip between horizons sample to sample.
    private val segmentDistance = FloatArray(SPEED_WINDOW)
    private val segmentTime = FloatArray(SPEED_WINDOW)
    private var segments = 0
    private var segmentAt = 0

    fun reset(x: Float, y: Float, timeMillis: Long) {
        segments = 0
        segmentAt = 0
        lastX = x
        lastY = y
        lastTimeMillis = timeMillis
        lastDx = 0f
        lastDy = 0f
        hasVector = false
        stableVectors = 0
        speedPxPerMs = 0f
    }

    /**
     * Adds one real (never predicted) input sample and returns the permitted
     * prediction horizon. A zero result means that prediction is suppressed.
     */
    fun add(x: Float, y: Float, timeMillis: Long, maximumLeadMs: Int): Int {
        if (!x.isFinite() || !y.isFinite()) return 0
        val dx = x - lastX
        val dy = y - lastY
        val distance = hypot(dx, dy)
        val dt = (timeMillis - lastTimeMillis).coerceIn(1L, MAX_SAMPLE_DT_MS).toFloat()

        if (distance < MIN_VECTOR_DISTANCE_PX * densityScale) {
            speedPxPerMs = 0f
            lastX = x
            lastY = y
            lastTimeMillis = maxOf(lastTimeMillis, timeMillis)
            return 0
        }

        val instant = distance / dt
        segmentDistance[segmentAt] = distance
        segmentTime[segmentAt] = dt
        segmentAt = (segmentAt + 1) % SPEED_WINDOW
        if (segments < SPEED_WINDOW) segments++
        var totalDistance = 0f
        var totalTime = 0f
        for (i in 0 until segments) {
            totalDistance += segmentDistance[i]
            totalTime += segmentTime[i]
        }
        speedPxPerMs = if (totalTime > 0f) totalDistance / totalTime else instant
        val cosine = if (hasVector) {
            val previousDistance = hypot(lastDx, lastDy)
            if (previousDistance >= MIN_VECTOR_DISTANCE_PX * densityScale) {
                ((dx * lastDx + dy * lastDy) / (distance * previousDistance))
                    .coerceIn(-1f, 1f)
            } else 1f
        } else 1f

        stableVectors = when {
            !hasVector -> 1
            cosine < CORNER_COSINE -> 0
            cosine >= STABLE_DIRECTION_COSINE -> stableVectors + 1
            else -> maxOf(0, stableVectors - 1)
        }

        lastDx = dx
        lastDy = dy
        hasVector = true
        lastX = x
        lastY = y
        lastTimeMillis = maxOf(lastTimeMillis, timeMillis)

        if (stableVectors < MIN_STABLE_VECTORS || cosine < CORNER_COSINE) return 0
        val speed = speedPxPerMs / densityScale
        if (speed < MIN_PREDICTION_SPEED_PX_PER_MS) return 0

        val allowedMaximum = sanitizePredictionLead(maximumLeadMs)
        // A pen braking hard is about to stop or turn, and a prediction from
        // its speed a moment ago runs past where it stops: the tail that
        // flicks out at the end of a word and snaps back.
        val braking = instant / speedPxPerMs
        if (braking < STOPPING_RATIO) return 0
        if (braking < BRAKING_RATIO) return minOf(4, allowedMaximum)
        // A long horizon is only granted where the display needs it; medium
        // speed then gets a share of it as well.
        val medium = if (allowedMaximum >= VSYNC_BOUND_LEAD_MS) 8 else 6
        return when {
            speed < MEDIUM_SPEED_PX_PER_MS -> minOf(4, allowedMaximum)
            speed < FAST_SPEED_PX_PER_MS -> minOf(medium, allowedMaximum)
            else -> allowedMaximum
        }
    }

    fun shouldShowSoftHead(leadMs: Int): Boolean =
        leadMs >= 6 && speedPxPerMs / densityScale >= SOFT_HEAD_SPEED_PX_PER_MS

    private companion object {
        const val MIN_VECTOR_DISTANCE_PX = 0.45f
        const val MIN_PREDICTION_SPEED_PX_PER_MS = 0.12f
        const val MEDIUM_SPEED_PX_PER_MS = 0.35f
        const val FAST_SPEED_PX_PER_MS = 0.80f
        const val SOFT_HEAD_SPEED_PX_PER_MS = 0.95f
        const val CORNER_COSINE = 0.57f
        const val STABLE_DIRECTION_COSINE = 0.82f
        const val MIN_STABLE_VECTORS = 3
        const val MAX_SAMPLE_DT_MS = 32L
        /** 287dpi, the tablet the speeds above were tuned on. */
        const val REFERENCE_PX_PER_MM = 287f / 25.4f
        /** Segments averaged for the stroke's speed: about 80ms at 240Hz. */
        const val SPEED_WINDOW = 20
        /** This sample at under 35% of the stroke's speed: the pen is stopping. */
        const val STOPPING_RATIO = 0.35f
        /** Under 60%: it is braking, so only a short head. */
        const val BRAKING_RATIO = 0.6f
    }
}

/**
 * How much of the prediction a stabilized stroke keeps. The stabilizer pulls
 * the line behind the pen on purpose; a full-length prediction from it
 * reaches back out to where the pen is and undoes that, wobbling as it goes.
 * The stronger the stabilizer, the shorter the head, down to 40% at full.
 */
internal fun stabilizedLeadMs(leadMs: Int, stabilizer: Int): Int {
    if (leadMs <= 0) return 0
    val strength = stabilizer.coerceIn(0, 100) / 100f
    return (leadMs * (1f - 0.6f * strength)).roundToInt().coerceAtLeast(1)
}

/**
 * The horizon for ink shown by a regular frame (the compatible path), from
 * where in the frame the sample landed. Such ink is drawn at the next vsync
 * and shown at the one after, so a sample just after a vsync waits almost two
 * periods to be seen and one just before it barely more than one; a fixed
 * horizon was too short for the first and too long for the second. A share of
 * that wait is predicted - not all of it, since a prediction that far ahead
 * overshoots every turn - within the fixed horizon's own limit.
 */
internal fun vsyncAlignedLeadMs(
    maximumLeadMs: Int,
    eventNanos: Long,
    lastVsyncNanos: Long,
    refreshRateHz: Float,
): Int {
    if (maximumLeadMs <= 0 || lastVsyncNanos <= 0L || !(refreshRateHz > 0f)) return maximumLeadMs
    val period = (1_000_000_000.0 / refreshRateHz).toLong()
    val since = eventNanos - lastVsyncNanos
    // Stale or from the future: no frame grid to align to.
    if (period <= 0L || since < 0L || since > period * STALE_VSYNC_PERIODS) return maximumLeadMs
    val untilNext = period - since % period
    val shownAfterMs = (untilNext + period) / 1_000_000.0
    return (shownAfterMs * VSYNC_LEAD_SHARE).roundToInt().coerceIn(MIN_ALIGNED_LEAD_MS, maximumLeadMs)
}

private const val VSYNC_LEAD_SHARE = 0.45
private const val MIN_ALIGNED_LEAD_MS = 6
private const val STALE_VSYNC_PERIODS = 6

/** Automatic mode's horizon for ink that waits for vsync on a 60Hz panel. */
internal const val VSYNC_BOUND_LEAD_MS = 12

/** Any fixed lead the pen settings offer, 1 to 12ms; anything else falls back to 9. */
internal fun sanitizePredictionLead(value: Int): Int =
    if (value in 1..VSYNC_BOUND_LEAD_MS) value else 9

/**
 * Automatic mode keeps a shorter head on high-refresh displays. A fixed
 * 1-12ms setting bypasses this choice, so what was asked for is what is used.
 *
 * [vsyncBoundInk] is wet ink that is shown by a regular frame rather than a
 * front buffer (the compatible path). At 60Hz that frame is presented a whole
 * 16.7ms period after it was drawn, so the pen tip runs further ahead of the
 * ink than on a front buffer; a slightly longer prediction closes most of
 * that gap. Corners and slow strokes still get none or a short one.
 */
internal fun predictionLeadForRefresh(
    configuredMs: Int,
    refreshRateHz: Float,
    vsyncBoundInk: Boolean = false,
): Int {
    if (configuredMs in 1..VSYNC_BOUND_LEAD_MS) return configuredMs
    if (refreshRateHz >= 90f) return 6
    return if (vsyncBoundInk) VSYNC_BOUND_LEAD_MS else 9
}
