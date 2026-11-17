package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderLoadTest {
    private val frame = 16_666_667L

    @Test fun `a frame the render thread overran halves the GPU tile work, down to a floor`() {
        assertEquals(0.5f, nextRenderShare(1f, frame * 2, frame), 1e-6f)
        var share = 1f
        repeat(20) { share = nextRenderShare(share, frame * 3, frame) }
        assertEquals(MIN_RENDER_SHARE, share, 1e-6f)
    }

    @Test fun `frames on time give the work back a tenth at a time`() {
        assertEquals(0.6f, nextRenderShare(0.5f, frame / 2, frame), 1e-6f)
        assertEquals(1f, nextRenderShare(0.95f, frame / 2, frame), 1e-6f)
        assertEquals(1f, nextRenderShare(1f, frame, frame), 1e-6f)
    }

    @Test fun `workers give half their threads to a render thread that is behind`() {
        assertEquals(4, yieldToRender(4, false))
        assertEquals(2, yieldToRender(4, true))
        assertEquals(1, yieldToRender(1, true))
        assertEquals(1, yieldToRender(3, true))
    }

    @Test fun `the frame split reports averages, the worst frame and the late ones`() {
        val split = FrameSplit(capacity = 4)
        assertEquals("Frames: none drawn yet", split.render(frame))
        split.record(2_000_000, 1_000_000, 3_000_000, 4_000_000, 10_000_000)
        split.record(4_000_000, 1_000_000, 5_000_000, 40_000_000, 50_000_000)
        val line = split.render(frame)
        assertTrue(line, line.startsWith("Frames (last 2): ui 3.0 / sync 1.0 / render 4.0 / gpu 22.0 ms avg"))
        assertTrue(line, line.contains("worst ui 4.0, render 5.0, gpu 40.0, total 50.0 ms · 1 late"))
    }
}
