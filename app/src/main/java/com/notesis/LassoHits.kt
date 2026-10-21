package com.notesis

import java.util.IdentityHashMap

/**
 * Takes [gone] out of [list], each by identity, keeping the order of the rest.
 *
 * The eraser takes a stroke or two per sample it lands on, and it lands on
 * a dense page's newest ink more often than not: looking for those few from
 * the end finds them after a short walk, where filtering the whole list ran
 * every stroke of the page past a set lookup on every sample that hit
 * anything. Past [FEW_REMOVALS] at once that pass is the cheaper one again,
 * and [isGone] is it.
 */
internal fun <T : Any> removeByIdentity(list: MutableList<T>, gone: List<T>, isGone: (T) -> Boolean) {
    if (gone.isEmpty()) return
    if (gone.size > FEW_REMOVALS || list !is java.util.RandomAccess) {
        list.removeAll(isGone)
        return
    }
    for (item in gone) {
        var at = list.size - 1
        while (at >= 0 && list[at] !== item) at--
        if (at >= 0) list.removeAt(at)
    }
}

private const val FEW_REMOVALS = 8

/**
 * [FloatPointBuffer.contains] for a loop that is still being drawn, asked
 * again and again about the same points as it grows.
 *
 * Whether a point is inside is the parity of the loop's edges that cross a
 * ray from it. Every edge but the closing one - last point back to first -
 * stays put as the pen adds points, so the parity over those is kept per
 * point and only the edges added since are tested, plus the closing edge
 * afresh. The live lasso preview asked every stroke under the loop about
 * every edge of the loop each time; on a dense page with a long loop that
 * was the size of the page times the length of the loop, several times a
 * second. The answer is exactly [FloatPointBuffer.contains]'s: the same
 * edges, each tested the same way.
 *
 * Points are kept by [key] (a stroke, say) with the position asked about;
 * asked at another position, a key starts over. Call [reset] whenever the
 * loop is cleared or rewritten rather than only grown.
 */
internal class LassoHitCache {
    private class Probe(val x: Float, val y: Float) {
        /** Parity over the open edges 0..edges-1, edge k running from point k to k + 1. */
        var inside = false
        var edges = 0
    }

    private val probes = IdentityHashMap<Any, Probe>()
    private var points = 0

    fun reset() {
        probes.clear()
        points = 0
    }

    fun contains(key: Any, x: Float, y: Float, loop: FloatPointBuffer): Boolean {
        val count = loop.size / 2
        // Shorter than it was: not the same loop grown, so nothing kept applies.
        if (count < points) reset()
        points = count
        if (count < 3) return false
        var probe = probes[key]
        if (probe == null || probe.x != x || probe.y != y) {
            probe = Probe(x, y)
            probes[key] = probe
        }
        var inside = probe.inside
        for (k in probe.edges until count - 1) {
            if (loop.edgeCrosses(k + 1, k, x, y)) inside = !inside
        }
        probe.inside = inside
        probe.edges = count - 1
        // The closing edge moves with every new point, so it is never kept.
        return if (loop.edgeCrosses(0, count - 1, x, y)) !inside else inside
    }

    /** [contains] for a [key] asked about before, at the position it was asked at; null for a new one. */
    fun containsAgain(key: Any, loop: FloatPointBuffer): Boolean? {
        val probe = probes[key] ?: return null
        return contains(key, probe.x, probe.y, loop)
    }

    val size: Int get() = probes.size
}
