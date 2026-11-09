package com.notesis

/**
 * How the app's chrome is dressed. The note itself never changes - paper is
 * paper - so this only reaches the bars, panels and dialogs floating over it.
 *
 * All three share one toolbar, one set of shapes and one layout; what differs
 * is the material. Material is solid, Glassmorphism is frosted glass, and
 * Liquid glass is a refractive lens, drawn by one of its recipes - its own,
 * iOS's or Mornye's (see [LiquidStyle]). Every one of them moves: popups grow
 * out of their button, buttons answer a press, icons play.
 *
 * Saved by [name], so a constant is never renamed: Liquid glass is still
 * LIQUID_GLASS on disk, from when it was called the glass bar.
 */
enum class Skin(val label: String, val blurb: String) {
    MATERIAL("Material", "Layered tonal surfaces, crisp typography and responsive Material controls."),
    GLASSMORPHISM("Glassmorphism", "Frosted glass. Blurs what is behind it, evenly translucent."),
    LIQUID_GLASS(
        "Liquid glass",
        "A lens that bends the page. The selection glides after your fingertip.",
    ),
    ;

    companion object {
        /**
         * The skin saved as [name], from preferences or a settings backup.
         * Liquid glass was saved as "SPOTIGLASS" before its code took the
         * name it is shown by, and an older lens skin as "LIQUID_GLASS",
         * which is this one's name now; both come back as Liquid glass.
         * Anything else unknown is Material.
         */
        fun fromName(name: String?): Skin = when (name) {
            SAVED_BEFORE_RENAME -> LIQUID_GLASS
            else -> entries.firstOrNull { it.name == name } ?: MATERIAL
        }

        /** The name Liquid glass was saved under until 0.90. */
        private const val SAVED_BEFORE_RENAME = "SPOTIGLASS"
    }
}

/** The skin that bends the page behind it rather than only blurring it. */
val Skin.isRefractive: Boolean
    get() = this == Skin.LIQUID_GLASS
