package com.notesis

import java.util.Locale

/** Metrics collected only while the on-screen diagnostics panel is enabled. */
internal enum class PerformanceMetric {
    WET_INK,
    PEN_FINALIZE,
    MESH_REFINE,
    PDF_RENDER,
    CANVAS_DRAW,
    DENSE_DRAW,
    /** Pen down to the first wet-ink frame handed to the display (submission, not photons). */
    FIRST_INK,
    /** A pen MOVE event's timestamp to the end of its handling on the UI thread. */
    INPUT_HANDLED,
    /** A background page load or refine result being put into the UI, on the UI thread. */
    PAGE_APPLY,
    /** The autosave snapshot taken on the UI thread. */
    SAVE_SNAPSHOT,
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
    private var inkCacheTiles = 0
    private var inkCacheBytes = 0
    private var inkCacheRecorded = 0L
    private var inkCacheFrameRecorded = 0L

    /**
     * The display's refresh period, set by whoever shows the numbers. A frame
     * interval longer than [MISSED_FRAME_FACTOR] of it is a deadline the UI
     * thread missed.
     */
    @Volatile
    var refreshPeriodNanos: Long = DEFAULT_PERIOD_NANOS
        set(value) { if (value > 0L) field = value }

    private var framesCounted = 0L
    private var framesMissed = 0L

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
    internal fun addInkCache(stats: InkTileCache.Stats) {
        if (!enabled) return
        inkCacheTiles = stats.tiles
        inkCacheBytes = stats.layerBytes
        inkCacheFrameRecorded = (stats.recordedStrokes - inkCacheRecorded).coerceAtLeast(0L)
        inkCacheRecorded = stats.recordedStrokes
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
        if (!enabled || deltaNanos <= 0L) return
        frames.add(deltaNanos)
        framesCounted++
        if (isMissedFrame(deltaNanos, refreshPeriodNanos)) framesMissed++
    }

    /** Every number back to nothing, as when the readout is switched on. */
    @Synchronized
    fun reset() {
        inputSamples = 0
        inputSamplesSince = System.nanoTime()
        previousAllocatedBytes = -1L
        previousGcCount = -1L
        previousRuntimeSampleNanos = 0L
        resetLocked()
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

    /** Everything [render] shows besides the per-metric timings, for a screen that lays it out itself. */
    @Synchronized
    internal fun extras(): LatencyExtras = LatencyExtras(
        frame = frames.snapshot(),
        prediction = predictionLeads.snapshot(),
        allocationMiBPerSecond = allocationRates.snapshot(BYTES_PER_MIB),
        gcEvents = gcEvents,
        inputRateHz = lastInputRateHz,
        inkCacheTiles = inkCacheTiles,
        inkCacheMiB = inkCacheBytes / BYTES_PER_MIB,
        inkCacheFrameRecorded = inkCacheFrameRecorded,
        visibleStrokes = lastVisibleStrokes,
        framesCounted = framesCounted,
        framesMissed = framesMissed,
        refreshPeriodMs = refreshPeriodNanos / NANOS_PER_MS,
    )

    /**
     * The overlay's timings. Wet and first ink are timed to the frame being
     * handed to the display, not to light leaving it, and are labelled so.
     */
    @Synchronized
    fun render(strokeCount: Int): String {
        val frame = frames.snapshot()?.let {
            "frames p50 %.1fms p95 %.1fms p99 %.1fms".format(it.p50, it.p95, it.p99)
        } ?: "frames -"
        val missed = "missed %d/%d (%s) over %.1fms".format(
            framesMissed, framesCounted, missedPercentText(framesMissed, framesCounted),
            refreshPeriodNanos * MISSED_FRAME_FACTOR / NANOS_PER_MS,
        )
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
            metricLine("first ink (submitted)", PerformanceMetric.FIRST_INK),
            metricLine("input handled", PerformanceMetric.INPUT_HANDLED),
            metricLine("wet ink (submitted)", PerformanceMetric.WET_INK),
            metricLine("pen-up finalize", PerformanceMetric.PEN_FINALIZE),
            "$frame   $missed",
            metricLine("page apply", PerformanceMetric.PAGE_APPLY),
            metricLine("save snapshot", PerformanceMetric.SAVE_SNAPSHOT),
            metricLine("mesh/refine", PerformanceMetric.MESH_REFINE,
                if (lastRefinedStrokes > 0) "last ${lastRefinedStrokes} strokes" else null),
            metricLine("PDF render", PerformanceMetric.PDF_RENDER,
                if (lastPdfPixels > 0L) "last %.1fMP".format(lastPdfPixels / 1_000_000.0) else null),
            metricLine("page draw", PerformanceMetric.CANVAS_DRAW, "visible ${lastVisibleStrokes} strokes"),
            metricLine("dense draw(≥$DENSE_PAGE_STROKES)", PerformanceMetric.DENSE_DRAW),
            "mesh cache %d tiles %.1fMB   re-recorded this frame %d strokes".format(
                inkCacheTiles, inkCacheBytes / BYTES_PER_MIB, inkCacheFrameRecorded),
            "input %.0fHz   total %d strokes   %s".format(lastInputRateHz, strokeCount, lead),
            allocation,
        ).joinToString("\n")
    }

    /** Any metric without a dedicated recorder; one volatile read when diagnostics are off. */
    internal fun record(metric: PerformanceMetric, latencyNanos: Long) {
        if (enabled) addNanos(metric, latencyNanos)
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
        inkCacheTiles = 0
        inkCacheBytes = 0
        inkCacheRecorded = 0L
        inkCacheFrameRecorded = 0L
        gcEvents = 0L
        framesCounted = 0L
        framesMissed = 0L
    }

    companion object {
        const val DENSE_PAGE_STROKES = 500
        /** A frame interval this many refresh periods long missed its deadline. */
        const val MISSED_FRAME_FACTOR = 1.5
        private const val DEFAULT_PERIOD_NANOS = 16_666_667L
        private const val RATE_WINDOW_NANOS = 500_000_000L
        private const val NANOS_PER_MS = 1_000_000.0
        private const val BYTES_PER_MIB = 1024.0 * 1024.0
    }
}

internal data class LatencyExtras(
    val frame: Percentiles?,
    val prediction: Percentiles?,
    val allocationMiBPerSecond: Percentiles?,
    val gcEvents: Long,
    val inputRateHz: Double,
    val inkCacheTiles: Int,
    val inkCacheMiB: Double,
    val inkCacheFrameRecorded: Long,
    val visibleStrokes: Int,
    /** Frame intervals seen since the numbers were reset, and how many of them ran long. */
    val framesCounted: Long = 0L,
    val framesMissed: Long = 0L,
    val refreshPeriodMs: Double = 0.0,
)

/**
 * Whether one frame interval missed its deadline: longer than
 * [LatencyStats.MISSED_FRAME_FACTOR] refresh periods, so at least one vsync
 * went by without a frame. One period, or a little jitter over it, is on time.
 */
internal fun isMissedFrame(intervalNanos: Long, periodNanos: Long): Boolean =
    periodNanos > 0L && intervalNanos * 2 > periodNanos * 3

/** "0.4%", or "-" before there is anything to divide by. */
internal fun missedPercentText(missed: Long, counted: Long): String =
    if (counted <= 0L) "-" else String.format(Locale.US, "%.1f%%", missed * 100.0 / counted)

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
