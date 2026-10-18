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

    @Test
    fun `a chosen nib wins over pressure, and only for the pen`() {
        assertEquals(Tool.CALLIGRAPHY, PenPreset(Tool.PEN, 0, 5f, pressure = true, nib = Tool.CALLIGRAPHY).drawingTool())
        assertEquals(Tool.PRESSURE_PEN, PenPreset(Tool.PEN, 0, 5f, pressure = true).drawingTool())
        assertEquals(Tool.HIGHLIGHTER, PenPreset(Tool.HIGHLIGHTER, 0, 5f, nib = Tool.OIL).drawingTool())
    }

    @Test
    fun `the pencil draws with its chosen look, and graphite for anything else`() {
        for ((look, _) in PENCIL_NIBS) {
            assertEquals(look, PenPreset(Tool.PENCIL, 0, 5f, nib = look).drawingTool())
        }
        // A pencil saved before it had looks carries the pen's default nib.
        assertEquals(Tool.PENCIL, PenPreset(Tool.PENCIL, 0, 5f).drawingTool())
        assertEquals(Tool.PENCIL, PenPreset(Tool.PENCIL, 0, 5f, nib = Tool.CALLIGRAPHY).drawingTool())
    }

    @Test
    fun `the pencil is back with its own defaults and a broad range`() {
        val pencil = PenStore.DEFAULTS.getValue(EditMode.PENCIL)
        assertEquals(Tool.PENCIL, pencil.tool)
        assertTrue(pencil.width in PenStore.widthRange(EditMode.PENCIL))
        assertTrue(PenStore.widthRange(EditMode.PENCIL).endInclusive > PenStore.widthRange(EditMode.PEN).endInclusive)
        assertTrue(nibsFor(Tool.PENCIL).any { it.first == pencil.drawingTool() })
    }

    @Test
    fun `only the pencil highlighter and shapes may be see-through`() {
        assertTrue(allowsOpacity(EditMode.PENCIL))
        assertTrue(allowsOpacity(EditMode.HIGHLIGHTER))
        assertTrue(!allowsOpacity(EditMode.PEN))
        assertTrue(!allowsOpacity(EditMode.MASK))
    }

    @Test
    fun `each tool carries its own handling, following the display by default`() {
        val pen = PenStore.DEFAULTS.getValue(EditMode.PEN)
        assertEquals(LEAD_AUTO, pen.leadMs)
        assertEquals(0, pen.stabilizer)
        assertEquals(0, pen.linePattern)
        val pencil = PenStore.DEFAULTS.getValue(EditMode.PENCIL).copy(leadMs = 3, stabilizer = 40)
        // Changing one tool's hand leaves the others alone.
        assertEquals(LEAD_AUTO, PenStore.DEFAULTS.getValue(EditMode.PEN).leadMs)
        assertEquals(3, pencil.leadMs)
        assertTrue(MAX_LEAD_MS == 12)
    }

    @Test
    fun `pressure eraser starts at the set size and only grows`() {
        org.junit.Assert.assertEquals(1f, pressureEraserScale(0f), 0f)
        org.junit.Assert.assertEquals(PRESSURE_ERASER_MAX_SCALE, pressureEraserScale(1f), 0f)
        org.junit.Assert.assertEquals(1.75f, pressureEraserScale(0.5f), 1e-6f)
    }
}
