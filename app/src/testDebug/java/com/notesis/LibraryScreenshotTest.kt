package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The library's pieces drawn without a tablet. A layout that throws fails the
 * test; the pictures go to build/outputs/roborazzi for a person to look at,
 * since there is no device in CI to look at them on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h600dp-xhdpi")
class LibraryScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String) =
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun themed(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MaterialTheme {
                ProvideSkin(Skin.MATERIAL, SkinSettings()) {
                    Column(Modifier.background(Color.White).padding(16.dp)) { content() }
                }
            }
        }
    }

    @Test
    fun searchOpenAndFolded() {
        themed {
            val focus = remember { FocusRequester() }
            LibrarySearchMorph("", {}, fold = { 0f }, onIcon = {}, onFocus = {}, focusRequester = focus)
            LibrarySearchMorph("maths", {}, fold = { 0.5f }, onIcon = {}, onFocus = {}, focusRequester = remember { FocusRequester() })
            LibrarySearchMorph("", {}, fold = { 1f }, onIcon = {}, onFocus = {}, focusRequester = remember { FocusRequester() })
        }
        shot("search-morph")
    }

    @Test
    fun paperChoices() {
        themed {
            PaperPicker(
                selectedPlain = PageBackground.GRID,
                selectedTemplate = null,
                userTemplates = emptyList(),
                templateFile = { File("missing") },
                onPlain = {}, onTemplate = {}, onRenameTemplate = { _, _ -> true },
                same = true,
            )
        }
        shot("paper-picker")
    }

    @Test
    fun sectionsAndTitle() {
        themed {
            LibraryLargeTitle("Notes", "12 notes")
            LibrarySectionToggle("Folders", 3, expanded = true) {}
            LibrarySectionToggle("Markdown", 5, expanded = false) {}
            FolderTile("Maths", 4, selected = false, selectionMode = false, onOpen = {}, onRename = {}, onDelete = {},
                modifier = Modifier.width(176.dp))
        }
        shot("sections")
    }
}
