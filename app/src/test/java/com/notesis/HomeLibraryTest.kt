package com.notesis

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLibraryTest {
    @Test fun `a folder's key never reads as a note's`() {
        val key = folderKey("Physics")
        assertTrue(isFolderKey(key))
        assertEquals("Physics", folderOfKey(key))
        assertFalse(isFolderKey("3f2a-note-id"))
    }

    @Test fun `the tile under a finger is found in the grid's own coordinates`() {
        val tiles = LibraryTiles()
        tiles.origin = Offset(0f, 100f)
        tiles.bounds["a"] = Rect(0f, 100f, 200f, 300f)
        tiles.bounds[folderKey("b")] = Rect(220f, 100f, 420f, 300f)
        assertEquals("a", tiles.keyAt(Offset(10f, 10f)))
        assertEquals(folderKey("b"), tiles.keyAt(Offset(300f, 150f)))
        assertNull(tiles.keyAt(Offset(210f, 10f)))
    }

    @Test fun `font scale is followed up to the cap and held there`() {
        val normal = Density(3f, 1.15f)
        assertSame(normal, cappedFontScale(normal))
        val huge = cappedFontScale(Density(3f, 2f))
        assertEquals(MAX_FONT_SCALE, huge.fontScale, 0f)
        assertEquals(3f, huge.density, 0f)
    }
}
