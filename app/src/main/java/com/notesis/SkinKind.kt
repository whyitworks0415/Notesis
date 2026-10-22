package com.notesis

/**
 * How the app's chrome is dressed. The note itself never changes - paper is
 * paper - so this only reaches the bars, panels and dialogs floating over it.
 *
 * All three share one toolbar, one set of shapes and one layout; what differs
 * is the material. Material is solid, Glassmorphism is frosted glass, and
 * Liquid glass is a refractive lens.
 *
 * Saved by [name], so a constant is never renamed: Liquid glass is still
 * SPOTIGLASS on disk, from when it was called the glass bar.
 */
enum class Skin(val label: String, val blurb: String) {
    MATERIAL("Material", "Android default. Solid and still."),
    GLASSMORPHISM("Glassmorphism", "Frosted glass. Blurs what is behind it, evenly translucent."),
    SPOTIGLASS(
        "Liquid glass",
        "A lens that bends the page. The selection glides after your fingertip.",
    ),
    ;

    companion object {
        /**
         * The skin saved as [name], from preferences or a settings backup.
         * The old lens skin was folded into Liquid glass, so a note of it
         * comes back as that rather than falling to Material; anything else
         * unknown is Material.
         */
        fun fromName(name: String?): Skin = when (name) {
            RETIRED_LIQUID_GLASS -> SPOTIGLASS
            else -> entries.firstOrNull { it.name == name } ?: MATERIAL
        }

        /** The name the retired lens skin was saved under. */
        private const val RETIRED_LIQUID_GLASS = "LIQUID_GLASS"
    }
}

/** The skin that bends the page behind it rather than only blurring it. */
val Skin.isRefractive: Boolean
    get() = this == Skin.SPOTIGLASS

/**
 * Whether the chrome moves: icons that play, bars that morph between sizes,
 * a lens that swells under the finger, a ripple or hover wash. Material and
 * Glassmorphism stay still; the one motion they keep is the toolbar's
 * selection sliding to the tool in hand.
 */
val Skin.animatesChrome: Boolean
    get() = this == Skin.SPOTIGLASS

/**
 * Whether a popup grows out of the button that opened it. Glassmorphism keeps
 * this one, since a panel appearing from nowhere over glass loses where it
 * came from; Material simply shows it.
 */
val Skin.animatesPopups: Boolean
    get() = this != Skin.MATERIAL
