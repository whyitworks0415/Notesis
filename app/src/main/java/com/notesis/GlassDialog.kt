package com.notesis

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MenuDefaults
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
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
    // How it arrives, after Mornye: under Material out of 0.85 on Material 3
    // Expressive's default spatial spring, fading in as it comes; under the
    // glass looks out of 0.96 over 220 ms, unfaded, so its blur behind is
    // never seen through a fading layer. Still where animations are off.
    val material = LocalSkin.current == Skin.MATERIAL
    val moves = rememberLiquidGlassEffectsAllowed()
    val arrival = remember { Animatable(if (moves) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (arrival.value < 1f) arrival.animateTo(1f, dialogArrival(material).paced())
    }
    // Under the glass looks it comes out of the touch that opened it, as a
    // popup comes out of its button: cut to a shape that runs out of that
    // point, the dialog drawn once and never scaled (see PopupReveal.kt).
    val growth = rememberPopupGrowth(shown = !moves)
    val pours = moves && growth.profile?.reveal == true
    val opener = remember { LastTouch.recent() }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    LaunchedEffect(Unit) { if (pours) growth.open(true) }
    // Sent away by a tap past it or by Back, it goes back the way it came
    // before it is gone: into the touch under the glass looks, a quick shrink
    // and fade under Material. Its own buttons still close it at once - they
    // decide what happens next.
    val scope = rememberCoroutineScope()
    val leaving = remember { booleanArrayOf(false) }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val dismiss: () -> Unit = {
        if (!moves) latestDismiss()
        else if (!leaving[0]) {
            leaving[0] = true
            scope.launch {
                if (pours) growth.close(true)
                else arrival.animateTo(0f, if (material) MaterialMotion.rawExit<Float>().paced()
                    else tween(DIALOG_LEAVE_MS, easing = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)))
                latestDismiss()
            }
        }
    }
    MaterialAlertDialog(
        onDismissRequest = dismiss,
        // The window is only reachable from inside the dialog's own content, and
        // confirmButton is the one slot every call site fills.
        confirmButton = { BlurBehind(); confirmButton() },
        // Colour and shape a dialog takes from the theme; the lit edge it has
        // to be given. Without it a glass dialog was a Material panel that
        // happened to be translucent.
        modifier = modifier
            .then(if (pours) {
                Modifier.revealOutOf(growth, Ui.Corner.sheet) { c -> opener?.let { c.screenToLocal(it) } }
            } else {
                Modifier.onGloballyPositioned { c ->
                    if (material && opener != null && c.size.width > 0 && c.size.height > 0) {
                        val at = c.screenToLocal(opener)
                        origin = TransformOrigin((at.x / c.size.width).coerceIn(0f, 1f), (at.y / c.size.height).coerceIn(0f, 1f))
                    }
                }.graphicsLayer {
                    transformOrigin = origin
                    val scale = dialogArrivalScale(arrival.value, material)
                    scaleX = scale
                    scaleY = scale
                    if (material || leaving[0]) alpha = (arrival.value / 0.4f).coerceIn(0f, 1f)
                }
            })
            .then(skinEdge(shape)),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        // A dialog's fields and cards sit one dialog padding in from its
        // corner, so their corners are the dialog's moved in by that.
        text = if (text == null) null else { { NestedCorners(Ui.Corner.sheet, DIALOG_INSET) { text() } } },
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
        shadowElevation = if (LocalSkin.current == Skin.MATERIAL && LocalSkinSettings.current.shadows) {
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

/** How long a dialog sent away takes to go. */
private const val DIALOG_LEAVE_MS = 160

/** The spring or curve a dialog arrives on; see [AlertDialog]. */
internal fun dialogArrival(material: Boolean): androidx.compose.animation.core.AnimationSpec<Float> =
    if (material) MaterialMotion.rawContainer()
    else tween(220, easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f))

/** The scale a dialog is drawn at as it arrives, [progress] 0 to 1 (a spring may run past). */
internal fun dialogArrivalScale(progress: Float, material: Boolean): Float {
    val start = if (material) MaterialMotion.START_SCALE else 0.96f
    return start + (1f - start) * progress
}

/** Material's dialog padding: how far a dialog's content sits from its edge. */
private val DIALOG_INSET = 24.dp

/** The compositor's own ceiling; past this it clamps and costs more anyway. */
private const val MAX_BLUR_PX = 150
