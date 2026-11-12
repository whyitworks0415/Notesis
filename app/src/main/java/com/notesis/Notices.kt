package com.notesis

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * The app's notices (Todo.md, A4): a pill that grows out of the button that
 * was pressed, says what happened, and goes back after a few seconds - with a
 * way to take it back where there is one ("Undo"). In place of Android's toast,
 * which came from nowhere at the foot of the screen and looked like another app.
 */

/** One notice. [actionLabel] and [onAction] go together: a button on the pill. */
internal class Notice(
    val text: String,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
    /** Where it comes out of, in the root's coordinates: the touch that led to it, if recent. */
    val from: Offset?,
    val long: Boolean,
)

internal object Notices {
    /** The notice on screen, if any; a new one takes the place of the last. */
    var current by mutableStateOf<Notice?>(null)
        private set

    /** How many windows show notices now; with none, Android's toast is used instead. */
    @Volatile internal var hosts = 0

    fun show(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, long: Boolean = false) {
        current = Notice(text, actionLabel, onAction, LastTouch.recent(), long || onAction != null)
    }

    fun dismiss(notice: Notice) { if (current === notice) current = null }
}

/** How long a notice stays: longer with something to do on it, or a long message. */
internal fun noticeShownMs(long: Boolean): Long = if (long) 5_000L else 2_800L

/**
 * Where a notice is drawn at [progress] (0 at [from], 1 at rest at [rest]):
 * its centre moves on a line between them and it grows from a fifth of its
 * size, so it is seen to come out of what was pressed.
 */
internal fun noticeOffset(from: Offset?, rest: Offset, progress: Float): Offset {
    val start = from ?: rest
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    return Offset(start.x + (rest.x - start.x) * p, start.y + (rest.y - start.y) * p) - rest
}

internal fun noticeScale(progress: Float): Float = 0.2f + 0.8f * (if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f)

/** The notices, over everything else in the window, at its foot. */
@Composable
internal fun NoticeHost() {
    DisposableEffect(Unit) {
        Notices.hosts++
        onDispose { Notices.hosts-- }
    }
    val notice = Notices.current ?: return
    val moves = rememberMotionAllowed()
    val grow = remember(notice) { Animatable(if (moves) 0f else 1f) }
    LaunchedEffect(notice) {
        if (moves) grow.animateTo(1f, MaterialMotion.rawContainer<Float>().paced())
        delay(noticeShownMs(notice.long))
        if (moves) grow.animateTo(0f, tween<Float>(180, easing = EmphasizedDecelerateEasing).paced())
        Notices.dismiss(notice)
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars), contentAlignment = Alignment.BottomCenter) {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier
                .padding(bottom = Ui.Space.xl, start = Ui.Space.l, end = Ui.Space.l)
                .widthIn(max = 560.dp)
                .outOfPoint(notice.from) { grow.value }
                .skinMaterial(CircleShape, panel = true)
                .heightIn(min = 48.dp)
                .padding(start = Ui.Space.xl, end = if (notice.actionLabel != null) Ui.Space.s else Ui.Space.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f, fill = false).padding(vertical = Ui.Space.m)) {
                Text(notice.text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (notice.actionLabel != null && notice.onAction != null) {
                TextButton(onClick = { notice.onAction.invoke(); Notices.dismiss(notice) },
                    modifier = Modifier.padding(start = Ui.Space.s).heightIn(min = 48.dp)) {
                    Text(notice.actionLabel)
                }
            }
        }
    }
}

/**
 * Draws what this is put on as coming out of [from] (a point in the root,
 * the touch that led to it) at [progress]: from a fifth of its size there to
 * full size where it rests. Read in the layer only, so a frame of it is a
 * property change, not a layout. With no [from], it grows where it rests.
 */
internal fun Modifier.outOfPoint(from: Offset?, progress: () -> Float): Modifier {
    val rest = floatArrayOf(Float.NaN, Float.NaN)
    return this
        .onGloballyPositioned { c -> c.boundsInRoot().center.let { rest[0] = it.x; rest[1] = it.y } }
        .graphicsLayer {
            val p = progress()
            if (!rest[0].isNaN()) {
                val shift = noticeOffset(from, Offset(rest[0], rest[1]), p)
                translationX = shift.x
                translationY = shift.y
            }
            scaleX = noticeScale(p)
            scaleY = noticeScale(p)
            transformOrigin = TransformOrigin.Center
            alpha = (p / 0.3f).coerceIn(0f, 1f)
        }
}
