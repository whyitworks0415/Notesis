package com.notesis

/** Deterministic graphite islands with real gaps rather than a continuous veil. */
internal fun graphiteGrainAlpha(x: Int, y: Int): Int {
    fun noise(a: Int, b: Int): Int {
        var bits = a * 374761393 + b * 668265263 + 0x51F15EED
        bits = (bits xor (bits ushr 13)) * 1274126177
        return (bits xor (bits ushr 16)) ushr 24
    }
    val grain = noise(x / 2, y / 2)
    val speckle = noise(x, y)
    if (grain < 88 || speckle < 72) return 0
    return (72 + (grain - 88) / 2 + speckle / 3).coerceAtMost(240)
}
