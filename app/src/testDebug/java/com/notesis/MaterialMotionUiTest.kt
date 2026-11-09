package com.notesis

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h700dp-xhdpi")
class MaterialMotionUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun restoreIconChoice() { AppIconStyle.select(context, StaticIconSet.MATERIAL) }

    @Composable private fun Theme(skin: Skin = Skin.MATERIAL, content: @Composable () -> Unit) {
        val look = SkinSettings(dynamicColor = false)
        MaterialTheme(colorScheme = skinColors(lookScheme(null, skin, look), skin, look)) {
            CompositionLocalProvider(LocalLiquidGlassEffectsAllowed provides true) { ProvideSkin(skin, look, content) }
        }
    }

    @Test fun iconChoiceIsStaticPersistentAndIndependentOfSkin() {
        context.getSharedPreferences("pens", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("iconAnimateIcons", true).putBoolean("iconAnimation", true).apply()
        AppIconStyle.load(context)
        assertEquals(StaticIconSet.MATERIAL, AppIconStyle.selected)
        var skin by mutableStateOf(Skin.MATERIAL)
        var symbol: androidx.compose.ui.graphics.vector.ImageVector? = null
        compose.setContent { Theme(skin) { val icon = AppIcons.undo; SideEffect { symbol = icon }; AppIcon(icon, "Undo") } }
        for (look in Skin.entries) {
            compose.runOnIdle { skin = look }
            compose.runOnIdle { assertSame(MaterialSymbols.Undo, symbol) }
        }
        compose.runOnIdle { AppIconStyle.select(context, StaticIconSet.REICONS) }
        for (look in Skin.entries) {
            compose.runOnIdle { skin = look }
            compose.runOnIdle { assertNotSame(MaterialSymbols.Undo, symbol) }
        }
        compose.runOnIdle { AppIconStyle.load(context); assertEquals(StaticIconSet.REICONS, AppIconStyle.selected) }
    }

    @Test fun pressingKeepsTheHitAreaAndDoesNotDelayOrDuplicateTheClick() {
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { Theme { AppIconButton({ clicks++ }, AppIcons.undo, "Undo", Modifier.size(48.dp).testTag("button")) } }
        val button = compose.onNodeWithTag("button")
        val bounds = button.fetchSemanticsNode().boundsInRoot
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(64)
        assertEquals(bounds, button.fetchSemanticsNode().boundsInRoot)
        button.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(bounds, button.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun interruptedSettingsNavigationLeavesOnlyTheLatestPage() {
        var nav by mutableStateOf(SidebarNav())
        compose.mainClock.autoAdvance = false
        compose.setContent { Theme {
            NoteSettingsSidebar(nav, { nav = it }, {}, 380f, {}, emptyList(), emptyList(),
                penSettings = { Text("Pen destination") }, appearance = { Text("Appearance destination") },
                touch = {}, toolbar = {}, noteView = {}, diagnostics = {})
        } }
        compose.runOnIdle { nav = nav.open(SettingsPage.PEN); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Pen destination").assertExists()
        compose.runOnIdle { nav = nav.back(); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { nav = nav.open(SettingsPage.APPEARANCE); Snapshot.sendApplyNotifications() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Appearance destination").assertExists()
        compose.onNodeWithText("Pen destination").assertDoesNotExist()
        compose.runOnIdle { nav = nav.back() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Appearance destination").assertDoesNotExist()
        assertEquals(SettingsPage.ROOT, nav.current)
    }
}
