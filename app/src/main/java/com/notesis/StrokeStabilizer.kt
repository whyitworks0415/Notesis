package com.notesis

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Perceptual strength mapping for both position and pressure filters.
 *
 * A linear percentage made 5-15% practically indistinguishable from raw input.
 * Zero remains an exact bypass; non-zero values get a small base response and
 * then grow smoothly so 5/10/20/30% are useful rather than decorative.
 */
internal fun stabilizationAmount(strength: Int): Float {
    val normalized = strength.coerceIn(0, 100) / 100f
    if (normalized == 0f) return 0f
    return (0.10f + 0.90f * normalized.toDouble().pow(0.72).toFloat())
        .coerceIn(0f, 1f)
}

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
        amount = stabilizationAmount(strength)
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
            val distanceResponse = (distance / INTENTIONAL_DISTANCE_PX).coerceIn(0f, 1f)
            // Slow, tiny motion is most likely digitizer noise. Fast writing
            // and a clearly intentional long step follow the pen closely so
            // smoothing does not turn into lag or shrink small handwriting.
            val adaptiveSmoothing = MAX_SMOOTHING *
                (1f - speedResponse * SPEED_RELEASE) *
                (1f - distanceResponse * DISTANCE_RELEASE)
            var alpha = 1f - amount * adaptiveSmoothing
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
        const val INTENTIONAL_DISTANCE_PX = 7f
        const val SPEED_RELEASE = 0.76f
        const val DISTANCE_RELEASE = 0.34f
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

/**
 * Pressure is filtered independently from position.
 *
 * Small pressure oscillations get a gentle low-pass filter while a deliberate
 * press/release follows quickly. Keeping this state separate prevents pressure
 * noise from changing the position alpha or corner classification.
 */
internal class AdaptivePressureStabilizer {
    var pressure: Float = 0f
        private set

    private var amount = 0f
    private var lastRaw = 0f
    private var lastTimeMillis = 0L
    private var initialized = false

    fun reset(strength: Int, rawPressure: Float, timeMillis: Long) {
        amount = stabilizationAmount(strength)
        pressure = sanitize(rawPressure, 0f)
        lastRaw = pressure
        lastTimeMillis = timeMillis
        initialized = true
    }

    fun add(rawPressure: Float, timeMillis: Long, finalSample: Boolean = false): Float {
        if (!initialized) {
            reset(0, rawPressure, timeMillis)
            return pressure
        }
        val raw = sanitize(rawPressure, lastRaw)
        if (amount == 0f) {
            pressure = raw
        } else {
            val dt = (timeMillis - lastTimeMillis).coerceIn(1L, MAX_SAMPLE_DT_MS).toFloat()
            val velocity = abs(raw - lastRaw) / dt
            val intent = (velocity / INTENTIONAL_PRESSURE_PER_MS).coerceIn(0f, 1f)
            var alpha = 1f - amount * MAX_PRESSURE_SMOOTHING * (1f - intent * INTENT_RELEASE)
            if (finalSample) alpha = maxOf(alpha, FINAL_PRESSURE_ALPHA)
            pressure += (raw - pressure) * alpha.coerceIn(MIN_PRESSURE_ALPHA, 1f)
        }
        lastRaw = raw
        lastTimeMillis = maxOf(lastTimeMillis, timeMillis)
        return pressure
    }

    private fun sanitize(value: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else fallback

    private companion object {
        const val MAX_PRESSURE_SMOOTHING = 0.58f
        const val MIN_PRESSURE_ALPHA = 0.28f
        const val FINAL_PRESSURE_ALPHA = 0.86f
        const val INTENTIONAL_PRESSURE_PER_MS = 0.035f
        const val INTENT_RELEASE = 0.82f
        const val MAX_SAMPLE_DT_MS = 32L
    }
}
