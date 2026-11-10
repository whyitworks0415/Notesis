package com.notesis

import java.util.IdentityHashMap

/** Temporary display ink, owned by one benchmark run and never by a document or edit history. */
internal class BenchmarkInk<P : Any, T : Any> {
    private var serial = 0L
    var session = 0L
        private set
    private val pages = IdentityHashMap<P, MutableList<T>>()
    val active: Boolean get() = session != 0L
    val size: Int get() = pages.values.sumOf { it.size }

    fun begin() {
        pages.clear()
        session = ++serial
    }

    fun add(startedIn: Long, page: P, stroke: T): Boolean {
        if (!active || startedIn != session) return false
        pages.getOrPut(page) { ArrayList() }.add(stroke)
        return true
    }

    fun onPage(page: P): List<T> = pages[page].orEmpty()

    fun end() {
        session = 0L
        pages.clear()
    }
}
