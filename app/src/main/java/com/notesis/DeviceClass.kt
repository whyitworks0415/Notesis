package com.notesis

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Whether this is one of the slower tablets and phones the app should run
 * light on by default (see [InteractionGovernor.lightMode]).
 *
 * Asked once, at start, from what the system says about itself: a device
 * that calls itself low on memory, one with under about 5 GB, a model
 * known to need the compatible wet ink, the Galaxy Tab S6 Lite - an
 * eight-core phone chip and a three-core GPU behind a 2000 px screen - or
 * one on Android 12 or older. Those cannot draw the liquid glass at all
 * (its shaders need Android 13), so the full looks only recorded the whole
 * screen every frame for nothing; and they are the older tablets, like the
 * Galaxy Tab S5e (Android 11, a 2560 px screen), which came in a 6 GB
 * model that passed every other test and was dragged down running it all.
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
            sdk = Build.VERSION.SDK_INT,
        )
    }

    fun isSlow(lowRam: Boolean, totalMemoryBytes: Long, model: String, sdk: Int = FULL_EFFECTS_SDK): Boolean =
        lowRam || (totalMemoryBytes in 1 until SLOW_BELOW_BYTES) || frontBufferInkUnreliable(model) ||
            sdk < FULL_EFFECTS_SDK

    /**
     * Whether the tablet has a pen: any input device that is a stylus. The
     * Galaxy Tab S5e has none, so out of the box a finger there writes and
     * two fingers move the page, rather than a finger only scrolling.
     */
    fun hasStylus(context: Context): Boolean = runCatching {
        android.view.InputDevice.getDeviceIds().any { id ->
            android.view.InputDevice.getDevice(id)?.supportsSource(android.view.InputDevice.SOURCE_STYLUS) == true
        }
    }.getOrDefault(true)

    /** What one finger does out of the box: writes where there is no pen to write with. */
    fun defaultOneFinger(hasStylus: Boolean): Int = if (hasStylus) FINGER_SCROLL else FINGER_DRAW

    /** Android 13, where the liquid glass's shaders first run. */
    const val FULL_EFFECTS_SDK = 33

    /** 4 GB devices report about 3.6 GB; 6 GB ones about 5.5 GB. */
    private const val SLOW_BELOW_BYTES = 5_000_000_000L
}
