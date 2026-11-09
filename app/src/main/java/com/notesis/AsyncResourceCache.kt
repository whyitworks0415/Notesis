package com.notesis

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * A draw may look up or request a resource, but never wait for it. Cache and
 * request bookkeeping share a short lock; loading and notification never do.
 * A closed screen cannot be populated or notified by a late worker.
 */
internal class AsyncResourceCache<K : Any, V : Any>(
    private val executor: Executor,
    private val cached: (K) -> V?,
    private val store: (K, V) -> Unit,
    private val clear: () -> Unit,
    private val load: (K) -> V?,
    private val notifyReady: () -> Unit,
    private val maxPending: Int = 32,
    private val maxFailures: Int = 128,
) : AutoCloseable {
    private val lock = Any()
    private val pending = HashSet<K>()
    private val failures = LinkedHashSet<K>()
    private var closed = false

    fun peek(key: K): V? = synchronized(lock) { if (closed) null else cached(key) }

    fun request(key: K): V? {
        synchronized(lock) {
            if (closed) return null
            cached(key)?.let { return it }
            if (key in failures || key in pending || pending.size >= maxPending) return null
            pending += key
        }
        try {
            executor.execute {
                if (synchronized(lock) { closed }) return@execute
                val value = try { load(key) } catch (_: Exception) { null }
                val ready = synchronized(lock) {
                    pending -= key
                    if (closed) false else {
                        if (value != null) store(key, value) else {
                            failures += key
                            if (failures.size > maxFailures) failures.remove(failures.first())
                        }
                        true
                    }
                }
                if (ready) notifyReady()
            }
        } catch (_: RejectedExecutionException) {
            // A refused request is retryable; it was never a broken file.
            synchronized(lock) { pending -= key }
        }
        return null
    }

    /** Call on the notification's destination thread too, after a dispatch. */
    fun notifyIfOpen(block: () -> Unit) = synchronized(lock) { if (!closed) block() }

    override fun close() = synchronized(lock) {
        closed = true
        pending.clear()
        failures.clear()
        clear()
    }
}
