package com.notesis

import kotlin.math.sqrt

/**
 * How big a one-off bitmap - a captured region, an exported page - may be.
 *
 * Its pixels are capped at a sixteenth of the heap, and never more than
 * 64MB, and each side at [MAX_SIDE], which every GPU this runs on can still
 * show as one texture. A large capture at four times used to ask for
 * whatever its rectangle came to, and on a big page that was the out-of-memory
 * that closed the app.
 */
internal object BitmapBudget {
    const val MAX_SIDE = 4096
    private const val MAX_BYTES = 64L * 1024 * 1024

    fun bytes(): Long = minOf(Runtime.getRuntime().maxMemory() / 16, MAX_BYTES)

    /** The factor (at most 1) to scale a [width] x [height] ARGB bitmap by to fit. */
    fun scaleFor(width: Int, height: Int): Float = bitmapBudgetScale(width, height, bytes(), MAX_SIDE)
}

/** See [BitmapBudget]: the largest scale <= 1 keeping 4 bytes a pixel within [budgetBytes] and each side within [maxSide]. */
internal fun bitmapBudgetScale(width: Int, height: Int, budgetBytes: Long, maxSide: Int): Float {
    if (width <= 0 || height <= 0) return 1f
    val bytes = width.toDouble() * height * 4.0
    val byMemory = if (bytes <= budgetBytes) 1.0 else sqrt(budgetBytes / bytes)
    val bySide = minOf(1.0, maxSide.toDouble() / maxOf(width, height))
    return minOf(byMemory, bySide).toFloat()
}
