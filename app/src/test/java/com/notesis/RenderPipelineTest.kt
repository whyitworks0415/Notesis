package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPipelineTest {
    @Test
    fun `paper culling preserves grid phase and antialiasing edge cells`() {
        assertEquals(48f, firstVisiblePaperRule(-100f, 48f), 0f)
        assertEquals(48f, firstVisiblePaperRule(51f, 48f), 0f)
        assertEquals(96f, firstVisiblePaperRule(53f, 48f), 0f)
        assertEquals(144f, firstVisiblePaperRule(148f, 48f), 0f)
        assertEquals(72f, firstVisiblePaperRule(74f, 24f), 0f)
        for (minimum in 0..1000) {
            val first = firstVisiblePaperRule(minimum.toFloat(), 48f)
            assertEquals(0f, first % 48f, 0f)
            assertTrue(first >= minimum - 4f)
            assertTrue(first == 48f || first - 48f < minimum - 4f)
        }
    }

    @Test
    fun `cached ink survives appends but rejects undo erase and replacement`() {
        val a = Any()
        val b = Any()
        val c = Any()
        val prefix = InkRenderPrefix(listOf(a, b))
        assertTrue(prefix.matches(listOf(a, b), 1))
        assertTrue(prefix.matches(listOf(a, b, c), 2))
        assertTrue(prefix.contains(a))
        assertFalse(prefix.contains(c))
        assertFalse(prefix.matches(listOf(a), 3))
        assertFalse(prefix.matches(listOf(a, c), 4))
        assertFalse(prefix.matches(listOf(b, a), 5))
        assertFalse(prefix.matches(listOf(c, a, b), 6))
        assertTrue(prefix.matches(listOf(a, b), 7))
    }

    @Test
    fun `equal geometry with different identity invalidates cached pixels`() {
        data class Geometry(val x: Int)
        val stroke = Geometry(1)
        val prefix = InkRenderPrefix(listOf(stroke))
        assertTrue(prefix.matches(listOf(stroke), 1))
        assertFalse(prefix.matches(listOf(Geometry(1)), 2))
    }

    @Test
    fun `unchanged draw frames do not rescan the cached prefix`() {
        var reads = 0
        val strokes = List(1000) { Any() }
        val current = object : AbstractList<Any>() {
            override val size get() = strokes.size
            override fun get(index: Int): Any { reads++; return strokes[index] }
        }
        val prefix = InkRenderPrefix(strokes)
        repeat(120) { assertTrue(prefix.matches(current, 1)) }
        assertEquals(1000, reads)
    }

    @Test
    fun `stale pending work does not suppress the current generation`() {
        assertFalse(shouldEnqueueRender(7, 7))
        assertTrue(shouldEnqueueRender(6, 7))
        assertTrue(shouldEnqueueRender(null, 7))
    }

    @Test
    fun `only the current open generation may publish`() {
        assertTrue(shouldPublishRender(12, 12, closed = false))
        assertFalse(shouldPublishRender(11, 12, closed = false))
        assertFalse(shouldPublishRender(12, 12, closed = true))
    }

    @Test
    fun `page refinement state distinguishes partial and complete results`() {
        assertEquals(RenderState.Dirty, completedRenderState(false, false))
        assertEquals(RenderState.ViewportCached, completedRenderState(true, false))
        assertEquals(RenderState.Complete, completedRenderState(true, true))
    }

    @Test
    fun `interactive viewport defers detail until every gesture has settled`() {
        assertTrue(shouldDeferDetail(true, viewportInteracting = true, zooming = false, flinging = false))
        assertTrue(shouldDeferDetail(true, viewportInteracting = false, zooming = true, flinging = false))
        assertTrue(shouldDeferDetail(true, viewportInteracting = false, zooming = false, flinging = true))
        assertFalse(shouldDeferDetail(true, viewportInteracting = false, zooming = false, flinging = false))
        assertFalse(shouldDeferDetail(false, viewportInteracting = true, zooming = true, flinging = true))
    }
}
