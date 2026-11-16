package com.notesis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The commands added for agents (Todo.md, Q1-Q5): small answers, PDF notes, more to change, and what they say when they fail. */
class AgentCommandsTest {

    private fun json(text: String) = JSONObject(text)
    private fun result(commands: NoteCommands, line: String): JSONObject =
        json(commands.run(line)).also { assertTrue(it.toString(), it.getBoolean("ok")) }.getJSONObject("result")
    private fun failure(commands: NoteCommands, line: String): JSONObject =
        json(commands.run(line)).also { assertFalse(it.toString(), it.getBoolean("ok")) }

    // ---- finding notes, and failing helpfully ----

    @Test
    fun `titles are matched without regard to case, spaces or how Hangul is composed`() {
        val decomposed = java.text.Normalizer.normalize("수학 노트", java.text.Normalizer.Form.NFD)
        val notes = listOf(NoteMeta("a1b2c3d4", decomposed, 0, 1, 0), NoteMeta("e5f6g7h8", "Physics Lab", 0, 1, 0))
        assertEquals("a1b2c3d4", findNote("수학노트", notes).id)
        assertEquals("e5f6g7h8", findNote("physicslab", notes).id)
    }

    @Test
    fun `a note not found or not alone says which were perhaps meant`() {
        val commands = NoteCommands(MemoryNotes().apply { add("n1", "Maths homework"); add("n2", "Maths exam"); add("n3", "Diary") })
        val missing = failure(commands, "note read Mahts")
        assertEquals("NOT_FOUND", missing.getString("code"))
        assertTrue(missing.getJSONArray("candidates").toString(), missing.getJSONArray("candidates").toString().contains("Maths"))
        val ambiguous = failure(commands, "note read Maths")
        assertEquals("AMBIGUOUS", ambiguous.getString("code"))
        assertEquals(2, ambiguous.getJSONArray("candidates").length())
        assertEquals("BAD_PAGE", failure(commands, "page rm Diary 9").getString("code"))
        assertEquals("UNKNOWN_COMMAND", failure(commands, "note explode Diary").getString("code"))
    }

    @Test
    fun `a change to a note on screen holds the screen, and it reads the note again after`() {
        val memory = MemoryNotes().apply { add("n1", "Maths"); add("n2", "Diary"); open = "n1" }
        val commands = NoteCommands(memory)
        assertTrue(json(commands.runAll(listOf("page add Maths", "toc set Maths 1 Start", "page add Diary"))).getBoolean("ok"))
        // Held once for the whole batch, before anything was changed, and let go after with word to read it again.
        assertEquals(listOf("hold n1", "let go n1 changed"), memory.screen)
        assertEquals(4, memory.pages.getValue("n1").size)
        memory.screen.clear()
        commands.run("undo")
        assertEquals(listOf("hold n1", "let go n1 changed"), memory.screen)
        assertEquals(3, memory.pages.getValue("n1").size)
        // A batch that fails is taken back, which is what the screen shows still: it is not read again.
        memory.screen.clear()
        assertFalse(json(commands.runAll(listOf("page add Maths", "page rm Maths 40"))).getBoolean("ok"))
        assertEquals(listOf("hold n1", "let go n1"), memory.screen)
        // Reading holds nothing.
        memory.screen.clear()
        commands.run("note read Maths")
        assertTrue(memory.screen.isEmpty())
    }

    @Test
    fun `filing a note on screen needs no hold, and one on screen is not put in the trash`() {
        val memory = MemoryNotes().apply { add("n1", "Maths"); open = "n1"; live = false }
        val commands = NoteCommands(memory)
        assertTrue(json(commands.run("note mv Maths School")).getBoolean("ok"))
        assertTrue(json(commands.run("note tag Maths exam")).getBoolean("ok"))
        assertTrue(json(commands.run("note fav Maths")).getBoolean("ok"))
        assertTrue(memory.screen.isEmpty())
        assertEquals("OPEN_ON_SCREEN", failure(commands, "note trash Maths").getString("code"))
        assertFalse("n1" in memory.trash)
    }

    @Test
    fun `preview says what a line would do and does nothing`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        val commands = NoteCommands(memory)
        val said = result(commands, "preview page add Maths --count 2")
        assertEquals("Add 2 pages to “Maths”", said.getString("does"))
        assertTrue(said.getBoolean("changes"))
        assertEquals(3, memory.pages.getValue("n1").size)
        assertFalse(result(commands, "preview notes list").getBoolean("changes"))
        assertEquals(0, commands.undoable)
    }

    @Test
    fun `status says what is there and what is open`() {
        val memory = MemoryNotes().apply { add("n1", "Maths"); add("m1", "Ideas", kind = NoteKind.MARKDOWN); open = "n1" }
        val status = result(NoteCommands(memory), "status")
        assertEquals(2, status.getInt("notes"))
        assertEquals(1, status.getInt("markdown"))
        assertEquals("n1 Maths", status.getJSONArray("openOnScreen").getString(0))
        assertEquals("test", status.getString("version"))
    }

    // ---- small answers (Q1) ----

    @Test
    fun `the list comes in parts, newest first, briefly when asked`() {
        val memory = MemoryNotes().apply { for (i in 1..7) add("n$i", "Note $i", modified = i.toLong()) }
        val commands = NoteCommands(memory)
        val first = result(commands, "notes list --limit 3 --brief")
        assertEquals(7, first.getInt("total"))
        assertEquals(3, first.getInt("count"))
        assertEquals("Note 7", first.getJSONArray("notes").getJSONObject(0).getString("title"))
        assertFalse(first.getJSONArray("notes").getJSONObject(0).has("tags"))
        assertEquals("--offset 3", first.getString("next"))
        val last = result(commands, "notes list --limit 3 --offset 6")
        assertEquals(1, last.getInt("count"))
        assertFalse(last.has("next"))
    }

    @Test
    fun `a long note is read in parts, each part saying how to get the next`() {
        val memory = MemoryNotes().apply { add("n1", "Book", pageCount = 30) }
        for (page in memory.pages.getValue("n1")) page.images += PageImage("t", 0f, 0f, 10f, 10f, TextBoxContent("x".repeat(100)))
        val commands = NoteCommands(memory)
        val part = result(commands, "note read Book --max-chars 1000")
        assertTrue(part.getBoolean("truncated"))
        val got = part.getJSONArray("pages").length()
        assertTrue(got in 1 until 30)
        assertFalse(part.getJSONArray("pages").getJSONObject(0).has("size"))
        val rest = result(commands, part.getString("next").replace("--pages", "--max-chars 200000 --pages"))
        assertEquals(30 - got, rest.getJSONArray("pages").length())
        assertEquals(got + 1, rest.getJSONArray("pages").getJSONObject(0).getInt("page"))
        assertTrue(result(commands, "note read Book --pages 1 --sizes").getJSONArray("pages").getJSONObject(0).has("size"))
    }

    @Test
    fun `text only leaves out pages with nothing to read, the outline leaves out the pages`() {
        val memory = MemoryNotes().apply { add("n1", "Maths", pageCount = 4) }
        memory.pages.getValue("n1")[2].images += PageImage("t", 0f, 0f, 10f, 10f, TextBoxContent("x = 3"))
        memory.pages.getValue("n1")[3].tocTitle = "End"
        val commands = NoteCommands(memory)
        val pages = result(commands, "note read Maths --text-only").getJSONArray("pages")
        // Page 1 has handwriting read for search, page 3 a text box, page 4 an outline entry.
        assertEquals(listOf(1, 3, 4), (0 until pages.length()).map { pages.getJSONObject(it).getInt("page") })
        assertEquals("x = 3", pages.getJSONObject(1).getJSONArray("text").getString(0))
        val outline = result(commands, "note read Maths --outline")
        assertFalse(outline.has("pages") && outline.get("pages") is org.json.JSONArray)
        assertEquals("End", outline.getJSONArray("toc").getJSONObject(0).getString("title"))
    }

    @Test
    fun `a long Markdown note is read from where the last part stopped`() {
        val memory = MemoryNotes().apply { add("m1", "Ideas", kind = NoteKind.MARKDOWN); texts["m1"] = "# Top\n" + "a".repeat(1500) + "\n## Under\nend" }
        val commands = NoteCommands(memory)
        val part = result(commands, "note read Ideas --max-chars 600")
        assertEquals(600, part.getString("text").length)
        assertEquals("note read m1 --from 600", part.getString("next"))
        val more = result(commands, "note read Ideas --from 600 --max-chars 200000")
        assertTrue(more.getString("text").endsWith("end"))
        assertFalse(more.has("truncated"))
        val outline = result(commands, "note read Ideas --outline").getJSONArray("outline")
        assertEquals("Under", outline.getJSONObject(1).getString("title"))
        assertEquals(2, outline.getJSONObject(1).getInt("level"))
    }

    // ---- PDF notes (Q5) ----

    private fun book(): MemoryNotes = MemoryNotes().apply {
        // Two pages of front matter in the PDF, then pages printed 1, 2, 3...
        val texts = listOf("Title page", "Contents\nOne 1\nTwo 3") + (1..6).map { "Chapter text $it\n\n$it" }
        addPdf("b1", "Book", texts, before = 1, outline = listOf(PdfMark("One", 2, 0), PdfMark("One a", 2, 1), PdfMark("Two", 4, 0), PdfMark("Gone", 40, 0)))
    }

    @Test
    fun `pdf info tells bookmarks, text and how printed pages sit`() {
        val info = result(NoteCommands(book()), "pdf info Book")
        assertEquals(9, info.getInt("pages"))
        assertEquals(8, info.getInt("pdfPages"))
        assertEquals("yes", info.getString("textLayer"))
        assertEquals(4, info.getInt("bookmarks"))
        assertEquals(2, info.getInt("printedOffset"))
        assertTrue(info.getJSONArray("next").getString(0).startsWith("toc import-pdf"))
        assertEquals("NO_PDF", failure(NoteCommands(MemoryNotes().apply { add("n1", "Plain") }), "pdf info Plain").getString("code"))
    }

    @Test
    fun `the PDF's bookmarks become the outline, on the pages that show them, and undo takes it back`() {
        val memory = book()
        val commands = NoteCommands(memory)
        val shown = result(commands, "toc show Book --pdf").getJSONArray("pdfBookmarks")
        assertEquals(4, shown.getJSONObject(0).getInt("page"))
        assertFalse(shown.getJSONObject(3).has("page"))
        val imported = result(commands, "toc import-pdf Book")
        assertEquals(2, imported.getInt("set"))
        // "One a" shares a page with "One", and "Gone" points past the PDF.
        assertEquals(2, imported.getInt("skipped"))
        val pages = memory.pages.getValue("b1")
        assertEquals("One", pages[3].tocTitle)
        assertEquals("Two", pages[5].tocTitle)
        commands.run("undo")
        assertTrue(memory.pages.getValue("b1").all { it.tocTitle == null })
        val none = MemoryNotes().apply { addPdf("p1", "Scan", listOf("", "")) }
        assertEquals("NO_OUTLINE", failure(NoteCommands(none), "toc import-pdf Scan").getString("code"))
    }

    @Test
    fun `an entry can be set by the PDF's page number`() {
        val memory = book()
        result(NoteCommands(memory), "toc set Book 5 \"Chapter three\" --pdf")
        // PDF page 5 is the note's page 6: the note has a page of its own first.
        assertEquals("Chapter three", memory.pages.getValue("b1")[5].tocTitle)
    }

    @Test
    fun `pdf text comes in parts and pdf find says where words are`() {
        val commands = NoteCommands(book())
        val text = result(commands, "pdf text Book --max-chars 500 --pages 2-3")
        assertEquals("Contents\nOne 1\nTwo 3", text.getJSONArray("pages").getJSONObject(1).getString("text"))
        assertEquals(2, text.getJSONArray("pages").getJSONObject(1).getInt("pdfPage"))
        val small = result(commands, "pdf text Book --max-chars 500")
        assertFalse(small.has("truncated"))
        val found = result(commands, "pdf find Book chapter text 4")
        assertEquals(1, found.getInt("count"))
        assertEquals(7, found.getJSONArray("hits").getJSONObject(0).getInt("page"))
        val scan = MemoryNotes().apply { addPdf("p1", "Scan", listOf("", "")) }
        assertTrue(result(NoteCommands(scan), "pdf text Scan").has("hint"))
    }

    @Test
    fun `a page is given as a picture`() {
        val image = result(NoteCommands(book()), "page image Book 2 --width 400")
        assertEquals("image/jpeg", image.getJSONObject("image").getString("mime"))
        assertEquals(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)), image.getJSONObject("image").getString("data"))
        assertFalse(json(NoteCommands(book()).run("page image Book 2 --width 5000")).getBoolean("ok"))
    }

    @Test
    fun `printed page numbers are found at the top or the foot of a page`() {
        assertEquals(12, printedPageOffset(listOf(12 to "1\nIntro", 13 to "text\n2", 14 to "- 3 -\ntext", 15 to "Page 4")))
        assertNull(printedPageOffset(listOf(0 to "no numbers", 1 to "here either")))
        // Two pages that disagree say nothing.
        assertNull(printedPageOffset(listOf(5 to "1", 9 to "7")))
    }

    // ---- making, copying and filing notes (Q4) ----

    @Test
    fun `notes are made, copied and joined, and undo sends the new one to the trash`() {
        val memory = MemoryNotes().apply { add("n1", "Maths"); add("n2", "Physics") }
        val commands = NoteCommands(memory)
        val made = result(commands, "note new Algebra --folder School --pages 2")
        assertEquals(2, memory.pages.getValue(made.getString("id")).size)
        assertEquals("School", memory.metas.getValue(made.getString("id")).folder)
        val md = result(commands, "note new-md Ideas --text \"# Ideas\\nfirst\"")
        assertEquals("# Ideas\nfirst", memory.texts.getValue(md.getString("id")))
        val copy = result(commands, "note dup Maths")
        assertEquals("Maths copy", memory.metas.getValue(copy.getString("id")).title)
        val merged = result(commands, "note merge Maths Physics --title Science")
        assertEquals(6, memory.pages.getValue(merged.getString("id")).size)
        assertFalse(json(commands.run("note merge Maths --title X")).getBoolean("ok"))
        repeat(4) { commands.run("undo") }
        for (id in listOf(made, md, copy, merged).map { it.getString("id") }) assertTrue(id, id in memory.trash)
        assertTrue(failure(commands, "note new Bad --paper gold").getString("error").contains("lined"))
    }

    @Test
    fun `favourites, labels and folders are set and undone`() {
        val memory = MemoryNotes().apply { add("n1", "Maths", folder = "School/Maths"); add("n2", "Diary") }
        val commands = NoteCommands(memory)
        result(commands, "note fav Maths")
        assertTrue(memory.metas.getValue("n1").favorite)
        result(commands, "note label Maths red")
        assertTrue(memory.metas.getValue("n1").label != 0)
        result(commands, "folder new Home/Recipes")
        assertTrue("Home/Recipes" in memory.folders())
        assertFalse(json(commands.run("folder new Home/Recipes")).getBoolean("ok"))
        result(commands, "folder rename School Uni")
        assertEquals("Uni/Maths", memory.metas.getValue("n1").folder)
        val folders = result(commands, "folder list").getJSONArray("folders")
        assertTrue(folders.toString().contains("Uni/Maths"))
        repeat(4) { commands.run("undo") }
        assertEquals("School/Maths", memory.metas.getValue("n1").folder)
        assertFalse("Home/Recipes" in memory.folders())
        assertEquals(0, memory.metas.getValue("n1").label)
        assertFalse(memory.metas.getValue("n1").favorite)
        // A note on screen in the folder moves with it: its saves read its folder back.
        memory.open = "n1"
        assertTrue(json(commands.run("folder rename School Uni")).getBoolean("ok"))
        assertEquals("Uni/Maths", memory.metas.getValue("n1").folder)
    }

    @Test
    fun `tags are counted`() {
        val memory = MemoryNotes().apply { add("n1", "A", tags = listOf("exam", "maths")); add("n2", "B", tags = listOf("exam")) }
        val tags = result(NoteCommands(memory), "tags list").getJSONArray("tags")
        assertEquals("exam", tags.getJSONObject(0).getString("tag"))
        assertEquals(2, tags.getJSONObject(0).getInt("notes"))
    }

    @Test
    fun `pages are copied, given paper and bookmarked, each undone`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        memory.pages.getValue("n1")[0].images += PageImage("pic", 0f, 0f, 10f, 10f)
        val commands = NoteCommands(memory)
        result(commands, "page dup Maths 1")
        val pages = memory.pages.getValue("n1")
        assertEquals(4, pages.size)
        assertEquals("copy-pic", pages[1].images.single().id)
        result(commands, "page paper Maths 1-2 grid")
        assertEquals(PageBackground.GRID, memory.pages.getValue("n1")[1].background)
        result(commands, "page bookmark Maths 3")
        assertTrue(memory.pages.getValue("n1")[2].bookmarked)
        repeat(3) { commands.run("undo") }
        assertEquals(3, memory.pages.getValue("n1").size)
        assertEquals(PageBackground.BLANK, memory.pages.getValue("n1")[0].background)
        val pdf = MemoryNotes().apply { addPdf("p1", "Book", listOf("a")) }
        assertEquals("NOT_SUPPORTED", failure(NoteCommands(pdf), "page paper Book 1 lined").getString("code"))
    }

    @Test
    fun `text boxes are listed, changed and taken off`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        val commands = NoteCommands(memory)
        commands.run("text add Maths 1 \"x = 3\" --y 100")
        commands.run("text add Maths 1 \"y = 4\" --y 300")
        val boxes = result(commands, "text list Maths").getJSONArray("pages").getJSONObject(0).getJSONArray("boxes")
        assertEquals(2, boxes.length())
        assertEquals("y = 4", boxes.getJSONObject(1).getString("text"))
        result(commands, "text edit Maths 1 2 \"y = 5\"")
        val edited = memory.pages.getValue("n1")[0].images[1]
        assertEquals("y = 5", edited.textContent?.text)
        assertEquals(300f, edited.y)
        result(commands, "text rm Maths 1 1")
        assertEquals(listOf("y = 5"), memory.pages.getValue("n1")[0].images.map { it.textContent?.text })
        commands.run("undo"); commands.run("undo")
        assertEquals(listOf("x = 3", "y = 4"), memory.pages.getValue("n1")[0].images.map { it.textContent?.text })
        assertEquals("NOT_FOUND", failure(commands, "text rm Maths 1 9").getString("code"))
    }

    // ---- Markdown notes ----

    @Test
    fun `Markdown notes are written, added to under a heading and changed, each undone`() {
        val memory = MemoryNotes().apply {
            add("m1", "Plan", kind = NoteKind.MARKDOWN); texts["m1"] = "# Plan\nintro\n\n## Todo\n- a\n\n## Done\n- b\n"
            add("m2", "Other", kind = NoteKind.MARKDOWN); texts["m2"] = "See [[Plan]] and [[Missing|that]]"
            add("n1", "Ink")
        }
        val commands = NoteCommands(memory)
        result(commands, "md append Plan \"- c\" --under Todo")
        // Into the list under the heading, not after a blank line that would start another.
        assertEquals("# Plan\nintro\n\n## Todo\n- a\n- c\n\n## Done\n- b\n", memory.texts["m1"])
        assertEquals("NOT_FOUND", failure(commands, "md append Plan x --under Nowhere").getString("code"))
        assertEquals("AMBIGUOUS", failure(commands, "md replace Plan \"- \" \"* \"").getString("code"))
        result(commands, "md replace Plan \"- \" \"* \" --all")
        assertFalse(memory.texts.getValue("m1").contains("- "))
        result(commands, "md write Plan \"# New\\nall new\"")
        assertEquals("# New\nall new", memory.texts["m1"])
        repeat(3) { commands.run("undo") }
        assertEquals("# Plan\nintro\n\n## Todo\n- a\n\n## Done\n- b\n", memory.texts["m1"])
        val links = result(commands, "md links Other")
        assertEquals("m1", links.getJSONArray("links").getJSONObject(0).getString("id"))
        assertTrue(links.getJSONArray("links").getJSONObject(1).getBoolean("missing"))
        assertEquals("m2", result(commands, "md links Plan").getJSONArray("backlinks").getJSONObject(0).getString("id"))
        assertEquals("NOT_SUPPORTED", failure(commands, "md write Ink hello").getString("code"))
    }

    @Test
    fun `headings, links and sections are read as Markdown has them`() {
        val text = "# A\n```\n# not a heading\n```\n## B ##\ntext\n# C"
        assertEquals(listOf("A", "B", "C"), markdownHeadings(text).map { it.title })
        assertEquals(listOf(1, 2, 1), markdownHeadings(text).map { it.level })
        assertEquals(listOf("One", "Two"), wikiLinks("[[One]] [[Two#part]] [[One|again]]"))
        assertEquals("# A\nx\n\nadded\n\n# B\n", appendUnderHeading("# A\nx\n\n# B\n", "a", "added"))
        assertEquals("# A\nx\n\nadded\n", appendUnderHeading("# A\nx", "A", "added"))
        assertNull(appendUnderHeading("# A", "B", "x"))
    }

    // ---- sharing, search, small helpers ----

    @Test
    fun `a file put out for Termux is a change undo takes back`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        val commands = NoteCommands(memory)
        val answer = json(commands.run("export Maths --to shared"))
        assertTrue(answer.getBoolean("ok"))
        assertEquals("/shared/Maths.pdf", answer.getJSONObject("result").getString("file"))
        assertTrue(answer.has("undo"))
        commands.run("undo")
        assertTrue(memory.shared.isEmpty())
        // Without --to the file stays in the app, and nothing is changed.
        assertFalse(json(commands.run("export Maths")).has("undo"))
    }

    @Test
    fun `search finds notes by what is in them`() {
        val memory = MemoryNotes().apply { add("m1", "Ideas", kind = NoteKind.MARKDOWN); texts["m1"] = "the quadratic formula"; add("n1", "Diary") }
        val found = result(NoteCommands(memory), "search quadratic")
        assertEquals(1, found.getInt("count"))
        assertEquals("m1", found.getJSONArray("hits").getJSONObject(0).getString("id"))
    }

    @Test
    fun `page lists are written as runs`() {
        assertEquals("1-3,6,8-9", pageList(listOf(0, 1, 2, 5, 7, 8)))
        assertEquals("4", pageList(listOf(3)))
        assertEquals("", pageList(emptyList()))
        assertEquals("\"two words\"", commandQuote("two words"))
        assertEquals("one", commandQuote("one"))
    }

    @Test
    fun `every command's help names its options as the commands read them`() {
        // Each --option in a usage line is one its command takes; an option the help shows and the command refuses would mislead an agent.
        val memory = MemoryNotes().apply { add("n1", "Maths"); add("m1", "Ideas", kind = NoteKind.MARKDOWN); texts["m1"] = "# A" }
        val commands = NoteCommands(memory)
        for ((name, usage) in COMMAND_USAGE) {
            for (option in Regex("--([a-z-]+)").findAll(usage).map { it.groupValues[1] }.distinct()) {
                val answer = json(commands.run("$name Maths --$option 1"))
                if (!answer.getBoolean("ok")) assertFalse("$name --$option", answer.getString("error").startsWith("There is no option"))
                commands.run("undo")
            }
        }
    }

    // ---- fewer tokens ----

    @Test
    fun `ids are cut as short as they stay apart`() {
        val ids = shortIds(listOf("3f9c2a71-aaaa", "3f9c2a71-bbbb", "77aa0000-cccc", "ab"))
        assertEquals("3f9c2a71-a", ids["3f9c2a71-aaaa"])
        assertEquals("3f9c2a71-b", ids["3f9c2a71-bbbb"])
        assertEquals("77aa0000", ids["77aa0000-cccc"])
        assertEquals("ab", ids["ab"])
        // A cut id names its note.
        val notes = listOf(NoteMeta("77aa0000-cccc-dddd", "Maths", 0, 1, 0))
        assertEquals("Maths", findNote(shortIds(notes.map { it.id }).values.single(), notes).title)
    }

    @Test
    fun `a note read before and not changed since is said to be unchanged`() {
        val memory = MemoryNotes().apply { add("n1", "Maths", modified = 500L); add("n2", "Diary", modified = 900L) }
        val commands = NoteCommands(memory)
        val same = result(commands, "note read Maths --since 500")
        assertTrue(same.getBoolean("unchanged"))
        assertFalse(same.has("pages"))
        assertTrue(result(commands, "note read Maths --since 400").has("pages"))
        val newer = result(commands, "notes list --since 600").getJSONArray("notes")
        assertEquals(1, newer.length())
        assertEquals("Diary", newer.getJSONObject(0).getString("title"))
    }

    @Test
    fun `empty fields are left out of a note`() {
        val note = result(NoteCommands(MemoryNotes().apply { add("n1", "Maths") }), "notes list").getJSONArray("notes").getJSONObject(0)
        assertFalse(note.has("folder"))
        assertFalse(note.has("tags"))
        assertFalse(note.has("kind"))
        assertEquals(3, note.getInt("pages"))
    }

    @Test
    fun `help comes whole, by command, or as recipes`() {
        val all = agentHelp()
        for (usage in COMMAND_USAGE.values) assertTrue(usage, all.contains(usage))
        assertFalse(all.contains("Outline a PDF note"))
        val pdf = agentHelp("pdf")
        assertTrue(pdf.contains("pdf text") && !pdf.contains("note read"))
        assertTrue(agentHelp("recipes").contains("Outline a PDF note"))
        assertTrue(agentHelp("nothing").startsWith("No command"))
    }

    // ---- the agent sidebar's requests ----

    @Test
    fun `a request goes to Termux as fixed words, the owner's words on its input`() {
        val words = agentAskArguments(AgentCli.CLAUDE, carryOn = true)
        assertEquals(listOf("login", "debian", "--", "bash", ASK_HELPER, "claude", "continue"), words)
        assertTrue(words.none { it.contains(' ') })
        assertEquals(listOf("login", "debian", "--", "bash", ASK_HELPER, "gemini"), agentAskArguments(AgentCli.GEMINI, carryOn = false))
        // An earlier conversation picked from the list goes by its id, as one word; anything else is refused.
        assertEquals(listOf("login", "debian", "--", "bash", ASK_HELPER, "codex", "resume", "019a-77f2.b_c"),
            agentAskArguments(AgentCli.CODEX, carryOn = true, resumeId = "019a-77f2.b_c"))
        assertTrue(runCatching { agentAskArguments(AgentCli.CLAUDE, false, "a b; rm -rf ~") }.isFailure)
        assertEquals(listOf("login", "debian", "--", "bash", ASK_HELPER, "sessions", "opencode"), agentSessionsArguments(AgentCli.OPENCODE))
        assertEquals("[Notesis: the note on screen is \"Maths\" (id 3f9c2a71).]\nAdd an outline", askText(" Add an outline ", "Maths", "3f9c2a71-aaaa"))
        assertEquals("Hi", askText("Hi", null, null))
    }

    @Test
    fun `Termux's answers are put in words`() {
        assertEquals("Done." to false, askReply("Done.\n", "", 0, 0, ""))
        // Termux reports a command it ran as -1 (Android's RESULT_OK): the agent's answer, not an error.
        assertEquals("Put 12 entries in the outline." to false, askReply("Put 12 entries in the outline.\n", "", 0, TERMUX_OK, ""))
        assertTrue(askReply("", "", -1, 1, "Failed to execute").second)
        assertTrue(askReply("", "", -1, 2, "Termux RUN_COMMAND: allow-external-apps is not true").first.contains("setup script"))
        assertTrue(askReply("", "bash: /root/.notesis/ask: No such file or directory", 127, 0, "").first.contains("helper"))
        assertEquals("boom" to true, askReply("", "boom", 1, 0, ""))
    }

    @Test
    fun `an agent's Markdown answer reads as it is meant to`() {
        val shown = markdownPreview("## Done\n- Put **12** entries in the outline\n- `toc show` to check", maxChars = Int.MAX_VALUE)
        assertEquals("Done\n• Put 12 entries in the outline\n• toc show to check", shown.text)
        assertTrue(shown.spans.any { it.kind == MdKind.HEADING })
        assertTrue(shown.spans.any { it.kind == MdKind.BOLD && shown.text.substring(it.start, it.end) == "12" })
        assertTrue(shown.spans.any { it.kind == MdKind.CODE })
    }

    @Test
    fun `the list of earlier conversations is read line by line`() {
        val sessions = parseSessions("abc-1\t2026-11-15 09:30\tOutline of the PDF\n\nnot an id line with spaces\t\t\nzz_9\t\t(untitled)\n")
        assertEquals(listOf("abc-1", "zz_9"), sessions.map { it.id })
        assertEquals("Outline of the PDF", sessions[0].title)
        assertEquals("2026-11-15 09:30", sessions[0].at)
    }

    @Test
    fun `ink goes beside the pages, is erased and read there, and undo takes it back`() {
        val memory = MemoryNotes().apply { add("n1", "Maths") }
        val commands = NoteCommands(memory)
        val drawn = result(commands, "ink add Maths board --near 2 --points \"1300,10 1400,20\"")
        assertEquals("board", drawn.getString("page"))
        assertEquals(1, memory.ink[Document.BOARD_PAGE_ID])
        assertEquals(1, result(commands, "note read Maths").getJSONObject("board").getInt("ink"))
        assertEquals(1, result(commands, "ink erase Maths board --all").getInt("erased"))
        commands.run("undo"); commands.run("undo")
        assertEquals(0, memory.ink[Document.BOARD_PAGE_ID] ?: 0)
        assertFalse(result(commands, "note read Maths").has("board"))
        // On a page the ink stays on it, and the answer says where else it can go.
        assertTrue(failure(commands, "ink add Maths 1 --points \"1300,10 1400,20\"").getString("error").contains("board"))
        assertEquals("Draw beside the pages of “Maths”", result(commands, "preview ink add Maths board --path \"M 0 0 L 5 5\"").getString("does"))
    }
}
