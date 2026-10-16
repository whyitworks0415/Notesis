package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPredictionPolicyTest {
    @Test
    fun `prediction waits for three stable movement vectors`() {
        val policy = InkPredictionPolicy()
        policy.reset(0f, 0f, 0L)
        assertEquals(0, policy.add(4f, 0f, 4L, 9))
        assertEquals(0, policy.add(8f, 0f, 8L, 9))
        assertEquals(9, policy.add(12f, 0f, 12L, 9))
    }

    @Test
    fun `slow movement disables prediction and speed selects shorter leads`() {
        val slow = InkPredictionPolicy()
        slow.reset(0f, 0f, 0L)
        slow.add(1f, 0f, 10L, 9)
        slow.add(2f, 0f, 20L, 9)
        assertEquals(0, slow.add(3f, 0f, 30L, 9))

        val medium = InkPredictionPolicy()
        medium.reset(0f, 0f, 0L)
        medium.add(2f, 0f, 8L, 9)
        medium.add(4f, 0f, 16L, 9)
        assertEquals(4, medium.add(6f, 0f, 24L, 9))

        val quick = InkPredictionPolicy()
        quick.reset(0f, 0f, 0L)
        quick.add(4f, 0f, 8L, 9)
        quick.add(8f, 0f, 16L, 9)
        assertEquals(6, quick.add(12f, 0f, 24L, 9))
    }

    @Test
    fun `fixed maximum lead is never exceeded`() {
        val policy = InkPredictionPolicy()
        policy.reset(0f, 0f, 0L)
        policy.add(5f, 0f, 4L, 4)
        policy.add(10f, 0f, 8L, 4)
        assertEquals(4, policy.add(15f, 0f, 12L, 4))
    }

    @Test
    fun `corner suppresses prediction until direction stabilizes again`() {
        val policy = InkPredictionPolicy()
        policy.reset(0f, 0f, 0L)
        policy.add(4f, 0f, 4L, 9)
        policy.add(8f, 0f, 8L, 9)
        assertEquals(9, policy.add(12f, 0f, 12L, 9))
        assertEquals(0, policy.add(12f, 4f, 16L, 9))
        assertEquals(0, policy.add(12f, 8f, 20L, 9))
        assertEquals(0, policy.add(12f, 12f, 24L, 9))
        assertEquals(9, policy.add(12f, 16f, 28L, 9))
    }

    @Test
    fun `soft head is reserved for fast six or nine millisecond prediction`() {
        val policy = InkPredictionPolicy()
        policy.reset(0f, 0f, 0L)
        policy.add(5f, 0f, 4L, 9)
        policy.add(10f, 0f, 8L, 9)
        val lead = policy.add(15f, 0f, 12L, 9)
        assertTrue(policy.shouldShowSoftHead(lead))
        assertFalse(policy.shouldShowSoftHead(4))
    }

    @Test
    fun `automatic lead follows refresh rate and fixed modes remain comparable`() {
        // Half a frame on a front buffer: 4ms at 120Hz, as on a Tab S8 Ultra.
        assertEquals(8, predictionLeadForRefresh(0, 60f))
        assertEquals(6, predictionLeadForRefresh(0, 90f))
        assertEquals(4, predictionLeadForRefresh(0, 120f))
        assertEquals(4, predictionLeadForRefresh(4, 60f))
        assertEquals(9, predictionLeadForRefresh(9, 120f))
    }

    @Test
    fun `vsync bound ink on a 60Hz panel predicts further only in automatic mode`() {
        assertEquals(12, predictionLeadForRefresh(0, 60f, vsyncBoundInk = true))
        assertEquals(6, predictionLeadForRefresh(0, 120f, vsyncBoundInk = true))
        // A device measured to show ink a frame late predicts further, within 12ms.
        assertEquals(8, predictionLeadForRefresh(0, 120f, measuredLatencyMs = 16.0))
        assertEquals(12, predictionLeadForRefresh(0, 60f, measuredLatencyMs = 60.0))
        assertEquals(9, predictionLeadForRefresh(9, 60f, vsyncBoundInk = true))

        val fast = InkPredictionPolicy()
        fast.reset(0f, 0f, 0L)
        fast.add(5f, 0f, 4L, 12)
        fast.add(10f, 0f, 8L, 12)
        assertEquals(12, fast.add(15f, 0f, 12L, 12))

        val medium = InkPredictionPolicy()
        medium.reset(0f, 0f, 0L)
        medium.add(4f, 0f, 8L, 12)
        medium.add(8f, 0f, 16L, 12)
        assertEquals(8, medium.add(12f, 0f, 24L, 12))
    }

    @Test
    fun `any fixed lead from one to twelve milliseconds is used as set`() {
        for (lead in 1..12) {
            assertEquals(lead, predictionLeadForRefresh(lead, 60f))
            assertEquals(lead, predictionLeadForRefresh(lead, 120f, vsyncBoundInk = true))
            assertEquals(lead, sanitizePredictionLead(lead))
        }
        assertEquals(8, predictionLeadForRefresh(0, 60f))
        assertEquals(9, sanitizePredictionLead(40))
    }

    @Test
    fun `a pen braking to a stop gets no prediction`() {
        val policy = InkPredictionPolicy()
        policy.reset(0f, 0f, 0L)
        var x = 0f
        for (t in 1..6) { x += 5f; policy.add(x, 0f, t * 4L, 9) }
        // A tenth of the stroke's speed: stopping.
        assertEquals(0, policy.add(x + 0.5f, 0f, 28L, 9))
    }

    @Test
    fun `speed thresholds follow the panel density`() {
        // 3.2px per 8ms is medium speed on the reference panel...
        val reference = InkPredictionPolicy()
        reference.reset(0f, 0f, 0L)
        reference.add(3.2f, 0f, 8L, 9)
        reference.add(6.4f, 0f, 16L, 9)
        assertEquals(6, reference.add(9.6f, 0f, 24L, 9))
        // ...but on a panel with twice the pixels per millimetre the pen is
        // moving half as fast, and gets the short head.
        val dense = InkPredictionPolicy().apply { pxPerMm = 2f * 287f / 25.4f }
        dense.reset(0f, 0f, 0L)
        dense.add(3.2f, 0f, 8L, 9)
        dense.add(6.4f, 0f, 16L, 9)
        assertEquals(4, dense.add(9.6f, 0f, 24L, 9))
    }

    @Test
    fun `stabilized strokes keep a shorter head`() {
        assertEquals(9, stabilizedLeadMs(9, 0))
        assertEquals(4, stabilizedLeadMs(9, 100))
        assertEquals(0, stabilizedLeadMs(0, 50))
    }

    @Test
    fun `compatible ink predicts by where the sample fell in the frame`() {
        val period = 16_666_667L
        val vsync = 1_000_000_000L
        // Just after a vsync: almost two periods to wait, so the full horizon.
        assertEquals(12, vsyncAlignedLeadMs(12, vsync + 1_000_000L, vsync, 60f))
        // Just before the next: a little over one period.
        assertEquals(8, vsyncAlignedLeadMs(12, vsync + period - 1_000_000L, vsync, 60f))
        // No frame to go by: the fixed horizon.
        assertEquals(12, vsyncAlignedLeadMs(12, vsync, 0L, 60f))
        assertEquals(12, vsyncAlignedLeadMs(12, vsync + 20 * period, vsync, 60f))
    }
}
