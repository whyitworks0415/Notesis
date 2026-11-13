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
}
