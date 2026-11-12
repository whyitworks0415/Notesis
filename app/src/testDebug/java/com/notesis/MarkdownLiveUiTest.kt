package com.notesis

import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Live editor drawn and used: blocks read as they look, a touch opens one, boxes tick. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h1400dp-xhdpi")
class MarkdownLiveUiTest {
    @get:Rule val compose = createComposeRule()

    private object NoFiles : MarkdownSource {
        override val key = "test"
        override val title = "Test"
        override val where = ""
        override suspend fun load() = ""
        override suspend fun save(text: String) = Unit
        override suspend fun names() = emptyList<String>()
        override suspend fun backlinks() = emptyList<String>()
        override suspend fun follow(name: String) = Unit
        override fun openFile(name: String): java.io.InputStream? = null
        override suspend fun addFile(name: String, input: java.io.InputStream): String? = null
    }

    private val note = """
        ---
        tags: [school]
        ---
        # Notes ***bold italic*** title
        Body **bold**, *italic*, ~~gone~~, ==marked==, `code`, [[Other note]], #tag, x<sup>2</sup>[^1]
        - one
          - nested
        1. first
        - [ ] to do
        - [x] done
        > [!tip] A tip
        > with **body**
        > plain quote
        | Left | Right |
        | :--- | ---: |
        | a | **b** |
        ```kotlin
        fun main() { println("hi") } // note
        ```
        ***
        [^1]: The footnote.
    """.trimIndent()

    private var value by mutableStateOf(TextFieldValue(""))
    private var editing by mutableStateOf(false)

    private fun show() {
        value = TextFieldValue(note)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMotionAllowed provides false) {
                    ProvideSkin(SkinSettings()) {
                        MarkdownBlockEditor(
                            value = value,
                            onValueChange = { value = it },
                            onChange = { value = TextFieldValue(it.text, TextRange(it.start, it.end)) },
                            source = NoFiles,
                            onFollow = {}, onUrl = {}, onKey = { false },
                            scroll = rememberScrollState(),
                            jumpTo = null, onJumped = {},
                            editing = editing, onEditing = { editing = it },
                        )
                    }
                }
            }
        }
    }

    @Test fun blocksReadAsTheyLookAndATouchOpensOne() {
        show()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/markdown-live.png")
        // Marks are gone from what is drawn.
        compose.onNodeWithText("Notes bold italic title").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("# Notes", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("A tip").assertIsDisplayed()
        // A touch on the heading opens it with its marks, and typing writes into the note.
        compose.onNodeWithText("Notes bold italic title").performClick()
        compose.waitForIdle()
        assertTrue(editing)
        compose.onNodeWithContentDescription("Note text").performTextInput("!")
        compose.runOnIdle { assertTrue(value.text, value.text.contains("title!\n") || value.text.contains("!# Notes")) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/markdown-live-open.png")
    }

    @Test fun aBoxTicksInTheText() {
        show()
        compose.onAllNodesWithText("to do").fetchSemanticsNodes()
        val before = value.text
        // The first checkbox drawn is the open task's.
        compose.onAllNodes(androidx.compose.ui.test.isToggleable())[0].performClick()
        compose.runOnIdle { assertEquals(before.replace("- [ ] to do", "- [x] to do"), value.text) }
    }
}
