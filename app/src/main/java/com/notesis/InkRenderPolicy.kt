package com.notesis

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/** Page coordinates. These policies deliberately have no Android or Stroke dependency. */
internal data class InkRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = (right - left).coerceAtLeast(0f)
    val height get() = (bottom - top).coerceAtLeast(0f)
    fun intersects(other: InkRect) = right >= other.left && left <= other.right &&
        bottom >= other.top && top <= other.bottom
    fun contains(other: InkRect) = left <= other.left && top <= other.top &&
        right >= other.right && bottom >= other.bottom
    fun expand(amount: Float) = InkRect(left - amount, top - amount, right + amount, bottom + amount)
    fun intersection(other: InkRect): InkRect? = InkRect(maxOf(left, other.left), maxOf(top, other.top),
        minOf(right, other.right), minOf(bottom, other.bottom)).takeIf { it.width > 0 && it.height > 0 }
}

internal enum class InkLayer { HIGHLIGHTER, PEN }
internal enum class InkComposition { PAPER, HIGHLIGHTER, IMAGES_AND_TEXT, PEN, MASKS_AND_SELECTION, WET }
internal data class InkTileAddress(val session: Long, val layer: InkLayer, val level: Int, val x: Int, val y: Int) {
    val density get() = 2.0.pow(level).toFloat()
    fun bounds(): InkRect {
        val size = InkRenderPolicy.TILE_PX / density
        return InkRect(x * size, y * size, (x + 1) * size, (y + 1) * size)
    }
}
internal data class InkTileVersion(val epoch: Long, val generation: Long)
internal data class InkChangedBounds(val layer: InkLayer, val before: InkRect?, val after: InkRect?)
internal data class InkChangeSet(val regions: List<InkChangedBounds>) {
    val destructive get() = regions.any { it.before != null }
}

/** Only addresses that have been requested are tracked; distant rectangles never become one union. */
internal class InkDirtyRegionTracker {
    private var epoch = 0L
    private val generations = HashMap<InkTileAddress, Long>()
    @Synchronized fun version(address: InkTileAddress) = InkTileVersion(epoch, generations.getOrPut(address) { 0 })
    @Synchronized fun change(change: InkChangeSet): Set<InkTileAddress> {
        val dirty = generations.keys.filterTo(linkedSetOf()) { address ->
            val bounds = address.bounds().expand((InkRenderPolicy.BLEED_PX + 1) / address.density)
            change.regions.any { region -> region.layer == address.layer &&
                (region.before?.intersects(bounds) == true || region.after?.intersects(bounds) == true) }
        }
        dirty.forEach { generations[it] = generations.getValue(it) + 1 }
        return dirty
    }
    @Synchronized fun forget(address: InkTileAddress) { generations.remove(address) }
    @Synchronized fun reset() { epoch++; generations.clear() }
}

internal enum class InkSourceKind { EXACT, STALE, COARSE, SNAPSHOT }
internal data class InkSource<T>(val address: InkTileAddress, val version: InkTileVersion,
    val bounds: InkRect, val edit: Long, val value: T, val snapshot: Boolean = false)
internal data class InkCell<T>(val address: InkTileAddress, val bounds: InkRect,
    val version: InkTileVersion, val sources: List<InkSource<T>>, val minimumEdit: Long = 0)
internal data class InkDrawSource<T>(val source: InkSource<T>, val clip: InkRect, val kind: InkSourceKind)
internal data class InkVectorRegion<T>(val bounds: InkRect, val layer: InkLayer,
    val strokes: List<T>, val outlineVertices: Int)
internal data class InkDrawPlan<T, V>(val sources: List<InkDrawSource<T>>, val missing: List<InkCell<T>>,
    val vectors: List<InkVectorRegion<V>>) {
    val covered get() = missing.isEmpty()
}

internal object InkRenderPolicy {
    const val TILE_PX = 512
    const val BLEED_PX = 2
    const val MAX_LEVEL = 4
    const val MAX_VECTOR_REGIONS = 2
    const val MAX_VECTOR_STROKES = 32
    const val MAX_OUTLINE_VERTICES = 4096
    const val MAX_OVERLAY_STROKES = 32
    val composition = InkComposition.entries.toList()

    fun addresses(session: Long, layer: InkLayer, level: Int, viewport: InkRect): List<InkTileAddress> {
        if (viewport.width <= 0 || viewport.height <= 0) return emptyList()
        val size = TILE_PX / 2.0.pow(level).toFloat()
        return buildList {
            for (y in floor(viewport.top / size).toInt() until ceil(viewport.bottom / size).toInt())
                for (x in floor(viewport.left / size).toInt() until ceil(viewport.right / size).toInt())
                    add(InkTileAddress(session, layer, level, x, y))
        }
    }

    /** No page collection can be supplied. A ready cell survives a neighbouring miss. */
    fun <T, V> plan(cells: List<InkCell<T>>, interacting: Boolean,
        preparedVectors: List<InkVectorRegion<V>> = emptyList()): InkDrawPlan<T, V> {
        val drawn = ArrayList<InkDrawSource<T>>(cells.size)
        val missing = ArrayList<InkCell<T>>()
        for (cell in cells) {
            val valid = cell.sources.filter { it.address.session == cell.address.session &&
                it.address.layer == cell.address.layer && it.version.epoch == cell.version.epoch &&
                it.bounds.contains(cell.bounds) && it.edit >= cell.minimumEdit }
            fun kind(source: InkSource<T>) = when {
                source.snapshot -> InkSourceKind.SNAPSHOT
                source.address == cell.address && source.version == cell.version -> InkSourceKind.EXACT
                source.address == cell.address -> InkSourceKind.STALE
                else -> InkSourceKind.COARSE
            }
            val best = valid.minWithOrNull(compareBy<InkSource<T>> { kind(it).ordinal }
                .thenByDescending { it.address.level }.thenByDescending { it.edit })
            if (best == null) missing += cell else drawn += InkDrawSource(best, cell.bounds, kind(best))
        }
        val vectors = ArrayList<InkVectorRegion<V>>()
        if (!interacting) {
            var strokes = 0
            var vertices = 0
            for (region in preparedVectors) {
                if (vectors.size == MAX_VECTOR_REGIONS) break
                if (missing.none { it.bounds == region.bounds && it.address.layer == region.layer }) continue
                if (region.strokes.size + strokes > MAX_VECTOR_STROKES ||
                    region.outlineVertices + vertices > MAX_OUTLINE_VERTICES) continue
                // Complete regions only. Never truncate a candidate list.
                vectors += region
                strokes += region.strokes.size
                vertices += region.outlineVertices
            }
        }
        return InkDrawPlan(drawn, missing, vectors)
    }

    /** Appended ink is clipped to the part whose chosen source does not yet include it. */
    fun <T> overlayClips(edit: Long, bounds: InkRect, layer: InkLayer,
        sources: List<InkDrawSource<T>>): List<InkRect> = sources.mapNotNull {
        if (it.source.address.layer == layer && it.source.edit < edit) bounds.intersection(it.clip) else null
    }
}

/** Pins the last complete plan and its coordinate system until the next viewport is covered. */
internal class InkCoverageGate<T> {
    var last: T? = null
        private set
    var preparing = true
        private set
    fun choose(candidate: T, covered: Boolean): T? {
        preparing = !covered
        if (covered) last = candidate
        return last
    }
    fun clear() { last = null; preparing = true }
}
