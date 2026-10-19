package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference

/**
 * Saving skips a page's tape on the strength of [sameReferences], so a false
 * "same" loses a change on disk. Load parallelism is pinned down beside it.
 */
class SaveSkipTest {

    private fun remembered(vararg items: Any) = items.map { WeakReference(it) }

    @Test
    fun `the very same objects in the same order are the same`() {
        val a = Any()
        val b = Any()
        assertTrue(sameReferences(remembered(a, b), listOf(a, b)))
        assertTrue(sameReferences(remembered(), emptyList<Any>()))
    }

    @Test
    fun `equal but different objects are a change`() {
        // A turned page replaces each mask's stroke; it may well compare equal.
        val a = String(charArrayOf('x'))
        val b = String(charArrayOf('x'))
        assertFalse(sameReferences(remembered(a), listOf(b)))
    }

    @Test
    fun `added removed and reordered tape are changes`() {
        val a = Any()
        val b = Any()
        assertFalse(sameReferences(remembered(a), listOf(a, b)))
        assertFalse(sameReferences(remembered(a, b), listOf(a)))
        assertFalse(sameReferences(remembered(a, b), listOf(b, a)))
        assertFalse(sameReferences(remembered(a), emptyList()))
    }

    @Test
    fun `a forgotten reference never matches`() {
        val a = Any()
        val cleared = WeakReference(a).also { it.clear() }
        assertFalse(sameReferences(listOf(cleared), listOf(a)))
    }

    @Test
    fun `small loads and single threads build on the caller`() {
        assertEquals(1, inkBuildSlices(threads = 6, records = INK_PARALLEL_FROM - 1, throttled = false))
        assertEquals(1, inkBuildSlices(threads = 1, records = 10_000, throttled = false))
        assertEquals(1, inkBuildSlices(threads = 1, records = 10_000, throttled = true))
    }

    @Test
    fun `unthrottled loads use two slices per thread`() {
        assertEquals(12, inkBuildSlices(threads = 6, records = 10_000, throttled = false))
        assertEquals(6, inkBuildSlices(threads = 3, records = 10_000, throttled = false))
    }

    @Test
    fun `throttled loads use at most two threads`() {
        assertEquals(INK_THROTTLED_SLICES, inkBuildSlices(threads = 6, records = 10_000, throttled = true))
        assertEquals(INK_THROTTLED_SLICES, inkBuildSlices(threads = 2, records = 10_000, throttled = true))
    }
}
