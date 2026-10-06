package com.notesis

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Attached hardware View: checks capture cost and collects synthetic pen timings. */
internal fun Instrumentation.checkRenderingUi(strokeCount: Int = 24) {
    val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val report = StringBuilder()
    try {
        val draws = AtomicInteger()
        var paused by mutableStateOf(false)
        var revision by mutableIntStateOf(0)
        runOnMainSync {
            activity.setContent {
                val backdrop = rememberLiquidGlassBackdrop()
                Box(Modifier.fillMaxSize().captureLiquidGlassBackdrop(backdrop) { paused }
                    .drawWithContent {
                        revision
                        draws.incrementAndGet()
                        drawRect(if (revision % 2 == 0) Color.White else Color.LightGray)
                    })
            }
        }
        waitForIdleSync(); Thread.sleep(250)
        fun sample(freeze: Boolean): Int {
            runOnMainSync { paused = freeze }
            waitForIdleSync(); Thread.sleep(100)
            draws.set(0)
            repeat(12) {
                runOnMainSync { revision++ }
                Thread.sleep(40)
                waitForIdleSync()
            }
            return draws.get()
        }
        val live = sample(false)
        val frozen = sample(true)
        check(frozen >= 12 && live >= frozen * 1.8) { "Backdrop freeze still duplicated content: $live / $frozen" }
        report.appendLine("Backdrop content draws over 12 updates: live=$live frozen=$frozen")

        val store = NoteStore(targetContext)
        val note = store.create("입력 성능 검사")
        val document = store.load(note.id)
        lateinit var ink: InkCanvasView
        try {
            runOnMainSync {
                activity.setContent {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                        InkCanvasView(context).also {
                            ink = it
                            it.open(document, null)
                            it.stabilizer = 0
                            it.latencyMonitoringEnabled = true
                        }
                    })
                }
            }
            waitForIdleSync(); Thread.sleep(250)
            val timings = mutableListOf<Long>()
            repeat(strokeCount) { stroke ->
                val start = SystemClock.uptimeMillis()
                repeat(40) { point ->
                    runOnMainSync {
                        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                        val coords = MotionEvent.PointerCoords().apply {
                            x = ink.width * (0.2f + point / 80f)
                            y = ink.height * (0.25f + (stroke % 12) / 40f) + 12f * kotlin.math.sin(point * 0.3f)
                            pressure = 0.5f; size = 0.01f
                        }
                        val action = when (point) { 0 -> MotionEvent.ACTION_DOWN; 39 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1,
                            arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                        val before = System.nanoTime()
                        ink.dispatchTouchEvent(event)
                        timings += System.nanoTime() - before
                        event.recycle()
                    }
                    Thread.sleep(4)
                }
                Thread.sleep(30)
            }
            Thread.sleep(600)
            check(document.pages.sumOf { it.strokes.size } >= strokeCount) { "Synthetic pen lost strokes" }
            val sorted = timings.sorted()
            fun percentile(fraction: Double) = sorted[((sorted.size - 1) * fraction).toInt()] / 1e6
            report.appendLine("Synthetic stylus dispatch n=${sorted.size}: p50=${percentile(0.5)}ms p95=${percentile(0.95)}ms p99=${percentile(0.99)}ms")
            report.appendLine(ink.latency.render(document.pages.sumOf { it.strokes.size }))
            uiAutomation.takeScreenshot()?.let { image ->
                File(targetContext.cacheDir, "rendering-ui.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            runOnMainSync { ink.latencyMonitoringEnabled = false }
        } finally { store.delete(note.id) }
    } finally {
        File(targetContext.cacheDir, "rendering-performance.txt").writeText(report.toString())
        runOnMainSync { activity.finish() }
    }
}
