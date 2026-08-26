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
    fun render(strokeCount: Int): String {
        if (count == 0) return "펜으로 그리면 측정됩니다"
        val sorted = nanos.copyOf(count).apply { sort() }
        val median = sorted[count / 2] / 1e6
        val p95 = sorted[(count * 95 / 100).coerceAtMost(count - 1)] / 1e6
        val worst = sorted[count - 1] / 1e6
        return ("지연 median %.1fms  p95 %.1fms  max %.1fms" +
            "\n입력 %.0fHz   획 %d개   n=%d")
            .format(median, p95, worst, lastRateHz, strokeCount, count)
    }
}
