package com.notesis

import kotlin.math.*

internal data class InkSpan(val from: Double, val to: Double)
private const val CLIP_EPS = 1e-9

/** Clips continuous input edges, including cuts between two samples. Parameters are index+t. */
internal fun continuousKeep(xs: FloatArray, ys: FloatArray, count: Int, sweeps: FloatArray,
    sweepCount: Int, brushRadius: Float): List<InkSpan>? {
    require(count in 0..minOf(xs.size, ys.size) && sweepCount in 0..sweeps.size / 5)
    if (count == 0 || sweepCount == 0) return null
    if (count == 1) {
        for (s in 0 until sweepCount) {
            val p = s * 5
            if (capsuleInterval(xs[0].toDouble(), ys[0].toDouble(), xs[0].toDouble(), ys[0].toDouble(),
                sweeps[p].toDouble(), sweeps[p + 1].toDouble(), sweeps[p + 2].toDouble(), sweeps[p + 3].toDouble(),
                (sweeps[p + 4] + brushRadius).toDouble()) != null)
                return emptyList()
        }
        return null
    }
    val kept = ArrayList<InkSpan>()
    val cuts = ArrayList<InkSpan>(sweepCount)
    var cursor = 0.0
    var changed = false
    for (edge in 0 until count - 1) {
        cuts.clear()
        for (s in 0 until sweepCount) {
            val p = s * 5
            capsuleInterval(xs[edge].toDouble(), ys[edge].toDouble(), xs[edge + 1].toDouble(), ys[edge + 1].toDouble(),
                sweeps[p].toDouble(), sweeps[p + 1].toDouble(), sweeps[p + 2].toDouble(), sweeps[p + 3].toDouble(),
                (sweeps[p + 4] + brushRadius).toDouble())?.let { cuts += it }
        }
        cuts.sortBy { it.from }
        for (cut in cuts) {
            val start = edge + cut.from; val end = edge + cut.to
            if (end <= cursor + CLIP_EPS) continue
            if (start > cursor + CLIP_EPS) kept += InkSpan(cursor, start)
            cursor = max(cursor, end)
            changed = true
        }
    }
    if (!changed) return null
    if (count - 1.0 > cursor + CLIP_EPS) kept += InkSpan(cursor, count - 1.0)
    return kept
}

/** A capsule is the union of a strip and its endpoint disks; their line intersection is convex. */
private fun capsuleInterval(px: Double, py: Double, qx: Double, qy: Double,
    ax: Double, ay: Double, bx: Double, by: Double, radius: Double): InkSpan? {
    if (max(px, qx) < min(ax, bx) - radius || min(px, qx) > max(ax, bx) + radius ||
        max(py, qy) < min(ay, by) - radius || min(py, qy) > max(ay, by) + radius) return null
    val dx = qx - px; val dy = qy - py
    val length = dx * dx + dy * dy
    var first = Double.POSITIVE_INFINITY; var last = Double.NEGATIVE_INFINITY
    fun include(lo: Double, hi: Double) {
        val a = max(0.0, lo); val b = min(1.0, hi)
        if (b > a + CLIP_EPS) { first = min(first, a); last = max(last, b) }
    }
    fun disk(x: Double, y: Double) {
        if (length <= 1e-24) {
            if ((px - x).pow(2) + (py - y).pow(2) < radius * radius) include(0.0, 1.0)
            return
        }
        val middle = ((x - px) * dx + (y - py) * dy) / length
        val ex = px + middle * dx - x; val ey = py + middle * dy - y
        val remaining = radius * radius - ex * ex - ey * ey
        if (remaining <= 0.0) return
        val half = sqrt(remaining / length)
        include(middle - half, middle + half)
    }
    disk(ax, ay)
    val sx = bx - ax; val sy = by - ay; val sweepLength = hypot(sx, sy)
    if (sweepLength > 1e-12) {
        disk(bx, by)
        var lo = 0.0; var hi = 1.0
        fun slab(origin: Double, delta: Double, low: Double, high: Double): Boolean {
            if (abs(delta) <= 1e-12) return origin >= low && origin <= high
            val a = (low - origin) / delta; val b = (high - origin) / delta
            lo = max(lo, min(a, b)); hi = min(hi, max(a, b))
            return hi >= lo
        }
        if (slab(((px - ax) * sx + (py - ay) * sy) / sweepLength,
                (dx * sx + dy * sy) / sweepLength, 0.0, sweepLength) &&
            slab(((px - ax) * -sy + (py - ay) * sx) / sweepLength,
                (-dx * sy + dy * sx) / sweepLength, -radius, radius)) include(lo, hi)
    }
    return if (last > first + CLIP_EPS) InkSpan(first, last) else null
}

/** Retains historical turns and a cross-frame anchor, without keeping MotionEvents alive. */
internal class EraseBatch {
    var data = FloatArray(5 * 32); private set
    var count = 0; private set
    private var lastX = 0f; private var lastY = 0f; private var lastRadius = 0f
    private var hasLast = false
    private var lastTime = Long.MIN_VALUE
    var oldestMillis = 0L; private set
    fun append(x: Float, y: Float, radius: Float, time: Long) {
        if (!x.isFinite() || !y.isFinite() || !radius.isFinite() || radius <= 0f || time < lastTime) return
        lastTime = time
        if (hasLast && x == lastX && y == lastY && radius == lastRadius) return
        val r = if (hasLast) max(radius,lastRadius) else radius
        if (hasLast && count > 0) {
            val p=(count-1)*5
            val ux=data[p+2].toDouble()-data[p];val uy=data[p+3].toDouble()-data[p+1]
            val vx=x.toDouble()-lastX;val vy=y.toDouble()-lastY
            // Exact collinear continuation has identical capsule coverage. Never smooth a turn.
            if (data[p+4]==r && data[p+2]==lastX && data[p+3]==lastY && ux*vy-uy*vx==0.0 && ux*vx+uy*vy>=0.0) {
                data[p+2]=x;data[p+3]=y
                lastX=x;lastY=y;lastRadius=radius
                return
            }
        }
        if (count * 5 == data.size) data = data.copyOf(data.size * 2)
        if (count == 0) oldestMillis = time
        val p = count++ * 5
        data[p] = if (hasLast) lastX else x; data[p + 1] = if (hasLast) lastY else y
        data[p + 2] = x; data[p + 3] = y
        data[p + 4] = r
        lastX = x; lastY = y; lastRadius = radius; hasLast = true
    }
    fun drained() { count = 0; oldestMillis = 0L }
    fun reset() { drained(); hasLast = false; lastTime = Long.MIN_VALUE }
}

internal fun eraseContact(selected: Boolean, button: Boolean): Boolean = selected || button

internal fun <T> restoreEraseOrder(current: MutableList<T>, removed: List<T>, indices: IntArray?) {
    if (indices == null) current.addAll(removed)
    else removed.forEachIndexed { i, item -> current.add(indices[i].coerceIn(0, current.size), item) }
}

internal fun <T> redoInkAt(current: MutableList<T>, item: T, index: Int?) {
    if (index == null) current.add(item) else current.add(index.coerceIn(0,current.size),item)
}

internal fun inkOrientationBetween(a: Float, b: Float, t: Float): Float {
    if (a < 0f || b < 0f) return a
    val full = (2 * PI).toFloat()
    val delta = ((b - a + PI.toFloat()) % full + full) % full - PI.toFloat()
    return ((a + delta * t) % full + full) % full
}

internal fun sweepHitsBox(s: FloatArray, offset: Int, left: Float, top: Float, right: Float, bottom: Float): Boolean {
    val ax=s[offset]; val ay=s[offset+1]; val bx=s[offset+2]; val by=s[offset+3]; val r=s[offset+4]
    if (max(ax,bx)+r<left || min(ax,bx)-r>right || max(ay,by)+r<top || min(ay,by)-r>bottom) return false
    if ((ax in left..right && ay in top..bottom) || (bx in left..right && by in top..bottom)) return true
    return segmentSegmentDistance(ax,ay,bx,by,left,top,right,top)<=r ||
        segmentSegmentDistance(ax,ay,bx,by,right,top,right,bottom)<=r ||
        segmentSegmentDistance(ax,ay,bx,by,right,bottom,left,bottom)<=r ||
        segmentSegmentDistance(ax,ay,bx,by,left,bottom,left,top)<=r
}
