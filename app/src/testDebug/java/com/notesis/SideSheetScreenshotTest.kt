package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings, the pages and the agent open as one kind of panel: the same edge, header, handle and corner. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1480dp-h924dp-xhdpi")
class SideSheetScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        val look = SkinSettings(themeMode = AppThemeMode.LIGHT, dynamicColor = false)
        compose.setContent {
            MaterialTheme(colorScheme = skinColors(lookScheme(null, look), look), typography = chromeTypography()) {
                ProvideSkin(look) {
                    CompositionLocalProvider(LocalMotionAllowed provides false) {
                        Box(Modifier.fillMaxSize().background(Color(0xFFE8E4DC))) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun settings() = shoot("sheet_settings") {
        SettingsSidebarFrame("Settings", false, {}, {}, 420f, {}) { SettingsNote("Body", Modifier.padding(Ui.Space.l)) }
    }

    @Test fun pages() = shoot("sheet_pages") {
        SettingsSidebarFrame("Pages", false, {}, {}, PAGE_SIDEBAR_DEFAULT_DP, {}, widthRule = ::pageSidebarWidth,
            minDp = PAGE_SIDEBAR_MIN_DP) { SettingsNote("Body", Modifier.padding(Ui.Space.l)) }
    }

    @Test fun agent() {
        AgentChat.open = true
        shoot("sheet_agent") { AgentSidebarHost() }
    }
}
