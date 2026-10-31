package com.notesis

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A pen (or a mouse) held over this without touching for [holdMs]: [onHold].
 * It may move about inside meanwhile; leaving, or touching down, calls
 * [onLeave] and starts the wait again.
 */
@Composable
internal fun Modifier.hoverHold(holdMs: Long, onHold: () -> Unit, onLeave: () -> Unit): Modifier {
    val hold by rememberUpdatedState(onHold)
    val leave by rememberUpdatedState(onLeave)
    return hoverHoldInput(holdMs, { hold() }, { leave() })
}

private fun Modifier.hoverHoldInput(holdMs: Long, onHold: () -> Unit, onLeave: () -> Unit): Modifier =
    pointerInput(holdMs) {
        coroutineScope {
            var waiting: Job? = null
            fun stop() {
                waiting?.cancel()
                waiting = null
            }
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    val hovers = change.type == PointerType.Stylus || change.type == PointerType.Mouse ||
                        change.type == PointerType.Eraser
                    when (event.type) {
                        PointerEventType.Enter, PointerEventType.Move -> {
                            if (change.pressed) {
                                stop()
                                onLeave()
                            } else if (hovers && waiting == null) {
                                waiting = launch { delay(holdMs); onHold() }
                            }
                        }
                        PointerEventType.Exit, PointerEventType.Press -> {
                            stop()
                            onLeave()
                        }
                    }
                }
            }
        }
    }

/** How long the pen rests over a page in the panel before it is shown large. */
internal const val PAGE_PEEK_HOLD_MS = 800L

/**
 * Page [number] large, beside the card at [card] (window coordinates) it was
 * asked for over: grown out of the card's side, the panel's glass round it,
 * the picture drawn at its own size once it is ready and [small] until then.
 * It takes no touches, so the pen over the card keeps it up.
 */
@Composable
internal fun PagePeek(
    card: Rect,
    number: Int,
    aspect: Float,
    small: Bitmap?,
    large: Bitmap?,
    shown: Boolean,
    onGone: () -> Unit,
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val moves = LocalSkin.current.animatesPopups && rememberLiquidGlassEffectsAllowed()
    val grow = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) grow.animateTo(1f, if (moves) spring(dampingRatio = 0.78f, stiffness = 520f) else snap())
        else {
            grow.animateTo(0f, if (moves) tween(140) else snap())
            onGone()
        }
    }
    // As large as the screen leaves beside the panel, up to a page you could read.
    val gap = with(density) { 16.dp.roundToPx() }
    val widthDp = pagePeekWidth(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat(), aspect,
        with(density) { card.left.toDp().value })
    val position = remember(card, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                popupContentSize: IntSize): IntOffset {
                val x = (card.left.toInt() - popupContentSize.width - gap).coerceAtLeast(gap)
                val y = (card.center.y.toInt() - popupContentSize.height / 2)
                    .coerceIn(gap, (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap))
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        Column(
            Modifier
                .graphicsLayer {
                    val g = grow.value
                    alpha = g.coerceIn(0f, 1f)
                    val s = 0.82f + 0.18f * g
                    scaleX = s
                    scaleY = s
                    // Out of the side that faces the card.
                    transformOrigin = TransformOrigin(1f, 0.5f)
                }
                .padding(Ui.Space.s)
                .width(widthDp.dp)
                .skinMaterial(androidx.compose.foundation.shape.RoundedCornerShape(Ui.Corner.card), panel = true)
                .padding(Ui.Space.s),
        ) {
            Box { PagePicture(large ?: small, aspect, number) }
            Text("Page $number", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Ui.Space.xs))
        }
    }
}

/**
 * How wide, in dp, the peek at a page [aspect] wide to tall is on a screen
 * [screenW] by [screenH]: it fits the room left of the card at [roomLeft], is
 * at most four fifths of the screen's height tall, and never under 220.
 */
internal fun pagePeekWidth(screenW: Float, screenH: Float, aspect: Float, roomLeft: Float): Float {
    val a = aspect.coerceIn(0.2f, 5f)
    val byHeight = screenH * 0.8f * a
    val byRoom = (roomLeft - 48f).coerceAtLeast(220f)
    return minOf(byHeight, byRoom, 720f, screenW * 0.6f).coerceAtLeast(220f)
}

/** The width in pixels a peek picture is drawn at: its dp width on this screen, in steps so near sizes share one. */
internal fun pagePeekPixels(widthDp: Float, density: Float): Int =
    (((widthDp * density) / 160f).toInt() + 1) * 160
