package com.notesis

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The page in miniature with the part on screen boxed, shown while zoomed in.
 * Tapping or dragging on it brings that point of the page to the middle of
 * the screen - the quick way back across a page enlarged past the screen.
 *
 * [tick] changes whenever the view moves; it is read in here, which keeps
 * the box in step without recomposing the editor around it.
 */
@Composable
internal fun Minimap(view: InkCanvasView, page: Int, tick: () -> Int, modifier: Modifier = Modifier) {
    // Read here, not by the caller, so following the view recomposes only this.
    val moved = tick()
    var picture by remember(page) { mutableStateOf<Bitmap?>(null) }
    // Pictured afresh as the page is written on, a little after the fact.
    LaunchedEffect(page, moved / MINIMAP_REFRESH_TICKS) {
        repeat(MINIMAP_ATTEMPTS) {
            val ready = view.pageThumbnail(page, MINIMAP_PX)
            if (ready != null) {
                picture = ready
                return@LaunchedEffect
            }
            delay(MINIMAP_RETRY_MS)
        }
    }
    val bitmap = picture ?: return
    val widthDp = MINIMAP_WIDTH
    val heightDp = widthDp * bitmap.height / bitmap.width.coerceAtLeast(1)
    val pageSize = view.document.pages.getOrNull(page)?.let { it.width to it.height } ?: return
    val visible = view.visiblePartOf(page)
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    fun navigate(position: Offset, size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        view.centerOn(
            page,
            (position.x / size.width).coerceIn(0f, 1f) * pageSize.first,
            (position.y / size.height).coerceIn(0f, 1f) * pageSize.second,
        )
    }
    Box(
        modifier
            .size(widthDp, heightDp)
            .shadow(6.dp, shape)
            .clip(shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pointerInput(page) {
                detectTapGestures { navigate(it, size) }
            }
            .pointerInput(page) {
                detectDragGestures { change, _ ->
                    change.consume()
                    navigate(change.position, size)
                }
            },
    ) {
        Canvas(Modifier.size(widthDp, heightDp)) {
            drawImage(
                bitmap.asImageBitmap(),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                dstOffset = IntOffset.Zero,
            )
            if (visible != null) {
                val sx = size.width / pageSize.first
                val sy = size.height / pageSize.second
                // At least a few pixels, so a deep zoom still shows where it is.
                val minimum = 6.dp.toPx()
                val w = (visible.width() * sx).coerceAtLeast(minimum)
                val h = (visible.height() * sy).coerceAtLeast(minimum)
                val left = (visible.centerX() * sx - w / 2f).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
                val top = (visible.centerY() * sy - h / 2f).coerceIn(0f, (size.height - h).coerceAtLeast(0f))
                drawRect(accent.copy(alpha = 0.14f), Offset(left, top), Size(w, h))
                drawRect(accent, Offset(left, top), Size(w, h), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** Shown only once the page is enlarged this far past fit-to-width. */
internal const val MINIMAP_MIN_ZOOM = 1.5f
private val MINIMAP_WIDTH = 120.dp
private const val MINIMAP_PX = 240
private const val MINIMAP_ATTEMPTS = 12
private const val MINIMAP_RETRY_MS = 80L
/** Movement ticks between re-pictures of the page; the box itself follows every tick. */
private const val MINIMAP_REFRESH_TICKS = 240
