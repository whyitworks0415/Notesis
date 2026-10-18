package com.notesis

import android.graphics.ColorMatrixColorFilter

const val PAGE_TONE_NORMAL = 0
const val PAGE_TONE_SEPIA = 1
const val PAGE_TONE_NIGHT = 2

/** The colour filter for a page tone, or null for the page as it is. */
internal fun pageToneFilter(tone: Int): ColorMatrixColorFilter? =
    pageToneMatrix(tone)?.let { ColorMatrixColorFilter(it) }

/**
 * The 4x5 colour matrix behind [pageToneFilter].
 *
 * Sepia keeps black black and turns white to a warm paper (about 245, 237,
 * 217). Night inverts lightness but not hue - an inversion followed by a
 * half turn of hue - so a red underline stays red and black ink turns light,
 * then lifts the black to a dark grey and softens the white, which is easier
 * on the eyes than pure white on pure black.
 */
internal fun pageToneMatrix(tone: Int): FloatArray? = when (tone) {
    PAGE_TONE_SEPIA -> floatArrayOf(
        0.80f, 0.12f, 0.04f, 0f, 0f,
        0.08f, 0.80f, 0.05f, 0f, 0f,
        0.05f, 0.10f, 0.70f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
    PAGE_TONE_NIGHT -> {
        // -H x + 255, H being the 180 degree hue rotation whose rows sum to 1.
        val h = arrayOf(
            floatArrayOf(-0.574f, 1.430f, 0.144f),
            floatArrayOf(0.426f, 0.430f, 0.144f),
            floatArrayOf(0.426f, 1.430f, -0.856f),
        )
        val scale = NIGHT_RANGE
        val offset = 255f * NIGHT_RANGE + NIGHT_FLOOR
        floatArrayOf(
            -h[0][0] * scale, -h[0][1] * scale, -h[0][2] * scale, 0f, offset,
            -h[1][0] * scale, -h[1][1] * scale, -h[1][2] * scale, 0f, offset,
            -h[2][0] * scale, -h[2][1] * scale, -h[2][2] * scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }
    else -> null
}

/** Night maps black to [NIGHT_FLOOR] + 255 x [NIGHT_RANGE] and white to [NIGHT_FLOOR]. */
private const val NIGHT_RANGE = 0.82f
private const val NIGHT_FLOOR = 22f
