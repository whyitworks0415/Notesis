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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * A menu of the toolbar's own material, liquid glass under that skin, in
 * place of Material's flat white dropdown: under its anchor, its right edge
 * on the anchor's, kept on the screen. The same parameters as DropdownMenu.
 */
@Composable
internal fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    // Inside the library's recorded content the glass may not look at its own
    // recording; popups are given the page capture separately for that.
    val backdrop = LocalGlassPopupBackdrop.current ?: LocalLiquidGlassBackdrop.current
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) { BelowAnchor(gap) },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        CompositionLocalProvider(
            LocalLiquidGlassHostBackdrop provides null,
            LocalLiquidGlassBackdrop provides backdrop,
        ) {
            val shape = RoundedCornerShape(18.dp)
            Box(Modifier.padding(12.dp)) {
                Column(
                    modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(min = 200.dp, max = 320.dp)
                        .heightIn(max = 520.dp)
                        .skinMaterial(shape, panel = true, role = ChromeRole.POPUP)
                        .clip(shape)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    content = content,
                )
            }
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
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp))
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

private class BelowAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below
            else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
