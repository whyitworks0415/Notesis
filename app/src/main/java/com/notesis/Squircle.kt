package com.notesis

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * A rectangle whose corners have continuous curvature.
 *
 * A rounded rectangle turns a straight edge into a circular arc at a single
 * point, and the eye reads that join as a corner even when the radius is large.
 * Apple's corners never join: the curvature ramps up and back down, so the edge
 * flows into the corner and out of it. That is the difference between a rounded
 * box and the shape this skin is imitating, and at the radii the toolbar uses it
 * is the difference you can see without being told to look.
 *
 * A [CornerBasedShape] rather than a plain Shape, so it can be handed to
 * MaterialTheme's shape set and picked up by every dialog and menu.
 */
class SquircleShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        val mirror = layoutDirection == LayoutDirection.Rtl
        return Outline.Generic(
            squirclePath(
                size = size,
                tl = if (mirror) topEnd else topStart,
                tr = if (mirror) topStart else topEnd,
                br = if (mirror) bottomStart else bottomEnd,
                bl = if (mirror) bottomEnd else bottomStart,
            ),
        )
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ) = SquircleShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun toString() = "SquircleShape(topStart=$topStart)"
}

/** The usual case: one radius on all four corners. */
fun SquircleShape(corner: Dp) = SquircleShape(
    CornerSize(corner),
    CornerSize(corner),
    CornerSize(corner),
    CornerSize(corner),
)

/**
 * The four corners as superellipse quarters, joined by the straight parts of the
 * edges. Sampled rather than fitted with cubics: the path is built when the size
 * changes, so the cost is a few dozen points at layout and none per frame.
 */
private fun squirclePath(size: Size, tl: Float, tr: Float, br: Float, bl: Float): Path {
    val w = size.width
    val h = size.height
    val limit = minOf(w, h) / 2f
    val a = tl.coerceIn(0f, limit)
    val b = tr.coerceIn(0f, limit)
    val c = br.coerceIn(0f, limit)
    val d = bl.coerceIn(0f, limit)
    val path = Path()

    // Each corner sweeps a quarter of a superellipse. Sweeping alternate corners
    // in the opposite sense keeps the whole path running one way round, which is
    // what lets it be both filled and stroked.
    fun corner(cx: Float, cy: Float, r: Float, sx: Float, sy: Float, forward: Boolean) {
        if (r <= 0f) {
            path.lineTo(cx, cy)
            return
        }
        for (step in 0..SQUIRCLE_STEPS) {
            val t = step.toFloat() / SQUIRCLE_STEPS
            val angle = (if (forward) t else 1f - t) * (Math.PI / 2.0)
            path.lineTo(
                cx + sx * r * superellipse(cos(angle)).toFloat(),
                cy + sy * r * superellipse(sin(angle)).toFloat(),
            )
        }
    }

    path.moveTo(a, 0f)
    path.lineTo(w - b, 0f)
    corner(w - b, b, b, 1f, -1f, forward = false)
    path.lineTo(w, h - c)
    corner(w - c, h - c, c, 1f, 1f, forward = true)
    path.lineTo(d, h)
    corner(d, h - d, d, -1f, 1f, forward = false)
    path.lineTo(0f, a)
    corner(a, a, a, -1f, -1f, forward = true)
    path.close()
    return path
}

/** |v|^(2/n) with the sign kept, which is the superellipse in one dimension. */
private fun superellipse(v: Double): Double =
    v.sign * v.absoluteValue.pow(2.0 / SQUIRCLE_EXPONENT)

/** 2 is a circle - exactly the shape being avoided. 4 is close to Apple's. */
private const val SQUIRCLE_EXPONENT = 4.0
private const val SQUIRCLE_STEPS = 12
