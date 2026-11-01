package com.notesis

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Whether this is one of the slower tablets and phones the app should run
 * light on by default (see [InteractionGovernor.lightMode]).
 *
 * Asked once, at start, from what the system says about itself: a device
 * that calls itself low on memory, one with under about 5 GB, or a model
 * known to need the compatible wet ink, the Galaxy Tab S6 Lite - an
 * eight-core phone chip and a three-core GPU behind a 2000 px screen.
 */
internal object DeviceClass {
    fun isSlow(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = runCatching {
            ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }.totalMem
        }.getOrDefault(0L)
        return isSlow(
            lowRam = manager?.isLowRamDevice == true,
            totalMemoryBytes = memory,
            model = Build.MODEL ?: "",
        )
    }

    fun isSlow(lowRam: Boolean, totalMemoryBytes: Long, model: String): Boolean =
        lowRam || (totalMemoryBytes in 1 until SLOW_BELOW_BYTES) || frontBufferInkUnreliable(model)

    /** 4 GB devices report about 3.6 GB; 6 GB ones about 5.5 GB. */
    private const val SLOW_BELOW_BYTES = 5_000_000_000L
}
