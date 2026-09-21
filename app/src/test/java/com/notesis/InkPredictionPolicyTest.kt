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
        assertEquals(9, predictionLeadForRefresh(0, 60f))
        assertEquals(6, predictionLeadForRefresh(0, 120f))
        assertEquals(4, predictionLeadForRefresh(4, 60f))
        assertEquals(9, predictionLeadForRefresh(9, 120f))
    }
}
