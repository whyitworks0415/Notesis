package com.notesis

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.pow

/**
 * The whole theme out of one colour.
 *
 * Material's baseline scheme is purple, and overriding `primary` alone left the
 * other forty roles purple with it - containers, outlines, the tint on every
 * surface. So the accent is not painted over the baseline any more, it *is* the
 * baseline: each palette is the accent's hue at a fixed chroma, and every role
 * is a tone off one of them. Change the accent and nothing purple is left.
 *
 * The tones are Material's - 40 for a body colour, 90 for its container, 10 for
 * what sits on it - and they are real L*, solved for rather than guessed at. A
 * tone taken as HSL lightness instead is the trap here: it is within a percent
 * on grey and nowhere near on a saturated hue, because green at half lightness
 * is far brighter than blue at half lightness. Read that way a green accent
 * comes out at #10D55A with white lettering on it, which cannot be read. So
 * each tone is bisected to the lightness that measures the L* asked for, and
 * every accent lands at the same weight as every other.
 *
 * [highContrast] moves the tones rather than switching to a second palette:
 * bodies go darker, containers paler, outlines heavier. Same theme, further
 * apart - which is what a contrast setting should do to an app somebody has
 * already chosen the colours of.
 */
fun schemeFrom(accent: Int, highContrast: Boolean = false): ColorScheme {
    val seed = Hsl.of(accent)
    // A colour dragged to the grey end of the picker should still theme the app
    // rather than turning every container into a slab of the same grey, and one
    // at the far end should not make the containers glow.
    val chroma = seed.saturation.coerceIn(0.18f, 0.86f)
    val primary = Palette(seed.hue, chroma)
    // Material's secondary is the same hue gone quiet; the tertiary is a turn
    // round the wheel, which is what keeps a one-colour theme from being flat.
    val secondary = Palette(seed.hue, chroma * 0.34f)
    val tertiary = Palette((seed.hue + 60f) % 360f, chroma * 0.62f)
    // Not pure grey: the neutrals carry a trace of the accent, so white chrome
    // over a coloured theme reads as belonging to it.
    val neutral = Palette(seed.hue, chroma * 0.05f)
    val variant = Palette(seed.hue, chroma * 0.13f)
    // Error stays red whatever the accent is. A red that follows the theme is a
    // red that stops meaning error.
    val danger = Palette(3f, 0.71f)

    // The body of a colour, what sits inside its container, and the two lines
    // the app draws with. Everything else keeps the tone it had: a container
    // that goes paler and a body that goes darker is the whole of it.
    val body = if (highContrast) 30 else 40
    val onContainer = if (highContrast) 8 else 14
    val container = if (highContrast) 93 else 90
    val ink = if (highContrast) 0 else 10
    val line = if (highContrast) 38 else 52
    val faintLine = if (highContrast) 64 else 82

    return lightColorScheme(
        primary = primary.tone(body),
        onPrimary = Color.White,
        primaryContainer = primary.tone(container),
        onPrimaryContainer = primary.tone(onContainer),
        inversePrimary = primary.tone(78),
        secondary = secondary.tone(body),
        onSecondary = Color.White,
        secondaryContainer = secondary.tone(container),
        onSecondaryContainer = secondary.tone(onContainer),
        tertiary = tertiary.tone(body),
        onTertiary = Color.White,
        tertiaryContainer = tertiary.tone(container),
        onTertiaryContainer = tertiary.tone(onContainer),
        background = neutral.tone(99),
        onBackground = neutral.tone(ink),
        surface = neutral.tone(99),
        onSurface = neutral.tone(ink),
        surfaceVariant = variant.tone(if (highContrast) 88 else 92),
        onSurfaceVariant = variant.tone(if (highContrast) 20 else 32),
        surfaceTint = primary.tone(body),
        inverseSurface = neutral.tone(20),
        inverseOnSurface = neutral.tone(96),
        error = danger.tone(body),
        onError = Color.White,
        errorContainer = danger.tone(container),
        onErrorContainer = danger.tone(onContainer),
        outline = variant.tone(line),
        outlineVariant = variant.tone(faintLine),
        scrim = Color.Black,
        surfaceBright = neutral.tone(99),
        surfaceDim = neutral.tone(88),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = neutral.tone(97),
        surfaceContainer = neutral.tone(95),
        surfaceContainerHigh = neutral.tone(93),
        surfaceContainerHighest = neutral.tone(91),
    )
}

/** One hue at one chroma, sliced at whatever tone a role asks for. */
private class Palette(hue: Float, chroma: Float) {
    private val hue = hue.coerceIn(0f, 360f)
    private val chroma = chroma.coerceIn(0f, 1f)

    /** The colour of this hue that measures [tone] on the L* scale. */
    fun tone(tone: Int): Color {
        val target = tone.toFloat().coerceIn(0f, 100f)
        // L* climbs with lightness and nothing else moves, so twelve halvings
        // land inside a thousandth - far below anything a screen can show.
        var low = 0f
        var high = 1f
        repeat(12) {
            val middle = (low + high) / 2f
            if (lightnessOf(Color.hsl(hue, chroma, middle)) < target) {
                low = middle
            } else {
                high = middle
            }
        }
        return Color.hsl(hue, chroma, (low + high) / 2f)
    }
}

/** CIE L*: how light a colour looks, rather than how much light it is. */
private fun lightnessOf(colour: Color): Float {
    val y = 0.2126f * toLinear(colour.red) +
        0.7152f * toLinear(colour.green) +
        0.0722f * toLinear(colour.blue)
    return if (y > 0.008856f) 116f * y.pow(1f / 3f) - 16f else y * 903.3f
}

private fun toLinear(channel: Float): Float =
    if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

/** Only the two parts of a colour a palette is built from. */
private class Hsl(val hue: Float, val saturation: Float) {
    companion object {
        fun of(argb: Int): Hsl {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val high = maxOf(r, g, b)
            val low = minOf(r, g, b)
            val span = high - low
            // Grey has no hue to take and a theme has to start somewhere, so
            // it starts on Material's own violet: a grey accent gives back the
            // stock Android theme, quietened.
            if (span < 1e-4f) return Hsl(258f, 0f)
            val lightness = (high + low) / 2f
            val saturation = span / (1f - abs(2f * lightness - 1f)).coerceAtLeast(1e-4f)
            val hue = when (high) {
                r -> 60f * ((g - b) / span)
                g -> 60f * ((b - r) / span + 2f)
                else -> 60f * ((r - g) / span + 4f)
            }
            return Hsl((hue % 360f + 360f) % 360f, saturation.coerceIn(0f, 1f))
        }
    }
}
