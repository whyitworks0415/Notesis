package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedReviewTest {
    @Test fun `a never studied mask is due at once`() {
        assertTrue(maskDue(MaskRecord(), today = 20_000L))
    }

    @Test fun `right answers double the gap up to the cap`() {
        var record = MaskRecord()
        val gaps = (0 until 8).map { day ->
            record = answered(record, right = true, today = 20_000L + day)
            record.interval
        }
        assertEquals(listOf(1, 2, 4, 8, 16, 32, MAX_REVIEW_INTERVAL, MAX_REVIEW_INTERVAL), gaps)
        assertEquals(8, record.right)
        assertFalse(maskDue(record, today = record.due - 1))
        assertTrue(maskDue(record, today = record.due))
    }

    @Test fun `a wrong answer brings it back today and starts over`() {
        var record = answered(MaskRecord(), right = true, today = 100L)
        record = answered(record, right = true, today = 101L)
        record = answered(record, right = false, today = 103L)
        assertEquals(0, record.interval)
        assertEquals(103L, record.due)
        assertEquals(MaskRecord.RESULT_WRONG, record.last)
        assertTrue(maskDue(record, today = 103L))
        assertEquals(1, answered(record, right = true, today = 103L).interval)
    }
}
