package com.notesis

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h700dp-xhdpi")
class NoticeHostUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aNoticeShowsAndItsUndoRunsOnce() {
        var undone = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMotionAllowed provides false) {
                    ProvideSkin(SkinSettings()) { NoticeHost() }
                }
            }
        }
        compose.runOnIdle { Notices.show("Moved to Trash", "Undo", onAction = { undone++ }) }
        compose.onNodeWithText("Moved to Trash").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle {
            assertEquals(1, undone)
            assertNull(Notices.current)
        }
    }

    @Test fun aToastBecomesANoticeWhileTheHostIsUp() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMotionAllowed provides false) {
                    ProvideSkin(SkinSettings()) { NoticeHost() }
                }
            }
        }
        compose.runOnIdle {
            KToast.makeText(org.robolectric.RuntimeEnvironment.getApplication(), "PDF saved", android.widget.Toast.LENGTH_SHORT).show()
        }
        compose.onNodeWithText("PDF saved").assertIsDisplayed()
    }
}
