package com.notesis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Whether a [ToolPanel] is on its way out, and what runs once it has folded
 * away. One per panel, from [rememberToolPanelState].
 */
@Stable
internal class ToolPanelState(
    /** Whether the panel grows and folds at all; Material simply shows and hides it. */
    val animate: Boolean,
) {
    internal var closing by mutableStateOf<(() -> Unit)?>(null)
        private set

    /**
     * Folds the panel back into the point it came out of, then runs [after].
     * The host takes the panel away as soon as it hears, so whatever it is
     * told goes here, not before.
     */
    fun close(after: () -> Unit) {
        if (closing == null) closing = after
    }
}

@Composable
internal fun rememberToolPanelState(): ToolPanelState {
    val animate = LocalSkin.current.animatesPopups && rememberLiquidGlassEffectsAllowed()
    return remember { ToolPanelState(animate) }
}

/**
 * The panel every tool and choice opens on: the pen's settings, the shapes,
 * the look. It comes out of the point that was tapped and folds back into it
 * (see [PopupGrowth]), is the bar's own material with the bar's corner, keeps
 * one inset all round so everything inside is concentric with it, and has one
 * header: the title, then [actions] at the end.
 *
 * [onOutside] is a tap past the panel, or back; it usually calls
 * [ToolPanelState.close] with whatever keeping the panel's state means.
 */
@Composable
internal fun ToolPanel(
    title: String,
    state: ToolPanelState,
    onOutside: () -> Unit,
    maxWidth: Dp = TOOL_PANEL_WIDTH,
    /** Drawn in the title's place, when the head of the panel is a choice rather than a name. */
    heading: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val growth = remember { PopupGrowth(shown = !state.animate) }
    LaunchedEffect(state.closing) {
        val after = state.closing
        if (after == null) {
            growth.open(state.animate)
        } else {
            growth.close(state.animate)
            after()
        }
    }
    val anchor = remember { PenPopupAnchor.point() }
    val skin = LocalSkin.current

    Dialog(onDismissRequest = onOutside, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A light dim: the page should still show through the glass.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0.12f)
        // The panel's own corner is the one fixed radius, and everything
        // inside follows it, one inset in. It is the toolbar's and its menu's,
        // on the same round outline, in every skin (see [chromeCorner]).
        val corner = skin.tokens().corner
        val shape = RoundedCornerShape(corner)
        val tallest = LocalConfiguration.current.screenHeightDp.dp * 0.9f
        // Where the panel's top first lands, kept there: a part opening inside
        // it, or another tab of it, makes it longer downwards rather than
        // moving its top up and down the screen. Only a panel grown past the
        // bottom is pushed up, as far as it has to be.
        val pinnedTop = remember { intArrayOf(-1) }
        val panelBounds = remember { IntArray(4) }
        val outside by rememberUpdatedState(onOutside)
        ProvideMovingBackdrops(growth.motion) {
        NestedCorners(corner, TOOL_PANEL_INSET) {
        Layout(
            content = {
        Column(
            Modifier
                // Outside the padding: while the panel fades, the growth's
                // layer is drawn offscreen at its own bounds, and the padding
                // is the room the panel's shadow falls in.
                .growOutOf({ anchor }, growth)
                .padding(Ui.Space.l)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .heightIn(max = tallest)
                // Liquid glass under the glass skin, the same lens as the bar
                // it came from; frost or a solid panel under the others.
                .skinMaterial(shape, panel = true)
                .clip(shape)
                // No size animation of its own: a part that opens inside grows
                // on one curve and the panel, measured round it, comes with it.
                .verticalScroll(rememberScrollState())
                .padding(TOOL_PANEL_INSET),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                if (heading != null) heading()
                else Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                actions()
            }
            content()
        }
            },
            // The window runs the screen's height, the panel at its pinned top;
            // a tap above or below the panel is a tap past it.
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val at = down.position
                    val inside = at.x >= panelBounds[0] && at.x <= panelBounds[2] &&
                        at.y >= panelBounds[1] && at.y <= panelBounds[3]
                    if (!inside && waitForUpOrCancellation() != null) outside()
                }
            },
        ) { measurables, constraints ->
            val panel = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val height = if (constraints.hasBoundedHeight) constraints.maxHeight else panel.height
            val room = (height - panel.height).coerceAtLeast(0)
            if (pinnedTop[0] < 0) pinnedTop[0] = room / 2
            val top = pinnedTop[0].coerceAtMost(room)
            panelBounds[0] = 0
            panelBounds[1] = top
            panelBounds[2] = panel.width
            panelBounds[3] = top + panel.height
            layout(panel.width, height) { panel.place(0, top) }
        }
        }
        }
    }
}

/** A section's name with a hairline running out to the right edge, as every panel heads its parts. */
@Composable
internal fun PanelSectionHeader(label: String) {
    Row(Modifier.fillMaxWidth().padding(top = Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(GAP))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A setting that is on or off, with what it does under its name. */
@Composable
internal fun PanelSwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(Ui.Space.m))
        SkinSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/** A panel's padding, all round; its rows' corners follow from it. */
internal val TOOL_PANEL_INSET = Ui.Space.l

/** How wide a panel gets on a phone, or anywhere it has a single column. */
internal val TOOL_PANEL_WIDTH = 600.dp
