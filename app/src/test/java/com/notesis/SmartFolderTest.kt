package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which notes a smart folder takes in. */
class SmartFolderTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1000 * day
    private fun note(tags: List<String> = emptyList(), age: Int = 0, favorite: Boolean = false, kind: NoteKind = NoteKind.INK) =
        NoteMeta("n", "Note", now - age * day, 1, 0, tags = tags, favorite = favorite, kind = kind)

    @Test
    fun `nothing set takes every note`() {
        assertTrue(SmartFolder("s", "All").matches(note(age = 400), now))
    }

    @Test
    fun `each rule set must hold, all at once`() {
        val folder = SmartFolder("s", "Recent maths", tag = "maths", days = 7, kind = NoteKind.INK)
        assertTrue(folder.matches(note(tags = listOf("maths"), age = 3), now))
        assertFalse(folder.matches(note(tags = listOf("maths"), age = 9), now))
        assertFalse(folder.matches(note(tags = listOf("art"), age = 3), now))
        assertFalse(folder.matches(note(tags = listOf("maths"), age = 3, kind = NoteKind.MARKDOWN), now))
    }

    @Test
    fun `favourites only`() {
        val folder = SmartFolder("s", "Pinned", favoritesOnly = true)
        assertTrue(folder.matches(note(favorite = true), now))
        assertFalse(folder.matches(note(favorite = false), now))
    }

    @Test
    fun `kept and read back the same`() {
        val folder = SmartFolder("s", "Week", tag = "work", days = 7, favoritesOnly = true, kind = NoteKind.MARKDOWN)
        org.junit.Assert.assertEquals(folder, SmartFolder.fromJson(folder.toJson()))
    }
}
