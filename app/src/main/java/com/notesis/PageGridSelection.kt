package com.notesis

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The pages a sweep leaves chosen: those in [base], with every page from
 * [from] to [to] in [order] (either way round) added, or taken out when
 * [adding] is false. In page order.
 */
internal fun sweptSelection(order: List<String>, base: Set<String>, from: Int, to: Int, adding: Boolean): List<String> {
    if (order.isEmpty()) return emptyList()
    val a = from.coerceIn(order.indices)
    val b = to.coerceIn(order.indices)
    val range = order.subList(minOf(a, b), maxOf(a, b) + 1).toHashSet()
    return order.filter { id -> if (id in range) adding else id in base }
}

/** Pages [from] to [to] of [count] (1-based, either way round), as indices; empty if neither is a page. */
internal fun pageRange(from: Int, to: Int, count: Int): IntRange {
    if (count <= 0) return IntRange.EMPTY
    val a = (minOf(from, to) - 1).coerceIn(0, count - 1)
    val b = (maxOf(from, to) - 1).coerceIn(0, count - 1)
    return a..b
}

/**
 * Where each card of the page grid is on screen, and the page tapped last:
 * what a hold and a sweep across the grid are measured against.
 */
internal class PageGridSweep {
    val bounds = HashMap<String, Rect>()
    var grid: LayoutCoordinates? = null
    /** Where the last tap or hold was, the start of the next "from here to there". */
    var anchor: String? = null

    fun pageAt(local: Offset): String? {
        val coordinates = grid?.takeIf { it.isAttached } ?: return null
        val at = coordinates.localToRoot(local)
        return bounds.entries.firstOrNull { it.value.contains(at) }?.key
    }
}

/**
 * Selecting in the page grid by hand. Hold a card to start choosing with that
 * page; keep the finger down and sweep, and every page passed over is taken
 * in too (or, starting on a chosen page, taken out), the grid scrolling on
 * near its top and bottom. While choosing, a hold after a tap takes in every
 * page from the tapped one to the held one. A tap stays the card's own.
 *
 * Read in the Initial pass once the hold has fired, so the grid does not
 * scroll under the sweep and the card does not also take the lift as a tap.
 */
internal fun Modifier.pageGridSelection(
    sweep: PageGridSweep,
    gridState: LazyGridState,
    haptics: HapticFeedback,
    order: () -> List<String>,
    choosing: () -> Boolean,
    chosen: () -> List<String>,
    onChoose: (List<String>) -> Unit,
): Modifier = onGloballyPositioned { sweep.grid = it }.pointerInput(Unit) {
    val edge = 56.dp.toPx()
    val fastest = 18.dp.toPx()
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val start = sweep.pageAt(held.position) ?: return@awaitEachGesture
            val ids = order()
            val startAt = ids.indexOf(start).takeIf { it >= 0 } ?: return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val wasChoosing = choosing()
            val base = if (wasChoosing) chosen().toHashSet() else HashSet()
            // Choosing already, a hold reaches back to the page tapped last.
            val anchorAt = sweep.anchor?.takeIf { wasChoosing && it != start }?.let(ids::indexOf)?.takeIf { it >= 0 }
            val from = anchorAt ?: startAt
            val adding = anchorAt != null || start !in base
            var reached = startAt
            fun show(to: Int) {
                reached = to
                onChoose(sweptSelection(ids, base, from, to, adding))
            }
            show(startAt)
            held.consume()
            var last = held.position
            var speed = 0f
            val scroller = launch {
                while (isActive) {
                    if (speed != 0f) {
                        gridState.scrollBy(speed)
                        // The cards moved under a finger that stayed put.
                        sweep.pageAt(last)?.let(ids::indexOf)?.takeIf { it >= 0 && it != reached }?.let(::show)
                    }
                    delay(16)
                }
            }
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    last = change.position
                    speed = when {
                        last.y < edge -> -fastest * ((edge - last.y) / edge).coerceIn(0f, 1f)
                        last.y > size.height - edge -> fastest * ((last.y - (size.height - edge)) / edge).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    sweep.pageAt(last)?.let(ids::indexOf)?.takeIf { it >= 0 && it != reached }?.let { to ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        show(to)
                    }
                }
            } finally {
                scroller.cancel()
            }
            sweep.anchor = ids.getOrNull(reached)
        }
    }
}
