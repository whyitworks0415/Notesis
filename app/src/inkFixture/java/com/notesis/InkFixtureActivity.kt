// Same Ink 1.0 immutable-input API already used by InkCanvasView.
@file:Suppress("RestrictedApi")
package com.notesis

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import java.util.concurrent.Executors

/** Deterministic device fixture, compiled only into debug and benchmarkRelease. */
class InkFixtureActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var ink: InkCanvasView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        val count = intent.getIntExtra("strokes", 5000).takeIf { it in listOf(500, 1500, 5000, 10000) } ?: 5000
        val next = Button(this).apply { text = "Next page"; contentDescription = "Next page" }
        root.addView(next)
        worker.execute {
            val pages = MutableList(3) { pageIndex ->
                Page(strokes = MutableList(count) { i ->
                    val long = i % 17 == 0
                    val x = if (long) 20f else 20f + (i % 16) * 72f
                    val y = 24f + ((i / 16 + pageIndex * 7) % 80) * 20f
                    val inputs = MutableStrokeInputBatch()
                    repeat(if (long) 64 else 4) { sample ->
                        inputs.add(InputToolType.STYLUS, x + sample * if (long) 18f else 10f,
                            y + kotlin.math.sin(sample * .5f) * 5f, sample * 4L, pressure = .7f)
                    }
                    Stroke(Brush.createWithColorIntArgb(
                        (if (i % 4 == 0) Tool.HIGHLIGHTER else Tool.PEN).brushFamily(),
                        if (i % 4 == 0) 0x66FFCC00 else 0xFF163D6A.toInt(), if (i % 4 == 0) 14f else 2f, .1f),
                        inputs.toImmutable())
                })
            }
            val text = TextBoxContent("한글 / Text fixture", 28f, 0xFF333333.toInt())
            val picture = renderTextBox(text)
            pages[0].images += PageImage("fixture-text", 160f, 300f, 400f, 100f, text)
            pages[0].images += PageImage("fixture-image", 500f, 700f, 300f, 150f)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                ink = InkCanvasView(this).also { view ->
                    view.contentDescription = "Ink fixture $count"
                    view.imageLoader = { picture }
                    view.open(Document(pages), null)
                    root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
                    next.setOnClickListener { view.scrollToPage((view.currentPageIndex() + 1) % 3) }
                }
            }
        }
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
