package com.notesis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs

/*
 * A popup or a dialog is a window of its own, and its content is drawn at
 * the density of that window: the system's, not the size the reader set for
 * the app (see [UiScale]). These take the same arguments as Compose's and
 * draw their content at the app's size, so a menu or a dialog is as big as
 * the screen it came from. Named the same, so a file picks them up by not
 * importing Compose's.
 */

/**
 * [content] at the app's own density: the system's scaled by [UiScale], as
 * the main window draws. Where that is already in force it is left alone.
 */
@Composable
internal fun ProvideAppDensity(content: @Composable () -> Unit) {
    val system = LocalContext.current.resources.displayMetrics.density
    val current = LocalDensity.current
    // Already scaled by the window it is in: nothing to do.
    if (abs(current.density - system) > 0.001f) {
        content()
        return
    }
    CompositionLocalProvider(LocalDensity provides scaledDensity(current, UiScale.scale), content = content)
}

@Composable
internal fun Popup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) = androidx.compose.ui.window.Popup(popupPositionProvider, onDismissRequest, properties) { ProvideAppDensity(content) }

@Composable
internal fun Popup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) = androidx.compose.ui.window.Popup(alignment, offset, onDismissRequest, properties) { ProvideAppDensity(content) }

@Composable
internal fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) = androidx.compose.ui.window.Dialog(onDismissRequest, properties) { ProvideAppDensity(content) }
