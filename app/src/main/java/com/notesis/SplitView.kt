package com.notesis

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The note screen split in two: this note in one part, a second canvas (the
 * reference panel, pinned in place) in the other.
 */
internal enum class SplitMode(val label: String) {
    NONE("나누지 않기"), STACKED("위아래로 나누기"), SIDE("좌우로 나누기");

    companion object {
        /** How far the seam may go: neither side smaller than this share of the screen. */
        const val MIN_SHARE = 0.2f
    }
}

/** Where the seam sits for a drag of [delta] pixels across [length], held to [SplitMode.MIN_SHARE]. */
internal fun movedSplit(ratio: Float, delta: Float, length: Float): Float =
    if (length <= 0f) ratio else (ratio + delta / length).coerceIn(SplitMode.MIN_SHARE, 1f - SplitMode.MIN_SHARE)

@Composable
internal fun SplitDialog(current: SplitMode, onPick: (SplitMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("화면 나누기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "한쪽에는 이 노트, 다른 쪽에는 고른 노트가 열립니다. 가운데 손잡이를 끌어 크기를 바꿉니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                SplitMode.entries.forEach { mode ->
                    SettingsChoiceChip(selected = mode == current, onClick = { onPick(mode) }, label = mode.label)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/**
 * The handle on the seam between the two parts. Dragging moves a guide;
 * the parts are re-laid out once, when the hand comes off, so neither canvas
 * rebuilds its page on every frame of the drag.
 */
@Composable
internal fun SplitSeam(
    mode: SplitMode,
    ratio: Float,
    width: Float,
    height: Float,
    onRatio: (Float) -> Unit,
) {
    if (mode == SplitMode.NONE || width <= 0f || height <= 0f) return
    val density = LocalDensity.current
    var draft by remember { mutableStateOf<Float?>(null) }
    val shown = draft ?: ratio
    val thick = with(density) { 22.dp.toPx() }
    val stacked = mode == SplitMode.STACKED
    val at = if (stacked) height * shown else width * shown
    val latestRatio by rememberUpdatedState(ratio)
    Box(
        Modifier
            .offset { if (stacked) IntOffset(0, (at - thick / 2).roundToInt()) else IntOffset((at - thick / 2).roundToInt(), 0) }
            .then(if (stacked) Modifier.fillMaxWidth().height(22.dp) else Modifier.fillMaxHeight().width(22.dp))
            .pointerInput(mode, width, height) {
                detectDragGestures(
                    onDragStart = { draft = latestRatio },
                    onDragEnd = { draft?.let(onRatio); draft = null },
                    onDragCancel = { draft = null },
                ) { change, delta ->
                    change.consume()
                    val from = draft ?: latestRatio
                    draft = if (stacked) movedSplit(from, delta.y, height) else movedSplit(from, delta.x, width)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val scheme = MaterialTheme.colorScheme
        // The seam line itself, then a grip in the middle of it.
        Box(
            (if (stacked) Modifier.fillMaxWidth().height(if (draft != null) 3.dp else 1.dp)
            else Modifier.fillMaxHeight().width(if (draft != null) 3.dp else 1.dp))
                .background(if (draft != null) scheme.primary else scheme.outlineVariant),
        )
        Box(
            (if (stacked) Modifier.size(56.dp, 8.dp) else Modifier.size(8.dp, 56.dp))
                .background(if (draft != null) scheme.primary else scheme.outline, RoundedCornerShape(4.dp)),
        )
    }
}

/**
 * Accepts pictures dragged in from another app, which in Android's own split
 * screen is the natural way to bring one over. Each dropped image goes to [onImage].
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.dropImages(onImage: (Uri) -> Unit): Modifier = composed {
    val activity = LocalContext.current as? Activity
    val latest by rememberUpdatedState(onImage)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                // Another app's uri is readable only with the drop's grant.
                runCatching { activity?.requestDragAndDropPermissions(drag) }
                val clip = drag.clipData ?: return false
                var taken = false
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { latest(it); taken = true }
                }
                return taken
            }
        }
    }
    dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.mimeTypes().any { it.startsWith("image/") } },
        target = target,
    )
}

/** Room the main canvas leaves for the pinned panel. */
internal fun splitPadding(mode: SplitMode, ratio: Float, container: androidx.compose.ui.unit.IntSize,
    density: androidx.compose.ui.unit.Density): Modifier = with(density) {
    when (mode) {
        SplitMode.NONE -> Modifier
        SplitMode.STACKED -> Modifier.padding(bottom = (container.height * (1f - ratio)).toDp())
        SplitMode.SIDE -> Modifier.padding(end = (container.width * (1f - ratio)).toDp())
    }
}
