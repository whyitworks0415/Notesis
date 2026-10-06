package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class PaletteColorTest {
    @Test fun fluorescentSwatchSetsOpacity() {
        assertEquals(0x66FFEB3B, paletteColorArgb(0xFF000000.toInt(), 0x66FFEB3B))
        assertEquals(0x66FFEB3B, paletteColorArgb(0x99FF0000.toInt(), 0x66FFEB3B))
    }
    @Test fun opaqueSwatchKeepsExplicitToolOpacity() {
        assertEquals(0x661976D2, paletteColorArgb(0x66FFEB3B, 0xFF1976D2.toInt()))
        assertEquals(0xFF1976D2.toInt(), paletteColorArgb(0xFF000000.toInt(), 0xFF1976D2.toInt()))
    }
}
