package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PenPresetTest {

    @Test
    fun `pen range keeps thin strokes easy to choose`() {
        val range = PenStore.widthRange(EditMode.PEN)

        assertTrue(range.start <= 0.25f)
        assertEquals(12f, range.endInclusive)
    }

    @Test
    fun `eraser range starts on contact and reaches a broad area`() {
        val range = PenStore.widthRange(EditMode.ERASE)

        assertEquals(1f, range.start)
        assertTrue(range.endInclusive >= 240f)
    }

    @Test
    fun `highlighter and mask share their expanded range`() {
        assertEquals(
            PenStore.widthRange(EditMode.HIGHLIGHTER),
            PenStore.widthRange(EditMode.MASK),
        )
        assertTrue(PenStore.widthRange(EditMode.HIGHLIGHTER).endInclusive >= 180f)
    }

    @Test
    fun `old custom ceiling cannot restore the oversized pen range`() {
        val pen = PenPreset(Tool.PEN, 0, 5f, maxWidth = 240f)
        assertEquals(12f, PenStore.widthRange(EditMode.PEN, pen).endInclusive)
        assertTrue(PenStore.widthCeilings(EditMode.PEN).all { it.second <= 12f })
    }

    @Test
    fun `nonfinite ceiling falls back to the valid range`() {
        val pen = PenPreset(Tool.PEN, 0, 5f, maxWidth = Float.NaN)
        assertEquals(PenStore.widthRange(EditMode.PEN), PenStore.widthRange(EditMode.PEN, pen))
    }
}
