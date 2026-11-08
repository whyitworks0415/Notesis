package com.notesis

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * A popup that pours out of its button as Mornye's menus do: the glass really
 * changes size - a drop at the button running down to the panel's height and
 * spreading to its width - while what is on it is laid out once at its
 * resting size and stays still on the screen, seen only where the glass has
 * reached. Nothing is scaled, so the words and icons never squash, and the
 * glass's lens, rim and corners are true on every frame.
 *
 * Two layout steps, as Mornye's _MenuFrame and _MenuContent: [revealFrame]
 * takes the panel's resting room and places the glass inside it; between it
 * and [revealContent] go the glass and its clip, so they are drawn at the
 * glass's present size; [revealContent] lays the content out at the resting
 * size and holds it in place as the glass moves round it.
 */

/** Where a revealing popup's glass is, shared by its two layout steps and its corner. */
@Stable
internal class RevealGeometry(
    /** The glass's corner at rest, in px. */
    var restingCornerPx: Float = 0f,
) {
    internal var constraints = Constraints()
    internal var resting = IntSize.Zero
    /** The button it comes out of, in the panel's resting room, px. */
    internal var anchor: Offset? = null
    internal var left = 0f
    internal var top = 0f
    internal var cornerPx = 0f

    /** The glass's outline: its corner follows [cornerPx], read each time it is drawn. */
    val shape = RoundedCornerShape(LiveCornerSize { cornerPx })
}

/** A corner whose size is read when the outline is made, frame by frame. */
internal class LiveCornerSize(private val px: () -> Float) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        px().coerceIn(0f, min(shapeSize.width, shapeSize.height) / 2f)
}

/** The glass's place and size for a pour [w] wide and [h] tall; see [revealRect]. */
internal data class RevealRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Where the glass is, inside a resting room [restingW] by [restingH], when the
 * pour has gone [w] of the way across and [h] down: from a [drop] across at
 * ([anchorX], [anchorY]) to the whole room. Past 1 a spring runs a little
 * further, and the glass with it by only [POUR_OVERSHOOT_KEPT] of that.
 */
internal fun revealRect(anchorX: Float, anchorY: Float, restingW: Float, restingH: Float, drop: Float, w: Float, h: Float): RevealRect {
    fun kept(p: Float) = if (p <= 1f) p.coerceAtLeast(0f) else 1f + (p - 1f) * POUR_OVERSHOOT_KEPT
    val sw = kept(w)
    val sh = kept(h)
    val width = max(drop + (restingW - drop) * sw, 0f)
    val height = max(drop + (restingH - drop) * sh, 0f)
    val dropLeft = anchorX - drop / 2f
    val dropTop = anchorY - drop / 2f
    return RevealRect(dropLeft * (1f - sw), dropTop * (1f - sh), width, height)
}

/**
 * The glass's corner at [width] by [height]: a drop is round, and the corner
 * tightens to [resting] as the glass spreads, on an ease-in of the width [w].
 */
internal fun revealCorner(width: Float, height: Float, resting: Float, w: Float): Float {
    val half = min(width, height) / 2f
    val t = w.coerceIn(0f, 1f)
    val eased = t * t * t
    return min(half, half + (resting - half) * eased)
}

/**
 * How visible what is on the glass is: it waits until the glass has come most
 * of the way, then fades up; it goes the same way, ahead of the glass.
 */
internal fun revealFade(w: Float, h: Float): Float = ((min(w, h) - 0.3f) / 0.5f).coerceIn(0f, 1f)

/** The size of the drop a revealing popup starts from: Mornye's 44 points. */
internal val REVEAL_DROP: Dp = 44.dp

/** The panel's resting room, with the glass placed inside it; see the file's note. */
internal fun Modifier.revealFrame(geo: RevealGeometry, anchor: () -> Offset?): Modifier = this
    .onGloballyPositioned { coordinates ->
        val at = anchor() ?: return@onGloballyPositioned
        geo.anchor = coordinates.screenToLocal(at)
    }
    .layout { measurable, constraints ->
        geo.constraints = constraints
        // Loose, so the glass's size - smaller than the room, or a hair past
        // it at the top of its spring - is taken as it is, not centred.
        val glass = measurable.measure(Constraints())
        val width = geo.resting.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = geo.resting.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) { glass.place(geo.left.roundToInt(), geo.top.roundToInt()) }
    }

/**
 * The content at its resting size, held still while the glass round it grows
 * by [growth]; and faded as [revealFade] has it, draw by draw, so no layer
 * the size of the panel is filled each frame.
 */
internal fun Modifier.revealContent(geo: RevealGeometry, growth: PopupGrowth): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.ModulateAlpha
        alpha = revealFade(growth.width.value, growth.height.value)
    }
    .layout { measurable, _ ->
        val content = measurable.measure(geo.constraints)
        geo.resting = IntSize(content.width, content.height)
        val restingW = content.width.toFloat()
        val restingH = content.height.toFloat()
        val at = geo.anchor ?: Offset(restingW / 2f, 0f)
        val w = growth.width.value
        val h = growth.height.value
        val r = revealRect(at.x, at.y, restingW, restingH, REVEAL_DROP.toPx(), w, h)
        geo.left = r.left
        geo.top = r.top
        geo.cornerPx = revealCorner(r.width, r.height, geo.restingCornerPx, w)
        layout(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)) {
            content.place(-r.left.roundToInt(), -r.top.roundToInt())
        }
    }
