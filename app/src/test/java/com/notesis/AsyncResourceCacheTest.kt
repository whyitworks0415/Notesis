package com.notesis

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class AsyncResourceCacheTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun `repeated draws do not load or duplicate a pending decode`() {
        val worker = Queue()
        val values = HashMap<String, String>()
        var reads = 0
        var ready = 0
        val cache = AsyncResourceCache(worker, values::get, { k, v -> values[k] = v }, values::clear,
            { key -> reads++; "pixels:$key" }, { ready++ })
        repeat(120) { assertNull(cache.request("photo")) }
        assertEquals(0, reads)
        assertEquals(1, worker.tasks.size)
        worker.drain()
        assertEquals("pixels:photo", cache.request("photo"))
        assertEquals(1, reads)
        assertEquals(1, ready)
        // Eviction makes the file loadable again, rather than remembering a
        // completed request forever and leaving the page empty.
        values.clear()
        cache.request("photo")
        worker.drain()
        assertEquals(2, reads)
    }

    @Test fun `closing while a load runs discards its result and notification`() {
        val worker = Queue()
        val values = HashMap<String, String>()
        var ready = 0
        lateinit var cache: AsyncResourceCache<String, String>
        cache = AsyncResourceCache(worker, values::get, { k, v -> values[k] = v }, values::clear,
            { cache.close(); "old note" }, { ready++ })
        cache.request("photo")
        worker.drain()
        assertTrue(values.isEmpty())
        assertEquals(0, ready)
        assertNull(cache.request("photo"))
    }

    @Test fun `queued loads and dispatched callbacks cannot outlive a screen`() {
        val worker = Queue()
        val callbacks = Queue()
        val values = HashMap<String, String>()
        var reads = 0
        var notifications = 0
        lateinit var cache: AsyncResourceCache<String, String>
        cache = AsyncResourceCache(worker, values::get, { k, v -> values[k] = v }, values::clear,
            { reads++; "pixels" }, { callbacks.execute { cache.notifyIfOpen { notifications++ } } })
        cache.request("first")
        worker.drain()
        cache.request("second")
        cache.close()
        worker.drain()
        callbacks.drain()
        assertEquals(1, reads)
        assertEquals(0, notifications)
        assertTrue(values.isEmpty())
    }

    @Test fun `broken files are remembered but do not block good images`() {
        val worker = Queue()
        val values = HashMap<String, String>()
        var reads = 0
        val cache = AsyncResourceCache(worker, values::get, { k, v -> values[k] = v }, values::clear,
            { key -> reads++; if (key == "broken") error("bad file") else "pixels" }, {})
        cache.request("broken")
        worker.drain()
        repeat(120) { cache.request("broken") }
        cache.request("good")
        worker.drain()
        assertEquals(2, reads)
        assertEquals("pixels", cache.peek("good"))
    }

    @Test fun `pending queue is bounded and overflow can retry after a completion`() {
        val worker = Queue()
        val values = HashMap<String, String>()
        val cache = AsyncResourceCache(worker, values::get, { k, v -> values[k] = v }, values::clear,
            { "pixels" }, {}, maxPending = 2)
        repeat(100) { cache.request("$it") }
        assertEquals(2, worker.tasks.size)
        worker.drain()
        cache.request("99")
        worker.drain()
        assertEquals("pixels", cache.peek("99"))
    }

    @Test fun `rejected work remains retryable`() {
        val worker = Queue()
        var reject = true
        val values = HashMap<String, String>()
        val cache = AsyncResourceCache(Executor {
            if (reject) throw RejectedExecutionException() else worker.execute(it)
        }, values::get, { k, v -> values[k] = v }, values::clear, { "pixels" }, {})
        cache.request("photo")
        reject = false
        cache.request("photo")
        worker.drain()
        assertEquals("pixels", cache.peek("photo"))
    }
}
