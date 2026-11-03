package com.notesis

/**
 * Where a menu that belongs to something on screen - a selection, for one -
 * goes: centred on it, above it when there is room, below it when not, and
 * kept inside the space it may use either way. Pure arithmetic in pixels, so
 * it can be checked without a screen.
 *
 * [areaTop] and [areaBottom] are the usable band: the window less its bars,
 * the toolbar or the keyboard. [gap] is the space kept between the thing and
 * the menu. When the menu fits neither above nor below - a selection filling
 * most of the screen - it goes to the bottom of the band, where the menu
 * stood before it followed the selection.
 */
internal data class MenuPlacement(val x: Int, val y: Int, val side: Side) {
    enum class Side { ABOVE, BELOW, BOTTOM }
}

internal fun placeMenu(
    targetLeft: Float,
    targetTop: Float,
    targetRight: Float,
    targetBottom: Float,
    menuWidth: Int,
    menuHeight: Int,
    areaWidth: Int,
    areaTop: Int,
    areaBottom: Int,
    margin: Int,
    gap: Int,
): MenuPlacement {
    val minX = margin
    val maxX = (areaWidth - margin - menuWidth).coerceAtLeast(minX)
    val centre = (targetLeft + targetRight) / 2f
    val x = (centre - menuWidth / 2f).toInt().coerceIn(minX, maxX)
    val top = areaTop + margin
    val bottom = areaBottom - margin
    val above = (targetTop - gap - menuHeight).toInt()
    if (above >= top && targetTop <= bottom) return MenuPlacement(x, above, MenuPlacement.Side.ABOVE)
    val below = (targetBottom + gap).toInt()
    if (below + menuHeight <= bottom && below >= top) return MenuPlacement(x, below, MenuPlacement.Side.BELOW)
    return MenuPlacement(x, (bottom - menuHeight).coerceAtLeast(top), MenuPlacement.Side.BOTTOM)
}

