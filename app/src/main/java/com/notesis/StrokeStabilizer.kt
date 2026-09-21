package com.notesis

import kotlin.math.hypot

/**
 * Allocation-free streaming stabilizer for screen-space stylus samples.
 *
 * Screen space is intentional: digitizer jitter is a physical-pixel effect.
 * The AndroidX Ink transform still converts the stabilized event to page space.
 */
internal class AdaptiveStrokeStabilizer {
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set
    var predictionAllowed: Boolean = false
        private set
    var corner: Boolean = false
        private set

    private var amount = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastTimeMillis = 0L
    private var lastDx = 0f
    private var lastDy = 0f
    private var samples = 0
    private var stableDirections = 0

    fun reset(strength: Int, rawX: Float, rawY: Float, timeMillis: Long) {
        amount = strength.coerceIn(0, 100) / 100f
        x = rawX
        y = rawY
        lastRawX = rawX
        lastRawY = rawY
        lastTimeMillis = timeMillis
        lastDx = 0f
        lastDy = 0f
        samples = 1
        stableDirections = 0
        predictionAllowed = false
        corner = false
    }

    fun add(rawX: Float, rawY: Float, timeMillis: Long, finalSample: Boolean = false) {
        // Bad driver coordinates must never poison the rest of the gesture.
        if (!rawX.isFinite() || !rawY.isFinite()) {
            predictionAllowed = false
            corner = false
            return
        }
        if (samples == 0) {
            reset((amount * 100f).toInt(), rawX, rawY, timeMillis)
            return
        }

        val dx = rawX - lastRawX
        val dy = rawY - lastRawY
        val distance = hypot(dx, dy)
        val previousDistance = hypot(lastDx, lastDy)
        val cosine = if (distance >= MIN_DIRECTION_DISTANCE &&
            previousDistance >= MIN_DIRECTION_DISTANCE
        ) {
            ((dx * lastDx + dy * lastDy) / (distance * previousDistance)).coerceIn(-1f, 1f)
        } else 1f
        corner = distance >= MIN_DIRECTION_DISTANCE &&
            previousDistance >= MIN_DIRECTION_DISTANCE && cosine < CORNER_COSINE
        val startReverseSpike = samples < START_WINDOW_SAMPLES && cosine < START_REVERSE_COSINE &&
            maxOf(distance, previousDistance) > MIN_START_SPIKE_DISTANCE &&
            maxOf(distance, previousDistance) > minOf(distance, previousDistance) * 1.45f

        if (amount == 0f) {
            x = rawX
            y = rawY
        } else {
            val dt = (timeMillis - lastTimeMillis).coerceIn(1L, MAX_SAMPLE_DT_MS).toFloat()
            val speed = distance / dt
            val speedResponse = (speed / FAST_SPEED_PX_PER_MS).coerceIn(0f, 1f)
            // Slow, tiny motion is most likely digitizer noise. Fast writing
            // follows the pen closely so smoothing does not turn into lag.
            var alpha = 1f - amount * (MAX_SMOOTHING * (1f - speedResponse * 0.76f))
            if (corner) alpha = maxOf(alpha, CORNER_ALPHA)
            // A reversal in the first 4-8 samples is the classic contact spike.
            // The outgoing excursion was already damped; follow the returning
            // sample quickly so it cannot leave a hook behind.
            if (startReverseSpike) alpha = maxOf(alpha, START_RETURN_ALPHA)
            if (finalSample) alpha = maxOf(alpha, FINAL_ALPHA)
            x += (rawX - x) * alpha.coerceIn(MIN_ALPHA, 1f)
            y += (rawY - y) * alpha.coerceIn(MIN_ALPHA, 1f)
        }

        stableDirections = when {
            distance < MIN_DIRECTION_DISTANCE -> stableDirections
            corner || startReverseSpike -> 0
            cosine >= STABLE_DIRECTION_COSINE -> stableDirections + 1
            else -> 0
        }
        samples++
        predictionAllowed = samples >= MIN_PREDICTION_SAMPLES &&
            stableDirections >= MIN_STABLE_DIRECTIONS && !corner && !startReverseSpike
        if (distance >= MIN_DIRECTION_DISTANCE) {
            lastDx = dx
            lastDy = dy
        }
        lastRawX = rawX
        lastRawY = rawY
        lastTimeMillis = maxOf(lastTimeMillis, timeMillis)
    }

    fun shouldRejectLift(rawX: Float, rawY: Float): Boolean =
        unstableLift(rawX - lastRawX, rawY - lastRawY, lastDx, lastDy)

    private companion object {
        const val MAX_SMOOTHING = 0.72f
        const val MIN_ALPHA = 0.20f
        const val CORNER_ALPHA = 0.92f
        const val START_RETURN_ALPHA = 0.90f
        const val FINAL_ALPHA = 0.88f
        const val FAST_SPEED_PX_PER_MS = 1.25f
        const val MIN_DIRECTION_DISTANCE = 0.45f
        const val MIN_START_SPIKE_DISTANCE = 1.5f
        const val CORNER_COSINE = 0.57f
        const val START_REVERSE_COSINE = -0.20f
        const val STABLE_DIRECTION_COSINE = 0.82f
        const val START_WINDOW_SAMPLES = 8
        const val MIN_PREDICTION_SAMPLES = 4
        const val MIN_STABLE_DIRECTIONS = 2
        const val MAX_SAMPLE_DT_MS = 32L
    }
}
