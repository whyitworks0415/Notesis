package com.notesis

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
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
    }

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
