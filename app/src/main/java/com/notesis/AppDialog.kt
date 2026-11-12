package com.notesis

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog as MaterialAlertDialog

/**
 * The app's dialog: Material's, grown out of the touch that opened it as a
 * popup grows out of its button (see PopupReveal.kt), and sent back into it by
 * a tap past it or by Back.
 *
 * Named to shadow the Material one, so every call site picks it up by import
 * alone. Same signature, same defaults; the only difference is how it arrives.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    // Still where animations are off: the growth starts at its end.
    val moves = rememberMotionAllowed()
    val growth = rememberPopupGrowth(shown = !moves)
    val opener = remember { LastTouch.recent() }
    LaunchedEffect(Unit) { if (moves) growth.open(true) }
    // Sent away by a tap past it or by Back, it goes back the way it came
    // before it is gone. Its own buttons still close it at once - they decide
    // what happens next.
    val scope = rememberCoroutineScope()
    val leaving = remember { booleanArrayOf(false) }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val dismiss: () -> Unit = {
        if (!moves) latestDismiss()
        else if (!leaving[0]) {
            leaving[0] = true
            scope.launch {
                growth.close(true)
                latestDismiss()
            }
        }
    }
    MaterialAlertDialog(
        onDismissRequest = dismiss,
        // Each slot at the app's own size: the dialog is a window of its own (see ProvideAppDensity).
        confirmButton = { ProvideAppDensity(confirmButton) },
        modifier = modifier.then(if (moves) {
            Modifier.revealOutOf(growth, Ui.Corner.sheet) { c -> opener?.let { c.screenToLocal(it) } }
        } else Modifier),
        dismissButton = if (dismissButton == null) null else { { ProvideAppDensity(dismissButton) } },
        icon = if (icon == null) null else { { ProvideAppDensity(icon) } },
        title = if (title == null) null else { { ProvideAppDensity(title) } },
        // A dialog's fields and cards sit one dialog padding in from its
        // corner, so their corners are the dialog's moved in by that.
        text = if (text == null) null else { { ProvideAppDensity { NestedCorners(Ui.Corner.sheet, DIALOG_INSET) { text() } } } },
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/**
 * The app's menu under Material's name: every menu that was Material's own -
 * it grew from its middle - is the app's (see AppMenu.kt), grown out of what
 * opened it and gone back into it, as the card menus and the toolbar's are
 * (Todo.md, B1). Only the arguments the app actually passes are here.
 */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = GlassMenu(expanded, onDismissRequest, modifier, content)

/** Material's dialog padding: how far a dialog's content sits from its edge. */
private val DIALOG_INSET = 24.dp
