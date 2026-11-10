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

/**
 * A panel that slides in from the end edge while [visible] and back out past
 * it - the pages panel, the layers panel - on the settings sidebar's springs.
 *
 * Moved in the draw (see [slideInDraw]), so a frame of the slide is neither a
 * layout nor a move on screen: its glass looks at the page where the panel
 * comes to rest, once, and is not blurred again on each frame. The page
 * behind is not recorded again while it slides (see [ChromeMotion]). [instantExit] takes it away at once, as writing does.
 */
@Composable
internal fun SlideInPanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    instantExit: Boolean = false,
    content: @Composable () -> Unit,
) {
    val animate = rememberLiquidGlassEffectsAllowed()
    val skin = LocalSkin.current
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    val velocity = remember { floatArrayOf(0f) }
    var present by remember { mutableStateOf(visible) }
    LaunchedEffect(visible, animate) {
        if (visible) {
            present = true
            if (animate) {
                // The first frame with the panel in it is the slow one; the
                // slide starts after it, not on it.
                if (progress.value == 0f) awaitFirstFrames()
                ChromeMotion.during { progress.animateTo(1f, sidebarOpenFor(skin), initialVelocity = velocity[0]) { velocity[0] = this.velocity }; velocity[0] = 0f }
            } else {
                velocity[0] = 0f
                progress.snapTo(1f)
            }
        } else {
            if (animate && !instantExit) ChromeMotion.during { progress.animateTo(0f, sidebarCloseFor(skin), initialVelocity = velocity[0]) { velocity[0] = this.velocity }; velocity[0] = 0f }
            else { velocity[0] = 0f; progress.snapTo(0f) }
            present = false
        }
    }
    if (!visible && !present) return
    val shift = remember { SlideShift() }
    Box(modifier.slideInDraw { (1f - progress.value.coerceIn(0f, 1f)) * size.width }) {
        ProvideSlidingBackdrops(shift) { content() }
    }
}
