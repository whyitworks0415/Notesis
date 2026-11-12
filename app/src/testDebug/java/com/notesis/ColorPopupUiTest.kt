package com.notesis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.launch
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h700dp-xhdpi")
class ColorPopupUiTest {
    @get:Rule val compose = createComposeRule()
    private var previousSpeed = 1f
    @Before fun fixedSpeed() { previousSpeed = MotionPrefs.speed; MotionPrefs.setSpeed(RuntimeEnvironment.getApplication(),1f) }
    @After fun restoreSpeed() { MotionPrefs.setSpeed(RuntimeEnvironment.getApplication(),previousSpeed) }

    @Test fun allFourLayoutsRenderWithoutChangingTheSelectedColor() {
        var mode by mutableStateOf(ColorPickerMode.PALETTE)
        lateinit var color: PenColorState
        compose.setContent { MaterialTheme {
            color = remember { PenColorState(0x803366CC.toInt()) }
            Box(Modifier.size(480.dp, 260.dp)) { ColorPickerBody(mode, color, false, true) }
        } }
        val original = color.argb()
        for (view in COLOR_VIEWS) {
            compose.runOnIdle { mode = view }
            compose.onNodeWithTag("color-picker-${view.name}").assertIsDisplayed()
            compose.runOnIdle { assertEquals(original, color.argb()) }
        }
    }

    @Test fun aHueBarGestureKeepsSaturationValueAndOpacity() {
        lateinit var color: PenColorState
        compose.setContent { MaterialTheme {
            color = remember { PenColorState(0x80FF0000.toInt()) }
            Box(Modifier.size(480.dp,190.dp)) { ColorPickerBody(ColorPickerMode.BARS,color,false,true) }
        } }
        val alpha = color.alpha
        compose.onNodeWithTag("color-picker-BARS").performTouchInput { click(Offset(height*0.08f,height*0.5f)) }
        compose.runOnIdle {
            assertTrue(color.hue > 20f)
            assertEquals(1f,color.saturation,0f);assertEquals(1f,color.value,0f)
            assertEquals(alpha,color.alpha,0f)
        }
    }

    @Test fun aSquareGestureChangesOnlySaturationAndValue() {
        lateinit var color: PenColorState
        compose.setContent { MaterialTheme {
            color = remember { PenColorState(0x80FF0000.toInt()) }
            Box(Modifier.size(480.dp,190.dp)) { ColorPickerBody(ColorPickerMode.SQUARE,color,false,true) }
        } }
        val alpha = color.alpha
        compose.onNodeWithTag("color-picker-SQUARE").performTouchInput { click(Offset(width*0.5f,height*0.25f)) }
        compose.runOnIdle {
            assertEquals(0f,color.hue,0f);assertEquals(alpha,color.alpha,0f)
            assertTrue(color.saturation in 0.4f..0.6f)
            assertTrue(color.value < 1f)
        }
    }

    @Test fun aPouringPanelKeepsItsRowsInPlaceAndAcceptsOneClick() {
        var clicks = 0
        lateinit var growth: PopupGrowth
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            growth = remember { PopupGrowth(false) }
            scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { growth.open(true) }
            Box(Modifier.size(300.dp,240.dp).revealOutOf(growth,28.dp) { Offset(0f,0f) }.testTag("panel")) {
                androidx.compose.material3.TextButton(onClick = { clicks++ }, modifier = Modifier.testTag("target")) {
                    androidx.compose.material3.Text("Target")
                }
            }
        } }
        compose.mainClock.advanceTimeBy(80)
        // Poured, not scaled: on its way out the rows are where they will rest,
        // give or take the small run past full size.
        val target = compose.onNodeWithTag("target").fetchSemanticsNode().boundsInRoot
        val mid = compose.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("target").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1,clicks) }
        compose.mainClock.advanceTimeBy(1500)
        val full = compose.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot.width
        assertTrue(mid > 0f && kotlin.math.abs(mid - full) <= full * 0.05f)
        val resting = compose.onNodeWithTag("target").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(target.left - resting.left) <= 2f && kotlin.math.abs(target.top - resting.top) <= 2f)
        var closed = false
        compose.runOnIdle { scope.launch { growth.close(true); closed = true } }
        compose.mainClock.advanceTimeBy(64)
        assertFalse(closed)
        compose.runOnIdle { assertTrue(growth.width.value < 1f && growth.height.value < 1f) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertTrue(closed) }
    }
}
