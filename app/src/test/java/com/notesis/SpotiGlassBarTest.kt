package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class SpotiGlassBarTest {
    @Test fun `lens in the middle of the bar keeps its size`() {
        val body = spotiGlassLensBody(200f, 90f, 50f, barWidth = 400f, edge = 1f, minWidth = 60f)
        assertEquals(200f, body.x, 0f)
        assertEquals(90f, body.y, 0f)
        assertEquals(50f, body.z, 0f)
    }

    @Test fun `lens at either end is pressed against the bar instead of cut off`() {
        // First cell: a 90px lens centred at 36px would reach to -9px.
        val first = spotiGlassLensBody(36f, 90f, 50f, barWidth = 400f, edge = 1f, minWidth = 60f)
        assertEquals(1f, first.x - first.y / 2, 0.001f)
        assertEquals(81f, first.x + first.y / 2, 0.001f)
        org.junit.Assert.assertTrue(first.z > 50f)
        val last = spotiGlassLensBody(364f, 90f, 50f, barWidth = 400f, edge = 1f, minWidth = 60f)
        assertEquals(399f, last.x + last.y / 2, 0.001f)
        assertEquals(319f, last.x - last.y / 2, 0.001f)
        // A spring overshoot past the end squeezes it but never below a cell.
        val overshoot = spotiGlassLensBody(-40f, 90f, 50f, barWidth = 400f, edge = 1f, minWidth = 60f)
        assertEquals(1f, overshoot.x - overshoot.y / 2, 0.001f)
        assertEquals(60f, overshoot.y, 0.001f)
    }

    @Test fun `tap lifts the glass then lands in a flat idle state`() {
        val motion = SpotiGlassMotion(0f)
        motion.select(3f)
        var maximumLift = 0f
        var maximumDeviation = 0f
        repeat(300) { i ->
            motion.tick((i + 1) / 60.0, 1 / 60.0, 70f)
            maximumLift = maxOf(maximumLift, motion.growX)
            maximumDeviation = maxOf(maximumDeviation, kotlin.math.abs(motion.deviation))
        }
        assertEquals(3f, motion.position, 0f)
        assertEquals(false, motion.running)
        assertEquals(0f, motion.presence, 0f)
        assertEquals(0f, motion.growX, 0f)
        org.junit.Assert.assertTrue(maximumLift > 0.5f)
        org.junit.Assert.assertTrue(maximumDeviation > 0.01f && maximumDeviation <= 0.12f)
    }

    @Test fun `grab follows and release snaps while a reversal preserves motion`() {
        val motion = SpotiGlassMotion(0f)
        motion.grab(2.4f)
        repeat(60) { i -> motion.tick((i + 1) / 60.0, 1 / 60.0, 70f) }
        assertEquals(2.4f, motion.position, 0.001f)
        assertEquals(1f, motion.presence, 0.01f)
        motion.release(2f)
        repeat(10) { i -> motion.tick(1 + (i + 1) / 60.0, 1 / 60.0, 70f) }
        motion.select(0f)
        repeat(300) { i -> motion.tick(2 + (i + 1) / 60.0, 1 / 60.0, 70f) }
        assertEquals(0f, motion.position, 0f)
        assertEquals(false, motion.running)
        assertEquals(0f, motion.deviation, 0f)
    }

    @Test fun `slide selects the slot under the pointer in either direction`() {
        for (index in 0..3) {
            val x = 50f + index * 100f
            assertEquals(index, spotiGlassPosition(x, 400f, 4, false).roundToInt())
            assertEquals(3 - index, spotiGlassPosition(x, 400f, 4, true).roundToInt())
        }
    }

    @Test fun `lens follows intermediate positions and clamps outside the bar`() {
        assertEquals(0.5f, spotiGlassPosition(100f, 400f, 4, false), 0f)
        assertEquals(2.5f, spotiGlassPosition(100f, 400f, 4, true), 0f)
        assertEquals(0f, spotiGlassPosition(-200f, 400f, 4, false), 0f)
        assertEquals(3f, spotiGlassPosition(900f, 400f, 4, false), 0f)
        assertEquals(0f, spotiGlassPosition(900f, 400f, 4, true), 0f)
        assertEquals(0f, spotiGlassPosition(50f, 0f, 4, false), 0f)
    }

    @Test fun `dragging past an end gives way a little and never more than the limit`() {
        assertEquals(1.5f, spotiGlassDragPosition(200f, 400f, 4, false), 0f)
        val past = spotiGlassDragPosition(-100f, 400f, 4, false)
        org.junit.Assert.assertTrue(past < 0f && past > -SPOTIGLASS_RUBBER_CELLS)
        val far = spotiGlassDragPosition(-100000f, 400f, 4, false)
        org.junit.Assert.assertTrue(far > -SPOTIGLASS_RUBBER_CELLS && far < past)
        val end = spotiGlassDragPosition(500f, 400f, 4, false)
        org.junit.Assert.assertTrue(end > 3f && end < 3f + SPOTIGLASS_RUBBER_CELLS)
        assertEquals(3f - end, spotiGlassDragPosition(500f, 400f, 4, true), 0.0001f)
    }
}
