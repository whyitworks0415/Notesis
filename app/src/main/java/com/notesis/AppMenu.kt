package com.notesis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * A menu of the toolbar's own material that grows out of what opened it:
 * under its anchor, its right edge on the anchor's, kept on the screen. The
 * same parameters as DropdownMenu.
 */
@Composable
internal fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Nothing at all until it is first opened. Every card in the library has
    // one of these shut, and each kept a growth, a remembered state and a
    // coroutine for a menu most cards never open - composed again for every
    // card that scrolled in.
    val everOpened = remember { booleanArrayOf(false) }
    if (expanded) everOpened[0] = true
    if (!everOpened[0]) return
    // Out of what opened it and back into it, as the toolbar's popups: kept
    // on screen while it goes back, then taken away.
    val growth = rememberPopupGrowth(shown = false)
    val animate = rememberMotionAllowed()
    var present by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded, animate) {
        if (expanded) {
            present = true
            growth.open(animate)
        } else {
            growth.close(animate)
            present = false
        }
    }
    if (!expanded && !present) return
    // Its rows give at either end and spring back.
    val bounce = rememberEdgeBounce()
    val scrollSpeed = rememberScrollSpeed()
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    // What it was opened from, in the popup's window, as the position puts it.
    val anchor = remember { floatArrayOf(Float.NaN, Float.NaN) }
    Popup(
        popupPositionProvider = remember(gap) { BelowAnchor(gap, anchor) },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        // A popup is a panel: the panels' corner, its rows one inset in.
        val shape = Ui.Shape.card
        Box(Modifier.padding(12.dp)) {
            Column(
                modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 200.dp, max = 320.dp)
                    .heightIn(max = 520.dp)
                    // Where motion is off the growth is already at its end and nothing is cut.
                    .then(if (animate) {
                        Modifier.revealOutOf(growth, Ui.Corner.card) { c ->
                            if (anchor[0].isNaN()) null else c.windowToLocal(Offset(anchor[0], anchor[1]))
                        }
                    } else Modifier)
                    .skinMaterial(shape, panel = true)
                    .clip(shape)
                    .revealContent(growth, animate)
                    .edgeBounce(bounce)
                    .scrollBlur(scrollSpeed)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = GLASS_MENU_INSET),
                content = content,
            )
        }
    }
}

/** One choice in a [GlassMenu]: the same parameters as DropdownMenuItem. */
@Composable
internal fun GlassMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = GLASS_MENU_INSET)
            .clip(RoundedCornerShape(Ui.concentric(Ui.Corner.card, GLASS_MENU_INSET)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            if (leadingIcon != null) {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { leadingIcon() }
                Spacer(Modifier.width(12.dp))
            }
            Box(Modifier.weight(1f)) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyLarge) { text() }
            }
            if (trailingIcon != null) {
                Spacer(Modifier.width(8.dp))
                trailingIcon()
            }
        }
    }
}

/** Places the menu under its anchor, and writes where the anchor's middle is in the popup into [anchor]. */
private class BelowAnchor(private val gap: Int, private val anchor: FloatArray) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below
            else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        anchor[0] = (anchorBounds.left + anchorBounds.right) / 2f - x
        anchor[1] = (anchorBounds.top + anchorBounds.bottom) / 2f - y
        return IntOffset(x, y)
    }
}

/** How far a [GlassMenu]'s rows sit in from its edge, top and sides alike. */
private val GLASS_MENU_INSET = 6.dp
