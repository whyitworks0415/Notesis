package com.notesis

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
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
    // Mornye's drop, the rounder of the two: a box growing out of a round end
    // at the anchor to the panel, its corners round until it has spread.
    val boxW = max(lerp(start, width, s), 0f)
    val boxH = max(lerp(start, height, e), 0f)
    val boxL = (anchorX - start / 2f) * (1f - s)
    val boxT = (anchorY - start / 2f) * (1f - e)
    val boxR = boxL + boxW
    val boxB = boxT + boxH
    val half = min(boxW, boxH) / 2f
    val t = s.coerceIn(0f, 1f)
    val boxRadius = min(half, half + (corner - half) * t * t * t)
    // Which edge it comes out of: the one the anchor is beyond, or, from inside
    // the panel, it opens all round from the anchor.
    val above = anchorY <= 0f
    val below = anchorY >= height
    val before = anchorX <= 0f
    val after = anchorX >= width
    val trapRadius = lerp(start / 2f, corner, t)
    val trap: FloatArray
    val box: FloatArray
    if (above || below) {
        val centre = anchorX.coerceIn(start / 2f, max(start / 2f, width - start / 2f))
        val reach = lerp(start, height, e)
        val nearHalf = lerp(start, width, near) / 2f
        val farHalf = lerp(start, width, s) / 2f
        val nearCentre = lerp(centre, width / 2f, near)
        val farCentre = lerp(centre, width / 2f, s)
        val nearY = if (above) 0f else height
        val farY = if (above) reach else height - reach
        trap = floatArrayOf(
            nearCentre - nearHalf, nearY, nearCentre + nearHalf, nearY,
            farCentre + farHalf, farY, farCentre - farHalf, farY,
        )
        box = if (above) floatArrayOf(boxL, boxT, boxR, boxT, boxR, boxB, boxL, boxB)
            else floatArrayOf(boxL, boxB, boxR, boxB, boxR, boxT, boxL, boxT)
    } else if (before || after) {
        val centre = anchorY.coerceIn(start / 2f, max(start / 2f, height - start / 2f))
        val reach = lerp(start, width, e)
        val nearHalf = lerp(start, height, near) / 2f
        val farHalf = lerp(start, height, s) / 2f
        val nearCentre = lerp(centre, height / 2f, near)
        val farCentre = lerp(centre, height / 2f, s)
        val nearX = if (before) 0f else width
        val farX = if (before) reach else width - reach
        trap = floatArrayOf(
            nearX, nearCentre - nearHalf, nearX, nearCentre + nearHalf,
            farX, farCentre + farHalf, farX, farCentre - farHalf,
        )
        box = if (before) floatArrayOf(boxL, boxT, boxL, boxB, boxR, boxB, boxR, boxT)
            else floatArrayOf(boxR, boxT, boxR, boxB, boxL, boxB, boxL, boxT)
    } else {
        // From inside: a rounded box growing from the anchor, down first.
        trap = floatArrayOf(
            lerp(anchorX - start / 2f, 0f, s), lerp(anchorY - start / 2f, 0f, e),
            lerp(anchorX + start / 2f, width, s), lerp(anchorY - start / 2f, 0f, e),
            lerp(anchorX + start / 2f, width, s), lerp(anchorY + start / 2f, height, e),
            lerp(anchorX - start / 2f, 0f, s), lerp(anchorY + start / 2f, height, e),
        )
        box = floatArrayOf(boxL, boxT, boxR, boxT, boxR, boxB, boxL, boxB)
    }
    // Two parts trapezoid to three parts drop: it still comes out of the
    // button narrow, but round rather than angular.
    val points = FloatArray(8) { i -> trap[i] * TRAPEZOID_SHARE + box[i] * (1f - TRAPEZOID_SHARE) }
    return RevealOutline(points, trapRadius * TRAPEZOID_SHARE + boxRadius * (1f - TRAPEZOID_SHARE))
}

/** How much of the outline is the trapezoid out of the button; the rest is Mornye's round drop. */
internal const val TRAPEZOID_SHARE = 0.4f

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
 * the panel's own.
 *
 * While it moves, the panel is drawn once into a layer of its own that the
 * render thread keeps as a texture, and each frame only cuts that texture
 * to a new outline. Drawn live, every frame ran the glass's blur and lens
 * over the whole panel again, which held the motion near 20 frames a
 * second; a picture read back from the GPU first, as 0.90.15 took, cost the
 * main thread a stall before the motion could start, felt as a delay.
 */
@Composable
internal fun Modifier.revealOutOf(
    growth: PopupGrowth,
    corner: Dp,
    anchor: (LayoutCoordinates) -> Offset?,
): Modifier {
    val local = remember { floatArrayOf(Float.NaN, Float.NaN) }
    val layer = rememberGraphicsLayer()
    // The size the layer was last recorded at; 0 until the first recording.
    val recorded = remember { IntArray(2) }
    return this
        .onGloballyPositioned { coordinates ->
            val at = anchor(coordinates) ?: return@onGloballyPositioned
            local[0] = at.x
            local[1] = at.y
        }
        .graphicsLayer {
            val w = growth.width.value
            val h = growth.height.value
            if (!growth.moving(w, h)) {
                clip = false
                shape = RectangleShape
                return@graphicsLayer
            }
            val ax = if (local[0].isNaN()) size.width / 2f else local[0]
            val ay = if (local[1].isNaN()) -1f else local[1]
            val outline = revealOutline(ax, ay, size.width, size.height, REVEAL_START.toPx(), corner.toPx(), h, w)
            shape = RevealShape(outline)
            clip = true
        }
        .drawWithContent {
            if (layer.isReleased) {
                drawContent()
                return@drawWithContent
            }
            val w = size.width.toInt()
            val h = size.height.toInt()
            val moving = growth.moving(growth.width.value, growth.height.value)
            // Kept as a texture while it moves: only the outline above changes,
            // so the render thread composites what it already has.
            layer.compositingStrategy = if (moving) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            if (!moving || recorded[0] != w || recorded[1] != h) {
                layer.record { this@drawWithContent.drawContent() }
                recorded[0] = w
                recorded[1] = h
            }
            drawLayer(layer)
        }
}

/** A corner whose size is read when the outline is made; kept for shapes that change as they are drawn. */
internal class LiveCornerSize(private val px: () -> Float) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        px().coerceIn(0f, min(shapeSize.width, shapeSize.height) / 2f)
}
