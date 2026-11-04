package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folders inside folders, kept as paths. */
class FolderPathsTest {

    @Test
    fun `a path is cleaned of spaces and empty names`() {
        assertEquals("School/Maths", normalizeFolderPath(" School / /Maths/ "))
        assertNull(normalizeFolderPath(" / "))
        assertEquals("Plain", normalizeFolderPath("Plain"))
    }

    @Test
    fun `parent, own name and child`() {
        assertEquals("School", folderParent("School/Maths"))
        assertEquals("", folderParent("School"))
        assertEquals("Maths", folderLeaf("School/Maths"))
        assertEquals("School/Maths", folderChild("School", "Maths"))
        assertEquals("Maths", folderChild("", "Maths"))
    }

    @Test
    fun `inside a folder means itself or below, not a name that only starts the same`() {
        assertTrue(isInFolder("School", "School"))
        assertTrue(isInFolder("School/Maths/Algebra", "School"))
        assertFalse(isInFolder("Schoolwork", "School"))
        assertFalse(isInFolder("", ""))
    }

    @Test
    fun `a folder with only folders in it is still listed`() {
        assertEquals(listOf("A", "A/B", "A/B/C", "D"), withAncestorFolders(listOf("A/B/C", "D", "A/B")))
    }

    @Test
    fun `children are the folders one level down`() {
        val all = listOf("A", "A/B", "A/B/C", "A/c", "D")
        assertEquals(listOf("A", "D"), folderChildren(all, ""))
        assertEquals(listOf("A/B", "A/c"), folderChildren(all, "A"))
        assertEquals(emptyList<String>(), folderChildren(all, "D"))
    }

    @Test
    fun `renaming moves what is inside along`() {
        assertEquals("X", movedFolderPath("A", "A", "X"))
        assertEquals("X/B/C", movedFolderPath("A/B/C", "A", "X"))
        assertEquals("AB", movedFolderPath("AB", "A", "X"))
    }
}
