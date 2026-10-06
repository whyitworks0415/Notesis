package com.notesis

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Real Compose measurement and pointer routing inside a scrolling settings pane. */
internal fun Instrumentation.checkToolSettingsUi() {
    val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return uiAutomation.rootInActiveWindow?.let { visit(it) }
    }
    fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(50) {
            find(predicate)?.let { return it }
            // Cold emulator boots can leave a System UI ANR over the test app.
            val systemUiAnr = find { it.text?.toString() == "System UI isn't responding" }
            if (systemUiAnr != null) find { it.text?.toString() == "Close app" }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(100)
        }
        val nodes = mutableListOf<String>()
        find { nodes += "${it.text}/${it.contentDescription}/${it.className}/${it.rangeInfo}"; false }
        error("Settings node missing: $nodes")
    }
    fun touch(x: Float, y: Float, endX: Float = x, endY: Float = y) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, at: Long, px: Float, py: Float) {
            val event = MotionEvent.obtain(start, at, action, px, py, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, start, x, y)
        for (i in 1..8) {
            Thread.sleep(16)
            send(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), x + (endX - x) * i / 8, y + (endY - y) * i / 8)
        }
        send(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), endX, endY)
        Thread.sleep(250)
    }
    fun click(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        touch(bounds.exactCenterX(), bounds.exactCenterY())
    }
    runOnMainSync {
        activity.setShowWhenLocked(true)
        activity.setTurnScreenOn(true)
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        (activity.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager)
            .requestDismissKeyguard(activity, null)
    }
    var sliderValue = 0.25f
    try {
        runOnMainSync {
            activity.setContent {
                ProvideSkin(Skin.SPOTIGLASS, SkinSettings()) {
                    MaterialTheme {
                        var value by remember { mutableFloatStateOf(0.25f) }
                        Column(Modifier.padding(top = 64.dp).width(320.dp).verticalScroll(rememberScrollState())) {
                            SkinSlider(value, { value = it; sliderValue = it }, 0f..1f)
                        }
                    }
                }
            }
        }
        val slider = awaitNode { it.rangeInfo != null }
        val bounds = Rect().also(slider::getBoundsInScreen)
        check(bounds.width() > 200) { "Default slider collapsed: $bounds" }
        touch(bounds.left + bounds.width() * 0.3f, bounds.exactCenterY(),
            bounds.left + bounds.width() * 0.7f, bounds.exactCenterY())
        check(sliderValue > 0.5f) { "Scrolling pane stole slider drag: $sliderValue" }
        for (mode in listOf(EditMode.HIGHLIGHTER, EditMode.ERASE)) {
            var saved: PenPreset? = null
            var partialSelected = false
            var prediction = true
            val pen = PenStore.DEFAULTS.getValue(mode).let {
                if (mode == EditMode.HIGHLIGHTER) it.copy(colorArgb = it.colorArgb or -0x1000000) else it
            }
            runOnMainSync {
                activity.setContent {
                    ProvideSkin(Skin.SPOTIGLASS, SkinSettings()) {
                        MaterialTheme {
                            var enabled by remember { mutableStateOf(true) }
                            var partial by remember { mutableStateOf(false) }
                            PenDialog(mode, pen, enabled, { enabled = it; prediction = it },
                                0, {}, false, {}, 0, {}, false, {}, false, {}, false, {}, 0, {}, {}, { saved = it },
                                partialEraser = partial, onPartialEraser = { partial = it; partialSelected = it })
                        }
                    }
                }
            }
            awaitNode { it.text?.toString() == if (mode == EditMode.HIGHLIGHTER) "형광펜" else "지우개" }
            waitForIdleSync()
            Thread.sleep(250)
            if (mode == EditMode.HIGHLIGHTER) {
                val palette = awaitNode { it.text?.toString() == "색 팔레트" }
                click(palette)
                val color = awaitNode { it.contentDescription?.toString()?.startsWith("색상 #") == true }
                click(color)
                click(awaitNode { it.text?.toString() == "색 팔레트" })
                val fluorescent = awaitNode { it.contentDescription?.toString() == "색상 #FFEB3B" }
                check(fluorescent.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                click(awaitNode { it.text?.toString() == "저장" })
                Thread.sleep(250)
                check(saved != null && (saved!!.colorArgb and 0xFFFFFF) != (pen.colorArgb and 0xFFFFFF))
                check(saved != null && android.graphics.Color.alpha(saved!!.colorArgb) == 0x66) { "Fluorescent palette did not apply 40% opacity" }
            } else {
                click(awaitNode { it.text?.toString() == "부분 지우개" })
                Thread.sleep(150)
                check(partialSelected) { "Partial eraser mode did not update" }
                // Canvas switches have no standard widget class on every API.
                // Locate the row's labels and tap the actual switch hit area.
                val titleBounds = Rect().also(awaitNode { it.text?.toString() == "예측" }::getBoundsInScreen)
                val detailBounds = Rect().also(awaitNode { it.text?.toString()?.startsWith("펜보다 한 프레임") == true }::getBoundsInScreen)
                val density = targetContext.resources.displayMetrics.density
                touch(titleBounds.left - 52f * density, (titleBounds.top + detailBounds.bottom) / 2f)
                Thread.sleep(250)
                check(!prediction) { "Eraser prediction toggle did not update" }
                val width = awaitNode { it.rangeInfo != null }
                val rect = Rect().also(width::getBoundsInScreen)
                check(rect.width() > 200)
                touch(rect.left + rect.width() * 0.8f, rect.exactCenterY())
                click(awaitNode { it.text?.toString() == "저장" })
                Thread.sleep(250)
                check(saved != null && saved!!.width > pen.width) { "Eraser thickness did not save" }
            }
        }
    } finally { runOnMainSync { activity.finish() } }
}
