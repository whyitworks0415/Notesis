package com.notesis

/** Translucent swatches carry their preset opacity; RGB-only ones keep the tool's. */
internal fun paletteColorArgb(current: Int, swatch: Int): Int =
    if ((swatch ushr 24) < 255) swatch else (current and -0x1000000) or (swatch and 0xFFFFFF)
