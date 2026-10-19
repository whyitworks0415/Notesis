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
        "load ${pagesLoaded.get()} (늦게 도착 ${pagesLoadedStale.get()}, 중단 ${loadsAbandoned.get()})" +
            " · refine ${refinesApplied.get()}/낡음 ${refinesStale.get()} · 미리 ${prefetched.get()}\n" +
            "pdf ${pdfRenders.get()} (미룸 ${pdfDeferred.get()}) · 타일 기록 ${tilesRecorded.get()}" +
            " · 레이어 전체 ${layerSettlesFull.get()}/끝만 ${layerSettlesTail.get()}"
}
