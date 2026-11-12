package com.notesis

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h700dp-xhdpi")
class ShapePanelUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(moves: Boolean) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMotionAllowed provides moves) {
                    ProvideSkin(SkinSettings()) {
                        ShapePickerDialog(
                            current = ShapeKind.RECT, holdToShape = true, onHoldToShape = {},
                            cornerRadius = 0f, onCornerRadius = {}, color = 0xFF000000.toInt(), onColor = {},
                            onPenSettings = {}, onPick = {}, onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText("Shapes").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test fun theShapePensSettingsOpen() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMotionAllowed provides true) {
                    ProvideSkin(SkinSettings()) {
                        PenDialog(mode = EditMode.SHAPE, pen = PenStore.DEFAULTS.getValue(EditMode.SHAPE),
                            highlighterAboveInk = false, onHighlighterAboveInk = {}, axisSnap = false, onAxisSnap = {},
                            onDismiss = {}, onConfirm = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test fun tappingTheShapeToolInEachBarSizeReachesTheNote() {
        var level by androidx.compose.runtime.mutableIntStateOf(0)
        var picked: GlassToolbarTool? = null
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMotionAllowed provides false) {
                    ProvideSkin(SkinSettings()) {
                        FloatingToolbar(
                            sizeLevel = level, onSizeLevel = {}, mode = EditMode.SHAPE, toolColors = emptyMap(),
                            targetWidth = 900.dp, docked = false, modifier = Modifier, onDrag = {},
                            onTool = { picked = it }, functions = emptyList(), notes = emptyList(), ai = emptyList(),
                            topRow = {}, penOptions = {},
                        )
                    }
                }
            }
        }
        for (size in 0..2) {
            compose.runOnIdle { level = size; picked = null }
            compose.waitForIdle()
            if (compose.onAllNodesWithContentDescription("Shape").fetchSemanticsNodes().isEmpty()) continue
            compose.onAllNodesWithContentDescription("Shape")[0].performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals("level $size", GlassToolbarTool.SHAPE, picked) }
        }
    }

    @Test fun theShapePanelOpensWithMotion() = show(moves = true)
    @Test fun theShapePanelOpensWithoutMotion() = show(moves = false)
}
