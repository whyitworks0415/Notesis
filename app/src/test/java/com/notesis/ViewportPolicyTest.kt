package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ViewportPolicyTest {
    private fun screenOf(state: FloatArray, docX: Float, docY: Float) =
        floatArrayOf(docX * state[0] + state[1], docY * state[0] + state[2])

    private fun docOf(state: FloatArray, screenX: Float, screenY: Float) =
        floatArrayOf((screenX - state[1]) / state[0], (screenY - state[2]) / state[0])

    @Test
    fun `the document point under the pinch centroid follows the fingers`() {
        val state = floatArrayOf(1.7f, -120f, 340f)
        val under = docOf(state, 500f, 600f)
        // Fingers spread and drift over several samples.
        var fromX = 500f
        var fromY = 600f
        val samples = listOf(
            Triple(1.04f, 507f, 596f),
            Triple(1.03f, 515.5f, 590.25f),
            Triple(0.99f, 515.51f, 590.26f),
            Triple(1.10f, 540f, 570f),
        )
        for ((factor, toX, toY) in samples) {
            pinchStep(state, factor, fromX, fromY, toX, toY)
            val at = screenOf(state, under[0], under[1])
            assertEquals(toX, at[0], 1e-3f)
            assertEquals(toY, at[1], 1e-3f)
            fromX = toX
            fromY = toY
        }
        assertEquals(1.7f * 1.04f * 1.03f * 0.99f * 1.10f, state[0], 1e-4f)
    }

    @Test
    fun `a pure pan moves by exactly the delta, small ones included`() {
        val state = floatArrayOf(2f, 10f, 20f)
        pinchStep(state, 1f, 100f, 100f, 100.01f, 99.995f)
        assertEquals(2f, state[0], 0f)
        assertEquals(10.01f, state[1], 1e-4f)
        assertEquals(19.995f, state[2], 1e-4f)
    }

    @Test
    fun `a finger joining re-anchors instead of jumping`() {
        // Two fingers, then a third lands far away: the centroid jumps, but the
        // canvas takes the new centroid as the anchor and only moves the page
        // by what happens after.
        val state = floatArrayOf(1f, 0f, 0f)
        val two = floatArrayOf(300f, 400f)
        val three = floatArrayOf((300f + 400f + 1000f) / 3f, (400f + 400f + 900f) / 3f)
        // POINTER_DOWN: the anchor becomes the new centroid, with no step.
        val anchorX = three[0]
        val anchorY = three[1]
        val before = state.copyOf()
        // The next MOVE with the hand still: nothing moves.
        pinchStep(state, 1f, anchorX, anchorY, three[0], three[1])
        assertEquals(before.toList(), state.toList())
        // Translating by the raw centroid jump is what the re-anchor avoids.
        val jumped = floatArrayOf(1f, 0f, 0f)
        pinchStep(jumped, 1f, two[0], two[1], three[0], three[1])
        assertTrue(abs(jumped[1]) > 100f)
    }

    @Test
    fun `fling distance is the same at 60Hz and 120Hz`() {
        val friction = 3.2f
        fun glide(hz: Int, seconds: Float): Float {
            var v = 3000f
            var x = 0f
            val dt = 1f / hz
            var t = 0f
            while (t < seconds - 1e-6f) {
                x += flingDistance(v, dt, friction)
                v = flingVelocityAfter(v, dt, friction)
                t += dt
            }
            return x
        }
        val at60 = glide(60, 0.5f)
        val at120 = glide(120, 0.5f)
        assertEquals(at60, at120, at60 * 0.002f)
        // And it matches the closed form v0 (1 - e^-kt) / k.
        val exact = 3000f * (1f - kotlin.math.exp(-friction * 0.5f)) / friction
        assertEquals(exact, at60, exact * 0.002f)
    }

    @Test
    fun `a long frame is clamped, not made up in one jump`() {
        val last = 1_000_000_000L
        assertEquals(1f / 60f, flingDt(last + 16_666_667L, last), 1e-4f)
        // A 200ms stall moves the page at most 50ms worth.
        assertEquals(MAX_FLING_STEP_S, flingDt(last + 200_000_000L, last), 0f)
        // A frame time before the last reading never steps backwards.
        assertEquals(0f, flingDt(last - 5_000_000L, last), 0f)
        val step = flingDistance(4000f, flingDt(last + 200_000_000L, last), 3.2f)
        assertTrue(step < 4000f * MAX_FLING_STEP_S)
    }

    @Test
    fun `fling decays and its end condition is reached`() {
        var v = 6000f
        var frames = 0
        while (v >= 80f && frames < 10_000) {
            v = flingVelocityAfter(v, 1f / 60f, 3.2f)
            frames++
        }
        assertTrue("fling should end within a few seconds", frames in 60..400)
    }

    @Test
    fun `an axis pinned at the edge is recognised so its speed is dropped`() {
        assertTrue(flingAxisBlocked(intended = 20f, actual = 0f))
        assertTrue(flingAxisBlocked(intended = -20f, actual = -3f))
        assertFalse(flingAxisBlocked(intended = 20f, actual = 19.5f))
        // Sub-pixel steps at the end of a glide are not mistaken for an edge.
        assertFalse(flingAxisBlocked(intended = 0.2f, actual = 0f))
    }

    @Test
    fun `prefetch waits for the hands, narrows in battery saver and stops when hot`() {
        assertNull(prefetchSpan(Interaction.WRITING, powerSave = false, thermalStatus = 0))
        assertNull(prefetchSpan(Interaction.MOVING, powerSave = false, thermalStatus = 0))
        assertEquals(PrefetchSpan.FULL, prefetchSpan(Interaction.IDLE, powerSave = false, thermalStatus = 0))
        assertEquals(PrefetchSpan.FULL, prefetchSpan(Interaction.SETTLING, powerSave = false, thermalStatus = 0))
        assertEquals(PrefetchSpan.SAVING, prefetchSpan(Interaction.IDLE, powerSave = true, thermalStatus = 0))
        assertEquals(PrefetchSpan.NONE, prefetchSpan(Interaction.IDLE, powerSave = false, thermalStatus = THERMAL_MODERATE))
        assertEquals(PrefetchSpan.NONE, prefetchSpan(Interaction.WRITING, powerSave = true, thermalStatus = 4))
        val saving = PrefetchSpan.SAVING
        val full = PrefetchSpan.FULL
        assertTrue(saving.ahead + saving.behind < full.ahead + full.behind)
    }

    @Test
    fun `moving pdf radius is zero when fast or hot`() {
        assertEquals(0, movingPdfRadius(fast = true, thermalStatus = 0))
        assertEquals(0, movingPdfRadius(fast = false, thermalStatus = THERMAL_MODERATE))
        assertEquals(1, movingPdfRadius(fast = false, thermalStatus = 0))
    }

    @Test
    fun `nearby pages lead in the direction of travel`() {
        assertEquals(listOf(5, 6, 4, 7, 3, 8), nearbyPages(5, 20, PrefetchSpan(3, 2), direction = 1))
        assertEquals(listOf(5, 4, 6, 3, 7, 2), nearbyPages(5, 20, PrefetchSpan(3, 2), direction = -1))
        assertEquals(listOf(0, 1), nearbyPages(0, 20, PrefetchSpan(1, 1), direction = 1))
        assertEquals(listOf(19, 18), nearbyPages(19, 20, PrefetchSpan(1, 1), direction = 1))
        assertEquals(listOf(2), nearbyPages(2, 5, PrefetchSpan.NONE, direction = 1))
        assertTrue(nearbyPages(0, 0, PrefetchSpan.FULL, direction = 1).isEmpty())
    }

    @Test
    fun `tile recording budget is a fraction of the frame, smallest while writing`() {
        val writing = tileRecordBudgetNanos(16.67f, Interaction.WRITING)
        val moving = tileRecordBudgetNanos(16.67f, Interaction.MOVING)
        val idle = tileRecordBudgetNanos(16.67f, Interaction.IDLE)
        assertTrue(writing < moving)
        assertTrue(moving < idle)
        assertTrue(idle < 16_670_000L / 2)
        // Twice the refresh rate, half the time.
        assertEquals(idle / 2.0, tileRecordBudgetNanos(8.335f, Interaction.IDLE).toDouble(), idle * 0.01)
    }

    @Test
    fun `ink build width follows the budget, keeping a floor for the page on screen`() {
        assertEquals(6, inkBuildWidth(threads = 6, budget = 6, onScreen = false, floor = 2))
        assertEquals(1, inkBuildWidth(threads = 6, budget = 1, onScreen = false, floor = 2))
        assertEquals(2, inkBuildWidth(threads = 6, budget = 1, onScreen = true, floor = 2))
        assertEquals(2, inkBuildWidth(threads = 6, budget = 2, onScreen = false, floor = 2))
        assertEquals(1, inkBuildWidth(threads = 1, budget = 1, onScreen = true, floor = 2))
        // Composes with the slices helper the way StrokeBuilder uses it.
        val width = inkBuildWidth(threads = 6, budget = backgroundBudget(6, Interaction.MOVING, false, 0), onScreen = true, floor = INK_THROTTLED_SLICES)
        assertEquals(INK_THROTTLED_SLICES, inkBuildSlices(width, records = 10_000, throttled = width < 6))
        val idle = inkBuildWidth(threads = 6, budget = backgroundBudget(6, Interaction.IDLE, false, 0), onScreen = false, floor = INK_THROTTLED_SLICES)
        assertEquals(12, inkBuildSlices(idle, records = 10_000, throttled = idle < 6))
        val saving = inkBuildWidth(threads = 6, budget = backgroundBudget(6, Interaction.IDLE, true, 0), onScreen = false, floor = INK_THROTTLED_SLICES)
        assertEquals(2, inkBuildSlices(saving, records = 10_000, throttled = saving < 6))
    }
}
