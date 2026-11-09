package com.notesis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/** Avoid turning long sessions or large erases into linear scans of an old tree's tail. */
internal fun nativeBoundsUseful(live: Int, base: Int, appended: Int): Boolean =
    live in NativeBoundsTree.MIN_STROKES..NativeBoundsTree.MAX_STROKES &&
        base >= NativeBoundsTree.MIN_STROKES && live * 2 >= base &&
        appended in 0..(base / 4).coerceIn(64, 256)

/** Load away from pen-down. A missing backend leaves the existing grid in charge. */
internal object SpatialNative {
    @Volatile var ready = false
        private set
    private var started = false

    @Synchronized fun prepare() {
        if (started) return
        started = true
        Thread({ ready = runCatching { System.loadLibrary("notesis_spatial"); true }.getOrDefault(false) },
            "notesis-spatial-init").apply { isDaemon = true; start() }
    }

    external fun build(records: ByteBuffer, count: Int, tree: ByteBuffer): Int
    external fun query(records: ByteBuffer, count: Int, tree: ByteBuffer, nodes: Int,
        left: Float, top: Float, right: Float, bottom: Float, result: ByteBuffer): Int
    external fun cutRuns(points: ByteBuffer, count: Int, ax: Float, ay: Float, bx: Float, by: Float,
        reach: Float, output: ByteBuffer): Int
    external fun clipPolyline(points: ByteBuffer, count: Int, sweeps: ByteBuffer, sweepCount: Int,
        brushRadius: Float, output: ByteBuffer): Int
}

/** Packed, GC-owned storage. JNI retains no handles and copies no coordinate arrays per query. */
internal class NativeBoundsTree private constructor(
    private val records: ByteBuffer, private val count: Int, private val tree: ByteBuffer,
    private val nodes: Int, private val output: ByteBuffer,
) {
    val ids: IntBuffer = output.asIntBuffer()

    fun query(left: Float, top: Float, right: Float, bottom: Float): Int =
        SpatialNative.query(records, count, tree, nodes, left, top, right, bottom, output)

    companion object {
        const val MIN_STROKES = 512
        const val MAX_STROKES = 65_536
        fun build(count: Int, fill: (ByteBuffer) -> Unit): NativeBoundsTree? {
            if (!SpatialNative.ready || count !in MIN_STROKES..MAX_STROKES) return null
            return runCatching {
                val records = buffer(count * 20)
                fill(records)
                var leaves = 1
                while (leaves < (count + 7) / 8) leaves *= 2
                val tree = buffer((leaves * 2 - 1) * 32)
                val nodes = SpatialNative.build(records, count, tree)
                if (nodes <= 0) null else NativeBoundsTree(records, count, tree, nodes, buffer(count * 4))
            }.getOrNull()
        }
        private fun buffer(bytes: Int) = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
