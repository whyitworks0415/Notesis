package com.notesis

import kotlin.math.hypot

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

    fun reset(x: Float, y: Float, timeMillis: Long) {
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

        if (distance < MIN_VECTOR_DISTANCE_PX) {
            speedPxPerMs = 0f
            lastX = x
            lastY = y
            lastTimeMillis = maxOf(lastTimeMillis, timeMillis)
            return 0
        }

        speedPxPerMs = distance / dt
        val cosine = if (hasVector) {
            val previousDistance = hypot(lastDx, lastDy)
            if (previousDistance >= MIN_VECTOR_DISTANCE_PX) {
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
        if (speedPxPerMs < MIN_PREDICTION_SPEED_PX_PER_MS) return 0

        val allowedMaximum = sanitizePredictionLead(maximumLeadMs)
        return when {
            speedPxPerMs < MEDIUM_SPEED_PX_PER_MS -> minOf(4, allowedMaximum)
            speedPxPerMs < FAST_SPEED_PX_PER_MS -> minOf(6, allowedMaximum)
            else -> allowedMaximum
        }
    }

    fun shouldShowSoftHead(leadMs: Int): Boolean =
        leadMs >= 6 && speedPxPerMs >= SOFT_HEAD_SPEED_PX_PER_MS

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
    }
}

/** Fixed comparison points exposed by the pen settings. */
internal fun sanitizePredictionLead(value: Int): Int = when (value) {
    4, 6, 9 -> value
    else -> 9
}

/**
 * Automatic mode keeps a shorter head on high-refresh displays. The fixed
 * 4/6/9ms modes bypass this choice so measurements are directly comparable.
 */
internal fun predictionLeadForRefresh(configuredMs: Int, refreshRateHz: Float): Int {
    if (configuredMs == 4 || configuredMs == 6 || configuredMs == 9) return configuredMs
    return if (refreshRateHz >= 90f) 6 else 9
}
