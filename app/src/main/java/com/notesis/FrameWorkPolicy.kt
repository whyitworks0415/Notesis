package com.notesis

/** Always make progress; subsequent optional records must fit the remaining draw allowance. */
internal fun optionalTileWork(completed: Int, elapsed: Long, budget: Long, expected: Long): Boolean =
    completed == 0 || completed < 8 && elapsed < budget && expected.coerceAtLeast(0) <= budget - elapsed

/**
 * How many strokes one frame's new GPU-recorded tiles may hold, by what the
 * person is doing: the render thread paints every one of them into a layer in
 * that frame. One tile always goes ahead whatever it holds.
 */
internal fun strokeCapFor(state: Interaction): Int = when (state) {
    Interaction.WRITING -> 300
    Interaction.MOVING -> 600
    Interaction.SETTLING, Interaction.IDLE -> 1_500
}

/** First opening publishes its requested page before building any visible neighbours. */
internal fun <T> openingLoads(missing: List<T>, preferred: T?, opening: Boolean): List<T> =
    if (!opening) missing else missing.firstOrNull { it === preferred }?.let { listOf(it) } ?: missing.take(1)

/** Only actual draw-completion timestamps qualify; presentation estimates are deliberately excluded. */
internal fun inkDrawCompletion(event: Long, common: Long, front: Long, hwui: Long): Long? =
    if (event < 0) null else when {
        front > event -> front
        hwui > event -> hwui
        common > event -> common
        else -> null
    }
