package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTest {
    private val names = listOf("Maths", "Maths homework", "Physics")

    @Test
    fun `tab completes commands, their second word and options`() {
        assertEquals(Completion("page ", 5, emptyList()), completeLine("pa", 2, names))
        assertEquals("page add ", completeLine("page ad", 7, names).line)
        assertEquals("notesis page add ", completeLine("notesis page ad", 15, names).line)
        val note = completeLine("note re", 7, names)
        assertEquals("note re", note.line)
        assertEquals(listOf("read", "rename", "restore"), note.options)
        assertEquals("page add Maths --after ", completeLine("page add Maths --af", 19, names).line)
        assertEquals("clear ", completeLine("cle", 3, names).line)
    }

    @Test
    fun `tab completes note names, quoting them when they have spaces`() {
        assertEquals("note read Physics ", completeLine("note read ph", 12, names).line)
        val both = completeLine("note read ma", 12, names)
        assertEquals("note read Maths", both.line)
        assertEquals(listOf("Maths", "Maths homework"), both.options)
        assertEquals("note read \"Maths homework\" ", completeLine("note read \"Maths h", 18, names).line)
        // Only the note's own place is completed with names.
        assertTrue(completeLine("page mv Maths 3", 15, names).options.isEmpty())
        assertTrue(completeLine("undo ", 5, names).options.isEmpty())
    }

    @Test
    fun `history goes up and down and keeps the line being written`() {
        val history = TerminalHistory()
        assertNull(history.up("x"))
        history.add("notes list")
        history.add("toc show Maths")
        history.add("toc show Maths")
        assertEquals(2, history.all.size)
        assertEquals("toc show Maths", history.up("draft"))
        assertEquals("notes list", history.up("toc show Maths"))
        assertEquals("notes list", history.up("notes list"))
        assertEquals("toc show Maths", history.down())
        assertEquals("draft", history.down())
        assertNull(history.down())
    }

    @Test
    fun `answers print as indented results, errors with their usage`() {
        val ok = terminalOutput("{\"ok\":true,\"result\":{\"file\":\"/data/x.pdf\"},\"undo\":\"notesis undo takes back: rename\"}")
        assertEquals(TerminalTone.OUTPUT, ok[0].tone)
        assertEquals("file: /data/x.pdf", ok[0].text)
        assertTrue(terminalOutput("{\"ok\":true,\"result\":{\"file\":\"/data/x.pdf\"}}", asJson = true)[0].text.contains("\"file\": \"/data/x.pdf\""))
        assertEquals(TerminalTone.HINT, ok[1].tone)
        val bad = terminalOutput("{\"ok\":false,\"error\":\"No note\",\"usage\":\"note read NOTE\",\"line\":2,\"of\":3,\"undone\":\"Taken back.\"}")
        assertEquals("error: No note", bad[0].text)
        assertEquals("on line 2. Taken back.", bad[1].text)
        assertEquals("  note read NOTE", bad[2].text)
        assertTrue(terminalChangedNotes("{\"ok\":true,\"result\":{},\"undo\":\"x\"}"))
        assertTrue(terminalChangedNotes("{\"ok\":true,\"result\":{\"undone\":\"x\"}}"))
        assertFalse(terminalChangedNotes("{\"ok\":true,\"result\":{\"count\":1}}"))
    }

    @Test
    fun `lines, built-ins and long answers`() {
        assertEquals(listOf("page add A", "toc set A 1 B"), terminalLines(" page add A \n\n# note\ntoc set A 1 B\n"))
        assertEquals(TerminalBuiltin.CLEAR, terminalBuiltin(" clear "))
        assertNull(terminalBuiltin("notes list"))
        assertEquals("1\n2\n… 3 more lines", clipLines("1\n2\n3\n4\n5", 2))
        assertEquals("1\n2", clipLines("1\n2", 2))
    }

    @Test
    fun `answers read as text - notes, hits, outlines and plain results`() {
        val list = readable(org.json.JSONObject("{\"total\":5,\"count\":1,\"notes\":[{\"id\":\"abcdef123456\",\"title\":\"Maths\"," +
            "\"folder\":\"School\",\"tags\":[\"exam\"],\"pages\":3}],\"next\":\"--offset 1\"}"))
        assertEquals("abcdef12  Maths   School  #exam  3 p\n1 of 5   more: --offset 1", list)
        val hits = readable(org.json.JSONObject("{\"query\":\"x\",\"count\":1,\"hits\":[{\"id\":\"a\",\"title\":\"Maths\",\"page\":2,\"snippet\":\"x = 3\"}]}"))
        assertEquals("Maths p.2:  x = 3\n1 found", hits)
        val toc = readable(org.json.JSONObject("{\"note\":\"Maths\",\"toc\":[{\"page\":1,\"title\":\"Start\",\"level\":0},{\"page\":4,\"title\":\"Part\",\"level\":1}]}"))
        assertEquals("Maths\np.1     Start\np.4       Part", toc)
        assertEquals("id: n1\ntags: exam, maths", readable(org.json.JSONObject("{\"id\":\"n1\",\"tags\":[\"exam\",\"maths\"]}")))
        // A picture is said to be there, not printed.
        val picture = terminalOutput("{\"ok\":true,\"result\":{\"page\":1,\"image\":{\"mime\":\"image/jpeg\",\"data\":\"${"A".repeat(4096)}\"}}}")
        assertTrue(picture[0].text, picture[0].text.contains("picture, 3 KB image/jpeg"))
        // A failure lists the notes it may have meant.
        val missed = terminalOutput("{\"ok\":false,\"error\":\"No note\",\"code\":\"NOT_FOUND\",\"candidates\":[\"n1 Maths\"]}")
        assertEquals("  n1 Maths", missed.last().text)
    }

    @Test
    fun `history kept from before comes back under what was typed since`() {
        val history = TerminalHistory(limit = 3)
        history.add("status")
        history.restore(listOf("notes list", "help", "notes list"))
        assertEquals(listOf("help", "notes list", "status"), history.all)
        assertEquals("status", history.up(""))
        assertEquals(TerminalBuiltin.JSON, terminalBuiltin("json"))
    }
}
