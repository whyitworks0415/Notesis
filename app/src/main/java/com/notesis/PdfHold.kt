package com.notesis

import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * A PDF a view has just let go of, kept open for a few seconds.
 *
 * Closing the reference popup and opening it again on the same note, or
 * stepping out to the library and back in, opened the PDF from scratch and
 * rendered every visible page again. Held here, the source comes back with
 * its pages and tiles still in memory. One at a time, for [HOLD_MS]; given
 * back to Android at once when memory is wanted.
 */
internal object PdfHold : MemoryTrim.Trimmable {
    private const val HOLD_MS = 20_000L

    private class Held(val key: String, val source: PdfSource)

    private val main = Handler(Looper.getMainLooper())
    private var held: Held? = null
    private val expire = Runnable { closeHeld() }

    init {
        MemoryTrim.register(this)
    }

    /** The held source for [file] at [cacheBytes] if there is one, else a newly opened one. */
    fun take(file: File, cacheBytes: Int): PdfSource? {
        val key = keyOf(file, cacheBytes)
        synchronized(this) {
            val kept = held
            if (kept != null && kept.key == key) {
                held = null
                main.removeCallbacks(expire)
                if (!kept.source.isClosed) return kept.source
            }
        }
        return PdfSource.open(file, cacheBytes, links = true)
    }

    /** Keeps [source] for a moment instead of closing it. */
    fun release(source: PdfSource) {
        val file = source.file
        if (file == null || source.isClosed) {
            source.close()
            return
        }
        source.onReady = null
        source.diagnostics = null
        val previous = synchronized(this) {
            val before = held
            held = Held(keyOf(file, source.cacheBytes), source)
            before
        }
        if (previous != null && previous.source !== source) previous.source.close()
        main.removeCallbacks(expire)
        main.postDelayed(expire, HOLD_MS)
    }

    override fun trimMemory(severe: Boolean) = closeHeld()

    private fun closeHeld() {
        val kept = synchronized(this) { held.also { held = null } }
        kept?.source?.close()
    }

    /** Same file, unchanged since, and the same budget. */
    private fun keyOf(file: File, cacheBytes: Int): String =
        "${file.absolutePath}|${file.length()}|${file.lastModified()}|$cacheBytes"
}
