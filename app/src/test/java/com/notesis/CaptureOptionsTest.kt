package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOptionsTest {
    private val plenty = 1L shl 40

    @Test
    fun `multiples are of the page's own resolution`() {
        val plan = planCapture(810f, 540f, CaptureSize.X4, screenScale = 1.7f, budgetBytes = plenty, maxSide = 8192)
        assertEquals(3240, plan.width)
        assertEquals(2160, plan.height)
        assertEquals(CaptureLimit.NONE, plan.limit)
    }

    @Test
    fun `screen is the region as it was seen`() {
        val plan = planCapture(400f, 200f, CaptureSize.SCREEN, screenScale = 2.5f, budgetBytes = plenty, maxSide = 8192)
        assertEquals(1000, plan.width)
        assertEquals(500, plan.height)
    }

    @Test
    fun `long edge presets fit the longer side and keep the ratio`() {
        val plan = planCapture(683.3f, 300f, CaptureSize.EDGE_2048, 1f, plenty, 8192)
        assertEquals(2048, plan.width)
        assertEquals(899, plan.height)
        val tall = planCapture(300f, 900f, CaptureSize.EDGE_1080, 1f, plenty, 8192)
        assertEquals(1080, tall.height)
        assertEquals(360, tall.width)
    }

    @Test
    fun `the largest side is capped and said so`() {
        val plan = planCapture(2000f, 1000f, CaptureSize.X4, 1f, plenty, 4096)
        assertEquals(4096, plan.width)
        assertEquals(2048, plan.height)
        assertEquals(CaptureLimit.SIDE, plan.limit)
    }

    @Test
    fun `a PDF page is capped at what the PDF renders`() {
        val plan = planCapture(2000f, 1000f, CaptureSize.X4, 1f, plenty, 8192, pdfMaxSide = 4096)
        assertEquals(4096, plan.width)
        assertEquals(CaptureLimit.PDF, plan.limit)
    }

    @Test
    fun `memory shrinks both sides alike`() {
        // 4000 x 4000 x 4 bytes against a quarter of that: half each side.
        val plan = planCapture(1000f, 1000f, CaptureSize.X4, 1f, budgetBytes = 16_000_000L, maxSide = 8192)
        assertEquals(CaptureLimit.MEMORY, plan.limit)
        assertEquals(plan.width, plan.height)
        assertTrue(plan.width.toLong() * plan.height * 4 <= 16_000_000L)
        assertTrue(plan.width >= 1990)
    }

    @Test
    fun `options survive being stored and garbage does not crash`() {
        val options = CaptureOptions(CaptureSize.X3, CaptureFormat.JPEG, 85)
        assertEquals(options, CaptureOptions.decode(options.encode()))
        assertEquals(CaptureOptions(), CaptureOptions.decode(null))
        assertEquals(CaptureOptions(), CaptureOptions.decode("nonsense"))
    }

    @Test
    fun `nothing held back says nothing`() {
        assertNull(captureLimitMessage(CaptureLimit.NONE, 4096))
    }
}
