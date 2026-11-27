package com.notesis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteCommandsTest {

    private fun json(text: String) = JSONObject(text)
    private fun ids(memory: MemoryNotes, note: String) = memory.pages.getValue(note).map { it.id.substringAfter('-') }

    @Test
    fun `words keep quoted spaces and escapes`() {
        assertEquals(listOf("text", "add", "Maths", "1", "a b c"), commandWords("text add Maths 1 \"a b c\""))
        assertEquals(listOf("it's", "x y"), commandWords("it\\'s 'x y'"))
        assertEquals(listOf("empty", ""), commandWords("empty \"\""))
        assertNull(commandWords("open \"quote"))
        // A new line and a tab stay written as \n and \t, for the text commands to make.
        assertEquals(listOf("a\\nb", "c\\td"), commandWords("\"a\\nb\" c\\td"))
    }

    @Test
    fun `svg paths become polylines`() {
        val lines = svgPolylines("M 10 10 L 100 10 L 100 50 Z m 0 100 h 20 v 20")
        assertEquals(2, lines.size)
        assertEquals(4, lines[0].size)
        assertEquals(10f, lines[0].last()[0]); assertEquals(10f, lines[0].last()[1])
        // The relative move starts from where the closed line ended.
        assertEquals(10f, lines[1][0][0]); assertEquals(110f, lines[1][0][1])
        assertEquals(30f, lines[1][2][0]); assertEquals(130f, lines[1][2][1])
        val curve = svgPolylines("M0,0 C 0,100 100,100 100,0").single()
        assertTrue(curve.size > 4)
        assertEquals(100f, curve.last()[0], 0.001f)
        assertEquals(75f, curve.maxOf { it[1] }, 1f)
        assertEquals(listOf(listOf(1f, 2f, 3f, 4f), listOf(5f, 6f, 7f, 8f)),
            pointLines("1,2 3,4; 5,6 7,8").map { line -> line.flatMap { it.toList() } })
    }

    @Test
    fun `notes are found by id, title, id start or part of the title`() {
        val notes = listOf(NoteMeta("abcd1234", "Maths", 0, 1, 0), NoteMeta("efgh5678", "Maths homework", 0, 1, 0))
        assertEquals("abcd1234", findNote("maths", notes).id)
        assertEquals("efgh5678", findNote("efgh", notes).id)
        assertEquals("efgh5678", findNote("homework", notes).id)
        val ambiguous = runCatching { findNote("ath", notes) }.exceptionOrNull()
        assertTrue(ambiguous is CommandError && ambiguous.message!!.contains("2 notes"))
    }

    @Test
    fun `list and read`() {
        val memory = MemoryNotes().apply { add("n1", "Maths", folder = "School/Maths", tags = listOf("exam")); add("n2", "Diary") }
        val commands = NoteCommands(memory)
        val listed = json(commands.run("notesis notes list --folder School"))
        assertTrue(listed.getBoolean("ok"))
        assertEquals(1, listed.getJSONObject("result").getInt("count"))
        assertEquals(2, json(commands.run("notes list")).getJSONObject("result").getInt("count"))
        val read = json(commands.run("note read Maths --pages 1")).getJSONObject("result")
        val page = read.getJSONArray("pages").getJSONObject(0)
        assertEquals(1, page.getInt("page"))
    }

    @Test
    fun `wrong commands say how they are written`() {
        val commands = NoteCommands(MemoryNotes().apply { add("n1", "Maths") })
        val unknown = json(commands.run("page explode Maths"))
        assertFalse(unknown.getBoolean("ok"))
        assertEquals("UNKNOWN_COMMAND", unknown.getString("code"))
        val option = json(commands.run("page add Maths --colour red"))
        assertTrue(option.getString("error").contains("--colour"))
        assertEquals(COMMAND_USAGE["page add"], option.getString("usage"))
        assertFalse(json(commands.run("page rm Maths 9")).getBoolean("ok"))
    }

    @Test
    fun `pages are added, moved and removed, and undo puts them back`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        val commands = NoteCommands(memory)
        assertTrue(json(commands.run("page add Maths --after 1 --count 2")).getBoolean("ok"))
        assertEquals(5, memory.pages.getValue("n1").size)
        assertEquals("p1", ids(memory, "n1")[0]); assertEquals("p2", ids(memory, "n1")[3])
        commands.run("undo")
        assertEquals(listOf("p1", "p2", "p3"), ids(memory, "n1"))
        commands.run("page mv Maths 3 1")
        assertEquals(listOf("p3", "p1", "p2"), ids(memory, "n1"))
        commands.run("page rm Maths 1-2")
        assertEquals(listOf("p2"), ids(memory, "n1"))
        assertFalse(json(commands.run("page rm Maths 1")).getBoolean("ok"))
        commands.run("undo"); commands.run("undo")
        assertEquals(listOf("p1", "p2", "p3"), ids(memory, "n1"))
        assertFalse(json(commands.run("undo")).getBoolean("ok"))
    }

    @Test
    fun `the outline is set, shown and cleared`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        val commands = NoteCommands(memory)
        commands.run("toc set Maths 1 Introduction")
        commands.run("toc set Maths 3 \"Chapter two\" --level 1")
        val toc = json(commands.run("toc show Maths")).getJSONObject("result").getJSONArray("toc")
        assertEquals(2, toc.length())
        assertEquals("Chapter two", toc.getJSONObject(1).getString("title"))
        assertEquals(1, toc.getJSONObject(1).getInt("level"))
        assertEquals(2, json(commands.run("toc clear Maths")).getJSONObject("result").getInt("cleared"))
        assertTrue(memory.pages.getValue("n1").all { it.tocTitle == null })
        commands.run("undo")
        assertEquals("Introduction", memory.pages.getValue("n1")[0].tocTitle)
    }

    @Test
    fun `text and ink go on the page, and erase takes ink off`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        val commands = NoteCommands(memory)
        val text = json(commands.run("text add Maths 2 \"x = 3\" --y 300"))
        assertTrue(text.toString(), text.getBoolean("ok"))
        val image = memory.pages.getValue("n1")[1].images.single()
        assertEquals("x = 3", image.textContent?.text)
        assertEquals(300f, image.y)
        assertEquals(2, json(commands.run("ink add Maths 1 --path \"M 10 10 L 50 50 M 60 60 L 90 90\" --color blue"))
            .getJSONObject("result").getInt("strokes"))
        assertFalse(json(commands.run("ink add Maths 1 --points \"0,0 5000,5000\"")).getBoolean("ok"))
        assertEquals(2, json(commands.run("ink erase Maths 1 --all")).getJSONObject("result").getInt("erased"))
        commands.run("undo")
        assertEquals(2, memory.ink["n1-p1"])
    }

    @Test
    fun `text takes its look from the options, and text style changes only the look`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        val commands = NoteCommands(memory)
        val made = json(commands.run("text add Maths 1 \"# Sum\\n\$x^2\$\" --md --size 40 --align center --line 1.5 --letter 0.05 " +
            "--font serif --bold --bg #FFF59D --border red --border-width 3 --para 10 --indent 1 --pad 12"))
        assertTrue(made.toString(), made.getBoolean("ok"))
        val box = memory.pages.getValue("n1")[0].images.single().textContent!!
        assertEquals("# Sum\n\$x^2\$", box.text)
        assertTrue(box.markdown)
        assertEquals(40f, box.size)
        assertEquals(TextBoxContent.ALIGN_CENTER, box.align)
        assertEquals(1.5f, box.lineSpacing)
        assertEquals(0.05f, box.letterSpacing)
        assertEquals("serif", box.font)
        assertTrue(box.bold)
        assertEquals(0xFFFFF59D.toInt(), box.background)
        assertEquals(0xFFD32F2F.toInt(), box.border)
        assertEquals(3f, box.borderWidth)
        assertEquals(10f, box.paragraphSpacing)
        assertEquals(1f, box.indent)
        assertEquals(12f, box.padding)

        val listed = json(commands.run("text list Maths")).getJSONObject("result").getJSONArray("pages").getJSONObject(0)
            .getJSONArray("boxes").getJSONObject(0)
        assertEquals("center", listed.getJSONObject("style").getString("align"))
        assertEquals("#FFF59D", listed.getJSONObject("style").getString("bg"))

        val styled = json(commands.run("text style Maths 1 1 --align right --no-bold --bg none --plain"))
        assertTrue(styled.toString(), styled.getBoolean("ok"))
        val after = memory.pages.getValue("n1")[0].images.single().textContent!!
        assertEquals(box.text, after.text)
        assertEquals(TextBoxContent.ALIGN_END, after.align)
        assertFalse(after.bold)
        assertFalse(after.markdown)
        assertEquals(0, after.background)
        assertEquals(40f, after.size)
        // Nothing to change, an unknown font, or both ways at once: refused, and the box stays.
        assertFalse(json(commands.run("text style Maths 1 1")).getBoolean("ok"))
        assertFalse(json(commands.run("text style Maths 1 1 --font comic")).getBoolean("ok"))
        assertFalse(json(commands.run("text style Maths 1 1 --md --plain")).getBoolean("ok"))
        assertFalse(json(commands.run("text style Maths 1 1 --size 500")).getBoolean("ok"))
        // New text keeps the look.
        commands.run("text edit Maths 1 1 \"Other\" --size 20")
        val edited = memory.pages.getValue("n1")[0].images.single().textContent!!
        assertEquals("Other", edited.text)
        assertEquals(20f, edited.size)
        assertEquals(TextBoxContent.ALIGN_END, edited.align)
        commands.run("undo")
        assertEquals(box.text, memory.pages.getValue("n1")[0].images.single().textContent!!.text)
    }

    @Test
    fun `text goes beside the pages too, and is listed, changed and taken away there`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        val commands = NoteCommands(memory)
        val made = json(commands.run("text add Maths board \"Beside\" --near 2 --x 1300 --y 40 --size 28"))
        assertTrue(made.toString(), made.getBoolean("ok"))
        val image = memory.boards.getValue("n1").single()
        assertEquals("Beside", image.textContent?.text)
        assertEquals(28f, image.textContent?.size)
        val document = memory.load("n1")
        assertEquals(document.leftOf(1) + 1300f, image.x)
        assertEquals(document.topOf(1) + 40f, image.y)
        val listed = json(commands.run("text list Maths --pages board")).getJSONObject("result")
        assertEquals("Beside", listed.getJSONArray("board").getJSONObject(0).getString("text"))
        assertEquals(0, listed.getJSONArray("pages").length())
        assertTrue(json(commands.run("note read Maths")).getJSONObject("result").getJSONObject("board").has("text"))
        assertTrue(json(commands.run("text style Maths board 1 --italic")).getBoolean("ok"))
        assertTrue(memory.boards.getValue("n1").single().textContent!!.italic)
        assertTrue(json(commands.run("text edit Maths board 1 \"Moved on\"")).getBoolean("ok"))
        assertEquals("Moved on", memory.boards.getValue("n1").single().textContent?.text)
        assertTrue(json(commands.run("text rm Maths board 1")).getBoolean("ok"))
        assertTrue(memory.boards.getValue("n1").isEmpty())
        assertFalse(json(commands.run("text rm Maths board 1")).getBoolean("ok"))
        commands.run("undo")
        assertEquals(1, memory.boards.getValue("n1").size)
        // Each says plainly what it would do before it is allowed.
        assertEquals("Change how text box {1} beside the pages of “{0}” looks",
            describeCommand(commandWords("text style Maths board 1 --bold")!!, memory.notes()).template)
    }

    @Test
    fun `names, folders, tags and the trash, each undone`() {
        val memory = MemoryNotes().apply { add("n1", "Maths", tags = listOf("old")) }
        val commands = NoteCommands(memory)
        commands.run("note rename Maths Algebra notes")
        assertEquals("Algebra notes", memory.metas.getValue("n1").title)
        commands.run("note mv Algebra School/Maths")
        assertEquals("School/Maths", memory.metas.getValue("n1").folder)
        commands.run("note tag n1 +exam -old")
        assertEquals(listOf("exam"), memory.metas.getValue("n1").tags)
        commands.run("note trash n1")
        assertTrue("n1" in memory.trash)
        repeat(4) { assertTrue(json(commands.run("undo")).getBoolean("ok")) }
        assertEquals("Maths", memory.metas.getValue("n1").title)
        assertEquals("", memory.metas.getValue("n1").folder)
        assertEquals(listOf("old"), memory.metas.getValue("n1").tags)
        assertFalse("n1" in memory.trash)
    }

    @Test
    fun `a note open where it cannot be held is read but not changed`() {
        val memory = MemoryNotes().apply { add("n1", "Maths"); open = "n1"; live = false }
        val commands = NoteCommands(memory)
        assertTrue(json(commands.run("note read Maths")).getBoolean("ok"))
        val refused = json(commands.run("page add Maths"))
        assertFalse(refused.getBoolean("ok"))
        assertEquals("OPEN_ON_SCREEN", refused.getString("code"))
        assertEquals(0, memory.saves)
        assertEquals(0, commands.undoable)
    }

    @Test
    fun `a batch is all or nothing and undone as one`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        val commands = NoteCommands(memory)
        val failed = json(commands.runAll(listOf("page add Maths", "toc set Maths 1 Start", "page rm Maths 40")))
        assertFalse(failed.getBoolean("ok"))
        assertEquals(3, failed.getInt("line"))
        assertEquals(listOf("p1", "p2", "p3"), ids(memory, "n1"))
        assertNull(memory.pages.getValue("n1")[0].tocTitle)
        assertEquals(0, commands.undoable)

        val done = json(commands.runAll(listOf("page add Maths", "# a comment", "toc set Maths 1 Start", "note rename Maths Algebra")))
        assertTrue(done.getBoolean("ok"))
        assertEquals(3, done.getJSONArray("results").length())
        assertEquals(1, commands.undoable)
        commands.run("undo")
        assertEquals(listOf("p1", "p2", "p3"), ids(memory, "n1"))
        assertNull(memory.pages.getValue("n1")[0].tocTitle)
        assertEquals("Maths", memory.metas.getValue("n1").title)
    }

    @Test
    fun `markdown notes are read as text and have no pages`() {
        val memory = MemoryNotes().apply { add("m1", "Ideas", kind = NoteKind.MARKDOWN); texts["m1"] = "# Ideas" }
        val commands = NoteCommands(memory)
        assertEquals("# Ideas", json(commands.run("note read Ideas")).getJSONObject("result").getString("text"))
        assertFalse(json(commands.run("page add Ideas")).getBoolean("ok"))
        assertTrue(json(commands.run("export Ideas --format md")).getBoolean("ok"))
        // A Markdown note goes out as Markdown unless told otherwise, and only as that.
        assertEquals("md", json(commands.run("export Ideas")).getJSONObject("result").getString("format"))
        assertFalse(json(commands.run("export Ideas --format pdf")).getBoolean("ok"))
    }

    @Test
    fun `colours read as hex or a name`() {
        assertEquals(0xFF112233.toInt(), commandColor("#112233"))
        assertEquals(0x80112233.toInt(), commandColor("80112233"))
        assertEquals(0xFF1E5BD8.toInt(), commandColor("Blue"))
        assertTrue(runCatching { commandColor("#12") }.exceptionOrNull() is CommandError)
    }

    @Test
    fun `copies kept for undo are let go when undone, when a batch fails, and when too old`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        val commands = NoteCommands(memory)
        commands.run("page add Maths")
        assertEquals(0, memory.released)
        commands.run("undo")
        assertEquals(1, memory.released)
        // A batch that fails lets go of the copy of the change that failed and of the one it took back.
        commands.runAll(listOf("page add Maths", "page rm Maths 40"))
        assertEquals(3, memory.released)
        // Past the steps that can be taken back, the oldest copies go as the newest are made.
        val before = memory.released
        repeat(NoteCommands.UNDO_STEPS + 5) { commands.run("page add Maths") }
        assertEquals(5, memory.released - before)
    }

    // A fake page: each line 10 tall, a line breaking every 20 characters.
    private fun tall(text: String) = text.split('\n').sumOf { maxOf(1, (it.length + 19) / 20) } * 10f

    @Test
    fun `text that fits stays whole`() {
        assertEquals(listOf("a\nb"), splitToFit("a\nb", 100f, 100f, ::tall))
    }

    @Test
    fun `text taller than the room breaks between lines`() {
        val text = (1..9).joinToString("\n") { "line $it" }
        val parts = splitToFit(text, 30f, 40f, ::tall)
        assertEquals(listOf("line 1\nline 2\nline 3", "line 4\nline 5\nline 6\nline 7", "line 8\nline 9"), parts)
    }

    @Test
    fun `one long line breaks between words`() {
        val text = (1..20).joinToString(" ") { "word" }
        val parts = splitToFit(text, 20f, 20f, ::tall)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { tall(it) <= 20f })
        assertEquals(text, parts.joinToString(" "))
    }

    @Test
    fun `no room on the first page leaves it empty`() {
        assertEquals(listOf("", "a\nb"), splitToFit("a\nb", 5f, 100f, ::tall))
    }

    @Test
    fun `a line taller than a page still goes somewhere`() {
        assertEquals("x".repeat(100), splitToFit("x".repeat(100), 10f, 10f) { 50f }.joinToString(""))
    }
}
