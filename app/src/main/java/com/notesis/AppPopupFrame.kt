package com.notesis

import kotlin.math.min

/**
 * One frame of a popup growing out of the button that opened it: the rounded
 * surface ([left], [top], [width], [height], [radius]) in the panel's own
 * coordinates.
 */
internal data class PopupDrop(
    val left: Float, val top: Float, val width: Float, val height: Float, val radius: Float,
)

/**
 * A round [drop]-wide surface at the anchor that grows to the panel's height
 * on [heightProgress] and to its width on [widthProgress], its corners
 * tightening from round to [corner] as it widens. A progress outside 0 to 1
 * is held at the end it passed. The anchor is held inside the panel by half
 * a drop: the window draws nothing outside it, and a drop there would be cut.
 */
internal fun popupDrop(
    anchorX: Float, anchorY: Float, width: Float, height: Float,
    drop: Float, corner: Float, widthProgress: Float, heightProgress: Float,
): PopupDrop {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return PopupDrop(0f, 0f, 0f, 0f, 0f)
    val d = drop.coerceIn(0f, min(width, height))
    val x = (if (anchorX.isFinite()) anchorX else width / 2f).coerceIn(d / 2f, width - d / 2f)
    val y = (if (anchorY.isFinite()) anchorY else height / 2f).coerceIn(d / 2f, height - d / 2f)
    val sw = unitProgress(widthProgress)
    val sh = unitProgress(heightProgress)
    val w = d + (width - d) * sw
    val h = d + (height - d) * sh
    val half = min(w, h) / 2f
    // Round while it is a drop; the corners come in as the width grows.
    val radius = min(half, half + (corner - half) * sw * sw * sw).coerceAtLeast(0f)
    return PopupDrop((x - d / 2f) * (1f - sw), (y - d / 2f) * (1f - sh), w, h, radius)
}

/** A progress held between 0 and 1; anything unreadable is 0. */
internal fun unitProgress(p: Float): Float = if (p.isFinite()) p.coerceIn(0f, 1f) else 0f

/**
 * How much of a popup's content is seen at a [height] progress. The rows
 * come up once the surface has grown half way, and on the way back go before
 * it has begun to shrink much, so what folds into the button is the surface
 * and not squeezed text.
 */
internal fun popupContentAlpha(height: Float, closing: Boolean): Float {
    if (!height.isFinite()) return 1f
    if (closing) return ((height - 0.9f) / 0.1f).coerceIn(0f, 1f)
    val t = ((height - 0.45f) / 0.5f).coerceIn(0f, 1f)
    return 1f - (1f - t) * (1f - t)
}
