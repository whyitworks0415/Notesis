package com.notesis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop

internal val LocalGlassPopupBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * Set while the popup a pane sits in is growing or folding away: read in the
 * pane's draw, so the pane redraws on every frame of that and the page under
 * it is placed again. A scale on a layer above a pane only moves pixels the
 * pane has already drawn, so without this the pane kept the page where the
 * popup's first frame had put it.
 */
internal val LocalBackdropMotion = compositionLocalOf<(() -> Float)?> { null }

/**
 * Draws [layer], a recording whose top-left corner is at [sourceOrigin] on
 * screen, so that it lines up with the screen under the element whose
 * [coordinates] these are - wherever that element is, in whichever window,
 * and at whatever scale the layers above it draw it. See [backdropAlignment].
 */
internal fun DrawScope.drawBackdropAligned(layer: GraphicsLayer, sourceOrigin: Offset, coordinates: LayoutCoordinates?) {
    if (coordinates == null || !coordinates.isAttached || layer.isReleased) return
    val width = coordinates.size.width.toFloat()
    val height = coordinates.size.height.toFloat()
    val near = coordinates.localToScreen(Offset.Zero)
    val far = coordinates.localToScreen(Offset(width, height))
    val a = backdropAlignment(sourceOrigin.x, sourceOrigin.y, near.x, near.y, far.x, far.y, width, height)
    withTransform({
        scale(a.scaleX, a.scaleY, pivot = Offset.Zero)
        translate(a.shiftX, a.shiftY)
    }) { drawLayer(layer) }
}

/** Popup and page are different Compose roots/windows. Backdrop 1.0.6's
 * positionInWindow fallback loses the popup's window offset; screen positions
 * preserve it. Only the page is sampled, so the menu cannot capture itself.
 * The scale a popup is drawn at while it grows is undone as well, so the page
 * seen through it stays put rather than shrinking and sliding with it. */
internal class GlassPopupBackdrop(
    private val source: LayerBackdrop,
    private val sourceOrigin: () -> Offset,
) : Backdrop {
    override val isCoordinatesDependent = true

    override fun DrawScope.drawBackdrop(
        density: Density, layoutCoordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        // The coordinates already carry any layer above the glass, its own
        // [layerBlock] included, so that is not undone a second time here.
        drawBackdropAligned(source.graphicsLayer, sourceOrigin(), layoutCoordinates)
    }
}

/**
 * A backdrop that reads [motion] each time it is drawn, for the glass inside a
 * popup while it grows: the read is what makes the glass redraw - and so look
 * at the page again from where it now is - on every frame of the animation.
 */
private class MovingBackdrop(private val inner: Backdrop, private val motion: () -> Float) : Backdrop {
    override val isCoordinatesDependent: Boolean get() = inner.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        motion()
        with(inner) { drawBackdrop(density, coordinates, layerBlock) }
    }
}

/**
 * The glass inside a popup that is drawn scaled by [motion]'s animation: both
 * kinds of glass - the refractive one and the frost - are told to look at the
 * page again on every frame of it.
 */
@Composable
internal fun ProvideMovingBackdrops(motion: () -> Float, content: @Composable () -> Unit) {
    val base = LocalLiquidGlassBackdrop.current
    val moving = remember(base) { base?.let { MovingBackdrop(it, motion) } }
    CompositionLocalProvider(
        LocalLiquidGlassBackdrop provides moving,
        LocalBackdropMotion provides motion,
        content = content,
    )
}

/**
 * How far a sliding panel is from where it comes to rest, in px along x,
 * written by the panel's own layer each frame it moves (see [ProvideSlidingBackdrops]).
 */
internal class SlideShift {
    /** Zero for panels moved by [slideInDraw], whose glass already sees where they rest. */
    var x = 0f
}

/**
 * Moves what this draws [distance] towards the end edge it slides in from -
 * past it at a full width - in the draw rather than by a layer.
 *
 * A layer's move is a move on screen, and Kyant's glass is redrawn whenever
 * its place on screen changes: a panel slid by its layer blurred the page
 * under it again on every frame of the slide, which was the sidebar's
 * stutter. Moved in the draw, the panel stays laid out where it comes to
 * rest, so its glass looks at that part of the page once and is carried in
 * as a picture; and since what is inside has a layer of its own, a frame of
 * the slide draws only this move.
 */
@androidx.compose.runtime.Composable
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
    val track = androidx.compose.runtime.remember { SlideSpeed() }
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

/** Whether a panel at slide [progress] (1 is all the way in) is still on its way: more than a hair short of rest. */
internal fun slidingNow(progress: Float): Boolean = progress.isFinite() && progress < 0.995f

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

internal val LocalSlideShift = compositionLocalOf<SlideShift?> { null }

/**
 * The glass inside a panel that slides in from an edge: it looks at the page
 * where the panel will come to rest, all the way in, and so need not be
 * drawn again on each frame of the slide - the frosted page under it moves
 * with it, which no one can tell from glass looking through. Redrawn at every
 * frame of a slide, the panel's blur was what made a sidebar stutter.
 */
@Composable
internal fun ProvideSlidingBackdrops(shift: SlideShift, content: @Composable () -> Unit) {
    val base = LocalLiquidGlassBackdrop.current
    val sliding = remember(base, shift) { base?.let { SlidingBackdrop(it, shift) } }
    CompositionLocalProvider(
        LocalLiquidGlassBackdrop provides sliding,
        LocalSlideShift provides shift,
        content = content,
    )
}

/** [inner], drawn as it would be with the pane [shift] further along: where it comes to rest. */
private class SlidingBackdrop(private val inner: Backdrop, private val shift: SlideShift) : Backdrop {
    override val isCoordinatesDependent: Boolean get() = inner.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val dx = shift.x
        withTransform({ translate(dx, 0f) }) {
            with(inner) { drawBackdrop(density, coordinates, layerBlock) }
        }
    }
}
