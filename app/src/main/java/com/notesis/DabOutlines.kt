package com.notesis

/**
 * Where [DabOutlineCache] reads outlines from: the stroke being written on
 * the device, a fake in tests. Outlines are listed per brush coat.
 */
internal interface OutlineSource {
    fun coatCount(): Int
    fun outlineCount(coat: Int): Int
    fun vertexCount(coat: Int, outline: Int): Int

    /** Vertex [vertex] of [outline] into [into], x at [at] and y after it. */
    fun position(coat: Int, outline: Int, vertex: Int, into: FloatArray, at: Int)
}

/** Takes one outline at a time: [count] points, x and y interleaved in [points]. */
internal fun interface OutlineSink {
    fun outline(points: FloatArray, count: Int)
}

/**
 * The outlines of a dab stroke being written, one per dab, kept from frame
 * to frame so a frame reads back only what Ink changed.
 *
 * Drawn without a GPU, Ink rebuilds a Path from every vertex of every dab
 * whenever the stroke grows - a call into native code per vertex, tens of
 * thousands of them for a long pencil line, every frame. Dabs Ink has laid
 * down do not move again: everything it adds, replaces or takes back lies
 * inside the region it reports as updated. So an outline whose bounds stay
 * clear of that region is still what it was, and only the rest is read.
 * The last few are read regardless, as cheap insurance on the moving end.
 */
internal class DabOutlineCache {
    private class Coat {
        var count = 0
        var points = arrayOfNulls<FloatArray>(INITIAL)
        var sizes = IntArray(INITIAL)
        var bounds = FloatArray(INITIAL * 4)

        fun ensure(capacity: Int) {
            if (capacity <= sizes.size) return
            val grown = maxOf(capacity, sizes.size * 2)
            points = points.copyOf(grown)
            sizes = sizes.copyOf(grown)
            bounds = bounds.copyOf(grown * 4)
        }
    }

    private val coats = ArrayList<Coat>(2)

    /** Coats currently held. */
    val coatCount: Int get() = coats.size

    /** Outlines read from a source so far; what this saves is the difference. */
    var reads = 0L
        private set

    /** Outlines held for [coat]. */
    fun outlineCount(coat: Int): Int = coats.getOrNull(coat)?.count ?: 0

    /** Forget every outline; the arrays stay for the next stroke. */
    fun clear() {
        for (coat in coats) coat.count = 0
    }

    /** Read every outline again, for when what changed is not known. */
    fun syncAll(source: OutlineSource) = sync(source,
        Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)

    /**
     * Brings the cache up to [source], whose geometry changed only within
     * [left]..[right] by [top]..[bottom] (stroke units) since the last sync.
     * An empty region (left > right) means nothing already held changed.
     */
    fun sync(source: OutlineSource, left: Float, top: Float, right: Float, bottom: Float) {
        val coatTotal = source.coatCount()
        while (coats.size < coatTotal) coats += Coat()
        while (coats.size > coatTotal) coats.removeAt(coats.size - 1)
        val anything = left <= right && top <= bottom
        for (c in 0 until coatTotal) {
            val coat = coats[c]
            val held = coat.count
            val total = source.outlineCount(c)
            coat.ensure(total)
            val kept = minOf(held, total)
            val tail = if (anything || total != held) kept - TAIL_OUTLINES else kept
            for (i in 0 until kept) {
                val b = i * 4
                val stale = i >= tail || (anything &&
                    coat.bounds[b] <= right && coat.bounds[b + 2] >= left &&
                    coat.bounds[b + 1] <= bottom && coat.bounds[b + 3] >= top)
                if (stale) read(source, coat, c, i)
            }
            for (i in kept until total) read(source, coat, c, i)
            coat.count = total
        }
    }

    private fun read(source: OutlineSource, coat: Coat, c: Int, i: Int) {
        reads++
        val vertices = source.vertexCount(c, i)
        var points = coat.points[i]
        if (points == null || points.size < vertices * 2) {
            points = FloatArray(maxOf(vertices * 2, 8))
            coat.points[i] = points
        }
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (v in 0 until vertices) {
            source.position(c, i, v, points, v * 2)
            val x = points[v * 2]
            val y = points[v * 2 + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        coat.sizes[i] = vertices
        val b = i * 4
        coat.bounds[b] = minX
        coat.bounds[b + 1] = minY
        coat.bounds[b + 2] = maxX
        coat.bounds[b + 3] = maxY
    }

    /**
     * Hands [sink] every outline of [coat] that can cover any of
     * [left]..[right] by [top]..[bottom]. Under the nonzero rule an outline
     * adds nothing outside its own bounds, so filling just these paints that
     * area exactly as filling them all would.
     */
    fun forEachMeeting(coat: Int, left: Float, top: Float, right: Float, bottom: Float, sink: OutlineSink) {
        val held = coats.getOrNull(coat) ?: return
        for (i in 0 until held.count) {
            val vertices = held.sizes[i]
            if (vertices < 3) continue
            val b = i * 4
            if (held.bounds[b] > right || held.bounds[b + 2] < left ||
                held.bounds[b + 1] > bottom || held.bounds[b + 3] < top) continue
            sink.outline(held.points[i] ?: continue, vertices)
        }
    }

    private companion object {
        const val INITIAL = 64

        /** Outlines at the end read every frame, whatever the region says. */
        const val TAIL_OUTLINES = 4
    }
}

/**
 * What drawing a stroke costs, in written pen strokes: [outlines] is how many
 * separate outlines its shape holds. A pen or graphite line is one outline
 * of a few hundred vertices; a dab stroke is one small outline per dab, and
 * about [DAB_OUTLINES_PER_PEN_STROKE] of those add up to one pen stroke.
 */
internal fun dabDrawWeight(outlines: Int): Int = maxOf(1, outlines / DAB_OUTLINES_PER_PEN_STROKE)

/**
 * A stamp dab is a circle of 15 to 20 vertices at its epsilon, the scatter
 * coats' smaller dots fewer; a handwritten pen stroke is some 250.
 */
internal const val DAB_OUTLINES_PER_PEN_STROKE = 16
