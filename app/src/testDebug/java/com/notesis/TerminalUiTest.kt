package com.notesis

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The terminal run as someone would: a command typed and run on real note files, Tab, and undo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h900dp-xhdpi")
class TerminalUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val note get() = File(context.filesDir, "notes/terminal-fixture")

    @Before fun prepare() {
        note.deleteRecursively()
        File(note, "pages").mkdirs()
        File(note, "meta.json").writeText("""{"title":"Chemistry","modified":1,"pages":[{"id":"p1","w":1240,"h":1754}]}""")
        SharedNotes.resetForTests()
        TerminalSession.resetForTests()
        TerminalSession.lines.clear()
        TerminalSession.open = true
    }
    @After fun clean() { note.deleteRecursively(); TerminalSession.open = false }

    private fun show() {
        val store = NoteStore(context)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMotionAllowed provides false) {
                    ProvideSkin(SkinSettings()) { TerminalHost(store) }
                }
            }
        }
    }

    private fun waitFor(text: String) {
        val found = runCatching {
            compose.waitUntil(10_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                TerminalSession.lines.any { it.text.contains(text) }
            }
        }
        if (found.isFailure) throw AssertionError("No \"$text\" in:\n" + TerminalSession.lines.joinToString("\n") { it.text })
    }

    @Test fun aCommandRunsOnTheNotesAndUndoTakesItBack() {
        show()
        val before = LibraryChanges.version
        compose.onNodeWithContentDescription("Command line").performTextInput("page add Chem")
        compose.onNodeWithText("Tab").performClick()
        compose.onNodeWithText("Run").performClick()
        waitFor("pages: 2")
        assertTrue(TerminalSession.lines.any { it.text == "$ page add Chemistry" })
        assertEquals(before + 1, LibraryChanges.version)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/terminal.png")
        compose.onNodeWithContentDescription("Command line").performTextInput("undo\n")
        waitFor("undone: ")
        assertEquals(1, NoteStore(context).load("terminal-fixture").pages.size)
    }

    @Test fun aWrongCommandSaysHowItIsWritten() {
        show()
        compose.onNodeWithContentDescription("Command line").performTextInput("page explode\n")
        waitFor("error: There is no command")
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
    }
}
