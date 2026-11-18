package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import android.graphics.Bitmap

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
                ProvideSkin(SkinSettings()) {
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

    @Test
    fun iosCardsRemainInteractiveAfterScrollingAndChangingTheme() {
        val entries = List(60) { it }
        var selected by mutableStateOf<Int?>(null)
        var dark by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                ProvideSkin(SkinSettings()) {
                    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().testTag("library")) {
                        items(entries, key = { it }, contentType = { if (it % 2 == 0) "note" else "folder" }) { id ->
                            if (id % 2 == 0) NoteCard(
                                note = NoteMeta("note-$id", "Library note $id", 1L, 1, 0),
                                selected = selected == id, selectionMode = selected != null,
                                hasCustomThumbnail = false, onOpen = { selected = id },
                                onDelete = {}, onPickThumbnail = {}, onClearThumbnail = {},
                                onExport = {}, onExportPdf = {}, onExportMarkdown = null,
                                onFile = {}, onToggleFavorite = {},
                            ) else FolderTile(
                                "Library folder $id", id, selected == id, selected != null,
                                onOpen = { selected = id }, onRename = {}, onDelete = {},
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("library").performScrollToIndex(42)
        compose.onNodeWithText("Library note 42").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(42, selected); dark = true }
        compose.onNodeWithTag("library").performScrollToIndex(0)
        compose.onNodeWithText("Library folder 1").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        shot("ios-library-scrolled-dark")
    }

    @Test
    fun aReusedThumbnailSlotUpdatesForAnotherFileAndSamePathReplacement() {
        val directory = Files.createTempDirectory("library-thumbnails").toFile()
        fun write(name: String, color: Int): File = File(directory, name).apply {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            val first = write("one.png", android.graphics.Color.RED)
            val second = write("two.png", android.graphics.Color.BLUE)
            var file by mutableStateOf<File?>(first)
            var revision by mutableStateOf(0)
            var picture: Bitmap? = null
            val bindings = mutableListOf<String>()
            compose.setContent {
                // A library revision rebinds the same slot after editing a picture.
                val requested = file
                val decoded = rememberFileBitmap(requested, 48)
                androidx.compose.runtime.SideEffect {
                    picture = decoded
                    bindings.add("${requested?.name}:${decoded?.getPixel(0, 0)}")
                }
                androidx.compose.foundation.layout.Box(Modifier.testTag("thumbnail-$revision")) {
                    Text(decoded?.getPixel(0, 0).toString())
                }
            }
            fun awaitColor(color: Int) {
                compose.waitForIdle()
                try {
                    compose.waitUntil(10_000) { picture?.getPixel(0, 0) == color }
                } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError("Expected thumbnail $color, got ${picture?.getPixel(0, 0)} for $file; bindings=$bindings", failure)
                }
            }
            awaitColor(android.graphics.Color.RED)
            compose.runOnIdle { file = second }
            awaitColor(android.graphics.Color.BLUE)
            val stamp = second.lastModified()
            write("two.png", android.graphics.Color.GREEN)
            assertTrue(second.setLastModified(stamp + 2_000))
            compose.runOnIdle { revision++ }
            awaitColor(android.graphics.Color.GREEN)
            compose.runOnIdle { file = null }
            compose.runOnIdle { assertEquals(null, picture) }
            compose.runOnIdle { file = first }
            awaitColor(android.graphics.Color.RED)
        } finally {
            directory.deleteRecursively()
        }
    }

}
