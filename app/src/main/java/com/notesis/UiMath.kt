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

/**
 * The radius of the selection pill inside a tool strip [barHeight] tall, whose
 * own corner is [corner] and whose cells sit [inset] in from its edge: the
 * strip's drawn curve moved in by the inset. Liquid glass's resting pill and
 * lens and the still skins' highlight all take it from here, so the three
 * skins round the selection the same.
 */
internal fun toolPillRadius(corner: Float, barHeight: Float, inset: Float, min: Float): Float =
    concentricRadius(drawnRadius(corner, Float.MAX_VALUE, barHeight), inset, min)

/**
 * Where a popup [contentWidth] x [contentHeight] goes for an anchor from
 * ([anchorLeft], [anchorTop]) to ([anchorRight], [anchorBottom]) in a window
 * [windowWidth] x [windowHeight]: under the anchor, or over it when there is
 * no room below, lined up with its [alignLeft] or right edge, and [gap] clear
 * of the window's edges. [inset] is clear room the content keeps all round
 * for its shadow; the panel inside it is what is placed, so the shadow can
 * fall past the gap without moving the panel.
 */
internal fun popupPlacement(
    anchorLeft: Int,
    anchorTop: Int,
    anchorRight: Int,
    anchorBottom: Int,
    windowWidth: Int,
    windowHeight: Int,
    contentWidth: Int,
    contentHeight: Int,
    gap: Int,
    inset: Int,
    alignLeft: Boolean,
): Pair<Int, Int> {
    val room = inset.coerceAtLeast(0)
    val width = (contentWidth - 2 * room).coerceAtLeast(0)
    val height = (contentHeight - 2 * room).coerceAtLeast(0)
    val desiredX = if (alignLeft) anchorLeft else anchorRight - width
    val maxX = (windowWidth - width - gap).coerceAtLeast(gap)
    val below = anchorBottom + gap
    val above = anchorTop - height - gap
    val maxY = (windowHeight - height - gap).coerceAtLeast(gap)
    val y = when {
        below <= maxY -> below
        above >= gap -> above
        else -> below.coerceIn(gap, maxY)
    }
    return (desiredX.coerceIn(gap, maxX) - room) to (y - room)
}

/**
 * Where a popup goes beside its anchor rather than under it - for a bar
 * down the side of the screen: to its right if it fits, else to its left,
 * and level with its top, kept the gap clear of the window's edges. [inset]
 * is clear room round the panel inside the content, as in [popupPlacement].
 */
internal fun popupBeside(
    anchorLeft: Int,
    anchorTop: Int,
    anchorRight: Int,
    anchorBottom: Int,
    windowWidth: Int,
    windowHeight: Int,
    contentWidth: Int,
    contentHeight: Int,
    gap: Int,
    inset: Int,
): Pair<Int, Int> {
    val room = inset.coerceAtLeast(0)
    val width = (contentWidth - 2 * room).coerceAtLeast(0)
    val height = (contentHeight - 2 * room).coerceAtLeast(0)
    val maxX = (windowWidth - width - gap).coerceAtLeast(gap)
    val right = anchorRight + gap
    val left = anchorLeft - gap - width
    val x = when {
        right <= maxX -> right
        left >= gap -> left
        else -> right.coerceIn(gap, maxX)
    }
    val maxY = (windowHeight - height - gap).coerceAtLeast(gap)
    // Level with the anchor's top, or as near as the window lets it be.
    val y = anchorTop.coerceIn(gap, maxY)
    return (x - room) to (y - room)
}

/**
 * The scale one axis of a popup is drawn at while it grows: [start] when
 * [progress] is 0, its own size at 1, and a little past it when a spring
 * overshoots. The two axes run on their own curves, so a popup does not grow
 * as a fixed-ratio rectangle.
 */
internal fun popupGrowScale(progress: Float, start: Float): Float = start + (1f - start) * progress

/**
 * How opaque a growing popup is: fully once the slower of its two axes is
 * [POPUP_OPAQUE_AT] of the way, so it is solid long before it lands and fades
 * only at the very end of folding away.
 */
internal fun popupGrowAlpha(width: Float, height: Float): Float =
    (minOf(width, height) / POPUP_OPAQUE_AT).coerceIn(0f, 1f)

internal const val POPUP_OPAQUE_AT = 0.3f

/**
 * The settings sidebar's content opacity as the panel slides in, [progress]
 * 0 to 1: none for the first quarter of the slide, then rising to full as the
 * panel lands, so what it holds arrives a beat after the panel.
 */
internal fun sidebarContentAlpha(progress: Float): Float =
    ((progress - SIDEBAR_CONTENT_DELAY) / (1f - SIDEBAR_CONTENT_DELAY)).coerceIn(0f, 1f)

private const val SIDEBAR_CONTENT_DELAY = 0.25f

/**
 * How big the note's chrome - the toolbar, its buttons, the way back - is
 * drawn in a window [widthDp] by [heightDp]: as designed (a hair under) on a
 * phone or in a split, growing to a fifth larger on a whole tablet.
 * Read from the window's shorter side, so turning the device keeps it.
 */
internal fun chromeScaleFor(widthDp: Int, heightDp: Int): Float {
    val shortest = minOf(widthDp, heightDp).toFloat()
    val t = ((shortest - CHROME_SMALL_DP) / (CHROME_LARGE_DP - CHROME_SMALL_DP)).coerceIn(0f, 1f)
    val scale = CHROME_SCALE_MIN + (CHROME_SCALE_MAX - CHROME_SCALE_MIN) * t
    // In twentieths, so a window resized by a few pixels does not resize the bar.
    return kotlin.math.round(scale * 20f) / 20f
}

private const val CHROME_SMALL_DP = 360f
private const val CHROME_LARGE_DP = 900f
/** No smaller: the round buttons are the capsule's height, and below this they would be under the 44dp a finger needs. */
private const val CHROME_SCALE_MIN = 0.95f
private const val CHROME_SCALE_MAX = 1.2f
