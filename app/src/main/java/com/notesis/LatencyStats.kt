package com.notesis

/**
 * Rolling window of motion-to-photon samples reported by ink itself. Written
 * from the ink render thread, read from the UI thread, so every access is
 * inside the lock.
 *
 * This is the instrument the whole project is judged by - keep it wired up.
 */
class LatencyStats(private val capacity: Int = 512) {
    private val nanos = LongArray(capacity)
    private var count = 0
    private var next = 0
    private var samples = 0
    private var samplesSince = System.nanoTime()
    private var lastRateHz = 0.0

    /** How long a display frame actually lasts, measured, not assumed. */
    private val frames = Ring(240)

    /** How far ahead of the newest real sample the prediction reaches. */
    private val leads = Ring(240)

    @Synchronized
    fun add(latencyNanos: Long) {
        if (latencyNanos <= 0) return
        nanos[next] = latencyNanos
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized
    fun addSamples(n: Int) {
        samples += n
        val elapsed = System.nanoTime() - samplesSince
        if (elapsed > 500_000_000L) {
            lastRateHz = samples * 1e9 / elapsed
            samples = 0
            samplesSince = System.nanoTime()
        }
    }

    @Synchronized
    fun addFrame(deltaNanos: Long) {
        if (deltaNanos > 0) frames.add(deltaNanos / 1e6)
    }

    @Synchronized
    fun addPredictionLead(millis: Double) {
        leads.add(millis)
    }

    @Synchronized
    fun render(strokeCount: Int): String {
        if (count == 0) return "펜으로 그리면 측정됩니다"
        val sorted = nanos.copyOf(count).apply { sort() }
        val median = sorted[count / 2] / 1e6
        val p95 = sorted[(count * 95 / 100).coerceAtMost(count - 1)] / 1e6
        val worst = sorted[count - 1] / 1e6
        // The prediction line is the one to read when the wet tip looks
        // unsteady: a lead that swings is a tip that swings with it.
        val prediction = if (leads.isEmpty()) {
            "예측 -"
        } else {
            "예측 %.1fms (%.1f~%.1f)".format(leads.median(), leads.min(), leads.max())
        }
        val frame = if (frames.isEmpty()) {
            "프레임 -"
        } else {
            "프레임 %.1fms (%.0fHz)".format(frames.median(), 1000.0 / frames.median())
        }
        return ("지연 median %.1fms  p95 %.1fms  max %.1fms" +
            "\n입력 %.0fHz   획 %d개   n=%d" +
            "\n%s   %s")
            .format(median, p95, worst, lastRateHz, strokeCount, count, frame, prediction)
    }
}

/** A fixed-size window of doubles. Not thread-safe; its owner holds the lock. */
private class Ring(private val capacity: Int) {
    private val values = DoubleArray(capacity)
    private var count = 0
    private var next = 0

    fun add(value: Double) {
        values[next] = value
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    fun isEmpty() = count == 0

    fun median(): Double = values.copyOf(count).apply { sort() }[count / 2]

    fun min(): Double = values.copyOf(count).min()

    fun max(): Double = values.copyOf(count).max()
}
