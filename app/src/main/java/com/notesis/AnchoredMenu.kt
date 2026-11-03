package com.notesis

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * Lays [content] out next to [target] - a box in window pixels, a selection
 * on the page for one - with [placeMenu]: above it when there is room, below
 * when not, never past the sides, and never wider than the space less its
 * margins, so a long row scrolls inside instead of running off the screen.
 *
 * [modifier] should fill the space the menu may use (matchParentSize in a
 * Box); its own position in the window is measured, so the target can come
 * from a view anywhere in the window - a split's pane included. With no
 * target the menu sits at the bottom centre, as it always did.
 */
@Composable
internal fun AnchoredMenu(
    /**
     * Read while the menu is placed, not composed: a target that follows the
     * page as it pans moves the menu without recomposing anything.
     */
    target: () -> Rect?,
    modifier: Modifier = Modifier,
    /** Kept clear at the top for the toolbar, besides the system bars. */
    topReserve: androidx.compose.ui.unit.Dp = 64.dp,
    content: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val insets = ChromeInsets
    val insetTop = insets.getTop(density)
    val insetBottom = insets.getBottom(density)
    val margin = with(density) { Ui.Space.l.roundToPx() }
    val gap = with(density) { Ui.Space.m.roundToPx() }
    val reserve = with(density) { topReserve.roundToPx() }
    Box(modifier.onGloballyPositioned { origin = it.positionInWindow() }) {
        Layout(content = content) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val maxMenu = (width - 2 * margin).coerceAtLeast(0)
            val placeables = measurables.map {
                it.measure(Constraints(maxWidth = maxMenu, maxHeight = (height - 2 * margin).coerceAtLeast(0)))
            }
            layout(width, height) {
                for (p in placeables) {
                    val local = target()?.translate(-origin.x, -origin.y)
                    val spot = if (local == null) {
                        MenuPlacement((width - p.width) / 2, height - insetBottom - margin - p.height,
                            MenuPlacement.Side.BOTTOM)
                    } else {
                        placeMenu(local.left, local.top, local.right, local.bottom, p.width, p.height,
                            areaWidth = width, areaTop = insetTop + reserve, areaBottom = height - insetBottom,
                            margin = margin, gap = gap)
                    }
                    p.place(spot.x, spot.y)
                }
            }
        }
    }
}
