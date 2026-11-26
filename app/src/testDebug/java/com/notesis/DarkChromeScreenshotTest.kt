package com.notesis

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w600dp-h1050dp-xhdpi")
class DarkChromeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun show(dark: Boolean, name: String) {
        val look = SkinSettings(themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT, dynamicColor = false)
        val scheme = skinColors(lookScheme(null, look), look)
        var bareInk = Color.Unspecified
        var buttonInk = Color.Unspecified
        compose.setContent {
            MaterialTheme(colorScheme = scheme, typography = chromeTypography()) {
                ProvideSkin(look) {
                    // This fixture checks real chrome, not a window compositor on a device.
                    CompositionLocalProvider(LocalMotionAllowed provides false) {
                        val inherited = LocalContentColor.current
                        SideEffect { bareInk = inherited }
                        Showcase {
                            Button(onClick = {}) {
                                val action = LocalContentColor.current
                                SideEffect { buttonInk = action }
                                Text("Continue")
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(scheme.onSurface, bareInk)
        assertEquals(scheme.onPrimary, buttonInk)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable private fun Showcase(action: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LibraryLargeTitle("Notes", "Your ideas, in focus")
            Text("A label outside Surface", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsChoiceChip(true, {}, "All notes")
                SettingsChoiceChip(false, {}, "Favorites")
            }
            SkinSurface(role = ChromeRole.SIDEBAR) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Settings", style = MaterialTheme.typography.titleLarge)
                    SettingsNavRow(SettingsPage.APPEARANCE) {}
                    SettingsNavRow(SettingsPage.TOUCH) {}
                    SettingsCard {
                        Text("Pen & paper", style = MaterialTheme.typography.titleMedium)
                        SettingsNote("Secondary labels stay clear in dark mode.")
                        SkinSlider(0.6f, {}, 0f..1f, Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Keep my preferences")
                            SkinSwitch(true, {})
                        }
                    }
                    OutlinedTextField("Lecture notes", {}, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    action()
                }
            }
            FolderTile("Research", 12, false, false, {}, {}, {}, modifier = Modifier.width(180.dp))
        }
    }

    @Test fun materialDark() = show(true, "material-studio-dark")
    @Test fun materialLight() = show(false, "material-studio-light")

    private fun checkOpaqueSidebar(dark: Boolean) {
        val skin = Skin.LIQUID_GLASS
        val look = SkinSettings(
            themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT,
            liquidStyle = LiquidStyle.IOS,
            sidebarClarity = 1f,
            dynamicColor = false,
        )
        val scheme = skinColors(lookScheme(null, look), look, skin)
        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                ProvideSkin(skin, look) {
                    Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                        SkinSurface(Modifier.fillMaxSize(), role = ChromeRole.SIDEBAR) {}
                    }
                }
            }
        }
        val mode = if (dark) "dark" else "light"
        val file = File("build/outputs/roborazzi/ios-sidebar-$mode.png")
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file.path)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        try {
            assertEquals(scheme.surfaceContainer.copy(alpha = 1f).toArgb(), bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun iosSidebarIsOpaqueInLightMode() = checkOpaqueSidebar(dark = false)
    @Test fun iosSidebarIsOpaqueInDarkMode() = checkOpaqueSidebar(dark = true)
}
