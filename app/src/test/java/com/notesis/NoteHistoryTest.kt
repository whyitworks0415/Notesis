package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoteHistoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun note(files: File, id: String): File = File(files, "notes/$id").apply {
        File(this, "pages").mkdirs()
        File(this, "meta.json").writeText("""{"title":"one"}""")
        File(this, "pages/p1.bin").writeText("ink v1")
        File(this, "doc.pdf").writeText("pdf")
    }

    @Test fun `a version keeps the note's files but not its PDF`() {
        val files = temp.root
        note(files, "n")
        val history = NoteHistory(files)
        assertTrue(history.keep("n", now = 1_000L))
        val version = File(files, "history/n/1000")
        assertEquals("ink v1", File(version, "pages/p1.bin").readText())
        assertFalse(File(version, "doc.pdf").exists())
        assertEquals(listOf(1_000L), history.versions("n"))
    }

    @Test fun `nothing is kept again until the note changes and some time has passed`() {
        val files = temp.root
        val dir = note(files, "n")
        val history = NoteHistory(files)
        val first = System.currentTimeMillis()
        assertTrue(history.keep("n", now = first))
        // A moment later: too soon.
        File(dir, "pages/p1.bin").writeText("ink v2")
        assertFalse(history.keep("n", now = first + 1_000L))
        // Long after, but with nothing changed since the version was taken.
        File(dir, "pages/p1.bin").setLastModified(first - 10_000L)
        File(dir, "meta.json").setLastModified(first - 10_000L)
        assertFalse(history.keep("n", now = first + NoteHistory.MIN_GAP_MS + 1))
        // Changed after it, and long after: kept.
        File(dir, "pages/p1.bin").setLastModified(first + 5_000L)
        assertTrue(history.keep("n", now = first + NoteHistory.MIN_GAP_MS + 1))
    }

    @Test fun `restoring puts the old files back, keeps the PDF and keeps the present as a version`() {
        val files = temp.root
        val dir = note(files, "n")
        val history = NoteHistory(files)
        history.keep("n", now = 1_000L)
        File(dir, "pages/p1.bin").writeText("ink v2")
        File(dir, "pages/p2.bin").writeText("new page")
        assertTrue(history.restore("n", 1_000L))
        assertEquals("ink v1", File(dir, "pages/p1.bin").readText())
        assertFalse(File(dir, "pages/p2.bin").exists())
        assertEquals("pdf", File(dir, "doc.pdf").readText())
        val newest = history.versions("n").first()
        assertEquals("ink v2", File(files, "history/n/$newest/pages/p1.bin").readText())
    }

    @Test fun `only the newest versions of a note are kept`() {
        val files = temp.root
        note(files, "n")
        val history = NoteHistory(files)
        repeat(NoteHistory.KEEP_PER_NOTE + 3) { history.keep("n", now = 1_000L + it, force = true) }
        val kept = history.versions("n")
        assertEquals(NoteHistory.KEEP_PER_NOTE, kept.size)
        assertEquals(1_000L + NoteHistory.KEEP_PER_NOTE + 2, kept.first())
    }
}
