package com.notesis

import java.util.concurrent.atomic.AtomicLong

/**
 * Counts of background work: how much was asked for, how much was used, how
 * much was thrown away. Shown in the latency readout, so a busy CPU on a page
 * nobody is touching can be traced to the queue that is doing it.
 */
internal object WorkStats {
    val pagesLoaded = AtomicLong()
    val pagesLoadedStale = AtomicLong()
    val loadsAbandoned = AtomicLong()
    val refinesApplied = AtomicLong()
    val refinesStale = AtomicLong()
    val prefetched = AtomicLong()
    val pdfRenders = AtomicLong()
    val pdfDeferred = AtomicLong()
    val tilesRecorded = AtomicLong()
    val layerSettlesFull = AtomicLong()
    val layerSettlesTail = AtomicLong()

    fun render(): String =
        "load ${pagesLoaded.get()} (late ${pagesLoadedStale.get()}, abandoned ${loadsAbandoned.get()})" +
            " · refine ${refinesApplied.get()}/stale ${refinesStale.get()} · prefetch ${prefetched.get()}\n" +
            "pdf ${pdfRenders.get()} (deferred ${pdfDeferred.get()}) · tiles recorded ${tilesRecorded.get()}" +
            " · layer settle full ${layerSettlesFull.get()}/tail ${layerSettlesTail.get()}"

    /** Ink tiles left for a later frame because this one's recording budget was spent. */
    val tilesDeferred = AtomicLong()
    /** Clean pages whose previous autosave copy was handed over again instead of rebuilt. */
    val savePagesReused = AtomicLong()
}
