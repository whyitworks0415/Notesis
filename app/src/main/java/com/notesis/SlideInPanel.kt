package com.notesis

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * A panel that slides in from the end edge while [visible] and back out past
 * it - the pages panel, the layers panel - on the settings sidebar's springs.
 *
 * Moved by its layer, so a frame of the slide is a recomposite and not a
 * layout; and its glass looks at the page where the panel comes to rest (see
 * [ProvideSlidingBackdrops]), so the glass is not blurred again on each frame
 * either. The page behind is not recorded again while it slides (see
 * [ChromeMotion]). [instantExit] takes it away at once, as writing does.
 */
@Composable
internal fun SlideInPanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    instantExit: Boolean = false,
    content: @Composable () -> Unit,
) {
    val animate = rememberLiquidGlassEffectsAllowed()
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    var present by remember { mutableStateOf(visible) }
    LaunchedEffect(visible, animate) {
        if (visible) {
            present = true
            if (animate) {
                // The first frame with the panel in it is the slow one; the
                // slide starts after it, not on it.
                if (progress.value == 0f) awaitFirstFrames()
                ChromeMotion.during { progress.animateTo(1f, sidebarOpenSpring()) }
            } else {
                progress.snapTo(1f)
            }
        } else {
            if (animate && !instantExit) ChromeMotion.during { progress.animateTo(0f, sidebarCloseSpring()) }
            else progress.snapTo(0f)
            present = false
        }
    }
    if (!visible && !present) return
    val shift = remember { SlideShift() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.graphicsLayer {
        val out = (1f - progress.value.coerceIn(0f, 1f)) * size.width * (if (rtl) -1f else 1f)
        translationX = out
        shift.x = out
    }) {
        ProvideSlidingBackdrops(shift) { content() }
    }
}
