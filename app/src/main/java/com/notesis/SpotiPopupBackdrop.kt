package com.notesis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop

internal val LocalSpotiPopupBackdrop = compositionLocalOf<Backdrop?> { null }

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
    if (coordinates == null || !coordinates.isAttached) return
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
internal class SpotiPopupBackdrop(
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
