@file:Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")
package com.notesis

/** Inline empty implementations: no tracing, strings, clocks or counters in release call sites. */
internal object InkRenderStats {
    inline fun beginPlan() = Unit
    inline fun endPlan() = Unit
    inline fun collectionRead() = Unit
    inline fun candidateQuery() = Unit
    inline fun plan(hit: Int, miss: Int) = Unit
    inline fun scheduled() = Unit
    inline fun evicted() = Unit
    inline fun memory(bytes: Long, queue: Int, cancel: Long, dedupe: Long) = Unit
    inline fun rasterStart() = 0L
    inline fun rasterEnd(start: Long) = Unit
}
