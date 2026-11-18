package com.notesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The GPU ink renderer and the mesh cache it draws from (ink_gl.cpp,
 * ink_batch.cpp). An experiment behind a switch in the diagnostics settings,
 * off by default; anything that fails here leaves the usual painting in
 * charge, and three failures turn it off for the rest of the run.
 *
 * The cache: once a page's strokes have been built, their triangles - the
 * very meshes Ink made - are written beside the page as `<page>.mesh`, with
 * a stamp of the page file they came from. Then
 *  - a page coming on screen is drawn straight from that file in C++, before
 *    Ink has read and rebuilt a single stroke of it (the stand-in while it
 *    loads), and
 *  - a tile of a page whose strokes are what the cache was made from is drawn
 *    from it in one batched draw call on a GL context of its own, instead of
 *    stroke by stroke.
 * Only opaque ink of the brushes a bitmap can show is taken; a page with
 * anything else (the pencil's graphite, particle brushes, highlighter,
 * see-through colours) gets no cache and is drawn the usual way.
 */
internal object NativeInk {
    private const val KEY = "nativeInk"
    private const val MAX_FAILURES = 3
    private const val OPEN_PAGES = 6

    @Volatile private var failures = 0
    @Volatile private var loaded: Boolean? = null
    @Volatile private var switchedOn: Boolean? = null
    private var renderer = 0L
    /** Open caches by file and stamp, the most recent last. */
    private val pages = object : LinkedHashMap<String, Long>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            val evict = size > OPEN_PAGES
            if (evict) nativeClosePage(eldest.value)
            return evict
        }
    }

    fun enabled(context: Context): Boolean = switchedOn
        ?: context.getSharedPreferences("pens", Context.MODE_PRIVATE).getBoolean(KEY, false).also { switchedOn = it }

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("pens", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        switchedOn = on
        failures = 0
    }

    /** Switched on, the library there, and not given up on this run. */
    fun usable(): Boolean = switchedOn == true && failures < MAX_FAILURES && available()

    /** What the diagnostics say about it. */
    fun status(): String = when {
        switchedOn != true -> "off"
        !available() -> "library not loaded"
        failures >= MAX_FAILURES -> "failed $failures times, off for this run"
        else -> "on"
    }

    @Synchronized private fun available(): Boolean {
        loaded?.let { return it }
        val ok = runCatching { System.loadLibrary("notesis_ink"); true }.getOrDefault(false)
        loaded = ok
        return ok
    }

    /** The cache file beside a page file. */
    fun meshFileFor(pageFile: File): File = File(pageFile.parentFile, pageFile.nameWithoutExtension + ".mesh")

    /** What a cache is checked against: the page file as it is on disk; 0 when there is none. */
    fun stampOf(pageFile: File): Long = if (pageFile.isFile) pageFile.lastModified() * 31 + pageFile.length() else 0L

    /**
     * Draws the part of a page whose top-left corner is ([left], [top]) in page
     * units, at [scale] pixels a unit, [width] by [height] pixels, from the
     * cache [meshFile] made for [stamp]. Blocking; on a worker. Null when it
     * could not be drawn (no cache, a stale one, or the GPU said no).
     */
    fun render(meshFile: File, stamp: Long, left: Float, top: Float, scale: Float, width: Int, height: Int): Bitmap? {
        if (!usable() || stamp == 0L) return null
        val buffer: HardwareBuffer = synchronized(this) {
            if (renderer == 0L) {
                renderer = runCatching { nativeCreate() }.getOrDefault(0L)
                if (renderer == 0L) { failures = MAX_FAILURES; return null }
            }
            val key = meshFile.path + "@" + stamp
            val page = pages[key] ?: runCatching { nativeOpenPage(meshFile.path, stamp) }.getOrDefault(0L)
                .also { if (it != 0L) pages[key] = it }
            // No cache, or one made for an older page file: not a failure, just nothing to draw from.
            if (page == 0L) return null
            runCatching { nativeRender(renderer, page, left, top, scale, width, height) }.getOrNull()
        } ?: run { failures++; return null }
        return try {
            Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
        } catch (_: Throwable) {
            failures++
            null
        } finally {
            buffer.close()
        }
    }

    /**
     * Writes the cache for a page whose [strokes] are what [pageFile] holds (a
     * page with no unsaved change), as ink_batch.cpp reads it. False, and no
     * file, when a stroke is not one the GPU path draws. Blocking; on a worker.
     */
    fun writeCache(strokes: List<Stroke>, pageFile: File): Boolean {
        val stamp = stampOf(pageFile)
        val target = meshFileFor(pageFile)
        if (stamp == 0L || strokes.isEmpty() || strokes.any { !drawable(it) }) {
            target.delete()
            return false
        }
        val records = ArrayList<MeshRecord>()
        val point = MutableVec()
        for (stroke in strokes) {
            val shape = stroke.shape
            for (group in 0 until shape.getRenderGroupCount()) for (mesh in shape.renderGroupMeshes(group)) {
                val count = mesh.vertexCount
                val triangles = mesh.triangleCount
                if (count == 0 || triangles == 0) continue
                if (count > 65_536) return false
                val positions = FloatArray(count * 2)
                for (i in 0 until count) {
                    mesh.fillPosition(i, point)
                    positions[i * 2] = point.x
                    positions[i * 2 + 1] = point.y
                }
                val raw = mesh.rawTriangleIndexData.duplicate()
                raw.position(0)
                val indices = ShortArray(triangles * 3)
                if (raw.remaining() < indices.size) return false
                raw.get(indices)
                records += MeshRecord(stroke.brush.colorIntArgb, positions, indices)
            }
        }
        return writeMeshCache(target, stamp, records)
    }

    /** One opaque brush the bitmap and GPU paths both draw as plain filled triangles. */
    private fun drawable(stroke: Stroke): Boolean =
        android.graphics.Color.alpha(stroke.brush.colorIntArgb) == 255 && !needsGpuPicture(stroke) &&
            stroke.brush.family != Tool.HIGHLIGHTER.brushFamily() && stroke.shape.getRenderGroupCount() == 1

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeOpenPage(path: String, stamp: Long): Long
    private external fun nativeClosePage(page: Long)
    private external fun nativeRender(handle: Long, page: Long, left: Float, top: Float, scale: Float, width: Int, height: Int): HardwareBuffer?
}

/** One mesh of a stroke as the cache keeps it: its colour, x and y per vertex, three indices per triangle. */
internal class MeshRecord(val argb: Int, val positions: FloatArray, val indices: ShortArray)

/** The cache file's bytes (see ink_batch.h for the reader): little-endian, each stroke on a four-byte boundary. */
internal fun meshCacheBytes(stamp: Long, records: List<MeshRecord>): ByteBuffer {
    var size = 24L
    for (r in records) size += 28L + r.positions.size * 4L + r.indices.size * 2L + if (r.indices.size % 2 == 1) 2 else 0
    require(size <= Int.MAX_VALUE) { "mesh cache too large" }
    val out = ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(MESH_MAGIC).putInt(MESH_VERSION).putLong(stamp).putInt(records.size).putInt(0)
    for (r in records) {
        var left = Float.POSITIVE_INFINITY; var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
        for (i in r.positions.indices step 2) {
            left = minOf(left, r.positions[i]); right = maxOf(right, r.positions[i])
            top = minOf(top, r.positions[i + 1]); bottom = maxOf(bottom, r.positions[i + 1])
        }
        if (r.positions.isEmpty()) { left = 0f; top = 0f; right = 0f; bottom = 0f }
        out.putInt(r.argb).putFloat(left).putFloat(top).putFloat(right).putFloat(bottom)
            .putInt(r.positions.size / 2).putInt(r.indices.size)
        for (v in r.positions) out.putFloat(v)
        for (i in r.indices) out.putShort(i)
        if (r.indices.size % 2 == 1) out.putShort(0)
    }
    out.flip()
    return out
}

/** Writes the cache whole through a temporary file, so a reader never meets half of one. */
internal fun writeMeshCache(target: File, stamp: Long, records: List<MeshRecord>): Boolean = runCatching {
    val bytes = meshCacheBytes(stamp, records)
    val temp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(temp).channel.use { channel -> while (bytes.hasRemaining()) channel.write(bytes) }
    temp.renameTo(target) || run { temp.delete(); false }
}.getOrDefault(false)

internal const val MESH_MAGIC = 0x48534d4e
internal const val MESH_VERSION = 1
