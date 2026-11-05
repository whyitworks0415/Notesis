package com.notesis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which notes a smart folder takes in. */
class SmartFolderTest {
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1000 * day
    private fun note(
        id: String = "n", tags: List<String> = emptyList(), ageHours: Int = 0,
        favorite: Boolean = false, kind: NoteKind = NoteKind.INK,
    ) = NoteMeta(id, "Note", now - ageHours * hour, 1, 0, tags = tags, favorite = favorite, kind = kind)

    @Test
    fun `nothing set takes every note`() {
        assertTrue(SmartFolder("s", "All").matches(note(ageHours = 400 * 24), now))
    }

    @Test
    fun `each rule set must hold, all at once`() {
        val folder = SmartFolder("s", "Recent maths", tag = "maths", hours = 7 * 24, kind = NoteKind.INK)
        assertTrue(folder.matches(note(tags = listOf("maths"), ageHours = 3 * 24), now))
        assertFalse(folder.matches(note(tags = listOf("maths"), ageHours = 9 * 24), now))
        assertFalse(folder.matches(note(tags = listOf("art"), ageHours = 3 * 24), now))
        assertFalse(folder.matches(note(tags = listOf("maths"), ageHours = 3 * 24, kind = NoteKind.MARKDOWN), now))
    }

    @Test
    fun `an age of hours`() {
        val folder = SmartFolder("s", "Just now", hours = 3)
        assertTrue(folder.matches(note(ageHours = 2), now))
        assertFalse(folder.matches(note(ageHours = 4), now))
    }

    @Test
    fun `favourites only`() {
        val folder = SmartFolder("s", "Pinned", favoritesOnly = true)
        assertTrue(folder.matches(note(favorite = true), now))
        assertFalse(folder.matches(note(favorite = false), now))
    }

    @Test
    fun `only the last few opened`() {
        val opened = listOf("c", "b", "a")
        val folder = SmartFolder("s", "Lately", recentlyOpened = 2)
        assertTrue(folder.matches(note(id = "c"), now, opened))
        assertTrue(folder.matches(note(id = "b"), now, opened))
        assertFalse(folder.matches(note(id = "a"), now, opened))
        assertFalse(folder.matches(note(id = "z"), now, opened))
    }

    @Test
    fun `opening a note puts it first and keeps twenty`() {
        assertEquals(listOf("b", "a", "c"), withOpened(listOf("a", "b", "c"), "b"))
        val full = (1..20).map { "n$it" }
        val after = withOpened(full, "new")
        assertEquals(20, after.size)
        assertEquals("new", after.first())
        assertFalse("n20" in after)
    }

    @Test
    fun `ages read as hours, then days`() {
        assertEquals("1 hour", SmartFolder.ageLabel(1))
        assertEquals("12 hours", SmartFolder.ageLabel(12))
        assertEquals("1 day", SmartFolder.ageLabel(24))
        assertEquals("30 days", SmartFolder.ageLabel(720))
        assertEquals(0, SmartFolder.ageStop(1))
        assertEquals(SmartFolder.AGE_STOPS.size - 1, SmartFolder.ageStop(10_000))
    }

    @Test
    fun `kept and read back the same`() {
        val folder = SmartFolder("s", "Week", tag = "work", hours = 6, favoritesOnly = true,
            kind = NoteKind.MARKDOWN, recentlyOpened = 12)
        assertEquals(folder, SmartFolder.fromJson(folder.toJson()))
    }

    @Test
    fun `a folder kept in days by 0_90_0 reads as hours`() {
        val old = JSONObject().put("id", "s").put("name", "Old").put("days", 7).put("favorites", false)
        assertEquals(7 * 24, SmartFolder.fromJson(old)?.hours)
    }
}
