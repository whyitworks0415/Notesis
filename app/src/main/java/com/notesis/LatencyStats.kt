package com.notesis

/** Metrics collected only while the on-screen diagnostics panel is enabled. */
internal enum class PerformanceMetric {
    WET_INK,
    PEN_FINALIZE,
    MESH_REFINE,
    PDF_RENDER,
    CANVAS_DRAW,
    DENSE_DRAW,
}

internal data class Percentiles(
    val count: Int,
    val p50: Double,
    val p95: Double,
    val p99: Double,
    val max: Double,
)

/**
 * Allocation-free rolling samples for the diagnostic HUD and Perfetto sessions.
 *
 * Writers include the UI, ink-render, PDF, and refine threads. All mutable state
 * therefore stays behind this object's monitor. The release hot path pays one
 * volatile boolean read only when diagnostics are hidden.
 */
class LatencyStats(private val capacity: Int = 512) {
    @Volatile
    var enabled: Boolean = false
        private set

    private val metrics = Array(PerformanceMetric.entries.size) { LongRing(capacity) }
    private val frames = LongRing(240)
    private val predictionLeads = LongRing(240)
    private val allocationRates = LongRing(120)

    private var inputSamples = 0
    private var inputSamplesSince = System.nanoTime()
    private var lastInputRateHz = 0.0
    private var lastRefinedStrokes = 0
    private var lastPdfPixels = 0L
    private var lastVisibleStrokes = 0

    private var previousAllocatedBytes = -1L
    private var previousGcCount = -1L
    private var previousRuntimeSampleNanos = 0L
    private var gcEvents = 0L

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        inputSamples = 0
        inputSamplesSince = System.nanoTime()
        previousAllocatedBytes = -1L
        previousGcCount = -1L
        previousRuntimeSampleNanos = 0L
        if (value) resetLocked()
    }

    /** Stylus event detection to the wet-ink draw/presentation boundary. */
    fun add(latencyNanos: Long) = addNanos(PerformanceMetric.WET_INK, latencyNanos)

    fun addPenFinalize(latencyNanos: Long) =
        addNanos(PerformanceMetric.PEN_FINALIZE, latencyNanos)

    @Synchronized
    fun addRefine(latencyNanos: Long, strokeCount: Int) {
        if (!enabled || latencyNanos <= 0L) return
        metrics[PerformanceMetric.MESH_REFINE.ordinal].add(latencyNanos)
        lastRefinedStrokes = strokeCount
    }

    @Synchronized
    fun addPdfRender(latencyNanos: Long, pixelCount: Long) {
        if (!enabled || latencyNanos <= 0L) return
        metrics[PerformanceMetric.PDF_RENDER.ordinal].add(latencyNanos)
        lastPdfPixels = pixelCount
    }

    @Synchronized
    fun addDraw(latencyNanos: Long, visibleStrokeCount: Int) {
        if (!enabled || latencyNanos <= 0L) return
        metrics[PerformanceMetric.CANVAS_DRAW.ordinal].add(latencyNanos)
        if (visibleStrokeCount >= DENSE_PAGE_STROKES) {
            metrics[PerformanceMetric.DENSE_DRAW.ordinal].add(latencyNanos)
        }
        lastVisibleStrokes = visibleStrokeCount
    }

    @Synchronized
    fun addSamples(count: Int) {
        if (!enabled || count <= 0) return
        inputSamples += count
        val now = System.nanoTime()
        val elapsed = now - inputSamplesSince
        if (elapsed > RATE_WINDOW_NANOS) {
            lastInputRateHz = inputSamples * 1e9 / elapsed
            inputSamples = 0
            inputSamplesSince = now
        }
    }

    @Synchronized
    fun addFrame(deltaNanos: Long) {
        if (enabled && deltaNanos > 0L) frames.add(deltaNanos)
    }

    /** The measured display period used to choose the prediction horizon. */
    @Synchronized
    fun framePeriodMs(): Double? = frames.snapshot()?.p50

    @Synchronized
    fun addPredictionLead(millis: Double) {
        if (enabled && millis >= 0.0) predictionLeads.add((millis * NANOS_PER_MS).toLong())
    }

    /**
     * Adds a low-frequency process-wide ART allocation/GC counter sample.
     * Sampling this at HUD refresh rate avoids perturbing the 120-240Hz path.
     */
    @Synchronized
    fun addRuntimeSample(totalAllocatedBytes: Long, totalGcCount: Long, nowNanos: Long) {
        if (!enabled || totalAllocatedBytes < 0L || totalGcCount < 0L) return
        val elapsed = nowNanos - previousRuntimeSampleNanos
        val allocated = totalAllocatedBytes - previousAllocatedBytes
        val collections = totalGcCount - previousGcCount
        if (previousRuntimeSampleNanos > 0L && elapsed > 0L && allocated >= 0L) {
            allocationRates.add((allocated.toDouble() * 1e9 / elapsed).toLong())
            if (collections > 0L) gcEvents += collections
        }
        previousAllocatedBytes = totalAllocatedBytes
        previousGcCount = totalGcCount
        previousRuntimeSampleNanos = nowNanos
    }

    @Synchronized
    internal fun snapshot(metric: PerformanceMetric): Percentiles? =
        metrics[metric.ordinal].snapshot()

    @Synchronized
    fun render(strokeCount: Int): String {
        val wet = metricLine("wet ink", PerformanceMetric.WET_INK)
        val finalize = metricLine("pen-up 확정", PerformanceMetric.PEN_FINALIZE)
        val refine = metricLine("mesh/refine", PerformanceMetric.MESH_REFINE,
            if (lastRefinedStrokes > 0) "최근 ${lastRefinedStrokes}획" else null)
        val pdf = metricLine("PDF render", PerformanceMetric.PDF_RENDER,
            if (lastPdfPixels > 0L) "최근 %.1fMP".format(lastPdfPixels / 1_000_000.0) else null)
        val draw = metricLine("page draw", PerformanceMetric.CANVAS_DRAW,
            "화면 ${lastVisibleStrokes}획")
        val dense = metricLine("dense draw(≥$DENSE_PAGE_STROKES)", PerformanceMetric.DENSE_DRAW)

        val frame = frames.snapshot()?.let {
            "frame p50 %.1fms p95 %.1fms p99 %.1fms".format(it.p50, it.p95, it.p99)
        } ?: "frame -"
        val lead = predictionLeads.snapshot()?.let {
            "prediction p50 %.1fms p95 %.1fms p99 %.1fms".format(it.p50, it.p95, it.p99)
        } ?: "prediction -"
        val allocation = allocationRates.snapshot(BYTES_PER_MIB)?.let {
            "allocation p50 %.1fMB/s p95 %.1fMB/s p99 %.1fMB/s   GC +%d".format(
                it.p50,
                it.p95,
                it.p99,
                gcEvents,
            )
        } ?: "allocation/GC -"

        return listOf(
            wet,
            finalize,
            refine,
            pdf,
            draw,
            dense,
            "input %.0fHz   total %d획".format(lastInputRateHz, strokeCount),
            "$frame   $lead",
            allocation,
        ).joinToString("\n")
    }

    @Synchronized
    private fun addNanos(metric: PerformanceMetric, latencyNanos: Long) {
        if (!enabled || latencyNanos <= 0L) return
        metrics[metric.ordinal].add(latencyNanos)
    }

    private fun metricLine(
        label: String,
        metric: PerformanceMetric,
        suffix: String? = null,
    ): String {
        val stats = metrics[metric.ordinal].snapshot()
            ?: return "$label -${suffix?.let { "   $it" }.orEmpty()}"
        return "$label p50 %.1fms p95 %.1fms p99 %.1fms (n=%d)%s".format(
            stats.p50,
            stats.p95,
            stats.p99,
            stats.count,
            suffix?.let { "   $it" }.orEmpty(),
        )
    }

    private fun resetLocked() {
        metrics.forEach(LongRing::clear)
        frames.clear()
        predictionLeads.clear()
        allocationRates.clear()
        lastInputRateHz = 0.0
        lastRefinedStrokes = 0
        lastPdfPixels = 0L
        lastVisibleStrokes = 0
        gcEvents = 0L
    }

    companion object {
        const val DENSE_PAGE_STROKES = 500
        private const val RATE_WINDOW_NANOS = 500_000_000L
        private const val NANOS_PER_MS = 1_000_000.0
        private const val BYTES_PER_MIB = 1024.0 * 1024.0
    }
}

/** Fixed storage: recording a sample never allocates. */
private class LongRing(private val capacity: Int) {
    private val values = LongArray(capacity)
    private var count = 0
    private var next = 0

    fun add(value: Long) {
        values[next] = value
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() {
        count = 0
        next = 0
    }

    fun snapshot(divisor: Double = 1_000_000.0): Percentiles? {
        if (count == 0) return null
        val sorted = values.copyOf(count).apply(LongArray::sort)
        fun percentile(percent: Int): Double =
            sorted[((count - 1) * percent / 100).coerceIn(0, count - 1)] / divisor
        return Percentiles(
            count = count,
            p50 = percentile(50),
            p95 = percentile(95),
            p99 = percentile(99),
            max = sorted[count - 1] / divisor,
        )
    }
}
