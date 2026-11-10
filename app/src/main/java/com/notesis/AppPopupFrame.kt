package com.notesis

import kotlin.math.max
import kotlin.math.min

/**
 * One frame of a popup pouring out of its button, as Mornye's menus do: the
 * rounded glass ([left], [top], [width], [height], [radius], in the panel's
 * own coordinates) and, past full size, how far it runs over ([scaleX],
 * [scaleY], about ([originX], [originY])).
 */
internal data class PopupDrop(
    val left: Float, val top: Float, val width: Float, val height: Float, val radius: Float,
    val scaleX: Float = 1f, val scaleY: Float = 1f, val originX: Float = 0f, val originY: Float = 0f,
)

/**
 * Mornye's drop: a round [drop]-wide blob at the anchor that runs down to
 * the panel's height on [heightProgress] and spreads to its width on
 * [widthProgress] - the width a moment behind - its corners tightening from
 * round to [corner] as it spreads. Each axis keeps only
 * [POUR_OVERSHOOT_KEPT] of its spring's overshoot, so it lands with a small
 * wobble rather than a bounce. The anchor is held inside the panel by half a
 * drop: the window draws nothing outside it, and a drop there would be cut.
 */
internal fun popupDrop(
    anchorX: Float, anchorY: Float, width: Float, height: Float,
    drop: Float, corner: Float, widthProgress: Float, heightProgress: Float,
): PopupDrop {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return PopupDrop(0f, 0f, 0f, 0f, 0f)
    val d = drop.coerceIn(0f, min(width, height))
    val x = (if (anchorX.isFinite()) anchorX else width / 2f).coerceIn(d / 2f, width - d / 2f)
    val y = (if (anchorY.isFinite()) anchorY else height / 2f).coerceIn(d / 2f, height - d / 2f)
    val pw = keptOvershoot(widthProgress)
    val ph = keptOvershoot(heightProgress)
    val sw = min(pw, 1f)
    val sh = min(ph, 1f)
    val w = d + (width - d) * sw
    val h = d + (height - d) * sh
    val half = min(w, h) / 2f
    // Round while it is a drop; the corners come in as the width spreads.
    val radius = min(half, half + (corner - half) * sw * sw * sw).coerceAtLeast(0f)
    return PopupDrop((x - d / 2f) * (1f - sw), (y - d / 2f) * (1f - sh), w, h, radius, max(pw, 1f), max(ph, 1f), x, y)
}

/** A spring's progress with only [POUR_OVERSHOOT_KEPT] of what it runs past 1. */
internal fun keptOvershoot(p: Float): Float = when {
    !p.isFinite() || p <= 0f -> 0f
    p <= 1f -> p
    else -> 1f + (p - 1f) * POUR_OVERSHOOT_KEPT
}

/**
 * How much of a popup's content is seen at a [height] progress. As in
 * Mornye, the rows come up once the drop has run half way down, and on the
 * way back go before the glass has begun to shrink much, so what folds into
 * the button is glass and not squeezed text.
 */
internal fun popupContentAlpha(height: Float, closing: Boolean): Float {
    if (!height.isFinite()) return 1f
    if (closing) return ((height - 0.9f) / 0.1f).coerceIn(0f, 1f)
    val t = ((height - 0.45f) / 0.5f).coerceIn(0f, 1f)
    return 1f - (1f - t) * (1f - t)
}
