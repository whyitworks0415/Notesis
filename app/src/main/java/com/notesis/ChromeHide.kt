package com.notesis

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The note's chrome - the bar, the way back, the page scrubber - put away
 * upwards so nothing lies over the page, and brought back down. [away] is how
 * far up it is, 0 to 1; a pull on [ChromePullTab] moves it by hand.
 */
@Stable
internal class ChromeHideState(private val scope: CoroutineScope, private val moves: () -> Boolean) {
    val away = Animatable(0f)

    var hidden by mutableStateOf(false)
        private set

    fun hide() {
        hidden = true
        scope.launch { away.animateTo(1f, spec()) }
    }

    fun show() {
        hidden = false
        scope.launch { away.animateTo(0f, spec()) }
    }

    fun toggle() = if (hidden) show() else hide()

    /** Pulled down [fraction] of the way, the chrome follows the finger. */
    fun pull(fraction: Float) {
        scope.launch { away.snapTo(1f - fraction.coerceIn(0f, 1f)) }
    }

    /** Let go: past a third of the way it comes back, short of that it goes up again. */
    fun release(fraction: Float) = if (fraction > 0.33f) show() else hide()

    private fun spec(): AnimationSpec<Float> = if (moves()) spring(dampingRatio = 0.86f, stiffness = 360f) else snap()
}

@Composable
internal fun rememberChromeHideState(moves: Boolean): ChromeHideState {
    val scope = rememberCoroutineScope()
    val latest = remember { booleanArrayOf(moves) }
    latest[0] = moves
    return remember { ChromeHideState(scope) { latest[0] } }
}

/**
 * The tab left at the top while the chrome is away: tapped, the chrome comes
 * back; pulled down, it comes down with the finger.
 */
@Composable
internal fun ChromePullTab(state: ChromeHideState, modifier: Modifier = Modifier) {
    val reach = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .graphicsLayer { alpha = state.away.value }
            .padding(top = 6.dp)
            .size(width = 76.dp, height = 26.dp)
            .skinMaterial(RoundedCornerShape(13.dp))
            .semantics {
                role = Role.Button
                contentDescription = "Show the toolbar"
                onClick { state.show(); true }
            }
            .pointerInput(Unit) { detectTapGestures { state.show() } }
            .pointerInput(Unit) {
                var pulled = 0f
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f },
                    onDragEnd = { state.release(pulled / reach) },
                    onDragCancel = { state.release(0f) },
                ) { change, dy ->
                    change.consume()
                    pulled = (pulled + dy).coerceAtLeast(0f)
                    state.pull(pulled / reach)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // A chevron pointing down: the way the chrome comes back.
            val w = 14.dp.toPx()
            val h = 5.dp.toPx()
            val c = center
            val stroke = 2.4.dp.toPx()
            drawLine(ink.copy(alpha = 0.7f), Offset(c.x - w / 2, c.y - h / 2), Offset(c.x, c.y + h / 2), stroke, StrokeCap.Round)
            drawLine(ink.copy(alpha = 0.7f), Offset(c.x, c.y + h / 2), Offset(c.x + w / 2, c.y - h / 2), stroke, StrokeCap.Round)
        }
    }
}
