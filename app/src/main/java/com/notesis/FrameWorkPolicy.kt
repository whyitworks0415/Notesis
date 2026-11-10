package com.notesis

/** Always make progress; subsequent optional records must fit the remaining draw allowance. */
internal fun optionalTileWork(completed: Int, elapsed: Long, budget: Long, expected: Long): Boolean =
    completed == 0 || completed < 8 && elapsed < budget && expected.coerceAtLeast(0) <= budget - elapsed

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
