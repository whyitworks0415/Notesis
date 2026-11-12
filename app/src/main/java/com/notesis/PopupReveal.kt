package com.notesis

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

/*
 * A popup that grows out of the button it was opened from (see [popupDrop]):
 * a round surface at the button grows into the panel's size, its corners
 * tightening, and goes back into the button the same way. The rows on it are
 * never scaled or laid out again: they are where they will rest, seen where
 * the surface has reached, and fade up once it has grown half way (see
 * [revealContent]).
 *
 * The outline is a rounded rectangle, which the render thread clips a layer
 * to by itself; a path can only be clipped to by drawing the layer again.
 */

/** One frame's outline: a rounded rectangle anywhere inside the panel. */
private class DropShape(private val drop: PopupDrop) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(drop.left, drop.top, drop.left + drop.width, drop.top + drop.height,
            CornerRadius(drop.radius, drop.radius)))
}

/** How wide the surface a popup comes out of starts: about a button. */
internal val REVEAL_START: Dp = 44.dp

/** How far a popup's blur runs at most. */
private val POPUP_BLUR_MAX: Dp = 4.dp

/**
 * Grows this panel out of the point [anchor] gives - in this panel's
 * coordinates, from its place on screen - at [growth]'s progress, and leaves
 * it uncut once it has come. [corner] is the panel's own.
 *
 * While it moves, the panel is drawn once into a layer the render thread
 * keeps as a texture, and each frame only clips that texture to a new
 * rounded rectangle: nothing inside is drawn again for the motion.
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
        shape = DropShape(popupDrop(local[0], local[1], size.width, size.height, REVEAL_START.toPx(), corner.toPx(), w, h))
        clip = true
        // Seen from the start; gone only once it is back in the button.
        alpha = ((w + h) / 2f / 0.08f).coerceIn(0f, 1f)
        // A trace of blur along its fastest moment, at the strength set in
        // Motion: the surface's edges move at its growth's speed times its size.
        renderEffect = motionBlurEffect(BlurPart.POPUPS, growth.width.velocity * size.width, growth.height.velocity * size.height,
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
 * The rows on a growing panel, put after its surface: they fade up as it
 * grows and go first on the way back (see [popupContentAlpha]). A layer of
 * their own, so the fade is a property the render thread changes inside the
 * panel's kept texture rather than a reason to record it again.
 */
internal fun Modifier.revealContent(growth: PopupGrowth, reveal: Boolean): Modifier =
    if (!reveal) this else graphicsLayer { alpha = popupContentAlpha(growth.height.value, growth.closing) }
