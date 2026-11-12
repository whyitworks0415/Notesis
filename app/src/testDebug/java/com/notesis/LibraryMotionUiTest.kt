package com.notesis

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h700dp-xhdpi")
class LibraryMotionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theSelectedCountRollsToEachNewNumber() {
        var count by mutableIntStateOf(1)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMotionAllowed provides true) {
                    ProvideSkin(SkinSettings()) { RollingCount(count) }
                }
            }
        }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.runOnIdle { count = 3 }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("3 selected").assertIsDisplayed()
    }

    @Test fun theEmptyLibraryDrawsItsLine() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMotionAllowed provides true) {
                    ProvideSkin(SkinSettings()) { EmptyLibraryStroke(Modifier.size(160.dp, 64.dp).testTag("stroke")) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("stroke").assertIsDisplayed()
    }
}
