package com.notesis

import android.content.Context
import java.io.DataOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A text box put off the pages, on the whiteboard, is saved with the note and read back where it was. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoardTextStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val note get() = File(context.filesDir, "notes/board-text-fixture")

    @Before fun prepare() {
        note.deleteRecursively()
        File(note, "pages").mkdirs()
        File(note, "meta.json").writeText("""{"title":"Board","modified":1,"pages":[{"id":"p1","w":1240,"h":1754}]}""")
        DataOutputStream(File(note, "pages/p1.bin").outputStream()).use {
            it.writeInt(0x4E545353); it.writeInt(4); it.writeInt(0)
        }
    }
    @After fun clean() { note.deleteRecursively() }

    @Test fun aTextBoxOffThePagesIsKept() {
        val store = NoteStore(context)
        val document = store.load("board-text-fixture")
        assertTrue(document.board.images.isEmpty())
        document.board.images += PageImage("t1", -400f, 120f, 300f, 80f, TextBoxContent("Beside the page"))
        document.board.dirty = true
        store.save("board-text-fixture", "Board", document)

        val again = store.load("board-text-fixture").board.images
        assertEquals(1, again.size)
        assertEquals("Beside the page", again[0].textContent?.text)
        assertEquals(-400f, again[0].x, 0.01f)
        assertEquals(120f, again[0].y, 0.01f)
        // And none on the page itself.
        assertTrue(store.load("board-text-fixture").pages[0].images.isEmpty())
    }
}
