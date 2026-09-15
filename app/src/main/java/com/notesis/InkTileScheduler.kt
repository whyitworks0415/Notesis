package com.notesis

import java.util.concurrent.Executor

internal data class InkWorkKey(val address: InkTileAddress, val version: InkTileVersion)
internal class InkWorkToken internal constructor(val key: InkWorkKey) {
    @Volatile var cancelled = false
        private set
    fun cancel() { cancelled = true }
}

/** One running raster, at most 32 waiting. The executor only ever holds the drain runnable. */
internal class InkTileScheduler(private val executor: Executor, private val capacity: Int = 32) {
    private data class Work(val token: InkWorkToken, var priority: Int, var distance: Float,
        var viewport: Long, val run: (InkWorkToken) -> Unit)
    private val waiting = ArrayList<Work>()
    private var running: Work? = null
    private var draining = false
    private var closed = false
    var deduped = 0L; private set
    var cancelled = 0L; private set
    @Synchronized fun queued() = waiting.size
    @Synchronized fun request(key: InkWorkKey, priority: Int, distance: Float, viewport: Long,
        run: (InkWorkToken) -> Unit): Boolean {
        if (closed) return false
        (waiting.firstOrNull { it.token.key == key && !it.token.cancelled }
            ?: running?.takeIf { it.token.key == key && !it.token.cancelled })?.let {
            it.priority = minOf(priority, it.priority); it.distance = distance; it.viewport = viewport
            deduped++; return false
        }
        waiting.filter { it.token.key.address == key.address }.toList().forEach { cancel(it); waiting.remove(it) }
        running?.takeIf { it.token.key.address == key.address }?.let(::cancel)
        val work = Work(InkWorkToken(key), priority, distance, viewport, run)
        if (waiting.size == capacity) {
            val worst = waiting.maxWithOrNull(order)!!
            if (order.compare(work, worst) >= 0) return false
            cancel(worst); waiting.remove(worst)
        }
        waiting += work
        if (!draining) {
            draining = true
            try { executor.execute(::drain) } catch (_: RuntimeException) {
                waiting.forEach(::cancel); waiting.clear(); draining = false; return false
            }
        }
        return true
    }
    @Synchronized fun retain(addresses: Set<InkTileAddress>) {
        waiting.filter { it.token.key.address !in addresses }.toList().forEach { cancel(it); waiting.remove(it) }
        running?.takeIf { it.token.key.address !in addresses }?.let(::cancel)
    }
    @Synchronized fun cancelSession(session: Long) {
        waiting.filter { it.token.key.address.session == session }.toList().forEach { cancel(it); waiting.remove(it) }
        running?.takeIf { it.token.key.address.session == session }?.let(::cancel)
    }
    @Synchronized fun clear() {
        waiting.forEach(::cancel); waiting.clear(); running?.let(::cancel)
    }
    @Synchronized fun close() { closed = true; clear() }
    private fun cancel(work: Work) { if (!work.token.cancelled) { work.token.cancel(); cancelled++ } }
    private fun drain() {
        while (true) {
            val work = synchronized(this) {
                if (waiting.isEmpty()) { running = null; draining = false; return }
                waiting.minWithOrNull(order)!!.also { waiting.remove(it); running = it }
            }
            try { if (!work.token.cancelled) work.run(work.token) } catch (_: Exception) {
                // One failed raster must not strand all subsequent coverage requests.
                work.token.cancel()
            } finally {
                synchronized(this) { if (running === work) running = null }
            }
        }
    }
    private val order = compareBy<Work> { it.priority }.thenByDescending { it.viewport }.thenBy { it.distance }
}

internal enum class InkMemoryPool { DETAIL, COVERAGE, WORK }

/** Reservations include running work, posted results, cache entries and retained frame references. */
internal class InkMemoryBudget(val limit: Long) {
    private val used = LongArray(3)
    fun cap(pool: InkMemoryPool) = when (pool) {
        InkMemoryPool.DETAIL -> limit / 2
        else -> limit / 4
    }
    @Synchronized fun used(pool: InkMemoryPool) = used[pool.ordinal]
    @Synchronized fun total() = used.sum()
    @Synchronized fun reserve(bytes: Long, pool: InkMemoryPool): Lease? {
        if (bytes <= 0 || bytes > cap(pool) - used[pool.ordinal]) return null
        used[pool.ordinal] += bytes
        return Lease(bytes, pool)
    }
    inner class Lease internal constructor(val bytes: Long, var pool: InkMemoryPool) {
        private var released = false
        fun moveTo(target: InkMemoryPool): Boolean = synchronized(this@InkMemoryBudget) {
            if (released) return false
            if (pool == target) return true
            if (bytes > cap(target) - used[target.ordinal]) return false
            used[pool.ordinal] -= bytes; used[target.ordinal] += bytes; pool = target
            true
        }
        fun release() = synchronized(this@InkMemoryBudget) {
            if (!released) { released = true; used[pool.ordinal] -= bytes }
        }
    }
}

/** Unpublished resources recycle exactly once. Published resources use GC after the last owner. */
internal class InkOwnedResource<T>(val value: T, private val lease: InkMemoryBudget.Lease,
    private val recycle: (T) -> Unit) {
    private var references = 1
    private var published = false
    @Synchronized fun publish() { check(references > 0); published = true }
    @Synchronized fun moveTo(pool: InkMemoryPool) = lease.moveTo(pool)
    @Synchronized fun retain() { check(references > 0); references++ }
    @Synchronized fun release() {
        check(references > 0)
        if (--references == 0) { if (!published) recycle(value); lease.release() }
    }
}
