package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NoteSearchTest {
    @Test
    fun `the snippet keeps the match where it can be emphasised`() {
        val body = "The quick brown fox\njumps over the lazy dog"
        val at = body.indexOf("jumps")
        val snippet = snippetAround(body, at, 5, radius = 6)
        assertEquals("jumps", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
        assertTrue(snippet.text.startsWith("…"))
        assertTrue(snippet.text.endsWith("…"))
        assertTrue('\n' !in snippet.text)
    }

    @Test
    fun `a match at the very start has no leading ellipsis`() {
        val snippet = snippetAround("Mitochondria are the powerhouse", 0, 12, radius = 4)
        assertEquals(0, snippet.matchStart)
        assertEquals("Mitochondria", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
    }

    @Test
    fun `matches come in page order, PDF before boxes before ink, case ignored`() {
        val texts = listOf(
            PageText("b", 1, SearchSource.INK, "cell wall"),
            PageText("b", 1, SearchSource.PDF, "The Cell and the cell"),
            PageText("a", 0, SearchSource.TEXT_BOX, "my cell note", InkBox(1f, 2f, 3f, 4f)),
        )
        val hits = searchPageTexts(texts, " CELL ")
        assertEquals(listOf(0, 1, 1, 1), hits.map { it.pageIndex })
        assertEquals(
            listOf(SearchSource.TEXT_BOX, SearchSource.PDF, SearchSource.PDF, SearchSource.INK),
            hits.map { it.source },
        )
        // The n-th match within its page's text, for finding it on a PDF page.
        assertEquals(listOf(0, 0, 1, 0), hits.map { it.occurrence })
        assertEquals(InkBox(1f, 2f, 3f, 4f), hits[0].box)
        for (hit in hits) {
            assertEquals("cell", hit.snippet.substring(hit.matchStart, hit.matchEnd).lowercase())
        }
    }

    @Test
    fun `a blank query finds nothing and the limit holds`() {
        val texts = listOf(PageText("a", 0, SearchSource.PDF, "a".repeat(50)))
        assertTrue(searchPageTexts(texts, "  ").isEmpty())
        assertEquals(10, searchPageTexts(texts, "a", limit = 10).size)
    }

    @Test
    fun `the text index rereads only what changed and forgets what went`() {
        val dir = Files.createTempDirectory("notesearch").toFile()
        try {
            File(dir, "p1.txt").writeText("pdf words")
            File(dir, "p1" + NoteStore.INK_INDEX).writeText("ink words")
            File(dir, "p1.bin").writeText("strokes, not text")
            val index = NoteTextIndex(dir)
            assertEquals(setOf("p1.txt", "p1" + NoteStore.INK_INDEX), index.refresh().keys)
            File(dir, "p1.txt").delete()
            assertEquals(setOf("p1" + NoteStore.INK_INDEX), index.refresh().keys)
        } finally {
            dir.deleteRecursively()
        }
    }
}
