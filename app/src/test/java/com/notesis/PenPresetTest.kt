package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PenPresetTest {

    @Test
    fun `pen range includes a hairline and a broad stroke`() {
        val range = PenStore.widthRange(EditMode.PEN)

        assertTrue(range.start <= 0.25f)
        assertTrue(range.endInclusive >= 80f)
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
}
