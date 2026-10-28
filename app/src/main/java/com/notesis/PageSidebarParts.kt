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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * A page's picture from [ask], which draws it on a worker and answers null
 * until it has: asked again a little later until it comes, for as long as
 * [key] stays the same. Keyed on what the picture shows, so a page written
 * on is pictured afresh.
 */
@Composable
internal fun rememberPageThumbnail(key: Any, ask: () -> Bitmap?): Bitmap? {
    var picture by remember(key) { mutableStateOf(ask()) }
    LaunchedEffect(key) {
        if (picture != null) return@LaunchedEffect
        repeat(PAGE_THUMB_ATTEMPTS) {
            delay(PAGE_THUMB_RETRY_MS)
            ask()?.let {
                picture = it
                return@LaunchedEffect
            }
        }
    }
    return picture
}

private const val PAGE_THUMB_ATTEMPTS = 60
private const val PAGE_THUMB_RETRY_MS = 120L

/**
 * A picture file read off the main thread, scaled down on the way in to about
 * [widthPx] wide: a list of full-size pictures decoded in composition, one per
 * card as it scrolled in, was what made a long list of notes stutter.
 */
@Composable
internal fun rememberFileBitmap(file: File?, widthPx: Int): Bitmap? {
    val stamp = file?.lastModified() ?: 0L
    val picture by produceState<Bitmap?>(FileBitmaps.cached(file, stamp, widthPx), file?.path, stamp, widthPx) {
        if (value != null || file == null) return@produceState
        value = withContext(Dispatchers.IO) { FileBitmaps.decode(file, stamp, widthPx) }
    }
    return picture
}

/** Decoded pictures of files, by path, change time and width, kept for when a card scrolls back in. */
internal object FileBitmaps {
    private val cache = object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun keyOf(file: File, stamp: Long, widthPx: Int) = "${file.path}:$stamp:$widthPx"

    fun cached(file: File?, stamp: Long, widthPx: Int): Bitmap? = file?.let { cache.get(keyOf(it, stamp, widthPx)) }

    fun decode(file: File, stamp: Long, widthPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, widthPx)
        }
        BitmapFactory.decodeFile(file.path, options)?.also { cache.put(keyOf(file, stamp, widthPx), it) }
    }.getOrNull()
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
            Image(picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds,
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
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val picture = rememberFileBitmap(note.thumbnail, NOTE_TILE_PX)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Ui.Shape.chip)
                .combinedClickable(onClick = { if (!current) onOpen() }, onLongClick = { if (!current) menu = true })
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
