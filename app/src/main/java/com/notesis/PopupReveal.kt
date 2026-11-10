package com.notesis

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
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

/** A panel and its contents shrink together into a rounded source tile, including during dismissal. */
private class AppPopupShape(private val radiusX: Float, private val radiusY: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(radiusX, radiusY)))
}

internal val REVEAL_START: Dp = 44.dp
internal val StillMotion: () -> Float = { 0f }

/** Record the full material once while moving; transform its texture instead of redrawing glass. */
@Composable
internal fun Modifier.revealOutOf(growth: PopupGrowth, corner: Dp,
    anchor: (LayoutCoordinates) -> Offset?): Modifier {
    val local = remember { floatArrayOf(Float.NaN, Float.NaN) }
    val layer = rememberGraphicsLayer()
    val recorded = remember { IntArray(2) }
    return this.onGloballyPositioned { coordinates ->
        val at = anchor(coordinates) ?: return@onGloballyPositioned
        local[0] = at.x; local[1] = at.y
    }.graphicsLayer {
        if (size.width <= 0f || size.height <= 0f) { alpha = 0f; return@graphicsLayer }
        val p = (growth.width.value + growth.height.value) / 2f
        val frame = appPopupFrame(local[0], local[1], size.width, size.height,
            REVEAL_START.toPx(), corner.toPx(), p)
        val sx = frame.width / size.width
        val sy = frame.height / size.height
        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = sx; scaleY = sy
        translationX = frame.left; translationY = frame.top
        // Inverse radii keep the visible corners circular even while the aspect ratio changes.
        shape = AppPopupShape(frame.radius / sx, frame.radius / sy)
        clip = growth.moving(growth.width.value, growth.height.value)
        // Disappear only after shrinking, rather than fading a full rectangular panel away.
        alpha = (p / 0.08f).coerceIn(0f, 1f)
    }.drawWithContent {
        if (layer.isReleased || size.width <= 0f || size.height <= 0f) {
            drawContent(); return@drawWithContent
        }
        val moving = growth.moving(growth.width.value, growth.height.value)
        layer.compositingStrategy = if (moving) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        val w = size.width.toInt(); val h = size.height.toInt()
        if (!moving || recorded[0] != w || recorded[1] != h) {
            layer.record { this@drawWithContent.drawContent() }
            recorded[0] = w; recorded[1] = h
        }
        drawLayer(layer)
    }
}

/** Dynamic corners used by other chrome surfaces. */
internal class LiveCornerSize(private val px: () -> Float) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        px().coerceIn(0f, min(shapeSize.width, shapeSize.height) / 2f)
}
