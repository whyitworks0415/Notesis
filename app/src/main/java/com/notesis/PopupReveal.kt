package com.notesis

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * A popup that comes out of the button it was opened from, as Apple's menus
 * do: a rounded shape narrow at the button and wide at its far end - a
 * rounded trapezoid - that runs out of the button and spreads until it is the
 * panel, and goes back into the button the same way. What is on the panel is
 * never scaled and never laid out again: the panel is drawn once, glass and
 * all, at its full size, and only the outline it is cut to changes from frame
 * to frame. An outline is a property of the panel's layer, so each frame of
 * the motion is a recomposite on the render thread, not a redraw of the glass -
 * which, redrawn at a new size on every frame, was what made the popups stutter.
 */

/** The corners of a popup's outline as it comes out, clockwise from the near edge, and how round they are. */
internal class RevealOutline(val points: FloatArray, val radius: Float)

/**
 * The outline of a [width] by [height] panel coming out of ([anchorX],
 * [anchorY]) - in the panel's own coordinates, usually just outside one of
 * its edges - when it has run [extent] of the way out and spread [spread] of
 * the way across. It starts as a [start]-wide round end at the anchor; its far
 * edge spreads first and its near edge, at the button, last, so it is narrow
 * where it comes out. At rest the corners are [corner].
 */
internal fun revealOutline(
    anchorX: Float, anchorY: Float, width: Float, height: Float,
    start: Float, corner: Float, extent: Float, spread: Float,
): RevealOutline {
    val e = keptOvershoot(extent)
    val s = keptOvershoot(spread)
    val near = (s * s).coerceAtMost(s)
    // Which edge it comes out of: the one the anchor is beyond, or, from inside
    // the panel, it opens all round from the anchor.
    val above = anchorY <= 0f
    val below = anchorY >= height
    val before = anchorX <= 0f
    val after = anchorX >= width
    val radius = lerp(start / 2f, corner, s.coerceIn(0f, 1f))
    if (above || below) {
        val centre = anchorX.coerceIn(start / 2f, max(start / 2f, width - start / 2f))
        val reach = lerp(start, height, e)
        val nearHalf = lerp(start, width, near) / 2f
        val farHalf = lerp(start, width, s) / 2f
        val nearCentre = lerp(centre, width / 2f, near)
        val farCentre = lerp(centre, width / 2f, s)
        val nearY = if (above) 0f else height
        val farY = if (above) reach else height - reach
        return RevealOutline(floatArrayOf(
            nearCentre - nearHalf, nearY, nearCentre + nearHalf, nearY,
            farCentre + farHalf, farY, farCentre - farHalf, farY,
        ), radius)
    }
    if (before || after) {
        val centre = anchorY.coerceIn(start / 2f, max(start / 2f, height - start / 2f))
        val reach = lerp(start, width, e)
        val nearHalf = lerp(start, height, near) / 2f
        val farHalf = lerp(start, height, s) / 2f
        val nearCentre = lerp(centre, height / 2f, near)
        val farCentre = lerp(centre, height / 2f, s)
        val nearX = if (before) 0f else width
        val farX = if (before) reach else width - reach
        return RevealOutline(floatArrayOf(
            nearX, nearCentre - nearHalf, nearX, nearCentre + nearHalf,
            farX, farCentre + farHalf, farX, farCentre - farHalf,
        ), radius)
    }
    // From inside: a rounded box growing from the anchor, down first.
    val left = lerp(anchorX - start / 2f, 0f, s)
    val right = lerp(anchorX + start / 2f, width, s)
    val top = lerp(anchorY - start / 2f, 0f, e)
    val bottom = lerp(anchorY + start / 2f, height, e)
    return RevealOutline(floatArrayOf(left, top, right, top, right, bottom, left, bottom), radius)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** A spring's overshoot past 1, kept only in part: see [POUR_OVERSHOOT_KEPT]. */
private fun keptOvershoot(p: Float): Float = if (p <= 1f) p.coerceAtLeast(0f) else 1f + (p - 1f) * POUR_OVERSHOOT_KEPT

/**
 * [outline] as a path: each corner rounded by as much of its radius as the
 * two edges meeting there leave room for, on a curve that leaves the edges
 * without a kink.
 */
internal fun revealPath(outline: RevealOutline): Path {
    val p = outline.points
    val n = p.size / 2
    val path = Path()
    // The two points each corner's curve runs between, along its edges.
    val inX = FloatArray(n)
    val inY = FloatArray(n)
    val outX = FloatArray(n)
    val outY = FloatArray(n)
    for (i in 0 until n) {
        val x = p[2 * i]
        val y = p[2 * i + 1]
        val px = p[2 * ((i + n - 1) % n)]
        val py = p[2 * ((i + n - 1) % n) + 1]
        val nx = p[2 * ((i + 1) % n)]
        val ny = p[2 * ((i + 1) % n) + 1]
        val toPrev = sqrt((px - x) * (px - x) + (py - y) * (py - y))
        val toNext = sqrt((nx - x) * (nx - x) + (ny - y) * (ny - y))
        val r = min(outline.radius, min(toPrev, toNext) / 2f).coerceAtLeast(0f)
        inX[i] = if (toPrev > 0f) x + (px - x) / toPrev * r else x
        inY[i] = if (toPrev > 0f) y + (py - y) / toPrev * r else y
        outX[i] = if (toNext > 0f) x + (nx - x) / toNext * r else x
        outY[i] = if (toNext > 0f) y + (ny - y) / toNext * r else y
    }
    path.moveTo(outX[0], outY[0])
    for (k in 1..n) {
        val i = k % n
        path.lineTo(inX[i], inY[i])
        path.quadraticTo(p[2 * i], p[2 * i + 1], outX[i], outY[i])
    }
    path.close()
    return path
}

/** A shape that is one frame's [RevealOutline], whatever size it is asked for. */
private class RevealShape(private val outline: RevealOutline) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(revealPath(outline))
}

/** How wide the end a popup comes out of starts: about a button, Mornye's 44 points. */
internal val REVEAL_START: Dp = 44.dp

/** A motion read in a glass's draw that never changes: for a popup that is cut, not moved. */
internal val StillMotion: () -> Float = { 0f }

/**
 * Cuts this panel to the outline it has at [growth]'s progress as it comes
 * out of the point [anchor] gives - in this panel's coordinates, from its
 * place on screen - and leaves it uncut once it has fully come. [corner] is
 * the panel's own. While it is cut it casts a shadow along the cut, so the
 * glass's own shadow, cut away until then, does not pop in at the end.
 */
@Composable
internal fun Modifier.revealOutOf(
    growth: PopupGrowth,
    corner: Dp,
    anchor: (LayoutCoordinates) -> Offset?,
): Modifier {
    val local = remember { floatArrayOf(Float.NaN, Float.NaN) }
    val shadows = LocalSkinSettings.current.shadows
    return this
        .onGloballyPositioned { coordinates ->
            val at = anchor(coordinates) ?: return@onGloballyPositioned
            local[0] = at.x
            local[1] = at.y
        }
        .graphicsLayer {
            val w = growth.width.value
            val h = growth.height.value
            val done = w >= 1f && h >= 1f && !growth.width.isRunning && !growth.height.isRunning
            if (done) {
                clip = false
                shape = RectangleShape
                shadowElevation = 0f
                return@graphicsLayer
            }
            val ax = if (local[0].isNaN()) size.width / 2f else local[0]
            val ay = if (local[1].isNaN()) -1f else local[1]
            val outline = revealOutline(ax, ay, size.width, size.height, REVEAL_START.toPx(), corner.toPx(), h, w)
            shape = RevealShape(outline)
            clip = true
            shadowElevation = if (shadows) REVEAL_SHADOW.toPx() * min(w, h).coerceIn(0f, 1f) else 0f
        }
}

/** The shadow a popup casts along its outline while it comes out. */
private val REVEAL_SHADOW: Dp = 6.dp

/**
 * What is on a revealing popup, faded as [revealFade] has it, draw by draw,
 * so no layer the size of the panel is filled each frame. Put after the
 * panel's glass, so the glass itself stays whole.
 */
internal fun Modifier.revealFade(growth: PopupGrowth): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.ModulateAlpha
    alpha = revealFade(growth.width.value, growth.height.value)
}

/**
 * How visible what is on the glass is: it waits until the glass has come most
 * of the way, then fades up; it goes the same way, ahead of the glass.
 */
internal fun revealFade(w: Float, h: Float): Float = ((min(w, h) - 0.3f) / 0.5f).coerceIn(0f, 1f)

/** A corner whose size is read when the outline is made; kept for shapes that change as they are drawn. */
internal class LiveCornerSize(private val px: () -> Float) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        px().coerceIn(0f, min(shapeSize.width, shapeSize.height) / 2f)
}
