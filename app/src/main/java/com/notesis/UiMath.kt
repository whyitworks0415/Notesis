package com.notesis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/*
 * The arithmetic behind corners, curves and popups, kept free of Compose so
 * it can be tested on its own.
 */

/**
 * The radius of a rounded element sitting [inset] inside a rounded container
 * of radius [outer], so the two curves run parallel - the inner one is the
 * outer one moved in, as on iOS. Never below [min]: deep enough inside, an
 * element would otherwise come out square, and a square tile in a round panel
 * is the clash this exists to avoid.
 */
internal fun concentricRadius(outer: Float, inset: Float, min: Float = 0f): Float {
    val floor = min.coerceAtLeast(0f)
    if (!outer.isFinite() || !inset.isFinite()) return floor
    return (outer - inset.coerceAtLeast(0f)).coerceAtLeast(floor)
}

/**
 * The radius a shape actually draws: no corner can be rounder than half the
 * shorter side. A bar's concentric children are measured from this, not from
 * the setting, or a 32 dp corner on a 40 dp bar would hand them 8 dp of curve
 * that is not there.
 */
internal fun drawnRadius(radius: Float, width: Float, height: Float): Float =
    radius.coerceIn(0f, (minOf(width, height) / 2f).coerceAtLeast(0f))

/** Sine in-out: -(cos(pi t) - 1) / 2. The gentlest start and finish. */
internal fun easeInOutSine(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return (-(cos(PI * x) - 1.0) / 2.0).toFloat()
}

/** Quad in-out: 2t^2 for the first half, 1 - (-2t + 2)^2 / 2 for the second. */
internal fun easeInOutQuad(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    if (x < 0.5f) return 2f * x * x
    val back = -2f * x + 2f
    return 1f - back * back / 2f
}

/**
 * The curves the toolbar can change size on, in the order the menu shows
 * them. [stored] is what the preference holds and never changes meaning: the
 * first four are the indices the menu used to save, and the two newer curves
 * were added after them, so a choice made before still comes back as itself.
 */
internal enum class ToolbarCurve(val label: String, val stored: Int) {
    SINE_IN_OUT("Sine in-out", 4),
    QUAD_IN_OUT("Quad in-out", 5),
    CUBIC_IN_OUT("Cubic in-out", 0),
    EASE_OUT("Ease out", 1),
    LINEAR("Linear", 2),
    SPRING("Spring", 3),
    ;

    companion object {
        val DEFAULT = SINE_IN_OUT

        /** The curve saved as [index]; one this build does not know is the default. */
        fun fromStored(index: Int): ToolbarCurve = entries.firstOrNull { it.stored == index } ?: DEFAULT
    }
}

/**
 * Where a popup grows from, as a fraction of its own size: the point
 * ([anchorX], [anchorY]) on screen it was opened from, measured against the
 * popup's unscaled bounds and kept inside them. A popup that cannot be laid
 * out yet grows from the middle of its top edge.
 */
internal fun popupGrowOrigin(
    anchorX: Float,
    anchorY: Float,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
): Pair<Float, Float> {
    if (!(width > 0f) || !(height > 0f)) return 0.5f to 0f
    return ((anchorX - left) / width).coerceIn(0f, 1f) to ((anchorY - top) / height).coerceIn(0f, 1f)
}

/**
 * How to draw a recording of the screen so it lines up under a pane: shift
 * it by ([shiftX], [shiftY]) in screen pixels, then scale it by ([scaleX],
 * [scaleY]) about the pane's own corner. The scale undoes whatever scale the
 * pane is drawn at - a popup growing out of a button is drawn smaller than
 * it is laid out, and without this the page under it was magnified with it
 * and slid off to one side.
 */
internal class BackdropAlignment(val shiftX: Float, val shiftY: Float, val scaleX: Float, val scaleY: Float)

/**
 * The alignment for a recording that starts at ([sourceX], [sourceY]) on
 * screen, under a pane of [width] x [height] whose top-left corner lands at
 * ([screenX], [screenY]) and bottom-right at ([farX], [farY]) once every
 * transform above it is applied.
 */
internal fun backdropAlignment(
    sourceX: Float,
    sourceY: Float,
    screenX: Float,
    screenY: Float,
    farX: Float,
    farY: Float,
    width: Float,
    height: Float,
): BackdropAlignment {
    fun drawnScale(near: Float, far: Float, length: Float): Float {
        if (!(length > 0f)) return 1f
        val scale = (far - near) / length
        // A pane folded to nothing has no inverse; draw it unscaled.
        return if (!scale.isFinite() || abs(scale) < 1e-4f) 1f else scale
    }
    val sx = drawnScale(screenX, farX, width)
    val sy = drawnScale(screenY, farY, height)
    return BackdropAlignment(sourceX - screenX, sourceY - screenY, 1f / sx, 1f / sy)
}
