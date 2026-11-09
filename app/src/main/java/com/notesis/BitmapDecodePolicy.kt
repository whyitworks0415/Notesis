package com.notesis

/** A power-of-two decode that fits both a texture and its share of the cache. */
internal fun previewSampleSize(width: Int, height: Int, maxSide: Int, maxBytes: Long): Int {
    if (width <= 0 || height <= 0) return 1
    val side = maxSide.coerceAtLeast(1)
    val bytes = maxBytes.coerceAtLeast(4)
    var sample = 1
    while (sample < (1 shl 30)) {
        val w = (width.toLong() + sample - 1) / sample
        val h = (height.toLong() + sample - 1) / sample
        // Division avoids overflow even for a malformed image's dimensions.
        if (w <= side && h <= side && w <= bytes / 4 / h) break
        sample *= 2
    }
    return sample
}
