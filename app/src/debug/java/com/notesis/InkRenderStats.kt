package com.notesis

import android.os.Trace
import java.util.concurrent.atomic.AtomicLong

internal object InkRenderStats {
    private val planning = ThreadLocal.withInitial { false }
    fun beginPlan() { planning.set(true) }
    fun endPlan() { planning.set(false) }
    fun collectionRead() { if (planning.get()) uiCollectionQueries.incrementAndGet() }
    fun candidateQuery() { if (planning.get()) uiCandidateCopies.incrementAndGet() }
    val hits = AtomicLong(); val missing = AtomicLong(); val scheduled = AtomicLong()
    val rasterNanos = AtomicLong(); val evictions = AtomicLong()
    val vectorDraws = AtomicLong(); val vectorFallbacks = AtomicLong(); val overlays = AtomicLong()
    val uiCollectionQueries = AtomicLong(); val uiCandidateCopies = AtomicLong()
    @Volatile var cacheBytes = 0L
    @Volatile var queued = 0
    @Volatile var cancelled = 0L
    @Volatile var deduped = 0L
    fun plan(hit: Int, miss: Int) { hits.addAndGet(hit.toLong()); missing.addAndGet(miss.toLong()) }
    fun scheduled() { scheduled.incrementAndGet() }
    fun evicted() { evictions.incrementAndGet() }
    fun memory(bytes: Long, queue: Int, cancel: Long, dedupe: Long) {
        cacheBytes = bytes; queued = queue; cancelled = cancel; deduped = dedupe
    }
    fun rasterStart(): Long { Trace.beginSection("Notesis.InkRaster"); return System.nanoTime() }
    fun rasterEnd(start: Long) { rasterNanos.addAndGet(System.nanoTime() - start); Trace.endSection() }
}
