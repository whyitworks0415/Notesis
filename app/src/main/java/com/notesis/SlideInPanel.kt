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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A panel that slides in from the end edge while [visible] and back out past
 * it - the pages panel, the layers panel - on the settings sidebar's springs.
 *
 * Moved in the draw (see [slideInDraw]), so a frame of the slide is not a
 * layout: the panel stays laid out where it comes to rest and is carried in
 * as a picture. [instantExit] takes it away at once, as writing does.
 */
@Composable
internal fun SlideInPanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    instantExit: Boolean = false,
    content: @Composable () -> Unit,
) {
    val animate = rememberMotionAllowed()
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
                ChromeMotion.during { progress.animateTo(1f, sidebarOpen(), initialVelocity = velocity[0]) { velocity[0] = this.velocity }; velocity[0] = 0f }
            } else {
                velocity[0] = 0f
                progress.snapTo(1f)
            }
        } else {
            if (progress.value == 0f && !progress.isRunning) { velocity[0] = 0f; present = false; return@LaunchedEffect }
            if (animate && !instantExit) ChromeMotion.during { progress.animateTo(0f, sidebarClose(), initialVelocity = velocity[0]) { velocity[0] = this.velocity }; velocity[0] = 0f }
            else { velocity[0] = 0f; progress.snapTo(0f) }
            present = false
        }
    }
    if (!visible && !present) return
    Box(modifier.slideInDraw(blur = true, sliding = { slidingNow(progress.value) }) { size -> (1f - progress.value.coerceIn(0f, 1f)) * size.width }) {
        content()
    }
}

/**
 * Moves what this draws [distance] towards the end edge it slides in from -
 * past it at a full width - in the draw rather than by a layout.
 *
 * The panel stays laid out where it comes to rest, and since what is inside
 * has a layer of its own, a frame of the slide draws only this move.
 */
@Composable
internal fun Modifier.slideInDraw(
    blur: Boolean = false,
    /**
     * Whether the panel is on its way in or out. While it is, it is drawn
     * somewhere other than where it is laid out, so a touch would land on a
     * button that is not under the finger: touches are taken and dropped
     * until it has come to rest.
     */
    sliding: () -> Boolean = { false },
    distance: Density.(Size) -> Float,
): Modifier {
    // The slide's speed, from where it was a frame ago, for its trace of blur.
    val track = remember { SlideSpeed() }
    val latestSliding = androidx.compose.runtime.rememberUpdatedState(sliding)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (latestSliding.value()) event.changes.forEach { it.consume() }
            }
        }
    }.drawWithContent {
        val d = distance(size)
        if (d == 0f) drawContent()
        else translate(left = if (layoutDirection == LayoutDirection.Rtl) -d else d) { this@drawWithContent.drawContent() }
    }.graphicsLayer {
        if (!blur) return@graphicsLayer
        // Read here as well as in the draw, so this runs on every frame of the
        // slide and on none at rest: the blur goes with the motion.
        val v = track.update(distance(size), System.nanoTime())
        renderEffect = motionBlurEffect(BlurPart.SIDEBARS, v, 0f, SLIDE_BLUR_MAX.toPx())
    }
}

/** How far a sliding panel's blur runs at most. */
private val SLIDE_BLUR_MAX = 8.dp

/** Px a second a slide is moving, from one frame's distance to the next. */
internal class SlideSpeed {
    private var last = Float.NaN
    private var lastNanos = 0L
    private var velocity = 0f

    fun update(distance: Float, nanos: Long): Float {
        val dt = (nanos - lastNanos) / 1e9f
        velocity = when {
            last.isNaN() || dt > 0.1f -> 0f
            dt <= 0f -> velocity
            else -> speedSample(velocity, (distance - last) / dt)
        }
        // Come to rest: nothing to blur, whatever the last step was.
        if (distance == 0f) velocity = 0f
        last = distance
        lastNanos = nanos
        return velocity
    }
}
