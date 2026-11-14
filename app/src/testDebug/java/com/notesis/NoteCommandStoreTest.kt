package com.notesis

import android.content.Context
import java.io.DataOutputStream
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The note commands on real files: a change is saved, and undo leaves the files as they were. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteCommandStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val note get() = File(context.filesDir, "notes/command-fixture")

    @Before fun prepare() {
        note.deleteRecursively()
        File(note, "pages").mkdirs()
        File(note, "meta.json").writeText("""{"title":"Physics","modified":1,"pages":[""" +
            """{"id":"p1","w":1240,"h":1754,"bg":"GRID"},{"id":"p2","w":1240,"h":1754},{"id":"p3","w":1240,"h":1754}]}""")
        for (id in listOf("p1", "p2", "p3")) DataOutputStream(File(note, "pages/$id.bin").outputStream()).use {
            it.writeInt(0x4E545353); it.writeInt(4); it.writeInt(0)
        }
    }
    @After fun clean() { note.deleteRecursively() }

    private fun pageIds(): List<String> = JSONObject(File(note, "meta.json").readText()).getJSONArray("pages").let { pages ->
        (0 until pages.length()).map { pages.getJSONObject(it).getString("id") }
    }

    @Test fun changesAreSavedAndUndoBringsTheFilesBack() {
        val store = NoteStore(context)
        val commands = NoteCommands(NoteStoreWorkspace(context, store) { false })
        val pageBefore = File(note, "pages/p2.bin").readBytes()

        val done = JSONObject(commands.runAll(listOf("page add Physics --after 1", "toc set Physics 1 Waves", "page rm Physics 3")))
        assertTrue(done.toString(), done.getBoolean("ok"))
        val ids = pageIds()
        assertEquals(3, ids.size)
        assertEquals(listOf("p1", "p3"), listOf(ids[0], ids[2]))
        assertFalse(File(note, "pages/p2.bin").exists())
        assertEquals("Waves", store.load("command-fixture").pages[0].tocTitle)
        // A page added after the first takes its paper.
        assertEquals(PageBackground.GRID, store.load("command-fixture").pages[1].background)

        assertTrue(JSONObject(commands.run("undo")).getBoolean("ok"))
        assertEquals(listOf("p1", "p2", "p3"), pageIds())
        assertArrayEquals(pageBefore, File(note, "pages/p2.bin").readBytes())
        assertEquals(null, store.load("command-fixture").pages[0].tocTitle)
    }

    @Test fun theCopiesKeptForUndoAreRemovedWithTheirChange() {
        val store = NoteStore(context)
        val commands = NoteCommands(NoteStoreWorkspace(context, store) { false })
        val kept = File(context.cacheDir, "command-undo")
        fun copies() = kept.listFiles()?.size ?: 0
        commands.run("page add Physics")
        assertEquals(1, copies())
        commands.run("undo")
        assertEquals(0, copies())
        commands.runAll(listOf("page add Physics", "page rm Physics 40"))
        assertEquals(0, copies())
    }

    @Test fun aNoteOnScreenIsLeftAlone() {
        val store = NoteStore(context)
        val commands = NoteCommands(NoteStoreWorkspace(context, store) { it == "command-fixture" })
        val before = File(note, "meta.json").readText()
        assertFalse(JSONObject(commands.run("page add Physics")).getBoolean("ok"))
        assertEquals(before, File(note, "meta.json").readText())
        val read = JSONObject(commands.run("note read Physics")).getJSONObject("result")
        assertEquals(3, read.getJSONArray("pages").length())
    }

    @Test fun renameAndTrashAreUndone() {
        val store = NoteStore(context)
        val commands = NoteCommands(NoteStoreWorkspace(context, store) { false })
        commands.run("note rename Physics Optics")
        commands.run("note trash Optics")
        assertTrue(store.trashed().any { it.id == "command-fixture" && it.title == "Optics" })
        commands.run("undo"); commands.run("undo")
        assertTrue(store.list().any { it.id == "command-fixture" && it.title == "Physics" })
    }

    /** A note over a two-page PDF with text and one bookmark, made with PDFBox as a scanner's or a publisher's would be. */
    private fun pdfNote(): File {
        val dir = File(context.filesDir, "notes/pdf-fixture").apply { deleteRecursively(); File(this, "pages").mkdirs() }
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { pdf ->
            val pages = listOf("Contents", "Chapter One begins here").map { words ->
                com.tom_roush.pdfbox.pdmodel.PDPage().also { page ->
                    pdf.addPage(page)
                    com.tom_roush.pdfbox.pdmodel.PDPageContentStream(pdf, page).use { out ->
                        out.beginText()
                        out.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 14f)
                        out.newLineAtOffset(72f, 700f)
                        out.showText(words)
                        out.endText()
                    }
                }
            }
            val outline = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline
            outline.addLast(com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem().apply {
                title = "Chapter One"
                destination = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination().apply { page = pages[1] }
            })
            pdf.save(File(dir, "doc.pdf"))
        }
        File(dir, "meta.json").writeText("""{"title":"Textbook","modified":2,"pages":[""" +
            """{"id":"q1","w":612,"h":792,"bg":"PDF","pdf":0},{"id":"q2","w":612,"h":792,"bg":"PDF","pdf":1}]}""")
        for (id in listOf("q1", "q2")) DataOutputStream(File(dir, "pages/$id.bin").outputStream()).use {
            it.writeInt(0x4E545353); it.writeInt(4); it.writeInt(0)
        }
        // As Android before 15 leaves it: a search text for each page, empty.
        for (id in listOf("q1", "q2")) File(dir, "pages/$id.txt").writeText("")
        return dir
    }

    @Test fun aPdfIsReadForItsTextAndBookmarks() {
        val dir = pdfNote()
        try {
            val commands = NoteCommands(NoteStoreWorkspace(context, NoteStore(context)) { false })
            val text = JSONObject(commands.run("pdf text Textbook"))
            assertTrue(text.toString(), text.getBoolean("ok"))
            assertEquals("Chapter One begins here", text.getJSONObject("result").getJSONArray("pages").getJSONObject(1).getString("text"))
            // What was read is kept as the page's search text, so search finds it from now on.
            assertTrue(File(dir, "pages/q2.txt").readText().contains("Chapter One"))
            val info = JSONObject(commands.run("pdf info Textbook")).getJSONObject("result")
            assertEquals(1, info.getInt("bookmarks"))
            assertEquals("yes", info.getString("textLayer"))
            assertTrue(JSONObject(commands.run("toc import-pdf Textbook")).getBoolean("ok"))
            assertEquals("Chapter One", NoteStore(context).load("pdf-fixture").pages[1].tocTitle)
            val found = JSONObject(commands.run("pdf find Textbook begins")).getJSONObject("result")
            assertEquals(2, found.getJSONArray("hits").getJSONObject(0).getInt("page"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun notesAreMadeAndCopiedOnDiskAndUndoSendsThemToTheTrash() {
        val store = NoteStore(context)
        val commands = NoteCommands(NoteStoreWorkspace(context, store) { false })
        val made = JSONObject(commands.run("note new Algebra --folder School --pages 3 --paper lined"))
        assertTrue(made.toString(), made.getBoolean("ok"))
        val id = made.getJSONObject("result").getString("id")
        val loaded = store.load(id)
        assertEquals(3, loaded.pages.size)
        assertEquals(PageBackground.LINED, loaded.pages[2].background)
        assertEquals("School", store.list().first { it.id == id }.folder)
        val copy = JSONObject(commands.run("note dup Physics --title \"Physics 2\"")).getJSONObject("result").getString("id")
        assertEquals(listOf("p1", "p2", "p3"), store.load(copy).pages.map { it.id })
        assertEquals("Physics 2", store.list().first { it.id == copy }.title)
        val md = JSONObject(commands.run("note new-md Ideas --text \"# Ideas\\n- one\"")).getJSONObject("result").getString("id")
        assertTrue(JSONObject(commands.run("md append Ideas \"- two\"")).getBoolean("ok"))
        assertEquals("# Ideas\n- one\n- two\n", store.loadMarkdown(md))
        repeat(4) { assertTrue(JSONObject(commands.run("undo")).getBoolean("ok")) }
        val trashed = store.trashed().map { it.id }
        assertTrue(trashed.containsAll(listOf(id, copy, md)))
        for (gone in listOf(id, copy, md)) File(context.filesDir, "notes/$gone").deleteRecursively()
    }

    @Test fun aNoteOnScreenWritesWhatItShowsFirstAndIsToldToReadItAgain() {
        val store = NoteStore(context)
        val events = mutableListOf<String>()
        // A screen showing the note with an outline entry it has not saved yet.
        val screen = object : LiveNoteScreen {
            override fun hold(): Boolean {
                val shown = store.load("command-fixture").also { it.pages[0].tocTitle = "Drawn on screen" }
                store.save("command-fixture", "Physics", shown)
                events += "hold"
                return true
            }
            override fun letGo(changed: Boolean, done: () -> Unit) { events += "let go $changed"; done() }
        }
        LiveNotes.register("command-fixture", screen)
        try {
            val commands = NoteCommands(NoteStoreWorkspace(context, store) { it == "command-fixture" })
            assertTrue(JSONObject(commands.run("toc set Physics 2 Waves")).getBoolean("ok"))
            assertEquals(listOf("hold", "let go true"), events)
            // The change was made on what the screen showed, not over it.
            val after = store.load("command-fixture")
            assertEquals("Drawn on screen", after.pages[0].tocTitle)
            assertEquals("Waves", after.pages[1].tocTitle)
        } finally {
            LiveNotes.unregister("command-fixture", screen)
        }
    }
}
