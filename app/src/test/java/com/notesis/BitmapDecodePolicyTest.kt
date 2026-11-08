package com.notesis

import org.junit.Assert.*
import org.junit.Test

class BitmapDecodePolicyTest {
    @Test fun `ordinary pictures keep their resolution when they fit`() {
        assertEquals(1, previewSampleSize(2048, 2048, 4096, 24L * 1024 * 1024))
    }

    @Test fun `large paper fits the note budget and a photo fits a small reference pane`() {
        assertEquals(2, previewSampleSize(4096, 4096, 4096, 24L * 1024 * 1024))
        assertEquals(2, previewSampleSize(2048, 2048, 4096, 4L * 1024 * 1024))
    }

    @Test fun `portrait panoramas and odd sizes fit both limits`() {
        for ((w, h) in listOf(101 to 50001, 50001 to 101, 4097 to 4097, 1 to 99999)) {
            val sample = previewSampleSize(w, h, 4096, 8L * 1024 * 1024)
            val width = (w.toLong() + sample - 1) / sample
            val height = (h.toLong() + sample - 1) / sample
            assertTrue(sample > 0 && sample and (sample - 1) == 0)
            assertTrue(width <= 4096 && height <= 4096)
            assertTrue(width * height * 4 <= 8L * 1024 * 1024)
        }
    }

    @Test fun `invalid dimensions and extreme headers cannot overflow or loop forever`() {
        assertEquals(1, previewSampleSize(-1, -1, 4096, 1024))
        assertTrue(previewSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 4096, 1024) > 0)
    }
}
