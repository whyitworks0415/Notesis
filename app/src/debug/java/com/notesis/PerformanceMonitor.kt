package com.notesis

import android.util.Log
import android.view.Window
import androidx.metrics.performance.JankStats

/** Debug-only frame diagnostics; release builds compile against a no-op twin. */
internal class PerformanceMonitor private constructor(
    private val stats: JankStats,
) : AutoCloseable {

    override fun close() {
        stats.isTrackingEnabled = false
    }

    companion object {
        fun install(window: Window): PerformanceMonitor {
            val stats = JankStats.createAndTrack(window) { frame ->
                if (frame.isJank) {
                    Log.w(
                        "NotesisJank",
                        "ui=${frame.frameDurationUiNanos / 1_000_000.0}ms states=${frame.states}",
                    )
                }
            }
            return PerformanceMonitor(stats)
        }
    }
}
