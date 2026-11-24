package com.notesis

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.storage.StrokeInputBatchSerialization
import androidx.ink.strokes.Stroke
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Handwriting between a Markdown note's paragraphs, kept the way Obsidian's
 * drawing plugins keep theirs: a picture the note embeds (`![[ink-….png]]`),
 * which any Markdown app shows, and beside it the strokes (the same name and
 * `.ink`) that Notesis reads back to go on writing. The pen writes on the
 * block at once; a finger scrolls the note past it.
 */

/** Whether an embedded file is one of these blocks' pictures. */
internal fun isInkName(name: String): Boolean = name.startsWith("ink-") && name.endsWith(".png")

/** A new block's picture name. */
internal fun inkFileName(time: Long): String = "ink-$time.png"

/** The strokes' file beside the picture [png]. */
internal fun inkStrokesName(png: String): String = "$png.ink"

/** The block's own units: as wide as this, whatever the screen, so writing keeps its place on any width. */
internal const val INK_BLOCK_WIDTH = 1000f

/** One block: its height in its own units and its strokes. */
internal class InkPage(val height: Float, val strokes: List<Stroke>)

private const val INK_MAGIC = 0x4E4B4231 // "NKB1"

/** The strokes as kept beside the picture: a mark, the height, then each stroke's colour, size and inputs. */
internal fun encodeInk(height: Float, strokes: List<Stroke>): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeInt(INK_MAGIC)
        out.writeFloat(height)
        out.writeInt(strokes.size)
        val inputs = ByteArrayOutputStream()
        for (stroke in strokes) {
            out.writeInt(stroke.brush.colorIntArgb)
            out.writeFloat(stroke.brush.size)
            inputs.reset()
            StrokeInputBatchSerialization.encode(stroke.inputs, inputs)
            out.writeInt(inputs.size())
            inputs.writeTo(out)
        }
    }
    return bytes.toByteArray()
}

/** What [encodeInk] wrote, or null when it is not that; a stroke that does not read is left out. */
internal fun decodeInk(bytes: ByteArray): InkPage? = runCatching {
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readInt() != INK_MAGIC) return null
        val height = input.readFloat()
        val count = input.readInt()
        require(count in 0..100_000)
        val strokes = ArrayList<Stroke>(count)
        repeat(count) {
            val color = input.readInt()
            val size = input.readFloat()
            val length = input.readInt()
            require(length in 0..(64 shl 20))
            val raw = ByteArray(length).also(input::readFully)
            runCatching {
                val batch = StrokeInputBatchSerialization.decode(ByteArrayInputStream(raw))
                strokes += Stroke(inkBrush(color, size), batch)
            }
        }
        InkPage(height, strokes)
    }
}.getOrNull()

/** The note pen's look, in the block's units. */
internal fun inkBrush(color: Int = Color.BLACK, size: Float = INK_PEN_SIZE): Brush =
    Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), color, size, INK_EPSILON)

private const val INK_PEN_SIZE = 3.2f
private const val INK_EPSILON = 0.1f
/** How tall a new block is, in its own units: about a third of its width. */
internal const val INK_BLOCK_HEIGHT = 360f

/**
 * The block on screen: the strokes drawn by [CanvasStrokeRenderer] and the one
 * being written by [InProgressStrokesView], the low-latency layer the notes'
 * pages use. A stylus writes, its eraser end (or button) takes strokes away;
 * anything else is left to the note to scroll.
 */
@SuppressLint("ViewConstructor")
private class InkBlockView(context: Context, private val onChanged: () -> Unit) : FrameLayout(context), InProgressStrokesFinishedListener {
    val strokes = ArrayList<Stroke>()
    var pageHeight = INK_BLOCK_HEIGHT
    private val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
    private val toView = Matrix()
    private val toPage = Matrix()
    private var current: InProgressStrokeId? = null
    private var erasing = false
    private val dry = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            for (stroke in strokes) renderer.draw(canvas, stroke, toView)
        }
    }
    private val wet = InProgressStrokesView(context)

    init {
        addView(dry)
        addView(wet)
        wet.addFinishedStrokesListener(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = w / INK_BLOCK_WIDTH
        toView.setScale(scale, scale)
        toView.invert(toPage)
        dry.invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val tool = event.getToolType(index)
        val pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (!pen && current == null && !erasing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (android.os.Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(event)
                erasing = tool == MotionEvent.TOOL_TYPE_ERASER || event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
                if (erasing) erase(event.x, event.y)
                else current = wet.startStroke(event, event.getPointerId(index), inkBrush(), toPage)
            }
            MotionEvent.ACTION_MOVE -> {
                if (erasing) for (h in 0 until event.historySize) erase(event.getHistoricalX(h), event.getHistoricalY(h))
                if (erasing) erase(event.x, event.y)
                else current?.let { wet.addToStroke(event, event.getPointerId(0), it) }
            }
            MotionEvent.ACTION_UP -> {
                current?.let { wet.finishStroke(event, event.getPointerId(index), it) }
                current = null
                if (erasing) { erasing = false; onChanged() }
            }
            MotionEvent.ACTION_CANCEL -> {
                current?.let { wet.cancelStroke(it, event) }
                current = null
                erasing = false
            }
        }
        return true
    }

    private fun erase(x: Float, y: Float) {
        val at = floatArrayOf(x, y).also(toPage::mapPoints)
        val tip = ImmutableBox.fromCenterAndDimensions(ImmutableVec(at[0], at[1]), ERASER_SIZE, ERASER_SIZE)
        val gone = with(Intersection) { strokes.removeAll { tip.intersects(it.shape, IDENTITY) } }
        if (gone) dry.invalidate()
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        this.strokes += strokes.values
        dry.invalidate()
        wet.removeFinishedStrokes(strokes.keys)
        onChanged()
    }

    /** The block as a picture, [width] pixels wide, on white: what any Markdown app shows of it. */
    fun picture(width: Int = PICTURE_WIDTH): Bitmap {
        val scale = width / INK_BLOCK_WIDTH
        val bitmap = Bitmap.createBitmap(width, (pageHeight * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val transform = Matrix().apply { setScale(scale, scale) }
        for (stroke in strokes) renderer.draw(canvas, stroke, transform)
        return bitmap
    }

    private companion object {
        val IDENTITY = androidx.ink.geometry.AffineTransform.IDENTITY
        const val ERASER_SIZE = 16f
        const val PICTURE_WIDTH = 1200
    }
}

/**
 * The block for the picture [name] in [source]: read from the strokes beside
 * it, written on with the pen, and kept - strokes and picture - a moment after
 * the pen lifts.
 */
@Composable
internal fun InkBlock(name: String, source: MarkdownSource, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val saving = remember { arrayOfNulls<Job>(1) }
    val holder = remember { arrayOfNulls<InkBlockView>(1) }
    fun keep() {
        val view = holder[0] ?: return
        saving[0]?.cancel()
        saving[0] = scope.launch {
            // Written in a burst, it is kept once the burst is over.
            delay(SAVE_AFTER_MS)
            val strokes = encodeInk(view.pageHeight, view.strokes.toList())
            val picture = view.picture()
            withContext(Dispatchers.IO) {
                val png = ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                picture.recycle()
                source.replaceFile(inkStrokesName(name), strokes)
                source.replaceFile(name, png)
            }
        }
    }
    val aspect = INK_BLOCK_HEIGHT / INK_BLOCK_WIDTH
    AndroidView(
        factory = { context -> InkBlockView(context) { keep() }.also { holder[0] = it } },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f / aspect)
            .clip(Ui.Shape.preview)
            .border(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant, Ui.Shape.preview),
    )
    LaunchedEffect(name) {
        val page = withContext(Dispatchers.IO) {
            runCatching { source.openFile(inkStrokesName(name))?.use { it.readBytes() } }.getOrNull()?.let(::decodeInk)
        }
        holder[0]?.let { view ->
            view.strokes.clear()
            page?.let { view.strokes += it.strokes; view.pageHeight = it.height }
            view.invalidate()
            (view.getChildAt(0)).invalidate()
        }
    }
    DisposableEffect(name) {
        onDispose {
            // Left before its pause was over, it is kept now rather than lost.
            if (saving[0]?.isActive == true) { saving[0]?.cancel(); holder[0]?.let { flushInk(it, name, source) } }
        }
    }
}

/** Keeps [view]'s strokes and picture now, off the main thread. */
private fun flushInk(view: InkBlockView, name: String, source: MarkdownSource) {
    val strokes = encodeInk(view.pageHeight, view.strokes.toList())
    val picture = view.picture()
    Thread {
        val png = ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        picture.recycle()
        kotlinx.coroutines.runBlocking {
            source.replaceFile(inkStrokesName(name), strokes)
            source.replaceFile(name, png)
        }
    }.start()
}

private const val SAVE_AFTER_MS = 800L
