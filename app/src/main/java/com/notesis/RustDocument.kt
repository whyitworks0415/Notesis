package com.notesis

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Opaque ownership tokens, never addresses, cross the JNI boundary. */
internal object RustNative {
    @Volatile var ready = false
        private set
    val reads = AtomicLong()
    val writes = AtomicLong()
    val historyOperations = AtomicLong()
    val active: Boolean get() = ready && CppCore.active

    fun connected() { ready = runCatching { abi() == 1 }.getOrDefault(false) }
    fun unavailable() { ready = false }
    external fun abi(): Int
    external fun openReader(path: ByteArray): Long
    external fun readerInfo(handle: Long): IntArray
    external fun readNext(handle: Long, metadata: IntArray): ByteArray?
    external fun closeReader(handle: Long)
    external fun openWriter(path: ByteArray, count: Int): Long
    external fun openAppender(path: ByteArray, oldCount: Int, count: Int): Long
    external fun writeNext(handle: Long, tool: Int, color: Int, size: Float, epsilon: Float,
        payload: ByteArray, length: Int, layer: Int, nib: Int)
    external fun commitWriter(handle: Long)
    external fun abortWriter(handle: Long)
    external fun newHistory(limit: Int): Long
    external fun recordHistory(handle: Long, before: String, afterLength: Int, start: Int, end: Int, typed: Boolean, now: Long): Int
    external fun moveHistory(handle: Long, current: String, start: Int, end: Int, redo: Boolean, selection: IntArray): String?
    external fun closeHistory(handle: Long)
}

/** Only the load that opened a reader owns it; cancellation closes the file. */
internal class RustPageReader private constructor(private var handle: Long, val version: Int, val count: Int) : Closeable {
    val metadata = IntArray(6)
    fun next(): ByteArray? {
        check(handle != 0L)
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Page read cancelled")
        return RustNative.readNext(handle, metadata)
    }
    override fun close() {
        val owned = handle; handle = 0
        if (owned != 0L) RustNative.closeReader(owned)
    }
    companion object {
        fun open(file: File): RustPageReader? {
            if (!RustNative.active || Thread.currentThread().isInterrupted) return null
            return try {
                val handle = RustNative.openReader(file.absolutePath.toByteArray(Charsets.UTF_8))
                if (handle == 0L) null else try {
                    val info = RustNative.readerInfo(handle)
                    RustNative.reads.incrementAndGet()
                    RustPageReader(handle, info[0], info[1])
                } catch (error: Throwable) {
                    RustNative.closeReader(handle)
                    throw error
                }
            } catch (_: LinkageError) { RustNative.unavailable(); null }
        }
    }
}

/** A failed or abandoned replacement never changes the previous save. */
internal class RustPageWriter private constructor(private var handle: Long) : Closeable {
    fun push(tool: Int, color: Int, size: Float, epsilon: Float, payload: ByteArray, length: Int, layer: Int, nib: Int) {
        check(handle != 0L)
        RustNative.writeNext(handle, tool, color, size, epsilon, payload, length, layer, nib)
    }
    fun commit() {
        val owned = handle; check(owned != 0L); handle = 0
        RustNative.commitWriter(owned)
        RustNative.writes.incrementAndGet()
    }
    override fun close() {
        val owned = handle; handle = 0
        if (owned != 0L) RustNative.abortWriter(owned)
    }
    companion object {
        fun append(file: File, oldCount: Int, count: Int): RustPageWriter? {
            if (!RustNative.active || oldCount !in 1 until count || count > 1_000_000) return null
            return try {
                val handle = RustNative.openAppender(file.absolutePath.toByteArray(Charsets.UTF_8), oldCount, count)
                if (handle == 0L) null else RustPageWriter(handle)
            } catch (_: LinkageError) { RustNative.unavailable(); null }
        }
        fun open(file: File, count: Int): RustPageWriter? {
            if (!RustNative.active || count !in 0..1_000_000) return null
            return try {
                val handle = RustNative.openWriter(file.absolutePath.toByteArray(Charsets.UTF_8), count)
                if (handle == 0L) throw IOException("Could not start the page replacement")
                RustPageWriter(handle)
            } catch (_: LinkageError) { RustNative.unavailable(); null }
        }
    }
}

/** Lets a native writer consume only the used prefix, avoiding toByteArray(). */
internal class StrokeScratchBuffer : java.io.ByteArrayOutputStream() {
    val bytes: ByteArray get() = buf
}
