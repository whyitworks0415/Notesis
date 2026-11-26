package com.notesis

import android.graphics.RenderEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.Backdrop
import kotlin.math.ceil

/**
 * One frame of a pane drawn by [lowResGlass]: the layer's size in its own
 * pixels, how much smaller than the screen it is, the screen's density and
 * the pane's corner in screen pixels.
 */
internal class GlassFrame(val width: Float, val height: Float, val scale: Float, val density: Float, val corner: Float)

/**
 * Glass worked out at [scale] of the screen's resolution and then drawn up to
 * the pane: the page behind is drawn into a layer that much smaller, the
 * [effect] - blur and lens, made for that layer's size - runs over it there,
 * and the result is stretched over the pane, clipped to [shape], with
 * [surface] over it. At a half the GPU does a quarter of the work for each
 * pane, at a quarter a sixteenth; the frost is a blur either way, so what is
 * lost is only a little of the sharpness of the page under the clear layer
 * and of the light along the rim.
 *
 * Backdrop's own pane always works at the screen's resolution, which is what
 * the glass costs: every pixel of every pane runs the blur and the lens.
 */
internal fun Modifier.lowResGlass(
    backdrop: Backdrop,
    shape: Shape,
    scale: Float,
    surface: Color,
    effect: (GlassFrame) -> RenderEffect?,
): Modifier = this.then(LowResGlassElement(backdrop, shape, scale.coerceIn(0.1f, 1f), surface, effect))

private class LowResGlassElement(
    val backdrop: Backdrop,
    val shape: Shape,
    val scale: Float,
    val surface: Color,
    val effect: (GlassFrame) -> RenderEffect?,
) : ModifierNodeElement<LowResGlassNode>() {
    override fun create() = LowResGlassNode(backdrop, shape, scale, surface, effect)

    override fun update(node: LowResGlassNode) {
        node.backdrop = backdrop
        node.shape = shape
        node.scale = scale
        node.surface = surface
        node.effect = effect
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "lowResGlass"
        properties["scale"] = scale
    }

    override fun equals(other: Any?): Boolean = other is LowResGlassElement && other.backdrop == backdrop &&
        other.shape == shape && other.scale == scale && other.surface == surface && other.effect === effect

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + shape.hashCode()
        result = 31 * result + scale.hashCode()
        result = 31 * result + surface.hashCode()
        result = 31 * result + effect.hashCode()
        return result
    }
}

private class LowResGlassNode(
    var backdrop: Backdrop,
    var shape: Shape,
    var scale: Float,
    var surface: Color,
    var effect: (GlassFrame) -> RenderEffect?,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var layer: GraphicsLayer? = null

    // Read while drawing, so the pane is drawn again when it moves over the page.
    private var coordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    private val clip = Path()

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        coordinates = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) this.coordinates = coordinates
    }

    override fun ContentDrawScope.draw() {
        val layer = layer
        val at = coordinates
        if (layer != null && at != null && at.isAttached && size.width >= 1f && size.height >= 1f) {
            val s = scale
            val w = ceil(size.width * s).toInt().coerceAtLeast(1)
            val h = ceil(size.height * s).toInt().coerceAtLeast(1)
            layer.record(IntSize(w, h)) {
                scale(s, s, pivot = Offset.Zero) {
                    with(backdrop) { drawBackdrop(this@draw, at, null) }
                }
            }
            val corner = cornerOf(shape, size, layoutDirection, this)
            layer.renderEffect = effect(GlassFrame(w.toFloat(), h.toFloat(), s, density, corner))?.asComposeRenderEffect()
            clip.reset()
            when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Generic -> clip.addPath(outline.path)
                else -> clip.addOutline(outline)
            }
            clipPath(clip) {
                scale(1f / s, 1f / s, pivot = Offset.Zero) { drawLayer(layer) }
                if (surface.alpha > 0f) drawRect(surface)
            }
        }
        drawContent()
    }
}
