package com.notesis

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import androidx.annotation.RequiresApi

/**
 * Tells the system which threads are working for the pen while it is down, and
 * how long their work should take, so it can raise the CPU's speed ahead of
 * the work instead of after it (Android's performance hint sessions, API 31).
 *
 * A tablet's governor ramps up when load shows; the first samples of a stroke,
 * and each one after a pause in writing, then run at a speed set for idle.
 * Whether this helps on the S6 Lite is not known: it is a switch in the Lab's
 * diagnostics (see [LabPerformance.writingBoost]) so it can be compared on and
 * off. It never changes what is drawn.
 */
internal class WritingBoost {
    // Held as Any: the session's class only exists from Android 12, and only
    // the guarded methods below name it.
    private var session: Any? = null

    /** Whether a session is open: the pen is down and the system accepted it. */
    val active: Boolean get() = session != null

    /** What the diagnostics show for it. */
    var status: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "idle" else "needs Android 12"
        private set

    /**
     * Opens a session for [threadIds] with each pen frame's work expected to
     * take [targetNanos]. Does nothing when one is open, when the switch is
     * off, below Android 12, or if the system refuses.
     */
    fun begin(context: Context, threadIds: IntArray, targetNanos: Long) {
        if (session != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!LabPerformance.writingBoost) {
            status = "off"
            return
        }
        session = open(context, threadIds, targetNanos)
        status = if (session != null) "on" else "refused by the system"
    }

    /** One pen frame's actual work, for the system to compare with the target. */
    fun report(actualNanos: Long) {
        val open = session ?: return
        if (actualNanos <= 0L || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        reportTo(open, actualNanos)
    }

    fun end() {
        val open = session ?: return
        session = null
        status = if (LabPerformance.writingBoost) "idle" else "off"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) close(open)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun reportTo(open: Any, actualNanos: Long) {
        runCatching { (open as PerformanceHintManager.Session).reportActualWorkDuration(actualNanos) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun close(open: Any) {
        runCatching { (open as PerformanceHintManager.Session).close() }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun open(context: Context, threadIds: IntArray, targetNanos: Long): Any? =
        runCatching {
            context.getSystemService(PerformanceHintManager::class.java)
                ?.createHintSession(threadIds, targetNanos)
        }.getOrNull()
}

/**
 * How long one pen frame's work should take: a share of the display's frame,
 * not all of it, so the system keeps the pen's threads ahead of the deadline.
 * The share is a first guess, to be tuned against the Lab's timings.
 */
internal fun hintTargetNanos(refreshHz: Float): Long =
    (1e9 / refreshHz.coerceIn(30f, 240f) * HINT_TARGET_SHARE).toLong()

internal const val HINT_TARGET_SHARE = 0.4
