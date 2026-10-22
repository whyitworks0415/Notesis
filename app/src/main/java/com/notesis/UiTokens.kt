package com.notesis

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's one set of fixed sizes: corners, gaps, hit areas and icons.
 *
 * Every value here is the one the app already used most for that kind of
 * element, so moving a call site onto a token pulls the odd ones in line
 * rather than restyling the app. The rule the set exists for: the same kind
 * of element gets the same radius, and the same kind of neighbours the same
 * gap, wherever it appears.
 *
 * Skins that let the user choose a corner ([SkinSettings.corner],
 * [SkinSettings.spotiglassCorner]) still own their surfaces; these are for the
 * places that hard-coded a number instead.
 */
object Ui {
    /**
     * Corner radii, from the smallest element to the largest. Nested elements
     * step down one size, so a chip inside a card does not argue with it.
     */
    object Corner {
        /** Badges and tiny swatches: a page number tag, a highlighter nib. */
        val badge = 6.dp
        /** Page and image previews. Paper keeps a nearly square corner. */
        val preview = 8.dp
        /** Chips, tiles, small swatches and rows inside a list. */
        val chip = 12.dp
        /**
         * Tile buttons (an icon over a label, a keypad key), segmented strips
         * and list items that stand alone. Text buttons stay capsules, as
         * Material draws them.
         */
        val control = 16.dp
        /**
         * Cards and panels, and Material's floating bars. Under glass, bars
         * use the skin's own corner setting instead (SkinSurface with no
         * corner), so they follow what the user chose.
         */
        val card = 20.dp
        /** Dialogs and sheets: the outermost container on screen. */
        val sheet = 28.dp
        /**
         * The least a nested element is rounded, however deep it sits: below
         * this a tile in a round panel reads as square.
         */
        val nestedMin = 6.dp
    }

    /**
     * The radius for an element [inset] inside a container rounded at
     * [outer], so the two curves run parallel - iOS's concentric corners.
     * The container's radius is the one fixed number; everything inside it
     * is derived from it, never chosen on its own.
     */
    fun concentric(outer: Dp, inset: Dp, min: Dp = Corner.nestedMin): Dp =
        concentricRadius(outer.value, inset.value, min.value).dp

    object Shape {
        val badge = RoundedCornerShape(Corner.badge)
        val preview = RoundedCornerShape(Corner.preview)
        val chip = RoundedCornerShape(Corner.chip)
        val control = RoundedCornerShape(Corner.control)
        val card = RoundedCornerShape(Corner.card)
        val sheet = RoundedCornerShape(Corner.sheet)
        /** Capsules: sliders, switches, pills, text buttons and round buttons. */
        val pill: androidx.compose.ui.graphics.Shape = CircleShape
    }

    /** Gaps and paddings. Anything between two steps rounds to the nearer one. */
    object Space {
        /** Rows of a list that should read as one block. */
        val hair = 2.dp
        val xs = 4.dp
        val s = 8.dp
        val m = 12.dp
        val l = 16.dp
        val xl = 24.dp
    }

    /** Hit areas and fixed control sizes. */
    object Size {
        /** The smallest thing a finger is asked to hit. */
        val touch = 48.dp
        /** A compact row's hit area, for bars where 48 would make them taller. */
        val touchCompact = 44.dp
        /** An icon button's visible body; its hit area is still [touch]. */
        val button = 40.dp
    }

    object Icon {
        /** Icons inside chips, rows and compact bars. */
        val small = 20.dp
        /** Stand-alone icons and toolbar glyphs. */
        val regular = 24.dp
    }

    /**
     * The theme's shapes, for the Material components that ask for them:
     * menus and fields take extraSmall, chips small, the small FAB medium, the
     * FAB and drawers large, dialogs and sheets extraLarge. Built from the same
     * corners as the hand-drawn pieces, so a Material chip matches a drawn
     * chip and a Material FAB the glass one beside it.
     */
    val shapes = Shapes(
        extraSmall = Shape.chip,
        small = Shape.chip,
        medium = Shape.control,
        large = Shape.card,
        extraLarge = Shape.sheet,
    )
}

/**
 * The corner an element should take where it is: set by a rounded container
 * for what sits inside it (see [NestedCorners]), null where nothing has said.
 */
internal val LocalNestedCorner = staticCompositionLocalOf<Dp?> { null }

/**
 * Tells everything in [content] that it sits [inset] inside a container
 * rounded at [outer]. Elements that read [nestedCorner] - menu buttons,
 * cards, rows, a dialog's fields - then take the concentric radius instead of
 * a fixed one. A square container ([outer] of zero, a panel flush with the
 * window) has no curve to follow, so it leaves its content as it was.
 */
@Composable
internal fun NestedCorners(outer: Dp, inset: Dp, content: @Composable () -> Unit) {
    if (outer <= 0.dp) {
        CompositionLocalProvider(LocalNestedCorner provides null, content = content)
        return
    }
    val corner = Ui.concentric(outer, inset)
    val nested = RoundedCornerShape(corner)
    // A theme hands out its own indication, so the skin's is carried in.
    val indication = LocalIndication.current
    // Material's own fields, menus and chips ask the theme for their corner,
    // so the theme's small shapes follow the container too.
    MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = nested, small = nested)) {
        CompositionLocalProvider(
            LocalNestedCorner provides corner,
            LocalIndication provides indication,
            content = content,
        )
    }
}

/** The concentric corner [NestedCorners] set here, or [fallback] outside one. */
@Composable
internal fun nestedCorner(fallback: Dp): Dp = LocalNestedCorner.current ?: fallback
