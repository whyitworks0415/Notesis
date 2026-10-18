package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageToneTest {
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): FloatArray = FloatArray(3) { row ->
        (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]).coerceIn(0f, 255f)
    }

    @Test
    fun `normal has no filter`() = assertNull(pageToneMatrix(PAGE_TONE_NORMAL))

    @Test
    fun `night turns white paper dark and black ink light`() {
        val m = pageToneMatrix(PAGE_TONE_NIGHT)!!
        val paper = apply(m, 255f, 255f, 255f)
        val ink = apply(m, 0f, 0f, 0f)
        paper.forEach { assertEquals(22f, it, 0.5f) }
        ink.forEach { assertEquals(22f + 255f * 0.82f, it, 0.5f) }
    }

    @Test
    fun `night keeps red reddish`() {
        val (r, g, b) = apply(pageToneMatrix(PAGE_TONE_NIGHT)!!, 255f, 0f, 0f).toList()
        assertTrue(r > g + 40f && r > b + 40f)
    }

    @Test
    fun `sepia keeps black and warms white`() {
        val m = pageToneMatrix(PAGE_TONE_SEPIA)!!
        apply(m, 0f, 0f, 0f).forEach { assertEquals(0f, it, 0f) }
        val (r, g, b) = apply(m, 255f, 255f, 255f).toList()
        assertTrue(r > g && g > b)
    }
}
