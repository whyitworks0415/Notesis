package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPipelineTest {
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
