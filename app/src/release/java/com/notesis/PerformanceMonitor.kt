package com.notesis

import android.view.Window

/** Keeps release builds free of frame-listener overhead. */
internal class PerformanceMonitor private constructor() : AutoCloseable {
    override fun close() = Unit

    companion object {
        fun install(window: Window): PerformanceMonitor = PerformanceMonitor()
    }
}
