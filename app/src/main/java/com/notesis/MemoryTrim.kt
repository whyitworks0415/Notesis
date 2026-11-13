package com.notesis

import android.content.ComponentCallbacks2
import java.util.Collections
import java.util.WeakHashMap

/**
 * Hands Android's requests for memory to everything holding caches it can
 * rebuild: the open canvases, with their PDF pages and tiles and their ink
 * bitmaps. Without it, an app in the background kept every one of those
 * until it was killed for them, and a tablet short on memory had nothing
 * to give back while a note stayed open.
 */
internal object MemoryTrim {
    fun interface Trimmable {
        /** [severe]: hidden or nearly out of memory, so keep only what is cheap to hold. */
        fun trimMemory(severe: Boolean)
    }

    private val held: MutableSet<Trimmable> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun register(target: Trimmable) {
        held += target
    }

    fun unregister(target: Trimmable) {
        held -= target
    }

    /** For [ComponentCallbacks2.onTrimMemory]; false when the level asks for nothing. */
    fun trim(level: Int): Boolean {
        val severe = isSevere(level) ?: return false
        val targets = synchronized(held) { held.toList() }
        val before = heldBytes()
        CppPageReader.trim()
        for (target in targets) runCatching { target.trimMemory(severe) }
        lastTrim = TrimRecord(System.currentTimeMillis(), level, severe, before, heldBytes())
        return true
    }

    /** The last time Android asked for memory back: when, how hard, and what was held before and after. */
    data class TrimRecord(val at: Long, val level: Int, val severe: Boolean, val before: Long, val after: Long)

    @Volatile var lastTrim: TrimRecord? = null
        private set

    /** The app's Java objects and its native memory (bitmaps live there), in bytes. */
    private fun heldBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory() + android.os.Debug.getNativeHeapAllocatedSize()
    }

    /** A line for the diagnostics report: how much the last trim gave back. */
    fun report(): String {
        val trim = lastTrim ?: return "Memory given back: Android has not asked yet"
        val mib = 1024L * 1024L
        val ago = (System.currentTimeMillis() - trim.at) / 1000
        return "Memory given back: ${(trim.before - trim.after) / mib} MB of ${trim.before / mib} MB held " +
            "(level ${trim.level}${if (trim.severe) ", hidden" else ""}, ${ago}s ago)"
    }

    @Suppress("DEPRECATION")
    internal fun isSevere(level: Int): Boolean? = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> true
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> false
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> true
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> false
        else -> null
    }
}
