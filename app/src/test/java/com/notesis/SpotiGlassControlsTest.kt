package com.notesis

import org.junit.Assert.*
import org.junit.Test

class SpotiGlassControlsTest {
    @Test fun `holding keeps a stationary thumb fully raised until release`() {
        val motion = SpotiGlassMotion(0.4f, control = true)
        motion.grab(0.4f)
        repeat(180) { motion.tick((it + 1) / 60.0, 1 / 60.0, 180f) }
        assertTrue(motion.running)
        assertEquals(1f, motion.progress, 0.001f)
        assertEquals(1f, motion.presence, 0.001f)
        assertEquals(1f, motion.growX, 0.001f)
        motion.release(0.4f)
        repeat(180) { motion.tick(3 + (it + 1) / 60.0, 1 / 60.0, 180f) }
        assertFalse(motion.running)
        assertEquals(0f, motion.progress, 0f)
        assertEquals(0f, motion.growX, 0f)
        assertEquals(0f, motion.presence, 0f)
    }

    @Test fun `drag is one to one and interrupted settling can be grabbed again`() {
        val motion = SpotiGlassMotion(0f, control = true)
        motion.grab(0.2f)
        motion.tick(1 / 60.0, 1 / 60.0, 22f)
        assertEquals(0.2f, motion.position, 0f)
        motion.follow(0.8f)
        motion.tick(2 / 60.0, 1 / 60.0, 22f)
        assertEquals(0.8f, motion.position, 0f)
        motion.release(1f)
        repeat(5) { motion.tick((it + 3) / 60.0, 1 / 60.0, 22f) }
        motion.grab(0.3f)
        repeat(120) { motion.tick(1 + (it + 1) / 60.0, 1 / 60.0, 22f) }
        assertEquals(0.3f, motion.position, 0f)
        assertEquals(1f, motion.progress, 0.001f)
        motion.snap(0f)
        assertFalse(motion.running)
        assertFalse(motion.dragging)
        assertEquals(0f, motion.presence, 0f)
    }

    @Test fun `external transitions stay liquid until travel settles then restore white`() {
        val motion = SpotiGlassMotion(0f, control = true)
        motion.select(1f)
        repeat(12) { motion.tick((it + 1) / 60.0, 1 / 60.0, 22f) }
        assertTrue(motion.progress > 0.9f)
        motion.select(0f)
        repeat(240) { motion.tick(1 + (it + 1) / 60.0, 1 / 60.0, 22f) }
        assertFalse(motion.running)
        assertEquals(0f, motion.position, 0f)
        assertEquals(0f, motion.growY, 0f)
    }

    @Test fun `slider respects discrete steps range and endpoints`() {
        assertEquals(20f, spotiSliderValue(-1f, 20f..40f, 3), 0f)
        assertEquals(25f, spotiSliderValue(0.31f, 20f..40f, 3), 0f)
        assertEquals(40f, spotiSliderValue(2f, 20f..40f, 3), 0f)
        assertEquals(26.2f, spotiSliderValue(0.31f, 20f..40f, 0), 0.001f)
        assertEquals(5f, spotiSliderValue(0.5f, 5f..5f, 0), 0f)
    }
}
