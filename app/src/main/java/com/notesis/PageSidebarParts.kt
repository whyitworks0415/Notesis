package com.notesis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

internal const val PAGE_SIDEBAR_DEFAULT_DP = 340f
internal const val PAGE_SIDEBAR_MIN_DP = 240f
internal const val PAGE_SIDEBAR_MAX_DP = 720f

/** How many pages the page panel's grid puts on a row. */
internal val PAGE_GRID_COLUMNS = 2..5

/**
 * The page panel's width for a [requested] width in a window [screen] wide,
 * both in dp: never under its narrowest, and never more than most of the
 * window, so the page it is about is still beside it.
 */
internal fun pageSidebarWidth(requested: Float, screen: Float): Float {
    val ceiling = minOf(PAGE_SIDEBAR_MAX_DP, screen * 0.75f).coerceAtLeast(PAGE_SIDEBAR_MIN_DP)
    val wanted = if (requested.isFinite()) requested else PAGE_SIDEBAR_DEFAULT_DP
    return wanted.coerceIn(PAGE_SIDEBAR_MIN_DP, ceiling)
}

/**
 * Where on its page a strip of tape is, in words: "Top left", "Middle",
 * "Bottom right" - from the middle of its box ([centerX], [centerY]) on a
 * page [width] by [height].
 */
internal fun maskPlace(centerX: Float, centerY: Float, width: Float, height: Float): String {
    if (width <= 0f || height <= 0f) return "Middle"
    fun third(v: Float) = (v * 3).toInt().coerceIn(0, 2)
    val row = third(centerY / height)
    val column = third(centerX / width)
    val vertical = listOf("Top", "Middle", "Bottom")[row]
    val horizontal = listOf("left", "", "right")[column]
    return when {
        horizontal.isEmpty() -> vertical
        row == 1 -> horizontal.replaceFirstChar { it.uppercase() }
        else -> "$vertical $horizontal"
    }
}

/**
 * A page's picture from [ask], which draws it on a worker: it answers the
 * picture if it has it, and otherwise calls back when it is drawn. Keyed on
 * what the picture shows, so a page written on is pictured afresh; until the
 * new picture comes, the one before it ([stale], or the last one shown here)
 * stays rather than the card going blank. Leaving stops the wait, so a page
 * scrolled past is not drawn.
 */
@Composable
internal fun rememberPageThumbnail(
    key: Any,
    stale: () -> Bitmap?,
    ask: (onReady: () -> Unit) -> InkCanvasView.ThumbnailRequest?,
): Bitmap? {
    var picture by remember { mutableStateOf(stale()) }
    DisposableEffect(key) {
        var live = true
        var cancel: () -> Unit = {}
        fun fetch() {
            val request = ask { if (live) fetch() } ?: return
            cancel = request.cancel
            request.picture?.let { picture = it }
        }
        fetch()
        onDispose {
            live = false
            cancel()
        }
    }
    return picture
}

/**
 * A picture file read off the main thread, scaled down on the way in to about
 * [widthPx] wide: a list of full-size pictures decoded in composition, one per
 * card as it scrolled in, was what made a long list of notes stutter.
 */
@Composable
internal fun rememberFileBitmap(file: File?, widthPx: Int): Bitmap? {
    val stamp = file?.lastModified() ?: 0L
    // Lazy reuse may bind this slot to another file. Restart the state as
    // well as the producer: produceState's keys alone keep the old value,
    // which would skip decoding and leave the previous thumbnail in place.
    return key(file?.path, stamp, widthPx) {
        val picture by produceState<Bitmap?>(FileBitmaps.cached(file, stamp, widthPx)) {
            if (value != null || file == null) return@produceState
            value = withContext(FileBitmaps.decoder) { FileBitmaps.decode(file, stamp, widthPx) }
        }
        picture
    }
}

/** Decoded pictures of files, by path, change time and width, kept for when a card scrolls back in. */
internal object FileBitmaps {
    // A screen of cards and the rows either side of it, once each is no
    // wider than asked (see [decode]).
    private val cache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).coerceIn(16L * 1024 * 1024, 48L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * Two decodes at a time, not one per card. A fling brings a dozen cards in
     * at once, and on the IO pool each started its own decode: the tablet's
     * cores all went to PNG decoding and allocation while the list was trying
     * to draw its next frame.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val decoder: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    /** How many pictures [widthPx] wide at about [aspect] height over width the cache holds with room to spare. */
    fun roomFor(widthPx: Int, aspect: Float = 4f / 3f): Int =
        prefetchCount(cache.maxSize().toLong(), widthPx, aspect)

    /** Decodes [file] into the cache if it is not there; for warming it before the list scrolls. */
    fun warm(file: File, widthPx: Int) {
        val stamp = file.lastModified()
        if (cached(file, stamp, widthPx) == null) decode(file, stamp, widthPx)
    }

    private fun keyOf(file: File, stamp: Long, widthPx: Int) = "${file.path}:$stamp:$widthPx"

    fun cached(file: File?, stamp: Long, widthPx: Int): Bitmap? = file?.let { cache.get(keyOf(it, stamp, widthPx)) }

    /**
     * The newest picture of [file] at [widthPx] in the cache, whatever its
     * stamp: for a note just closed, whose picture may have been drawn again
     * a moment ago and not read back yet.
     */
    fun newestCached(file: File?, widthPx: Int): Bitmap? {
        file ?: return null
        val prefix = file.path + ":"
        val suffix = ":$widthPx"
        return cache.snapshot().entries
            .filter { it.key.startsWith(prefix) && it.key.endsWith(suffix) }
            .maxByOrNull { it.key.removePrefix(prefix).removeSuffix(suffix).toLongOrNull() ?: 0L }?.value
    }

    fun decode(file: File, stamp: Long, widthPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, widthPx)
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return@runCatching null
        // A power-of-two sample leaves it up to twice as wide as asked: a
        // card's picture came out at up to 960 px, four times the pixels,
        // each a texture to upload as the card scrolled in and a quarter as
        // many kept in the cache. Scaled to the width here, off the main thread.
        val fitted = fittedSize(decoded.width, decoded.height, widthPx)?.let { (w, h) ->
            Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
        } ?: decoded
        // Start the texture upload now, on the render thread, rather than on
        // the frame the card first draws in.
        fitted.prepareToDraw()
        fitted.also { cache.put(keyOf(file, stamp, widthPx), it) }
    }.getOrNull()
}

/**
 * How many pictures [widthPx] wide and [aspect] times as tall fit in a cache
 * of [cacheBytes], leaving a quarter of it for the other pictures it holds.
 */
internal fun prefetchCount(cacheBytes: Long, widthPx: Int, aspect: Float): Int {
    if (cacheBytes <= 0 || widthPx <= 0 || !(aspect > 0f)) return 0
    val each = widthPx.toLong() * (widthPx * aspect).toLong().coerceAtLeast(1) * 4
    return (cacheBytes * 3 / 4 / each).toInt()
}

/**
 * The size a [width] by [height] picture is scaled to so it is [wanted] wide,
 * or null when it is near enough already (within a tenth) or narrower.
 */
internal fun fittedSize(width: Int, height: Int, wanted: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0 || wanted <= 0 || width <= wanted * 11 / 10) return null
    return wanted to (height.toLong() * wanted / width).toInt().coerceAtLeast(1)
}

/** The largest power of two that still leaves a picture [width] wide at least [wanted] wide. */
internal fun sampleSizeFor(width: Int, wanted: Int): Int {
    if (width <= 0 || wanted <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= wanted) sample *= 2
    return sample
}

/** A page's picture on white paper, or its number while the picture is still being drawn. */
@Composable
internal fun PagePicture(picture: Bitmap?, aspect: Float, number: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.2f, 5f))
            .clip(Ui.Shape.preview)
            .background(Color.White)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, Ui.Shape.preview),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null && !picture.isRecycled) {
            // Drawn at the card's own width (see pageThumbnailWidthPx); the last few
            // pixels of scaling are filtered rather than sampled.
            Image(picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                modifier = Modifier.fillMaxSize())
        } else {
            Text("$number", style = MaterialTheme.typography.titleMedium, color = Color(0xFF9AA0A6))
        }
    }
}

/**
 * The part of a page's [picture] round a strip of tape: its box [left],
 * [top], [right], [bottom] in page units on a page [pageWidth] wide, with a
 * margin round it so it is seen where it lies. Covered, the strip is drawn
 * over it as tape; revealed, what it hides shows.
 */
@Composable
internal fun MaskCrop(
    picture: Bitmap?, pageWidth: Float, pageHeight: Float,
    left: Float, top: Float, right: Float, bottom: Float, covered: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(Ui.Shape.preview)
            .background(Color.White)
            .border(0.5.dp, scheme.outlineVariant, Ui.Shape.preview),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null && !picture.isRecycled && pageWidth > 0f && pageHeight > 0f) {
            val scale = picture.width / pageWidth
            val margin = maxOf(right - left, bottom - top) * 0.25f + 8f
            val x0 = ((left - margin) * scale).roundToInt().coerceIn(0, picture.width - 1)
            val y0 = ((top - margin) * scale).roundToInt().coerceIn(0, picture.height - 1)
            val x1 = ((right + margin) * scale).roundToInt().coerceIn(x0 + 1, picture.width)
            val y1 = ((bottom + margin) * scale).roundToInt().coerceIn(y0 + 1, picture.height)
            val image = remember(picture) { picture.asImageBitmap() }
            val painter = remember(image, x0, y0, x1, y1) {
                BitmapPainter(image, IntOffset(x0, y0), IntSize(x1 - x0, y1 - y0))
            }
            Image(painter, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (covered) {
                // The strip itself, where it lies in the crop.
                val w = (x1 - x0).toFloat()
                val h = (y1 - y0).toFloat()
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    // Fitted as the image is: the same scale both ways, centred.
                    val fit = minOf(size.width / w, size.height / h)
                    val ox = (size.width - w * fit) / 2f
                    val oy = (size.height - h * fit) / 2f
                    drawRect(
                        color = TAPE,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            ox + (left * scale - x0) * fit, oy + (top * scale - y0) * fit),
                        size = androidx.compose.ui.geometry.Size(
                            (right - left) * scale * fit, (bottom - top) * scale * fit),
                    )
                }
            }
        }
    }
}

private val TAPE = Color(0xFFF2C14E)

/**
 * A note in the page panel's Notes tab: its picture and title. Tapping opens
 * it; holding offers it beside this one as well.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteTile(
    note: NoteMeta, current: Boolean,
    onOpen: () -> Unit, onOpenBeside: () -> Unit,
    modifier: Modifier = Modifier,
    /** Held and dragged: where the finger is in the window, null once let go or called off. */
    onDrag: (androidx.compose.ui.geometry.Offset?) -> Unit = {},
    /** Held, dragged and let go at a point in the window. */
    onDrop: (androidx.compose.ui.geometry.Offset) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    var travelled by remember { mutableStateOf(0f) }
    var last by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val scheme = MaterialTheme.colorScheme
    val picture = rememberFileBitmap(note.thumbnail, NOTE_TILE_PX)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Ui.Shape.chip)
                .onGloballyPositioned { placed[0] = it }
                .combinedClickable(onClick = { if (!current) onOpen() })
                // Held: dragged onto the note it opens beside it there; held still, its menu.
                .pointerInput(current) {
                    if (current) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            travelled = 0f
                            last = placed[0]?.takeIf { it.isAttached }?.localToWindow(at) ?: at
                            onDrag(last)
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            travelled += delta.getDistance()
                            last = placed[0]?.takeIf { it.isAttached }?.localToWindow(change.position) ?: last
                            onDrag(last)
                        },
                        onDragEnd = {
                            onDrag(null)
                            if (travelled < 24f) menu = true else onDrop(last)
                        },
                        onDragCancel = { onDrag(null) },
                    )
                }
                .padding(Ui.Space.xs),
            verticalArrangement = Arrangement.spacedBy(Ui.Space.xs),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(Ui.Shape.preview)
                    .background(Color.White)
                    .border(if (current) 2.dp else 0.5.dp, if (current) scheme.primary else scheme.outlineVariant, Ui.Shape.preview),
                contentAlignment = Alignment.Center,
            ) {
                if (picture != null) {
                    Image(picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                } else {
                    Icon(AppIcons.note, contentDescription = null, tint = Color(0xFF9AA0A6))
                }
            }
            Text(
                note.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.labelMedium,
                color = if (current) scheme.primary else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text("Open beside this note") }, onClick = { menu = false; onOpenBeside() })
        }
    }
}

private const val NOTE_TILE_PX = 320

/** The tile that starts a new note, the size of the notes beside it. */
@Composable
internal fun NewNoteTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(Ui.Shape.chip)
            .clickable(onClick = onClick)
            .padding(Ui.Space.xs),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.xs),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(Ui.Shape.preview)
                .background(scheme.onSurface.copy(alpha = 0.05f))
                .border(1.dp, scheme.outlineVariant, Ui.Shape.preview),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.addPage, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(32.dp))
        }
        Text("New note", style = MaterialTheme.typography.labelMedium, color = scheme.primary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** − n + for how many pages go on a row of the grid. */
@Composable
internal fun ColumnsStepper(columns: Int, onColumns: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onColumns((columns - 1).coerceIn(PAGE_GRID_COLUMNS)) },
            enabled = columns > PAGE_GRID_COLUMNS.first,
            modifier = Modifier.size(32.dp),
        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text("$columns", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            modifier = Modifier.width(STEPPER_NUMBER))
        IconButton(
            onClick = { onColumns((columns + 1).coerceIn(PAGE_GRID_COLUMNS)) },
            enabled = columns < PAGE_GRID_COLUMNS.last,
            modifier = Modifier.size(32.dp),
        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}

private val STEPPER_NUMBER: Dp = 20.dp
