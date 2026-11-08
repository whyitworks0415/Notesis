package com.notesis

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test fun `a spring's stiffness follows its response as SwiftUI's does`() {
        // (2π / 0.5)² for half a second.
        assertEquals((2 * Math.PI / 0.5).let { it * it }.toFloat(), springStiffness(0.5f), 0.01f)
        // Twice the response, a quarter of the stiffness.
        assertEquals(springStiffness(0.2f) / 4f, springStiffness(0.4f), 0.01f)
    }

    @Test fun `the speed alone sets how long a spring takes, up to three times as quick`() {
        assertEquals(1f, responseScaleFor(1f), 0f)
        assertEquals(0.5f, responseScaleFor(2f), 0.0001f)
        // Held to the slider's range.
        assertEquals(1f / 3f, responseScaleFor(10f), 0.0001f)
        assertEquals(1f / 0.7f, responseScaleFor(0.1f), 0.0001f)
        assertEquals(1.3f, MotionPrefs.DEFAULT_SPEED, 0f)
    }

    @Test fun `a throw is projected to where it would come to rest`() {
        // Standing still, it stays.
        assertEquals(100f, projectedEnd(100f, 0f), 0f)
        // 1000 px/s at iOS's normal rate goes about 499 px further.
        assertEquals(100f + 499f, projectedEnd(100f, 1000f), 0.5f)
        // A quicker stop reaches less far, and a throw back goes back.
        assertEquals(99f, projectedEnd(0f, 1000f, 0.99f), 0.5f)
        assertTrue(projectedEnd(0f, -500f) < 0f)
    }

    @Test fun `the rubber band follows at first, stiffens and never reaches its dimension`() {
        assertEquals(0f, rubberBand(0f, 100f), 0f)
        val small = rubberBand(5f, 100f)
        assertTrue(small in 2f..5f)
        val far = rubberBand(10_000f, 100f)
        assertTrue(far < 100f && far > 95f)
        // Ever further, ever slower.
        assertTrue(rubberBand(200f, 100f) - rubberBand(100f, 100f) < rubberBand(100f, 100f) - rubberBand(0f, 100f))
        // Both ways.
        assertEquals(-rubberBand(30f, 100f), rubberBand(-30f, 100f), 0f)
        assertEquals(0f, rubberBand(30f, 0f), 0f)
    }

    @Test fun `a dragged thing leads the finger by a frame, held to a limit`() {
        assertEquals(12f, dragLead(1000f, maxPx = 40f), 0.001f)
        assertEquals(40f, dragLead(100_000f, maxPx = 40f), 0f)
        assertEquals(-40f, dragLead(-100_000f, maxPx = 40f), 0f)
    }

    @Test fun `motion blur is a fifth of a frame's step, only on 60 Hz and only when fast`() {
        // 3000 px/s at 60 Hz steps 50 px a frame: 10 px of blur, held to 4.
        assertEquals(4f, motionBlurPx(3000f, 60f, 4f), 0f)
        assertEquals(2f, motionBlurPx(600f, 60f, 4f), 0.001f)
        // Slow, none; fast screen, none.
        assertEquals(0f, motionBlurPx(100f, 60f, 4f), 0f)
        assertEquals(0f, motionBlurPx(3000f, 120f, 4f), 0f)
    }

    @Test fun `the ordinary move is a spring that does not overshoot, a staggered one keeps its curve`() {
        val plain = Ui.Motion.tween<Float>()
        assertTrue(plain is SpringSpec<*>)
        assertEquals(1f, (plain as SpringSpec<*>).dampingRatio, 0f)
        assertTrue(Ui.Motion.tween<Float>(delayMillis = 40) is TweenSpec<*>)
    }

    @Test fun `pacing quickens a spec by the speed, whatever the screen's rate`() {
        val was = MotionPrefs.refreshHz
        try {
            val s = spring<Float>(dampingRatio = 0.8f, stiffness = 400f)
            for (hz in listOf(60f, 120f)) {
                MotionPrefs.refreshHz = hz
                // At the default speed of 1.3 a spring stiffens by 1.3 squared.
                val quicker = s.paced() as SpringSpec<Float>
                assertEquals(400f * 1.3f * 1.3f, quicker.stiffness, 0.5f)
                assertEquals(0.8f, quicker.dampingRatio, 0f)
                val shorter = tween<Float>(200).paced() as TweenSpec<Float>
                assertEquals(153f, shorter.durationMillis.toFloat(), 1f)
            }
        } finally {
            MotionPrefs.refreshHz = was
        }
    }
}
