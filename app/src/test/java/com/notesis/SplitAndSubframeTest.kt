package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitAndSubframeTest {
    @Test
    fun `the seam moves with the drag and stops short of either edge`() {
        assertEquals(0.6f, movedSplit(0.5f, 100f, 1000f), 1e-5f)
        assertEquals(SplitMode.MIN_SHARE, movedSplit(0.5f, -900f, 1000f), 0f)
        assertEquals(1f - SplitMode.MIN_SHARE, movedSplit(0.5f, 900f, 1000f), 0f)
        assertEquals(0.5f, movedSplit(0.5f, 10f, 0f), 0f)
    }

    @Test
    fun `sub-frame lead follows the newest samples`() {
        val track = SubframeTrack()
        // 480 Hz: a sample every ~2 ms, moving 1 px per ms to the right.
        for (i in 0..10) track.add(i * 2f, 5f, i * 2L)
        val lead = track.lead(8f)!!
        assertEquals(8f, lead[0], 1e-3f)
        assertEquals(0f, lead[1], 1e-3f)
    }

    @Test
    fun `no lead without movement or history`() {
        val track = SubframeTrack()
        assertNull(track.lead(8f))
        track.add(1f, 1f, 0L)
        assertNull(track.lead(8f))
        track.add(1f, 1f, 100L)
        // A pause longer than the window is not a velocity.
        assertNull(track.lead(8f))
    }

    @Test
    fun `layers hold edits to the chosen layer`() {
        val layers = NoteLayers()
        val second = layers.add()
        assertTrue(layers.editable(NoteLayers.BASE))
        layers.active = second.id
        assertFalse(layers.editable(NoteLayers.BASE))
        assertTrue(layers.editable(second.id))
        assertEquals(second.id, layers.target)
        layers.active = NoteLayers.ALL
        layers.writeTo = NoteLayers.BASE
        assertEquals(NoteLayers.BASE, layers.target)
        assertEquals(1, layers.rank(second.id))
        assertEquals(0, layers.rank(99))
    }
}
