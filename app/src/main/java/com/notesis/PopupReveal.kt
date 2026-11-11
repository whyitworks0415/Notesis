package com.notesis

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/*
 * A popup that pours out of the button it was opened from, as Mornye's menus
 * do (see [popupDrop]): a round drop at the button runs down into the panel's
 * height and spreads to its width, its corners tightening, and goes back into
 * the button the same way, the width first. The rows on it are never scaled
 * or laid out again: they are where they will rest, seen where the glass has
 * reached, and fade up once it has run half way (see [revealContent]).
 *
 * The outline is a rounded rectangle, which the render thread clips a layer
 * to by itself. 0.90.20's rounded trapezoid was a path, which a layer can
 * only be clipped to by drawing it again, and 0.90.21's scaled window
 * squeezed the rows with it.
 */

/** One frame's outline: a rounded rectangle anywhere inside the panel. */
private class DropShape(private val drop: PopupDrop) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(drop.left, drop.top, drop.left + drop.width, drop.top + drop.height,
            CornerRadius(drop.radius, drop.radius)))
}

/** How wide the drop a popup comes out of starts: Mornye's 44 points, about a button. */
internal val REVEAL_START: Dp = 44.dp

/** How far a popup's blur runs at most. */
private val POPUP_BLUR_MAX: Dp = 4.dp

/** A motion read in a glass's draw that never changes: for a popup that is cut, not moved. */
internal val StillMotion: () -> Float = { 0f }

/**
 * Pours this panel out of the point [anchor] gives - in this panel's
 * coordinates, from its place on screen - at [growth]'s progress, and leaves
 * it uncut once it has come. [corner] is the panel's own.
 *
 * While it moves, the panel is drawn once into a layer the render thread
 * keeps as a texture, and each frame only clips that texture to a new
 * rounded rectangle: drawn live, every frame ran the glass's blur and lens
 * over the whole panel again.
 */
@Composable
internal fun Modifier.revealOutOf(growth: PopupGrowth, corner: Dp, anchor: (LayoutCoordinates) -> Offset?): Modifier {
    val local = remember { floatArrayOf(Float.NaN, Float.NaN) }
    val layer = rememberGraphicsLayer()
    // The size the layer was last recorded at; 0 until the first recording.
    val recorded = remember { IntArray(2) }
    return this.onGloballyPositioned { coordinates ->
        val at = anchor(coordinates) ?: return@onGloballyPositioned
        local[0] = at.x; local[1] = at.y
    }.graphicsLayer {
        if (size.width <= 0f || size.height <= 0f) { alpha = 0f; return@graphicsLayer }
        val w = growth.width.value
        val h = growth.height.value
        if (!growth.moving(w, h)) return@graphicsLayer
        val drop = popupDrop(local[0], local[1], size.width, size.height, REVEAL_START.toPx(), corner.toPx(), w, h)
        // Past its size it runs over a little from the button, then settles.
        transformOrigin = TransformOrigin(drop.originX / size.width, drop.originY / size.height)
        scaleX = drop.scaleX
        scaleY = drop.scaleY
        shape = DropShape(drop)
        clip = true
        // Seen from the start, as glass is; gone only once it is back in the button.
        alpha = ((w + h) / 2f / 0.08f).coerceIn(0f, 1f)
        // A trace of blur along its fastest moment, at the strength set in
        // Motion: the drop's edges move at its growth's speed times its size.
        renderEffect = motionBlurEffect(growth.width.velocity * size.width, growth.height.velocity * size.height,
            POPUP_BLUR_MAX.toPx())
    }.drawWithContent {
        if (layer.isReleased || size.width <= 0f || size.height <= 0f) {
            drawContent(); return@drawWithContent
        }
        val moving = growth.moving(growth.width.value, growth.height.value)
        // Kept as a texture while it moves: only the outline above changes,
        // so the render thread composites what it already has.
        layer.compositingStrategy = if (moving) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        val w = size.width.toInt(); val h = size.height.toInt()
        if (!moving || recorded[0] != w || recorded[1] != h) {
            layer.record { this@drawWithContent.drawContent() }
            recorded[0] = w; recorded[1] = h
        }
        drawLayer(layer)
    }
}

/**
 * The rows on a poured panel, put after its glass: they fade up as the drop
 * runs down and go first on the way back (see [popupContentAlpha]). A layer
 * of their own, so the fade is a property the render thread changes inside
 * the panel's kept texture rather than a reason to record it again.
 */
internal fun Modifier.revealContent(growth: PopupGrowth, reveal: Boolean): Modifier =
    if (!reveal) this else graphicsLayer { alpha = popupContentAlpha(growth.height.value, growth.closing) }

/** Dynamic corners used by other chrome surfaces. */
internal class LiveCornerSize(private val px: () -> Float) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        px().coerceIn(0f, min(shapeSize.width, shapeSize.height) / 2f)
}
