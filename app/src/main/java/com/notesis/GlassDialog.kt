package com.notesis

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.DropdownMenu as MaterialDropdownMenu

/**
 * The app's dialog.
 *
 * A Compose dialog lives in a window of its own, which is why a translucent one
 * never looked like glass: the backdrop layer the in-app chrome samples does not
 * reach across windows, so all a low alpha bought was a dim view of the scrim.
 * Android has the right answer and it is one call - the compositor will blur
 * whatever is behind a window on request - so the dialog asks for that instead,
 * and can then be nearly solid and still read as glass.
 *
 * Named to shadow the Material one, so every call site picks it up by import
 * alone. Same signature, same defaults; the only difference is behind it.
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
    MaterialAlertDialog(
        onDismissRequest = onDismissRequest,
        // The window is only reachable from inside the dialog's own content, and
        // confirmButton is the one slot every call site fills.
        confirmButton = { BlurBehind(); confirmButton() },
        // Colour and shape a dialog takes from the theme; the lit edge it has
        // to be given. Without it a glass dialog was a Material panel that
        // happened to be translucent.
        modifier = modifier.then(skinEdge(shape)),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/**
 * A menu wearing the same treatment. Only the arguments the app actually passes
 * are here - the rest of Material's list moves between versions, and a wrapper
 * that repeats it is a wrapper that breaks on the next upgrade.
 */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialDropdownMenu(
        expanded,
        onDismissRequest,
        modifier,
        // No shadow on glass. A shadow is drawn under the whole panel and not
        // only around it, and a glass menu's body is translucent - so the
        // shadow showed through it, dark at the edges and clear in the middle.
        // That pale patch in the centre of the AI menu, and of every other
        // menu in the app, was its own shadow seen from the front. Same bug as
        // the one under each note's name; same fix.
        shadowElevation = if (LocalSkin.current == Skin.MATERIAL) {
            MenuDefaults.ShadowElevation
        } else {
            0.dp
        },
    ) {
        BlurBehind()
        content()
    }
}

/**
 * Asks the compositor to blur what is behind this window, and dims it less
 * while it does. Needs Android 12, and the platform ignores it under battery
 * saver or with animations off - in which case the popup is simply a solid
 * panel, which is a fine thing for it to be.
 *
 * A dialog owns a Window and a menu does not: a menu is a view handed straight
 * to the WindowManager, so the same two attributes have to be set on its layout
 * params and pushed back. Guarded, because a popup that is on its way out has
 * no manager left to update and throws rather than saying so.
 */
@Composable
fun BlurBehind() {
    val skin = LocalSkin.current
    val look = LocalSkinSettings.current
    val view = LocalView.current
    SideEffect {
        if (skin == Skin.MATERIAL) return@SideEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@SideEffect
        val radius = (look.blur * view.resources.displayMetrics.density)
            .toInt()
            .coerceIn(0, MAX_BLUR_PX)
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = radius }
            // Light enough to see the blur through, dark enough to lift the
            // dialog off it. Material's 0.6 buries what was just blurred.
            window.setDimAmount(DIM)
            return@SideEffect
        }
        runCatching {
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@runCatching
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = radius
            // A menu is not modal, so it dims almost nothing - just enough to
            // separate it from the page it is sitting on.
            params.dimAmount = DIM / 2f
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            val manager = view.context.getSystemService(WindowManager::class.java)
            manager?.updateViewLayout(view, params)
        }
    }
}

private const val DIM = 0.18f

/** The compositor's own ceiling; past this it clamps and costs more anyway. */
private const val MAX_BLUR_PX = 150
