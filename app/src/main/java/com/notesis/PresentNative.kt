package com.notesis

import android.graphics.Bitmap
import android.view.Surface

/**
 * The Lab's experiment in showing wet ink through hardware buffers of our own
 * and a surface transaction (see present_jni.cpp). Loaded only when it is asked
 * for; a library that does not load, or any call that fails, leaves the
 * surface's canvas in charge.
 */
internal object PresentNative {
    @Volatile private var loaded: Boolean? = null

    /** Whether the library is there, loading it the first time. */
    @Synchronized fun available(): Boolean {
        loaded?.let { return it }
        val ok = runCatching { System.loadLibrary("notesis_present"); true }.getOrDefault(false)
        loaded = ok
        return ok
    }

    /** A handle for [surface] and a picture [width] by [height], or 0 when it could not be made. */
    external fun create(surface: Surface, width: Int, height: Int): Long

    /** A buffer to write the next frame into: its index, -1 when none is free yet, -2 on a failure. */
    external fun acquire(handle: Long): Int

    /** Copies the changed part of [bitmap] into buffer [index] and submits it: 0, or negative on a failure. */
    external fun post(handle: Long, index: Int, bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int

    external fun destroy(handle: Long)
}

/** How the wet ink reached the screen, for the diagnostics. */
internal enum class OwnBufferState(val label: String) {
    OFF("off"),
    ACTIVE("on"),
    FELL_BACK("failed, back on the surface's canvas"),
    UNAVAILABLE("library not loaded"),
}
