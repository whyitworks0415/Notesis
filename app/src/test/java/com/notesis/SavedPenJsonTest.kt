package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedPenJsonTest {
    @Test fun aPenComesBackAsItWasSaved() {
        val pencil = PenStore.DEFAULTS.getValue(EditMode.PENCIL).copy(
            colorArgb = 0xFF336699.toInt(), width = 12f, nib = Tool.PENCIL, density = 8, stabilizer = 30,
        )
        assertEquals(pencil, penFromJson(penToJson(pencil), PenStore.DEFAULTS.getValue(EditMode.PENCIL), EditMode.PENCIL))
        val fountain = PenStore.DEFAULTS.getValue(EditMode.PEN).copy(nib = Tool.FOUNTAIN, nibAngle = -20, leadMs = 6)
        assertEquals(fountain, penFromJson(penToJson(fountain), PenStore.DEFAULTS.getValue(EditMode.PEN), EditMode.PEN))
    }

    @Test fun aDensityOutOfRangeIsHeldInside() {
        val json = penToJson(PenStore.DEFAULTS.getValue(EditMode.PENCIL)).put("density", 40)
        assertEquals(GRAPHITE_DENSITY_MAX, penFromJson(json, PenStore.DEFAULTS.getValue(EditMode.PENCIL), EditMode.PENCIL).density)
    }
}
