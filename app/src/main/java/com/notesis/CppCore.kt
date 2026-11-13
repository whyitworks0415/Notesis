package com.notesis

import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Bulk work only: no JNI round trip per digitizer sample, and no native-owned handle. */
internal object CppCore {
    @Volatile var ready = false
        private set
    @Volatile var enabled = true
    private var started = false
    val tidyCalls = AtomicLong()
    val pageCalls = AtomicLong()
    val active: Boolean get() = ready && enabled && !LabGuard.nativeOff
    fun unavailable() { ready = false }

    @Synchronized fun prepare() {
        if (started) return
        started = true
        Thread({
            ready = runCatching { System.loadLibrary("notesis_core"); true }.getOrDefault(false)
        }, "notesis-core-init").apply { isDaemon = true; start() }
    }

    external fun tidyMask(xs: FloatArray, ys: FloatArray, times: LongArray,
        count: Int, width: Float, mode: Int): BooleanArray?
    external fun scanPage(source: ByteBuffer, length: Int): IntArray?

    /** Short strokes stay in Kotlin: crossing JNI costs more than their arithmetic. */
    fun keepMask(count: Int, width: Float, xs: FloatArray, ys: FloatArray, times: LongArray,
        mode: Int, reference: () -> BooleanArray?): BooleanArray? {
        if (active && count in 32..1_048_576 && width.isFinite()) {
            try {
                val mask = tidyMask(xs, ys, times, count, width, mode)
                tidyCalls.incrementAndGet()
                return mask
            } catch (_: LinkageError) {
                ready = false
            }
        }
        return reference()
    }
}

/**
 * A bounded native framing experiment. AndroidX Ink still decodes input payloads
 * and builds meshes; neither that work nor file I/O is claimed as ported here.
 * Large files keep the streaming reader so this does not double their footprint.
 */
internal object CppPageReader {
    const val MAX_BYTES = 8 * 1024 * 1024
    // At most one staging buffer at a time. Other loads keep their streaming path;
    // no unbounded direct-buffer allocation during fast page navigation.
    private var staging: ByteBuffer? = null
    private var busy = false

    private fun acquire(size: Int): ByteBuffer? = synchronized(this) {
        if (busy) return@synchronized null
        val buffer = staging?.takeIf { it.capacity() >= size } ?: run {
            // Empty and small pages should not fault in an 8 MiB buffer.
            // Grow geometrically so successive pages do not allocate each time.
            var capacity = 4096
            while (capacity < size) capacity *= 2
            ByteBuffer.allocateDirect(capacity).also { staging = it }
        }
        busy = true
        buffer.clear()
        buffer.limit(size)
        buffer
    }

    private fun release() = synchronized(this) { busy = false }

    fun read(file: File): CppPageRecords? {
        if (!CppCore.active || Thread.currentThread().isInterrupted) return null
        val size = file.length()
        if (size !in 12L..MAX_BYTES.toLong()) return null
        val source = acquire(size.toInt()) ?: return null
        try {
            file.inputStream().channel.use { channel ->
                while (source.hasRemaining()) {
                    if (Thread.currentThread().isInterrupted || channel.read(source) < 0) return null
                }
                // A file that changed while opening belongs to the original reader.
                if (channel.size() != size) return null
            }
            val index = CppCore.scanPage(source, size.toInt()) ?: return null
            val fields = ArrayList<CppPageRecord>((index.size - 1) / 8)
            for (at in 1 until index.size step 8) {
                if (Thread.currentThread().isInterrupted) return null
                val bytes = ByteArray(index[at + 5])
                source.position(index[at + 4])
                source.get(bytes)
                fields += CppPageRecord(index[at], index[at + 1], Float.fromBits(index[at + 2]),
                    index[at + 6], index[at + 7], bytes)
            }
            CppCore.pageCalls.incrementAndGet()
            return CppPageRecords(index[0], fields)
        } catch (_: LinkageError) {
            CppCore.unavailable()
            return null
        } catch (_: java.io.IOException) {
            return null
        } finally {
            release()
        }
    }

    /** Idle buffers can be relinquished without touching a load that owns one. */
    fun trim() = synchronized(this) { if (!busy) staging = null }
}

internal class CppPageRecord(val rawTool: Int, val color: Int, val size: Float,
    val layer: Int, val nib: Int, val inputs: ByteArray)
internal class CppPageRecords(val version: Int, val records: List<CppPageRecord>)
