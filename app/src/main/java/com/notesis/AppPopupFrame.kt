package com.notesis

import kotlin.math.min

/** A rounded source tile expands into the panel; all edges stay parallel on both directions. */
internal data class AppPopupFrame(val left: Float, val top: Float, val width: Float, val height: Float, val radius: Float)

internal fun appPopupFrame(anchorX: Float, anchorY: Float, width: Float, height: Float,
    start: Float, corner: Float, progress: Float): AppPopupFrame {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f)
        return AppPopupFrame(0f, 0f, 0f, 0f, 0f)
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val side = start.coerceAtLeast(0f).coerceAtMost(min(width, height).coerceAtLeast(0f))
    // A source outside this popup window points to the nearest edge. Keep the entire tile inside
    // the drawable area so the window cannot slice a rounded corner into a square on dismissal.
    val x = (if (anchorX.isFinite()) anchorX else width / 2f).coerceIn(side / 2f, width - side / 2f)
    val y = (if (anchorY.isFinite()) anchorY else height / 2f).coerceIn(side / 2f, height - side / 2f)
    val w = side + (width - side) * p
    val h = side + (height - side) * p
    val ease = p * p * (3f - 2f * p)
    val radius = (side * 0.28f + (corner - side * 0.28f) * ease).coerceIn(0f, min(w, h) / 2f)
    return AppPopupFrame((x - side / 2f) * (1f - p), (y - side / 2f) * (1f - p), w, h, radius)
}
