package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class SpotiGlassBarTest {
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
}
