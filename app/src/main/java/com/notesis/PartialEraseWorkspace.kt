package com.notesis

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Owned by one canvas. Coordinate and result buffers survive successive erase hits. */
internal class PartialEraseWorkspace {
    var xs = FloatArray(0); private set
    var ys = FloatArray(0); private set
    private var points: ByteBuffer? = null
    private var output: ByteBuffer? = null
    private var native = false
    private var count = 0
    private var sweepBuffer: ByteBuffer? = null
    private var clipOutput: ByteBuffer? = null
    fun prepare(count: Int) {
        require(count >= 0)
        this.count = count
        if (count > xs.size) {
            val capacity = maxOf(count, (xs.size * 2).coerceAtLeast(128))
            xs = FloatArray(capacity); ys = FloatArray(capacity)
        }
        native = SpatialNative.ready && count in 128..65_536
        if (native && (points?.capacity() ?: 0) < count * 8) {
            val bytes = minOf(xs.size, 65_536) * 8
            points = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            output = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
    }
    fun fragments(sweeps: FloatArray, sweepCount: Int, brushRadius: Float): List<InkSpan>? {
        if (native && sweepCount in 1..128) {
            if ((sweepBuffer?.capacity() ?: 0) < sweepCount * 20)
                sweepBuffer = ByteBuffer.allocateDirect(sweepCount * 20).order(ByteOrder.nativeOrder())
            for (i in 0 until sweepCount * 5) sweepBuffer!!.putFloat(i * 4, sweeps[i])
            if ((clipOutput?.capacity() ?: 0) < count * 16)
                clipOutput = ByteBuffer.allocateDirect(count * 16).order(ByteOrder.nativeOrder())
            val found = SpatialNative.clipPolyline(points!!, count, sweepBuffer!!, sweepCount, brushRadius, clipOutput!!)
            if (found == -1) return null
            if (found >= 0) return List(found) { i -> InkSpan(clipOutput!!.getDouble(i * 16), clipOutput!!.getDouble(i * 16 + 8)) }
        }
        return continuousKeep(xs, ys, count, sweeps, sweepCount, brushRadius)
    }
    fun put(index: Int, x: Float, y: Float) {
        xs[index] = x; ys[index] = y
        if (native) { points!!.putFloat(index * 8, x); points!!.putFloat(index * 8 + 4, y) }
    }
    fun runs(count: Int, ax: Float, ay: Float, bx: Float, by: Float, reach: Float): List<IntRange>? {
        if (native) {
            val found = SpatialNative.cutRuns(points!!, count, ax, ay, bx, by, reach, output!!)
            if (found == -1) return null
            if (found >= 0) return List(found) { i -> output!!.getInt(i * 8) until output!!.getInt(i * 8 + 4) }
        }
        // Boundary cases keep the reference Float decisions and endpoint interpolation.
        return keptRuns(xs, ys, count, ax, ay, bx, by, reach)
    }
}
