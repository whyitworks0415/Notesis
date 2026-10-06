package com.notesis

/** Side of the square grain tile; the lattice wraps on it so the tile repeats seamlessly. */
internal const val GRAPHITE_TILE = 128

/**
 * Graphite islands with real gaps rather than a continuous veil.
 *
 * Smooth value noise at three scales, so islands have soft, antialiased rims
 * instead of the hard-edged 2x2 texel blocks the old hash grain produced -
 * those were visible as square pixels once a pencil stroke was magnified.
 */
internal fun graphiteGrainAlpha(x: Int, y: Int): Int {
    val noise = latticeNoise(x, y, 8, 0x51F15EED) * 0.55f +
        latticeNoise(x, y, 4, 0x2C1B3C6D) * 0.3f +
        latticeNoise(x, y, 2, 0x297A2D39) * 0.15f
    val ramp = (noise - GRAIN_THRESHOLD) / GRAIN_SOFTNESS
    if (ramp <= 0f) return 0
    val t = ramp.coerceAtMost(1f)
    return (t * t * (3f - 2f * t) * 240f).toInt()
}

private const val GRAIN_THRESHOLD = 0.5f
private const val GRAIN_SOFTNESS = 0.08f

private fun latticeNoise(x: Int, y: Int, cell: Int, seed: Int): Float {
    val period = GRAPHITE_TILE / cell
    val gx = x / cell
    val gy = y / cell
    val fx = (x % cell).toFloat() / cell
    val fy = (y % cell).toFloat() / cell
    val sx = fx * fx * (3f - 2f * fx)
    val sy = fy * fy * (3f - 2f * fy)
    fun corner(i: Int, j: Int): Float = grainHash(i % period, j % period, seed) / 255f
    val a = corner(gx, gy)
    val b = corner(gx + 1, gy)
    val c = corner(gx, gy + 1)
    val d = corner(gx + 1, gy + 1)
    val top = a + (b - a) * sx
    val bottom = c + (d - c) * sx
    return top + (bottom - top) * sy
}

private fun grainHash(a: Int, b: Int, seed: Int): Int {
    var bits = a * 374761393 + b * 668265263 + seed
    bits = (bits xor (bits ushr 13)) * 1274126177
    return (bits xor (bits ushr 16)) ushr 24
}
