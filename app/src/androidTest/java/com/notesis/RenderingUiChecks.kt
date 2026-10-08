package com.notesis

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
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
internal fun Instrumentation.checkRenderingUi(strokeCount: Int = 24, denseStrokes: Int = 0) {
    val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val report = StringBuilder()
    runOnMainSync {
        // Avoid the API 36 emulator's task-snapshot mapper crash at teardown.
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
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
        if (denseStrokes > 0) {
            val page = document.pages.single()
            val brush = Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), android.graphics.Color.BLACK,
                2.5f, STROKE_EPSILON)
            repeat(denseStrokes) { index ->
                val points = MutableStrokeInputBatch().apply {
                    repeat(12) { point -> add(InputToolType.STYLUS,
                        12f + (index % 30) * 40f + point * 1.8f,
                        15f + (index / 30) * 17f + kotlin.math.sin(point * 0.5f) * 4f,
                        point * 4L) }
                }
                page.strokes += Stroke(brush, points.toImmutable())
            }
            page.loaded = true
            page.revision++
        }
        lateinit var ink: InkCanvasView
        try {
            runOnMainSync {
                activity.setContent {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                        InkCanvasView(context).also {
                            ink = it
                            it.open(document, null)
                            it.compatWetInk = true
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
            check(document.pages.sumOf { it.strokes.size } >= denseStrokes + strokeCount) { "Synthetic pen lost strokes" }
            report.appendLine("Mesh-enabled attached canvas with $denseStrokes existing strokes")
            val sorted = timings.sorted()
            fun percentile(fraction: Double) = sorted[((sorted.size - 1) * fraction).toInt()] / 1e6
            report.appendLine("Synthetic stylus dispatch n=${sorted.size}: p50=${percentile(0.5)}ms p95=${percentile(0.95)}ms p99=${percentile(0.99)}ms")
            report.appendLine(ink.latency.render(document.pages.sumOf { it.strokes.size }))
            // MeshCacheChecks captures the GPU pixels separately; this secure
            // test window intentionally has no system screenshot/thumbnail.
            runOnMainSync { ink.latencyMonitoringEnabled = false }
        } finally { store.delete(note.id) }
    } finally {
        File(targetContext.cacheDir, "rendering-performance.txt").writeText(report.toString())
        runOnMainSync { activity.finish() }
    }
}
