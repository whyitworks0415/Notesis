package com.notesis

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drives an attached canvas with synthetic stylus events in real time, so the
 * hold timer, wet-ink commit and undo history all run as they do under a pen:
 * eraser targets, scribble-out, circle to lasso, whole-only lasso, clipboard,
 * page history (including a delete that an autosave sweeps up) and keys.
 */
internal fun Instrumentation.checkCanvasGestures() {
    val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    val report = StringBuilder()
    val store = NoteStore(targetContext)
    val note = store.create("제스처 검사")
    try {
        val document = store.load(note.id)
        lateinit var ink: InkCanvasView
        var selected = 0
        runOnMainSync {
            activity.setContent {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    InkCanvasView(context).also {
                        ink = it
                        it.pageLoader = { page, epsilon -> store.loadPage(note.id, page, epsilon) }
                        it.maskLoader = { page, epsilon -> store.loadMasks(note.id, page, epsilon) }
                        it.open(document, null)
                        it.stabilizer = 0
                        it.predictionEnabled = false
                        it.onLassoSelected = { count -> selected = count }
                        it.onUndo = { it.undo() }
                        it.onRedo = { it.redo() }
                    }
                })
            }
        }
        waitForIdleSync(); Thread.sleep(400)
        val page0 = document.pages[0]

        // Page units to screen: the note opens fitted to width with page 0 at the top.
        var scale = 1f
        var originX = 0f
        var originY = 0f
        fun screen(x: Float, y: Float) = (originX + x * scale) to (originY + y * scale)

        fun pen(points: List<Pair<Float, Float>>, holdMs: Long = 0, after: List<Pair<Float, Float>> = emptyList()) {
            val down = SystemClock.uptimeMillis()
            val all = points + after
            all.forEachIndexed { index, (x, y) ->
                if (index == points.size && holdMs > 0) Thread.sleep(holdMs)
                runOnMainSync {
                    val (sx, sy) = screen(x, y)
                    val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                    val coords = MotionEvent.PointerCoords().apply { this.x = sx; this.y = sy; pressure = 0.5f; size = 0.01f }
                    val action = when (index) {
                        0 -> MotionEvent.ACTION_DOWN
                        all.lastIndex -> MotionEvent.ACTION_UP
                        else -> MotionEvent.ACTION_MOVE
                    }
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                        arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                    ink.dispatchTouchEvent(event)
                    event.recycle()
                }
                Thread.sleep(6)
            }
            if (holdMs > 0 && after.isEmpty()) Thread.sleep(holdMs)
            Thread.sleep(250) // wet ink commits asynchronously
            waitForIdleSync()
        }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 30) =
            (0..steps).map { (x0 + (x1 - x0) * it / steps) to (y0 + (y1 - y0) * it / steps) }
        fun loop(cx: Float, cy: Float, r: Float, turns: Float = 1.08f) =
            (0..80).map { val t = it / 80.0 * turns * 2 * Math.PI; (cx + r * cos(t).toFloat()) to (cy + r * sin(t).toFloat()) }
        fun zigzag(x0: Float, x1: Float, y: Float, passes: Int) = (0 until passes).flatMap { pass ->
            (0..12).map { step -> val t = step / 12f
                (if (pass % 2 == 0) x0 + (x1 - x0) * t else x1 - (x1 - x0) * t) to (y - 12f + pass * 5f) }
        }
        fun firstPoint(stroke: Stroke): Pair<Float, Float> =
            StrokeInput().let { stroke.inputs.populate(0, it); it.x to it.y }
        fun count() = page0.strokes.size
        fun onMainCheck(block: () -> Unit) {
            var failure: Throwable? = null
            runOnMainSync { try { block() } catch (e: Throwable) { failure = e } }
            failure?.let { throw it }
        }

        // Calibrate with one probe stroke, then take it back.
        runOnMainSync { scale = ink.currentScale() }
        originX = 0f; originY = 0f
        runOnMainSync {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val t = SystemClock.uptimeMillis()
            for ((i, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).withIndex()) {
                val coords = MotionEvent.PointerCoords().apply { x = 400f + i * 40f; y = 300f; pressure = 0.5f; size = 0.01f }
                val event = MotionEvent.obtain(t, t + i * 8, action, 1, arrayOf(props), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                ink.dispatchTouchEvent(event); event.recycle()
            }
        }
        Thread.sleep(300); waitForIdleSync()
        check(count() == 1) { "Probe stroke was not committed" }
        val (px, py) = firstPoint(page0.strokes[0])
        originX = 400f - px * scale
        originY = 300f - py * scale
        onMainCheck { ink.undo(); check(count() == 0) }
        report.appendLine("calibrated scale=$scale origin=($originX,$originY)")

        // ERA-05..10: eraser that leaves highlighter alone.
        pen(line(100f, 200f, 600f, 200f))
        runOnMainSync {
            ink.tool = Tool.HIGHLIGHTER; ink.colorArgb = 0x66FFEB3B; ink.strokeWidth = 20f; ink.prepareBrush()
        }
        pen(line(100f, 300f, 600f, 300f))
        check(count() == 2) { "Setup strokes missing: ${count()}" }
        runOnMainSync { ink.tool = Tool.ERASER; ink.eraseHighlighter = false }
        pen(line(350f, 150f, 350f, 350f))
        onMainCheck {
            check(count() == 1) { "Eraser filter: expected only the highlighter left, have ${count()}" }
            check(page0.strokes[0].brush.family == Tool.HIGHLIGHTER.brushFamily()) { "Eraser took the highlighter" }
            ink.undo(); check(count() == 2) { "Erase undo" }
        }
        runOnMainSync { ink.eraseHighlighter = true; ink.eraseInk = false }
        pen(line(350f, 150f, 350f, 350f))
        onMainCheck {
            check(count() == 1 && page0.strokes[0].brush.family == Tool.PEN.brushFamily()) { "Ink-only filter wrong" }
            ink.undo(); ink.eraseInk = true
        }
        report.appendLine("PASS eraser targets (pen / highlighter)")

        // ERA-15: scribble-out takes the word under it; over nothing it is ink.
        runOnMainSync {
            ink.tool = Tool.PEN; ink.colorArgb = 0xFF000000.toInt(); ink.strokeWidth = 4f; ink.prepareBrush()
            ink.scribbleErase = true
        }
        pen(line(150f, 500f, 400f, 510f))
        val beforeScribble = count()
        pen(zigzag(130f, 420f, 505f, 6))
        onMainCheck {
            check(count() == beforeScribble - 1) { "Scribble did not erase: ${count()} vs $beforeScribble" }
            ink.undo(); check(count() == beforeScribble) { "Scribble undo" }
        }
        pen(zigzag(130f, 420f, 700f, 6))
        onMainCheck {
            check(count() == beforeScribble + 1) { "Scribble over nothing was not kept as ink" }
            ink.undo(); ink.scribbleErase = false
        }
        report.appendLine("PASS scribble to erase")

        // CIR: a held loop selects and then drags; a held loop round nothing stays ink.
        pen(line(320f, 900f, 380f, 900f))
        val target = page0.strokes.last()
        val strokesBefore = count()
        runOnMainSync { ink.circleToLasso = true }
        val circle = loop(350f, 900f, 140f)
        val (endX, endY) = circle.last()
        // The lift itself is not a drag sample, so the last move is repeated.
        pen(circle, holdMs = 800, after = (1..10).map { (endX + it * 10f) to endY } + ((endX + 100f) to endY))
        onMainCheck {
            check(count() == strokesBefore) { "Circle stayed as ink: ${count()} vs $strokesBefore" }
            check(selected == 1) { "Circle did not select the line: $selected" }
            val moved = page0.strokes.last()
            check(moved !== target) { "Selection was not dragged" }
            val dx = firstPoint(moved).first - firstPoint(target).first
            check(abs(dx - 100f) < 6f) { "Drag after circle moved $dx" }
        }
        // Pen elsewhere lets the selection go and writes.
        pen(line(100f, 1200f, 200f, 1200f))
        onMainCheck {
            check(selected == 0) { "Writing outside did not release the selection" }
            check(count() == strokesBefore + 1)
            ink.undo()
        }
        pen(loop(900f, 1300f, 120f), holdMs = 800)
        onMainCheck {
            check(count() == strokesBefore + 1) { "Loop round nothing was not kept" }
            check(selected == 0)
            ink.undo(); ink.circleToLasso = false
        }
        report.appendLine("PASS circle to lasso (select, drag, release, empty loop)")

        // SEL-04 and SEL-16/18: whole-only lasso and the clipboard.
        pen(line(100f, 1500f, 800f, 1500f))
        runOnMainSync { ink.lassoMode = true }
        val lassoLoop = loop(450f, 1500f, 250f, turns = 1f)
        pen(lassoLoop)
        check(selected == 1) { "Centre lasso missed the long line: $selected" }
        runOnMainSync { ink.clearLassoSelection(); ink.lassoWholeOnly = true }
        pen(lassoLoop)
        check(selected == 0) { "Whole-only lasso took a line sticking out: $selected" }
        runOnMainSync { ink.lassoWholeOnly = false }
        pen(lassoLoop)
        onMainCheck {
            check(selected == 1)
            ink.copyLassoSelection()
            check(InkClipboard.clips.first().strokes.size == 1)
            val before = count()
            ink.paste(InkClipboard.clips.first())
            check(count() == before + 1 && selected == 1) { "Paste did not add and select" }
            ink.undo(); check(count() == before) { "Paste undo" }
        }
        // Undo let the selection go; select again and cut.
        pen(lassoLoop)
        onMainCheck {
            val before = count()
            ink.cutLassoSelection()
            check(count() == before - 1 && InkClipboard.clips.size >= 2) { "Cut" }
            ink.lassoMode = false
        }
        report.appendLine("PASS whole-only lasso, copy, cut, paste")

        // PAGE-21..27: page history, and a deleted page surviving an autosave sweep.
        var second: Page? = null
        onMainCheck {
            val ink0 = count()
            ink.addPage(0)
            check(document.pages.size == 2)
            ink.undo(); check(document.pages.size == 1) { "Add undo" }
            ink.redo(); check(document.pages.size == 2) { "Add redo" }
            ink.duplicatePage(0); check(document.pages.size == 3 && document.pages[1].strokes.size == ink0)
            ink.undo(); check(document.pages.size == 2) { "Duplicate undo" }
            second = document.pages[1]
            ink.movePage(0, 1); check(document.pages[0] === second)
            ink.undo(); check(document.pages[0] === page0) { "Move undo" }
            ink.setBackground(0, PageBackground.GRID); check(page0.background == PageBackground.GRID)
            ink.undo(); check(page0.background == PageBackground.BLANK) { "Paper undo" }
            ink.redo(); check(page0.background == PageBackground.GRID) { "Paper redo" }
            ink.undo()
        }
        store.save(note.id, note.title, document)
        onMainCheck { ink.deletePage(0); check(document.pages.size == 1 && document.pages[0] === second) }
        store.save(note.id, note.title, document) // sweeps the deleted page's files
        onMainCheck {
            ink.undo()
            check(document.pages.size == 2 && document.pages[0] === page0) { "Delete undo" }
        }
        val inkOnPage0 = count()
        store.save(note.id, note.title, document)
        val reloaded = store.load(note.id)
        val restored = store.loadPage(note.id, reloaded.pages.first { it.id == page0.id }, 0.02f)
        check(restored.size == inkOnPage0) { "Restored page lost ink on disk: ${restored.size} vs $inkOnPage0" }
        report.appendLine("PASS page add/duplicate/move/paper/delete undo-redo, restored page saved ($inkOnPage0 strokes)")

        // KEY-03/04: keyboard undo, redo and zoom.
        pen(line(100f, 1700f, 300f, 1700f))
        onMainCheck {
            check(ink.requestFocus()) { "Canvas cannot take focus" }
            val before = count()
            fun key(code: Int, meta: Int) {
                val now = SystemClock.uptimeMillis()
                ink.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
                ink.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
            }
            key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            check(count() == before - 1) { "Ctrl+Z" }
            key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
            check(count() == before) { "Ctrl+Shift+Z" }
            val zoom = ink.currentScale()
            key(KeyEvent.KEYCODE_EQUALS, KeyEvent.META_CTRL_ON)
            check(ink.currentScale() > zoom * 1.1f) { "Ctrl+= did not zoom" }
            key(KeyEvent.KEYCODE_MINUS, KeyEvent.META_CTRL_ON)
            check(abs(ink.currentScale() - zoom) < zoom * 0.01f) { "Ctrl+- did not zoom back" }
        }
        report.appendLine("PASS keyboard undo/redo/zoom")
    } finally {
        File(targetContext.cacheDir, "gesture-checks.txt").writeText(report.toString())
        store.delete(note.id)
        runOnMainSync { activity.finish() }
    }
}
