package com.notesis

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.util.WeakHashMap

/** One process-wide governor for caches that otherwise cannot see each other. */
internal object RuntimeMemory {
    private val caches = WeakHashMap<MemoryTrimmable, Unit>()

    fun register(cache: MemoryTrimmable) = synchronized(caches) { caches[cache] = Unit }

    fun unregister(cache: MemoryTrimmable) = synchronized(caches) { caches.remove(cache) }

    fun trim(level: Int) {
        val snapshot = synchronized(caches) { caches.keys.toList() }
        snapshot.forEach { it.trimMemory(level) }
    }

    fun pdfCacheBytes(context: Context): Int = fractionOfHeap(
        context, 1, 4, 24 * MIB, 96 * MIB,
    )

    fun imageCacheBytes(context: Context): Int = fractionOfHeap(
        context, 1, 10, 8 * MIB, 32 * MIB,
    )

    fun inkTileCacheBytes(context: Context): Int = fractionOfHeap(
        context, 1, 10, 8 * MIB, 40 * MIB,
    )

    fun documentParseBytes(context: Context): Int = fractionOfHeap(
        context, 1, 4, 8 * MIB, 64 * MIB,
    )

    private fun fractionOfHeap(
        context: Context,
        numerator: Int,
        denominator: Int,
        minimum: Int,
        maximum: Int,
    ): Int {
        // memoryClass is the heap the app actually requested. largeMemoryClass
        // describes a largeHeap app even when the manifest did not opt into it.
        val manager = context.getSystemService(ActivityManager::class.java)
        val heapBytes = (manager?.memoryClass ?: 128).toLong() * MIB
        return (heapBytes * numerator / denominator)
            .coerceIn(minimum.toLong(), maximum.toLong()).toInt()
    }

    private const val MIB = 1024 * 1024
}

internal interface MemoryTrimmable {
    fun trimMemory(level: Int)
}

/** Byte-sized bitmap LRU that participates in Android memory-pressure callbacks. */
internal open class BitmapMemoryCache<K>(maxBytes: Int) :
    LruCache<K, Bitmap>(maxBytes), MemoryTrimmable, AutoCloseable {

    init {
        RuntimeMemory.register(this)
    }

    override fun sizeOf(key: K, value: Bitmap): Int = value.allocationByteCount

    override fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> trimToSize(maxSize() / 2)
        }
    }

    override fun close() {
        RuntimeMemory.unregister(this)
        evictAll()
    }
}
