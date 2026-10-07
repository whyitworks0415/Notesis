package com.notesis

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Dense ink cached as GPU layers at the current screen resolution. A display
 * list alone still submits every mesh to the GPU on every frame. Small layers
 * reuse the rendered pixels, including Ink's mesh antialiasing, and limit both
 * recording work and texture memory to the part of the page being viewed.
 *
 * Only committed ink is cached. New strokes use a short delta display list
 * until writing pauses; existing layers need no re-recording at pen-up.
 */
internal class InkTileCache(
    private val byteBudget: Int = 48 * 1024 * 1024,
    private val screenStroke: (Stroke) -> Stroke,
    private val query: (Page, Float, Float, Float, Float) -> List<Stroke>,
) {
    private data class Key(val page: Page, val x: Int, val y: Int, val highlight: Boolean)

    private class Tile(val key: Key, val scale: Float, val excluded: Set<Stroke>?) {
        val base = RenderNode("ink tile")
        val delta = RenderNode("ink delta")
        val appended = ArrayList<Stroke>()
        var dirty = true
        var deltaDirty = false
        var hasInk = false
    }

    private class PageState {
        var scale = 0f
        var revision = Long.MIN_VALUE
        var meshRevision = Long.MIN_VALUE
        var strokes: List<Stroke> = emptyList()
        val positions = IdentityHashMap<Stroke, Int>()
        var excluded: Set<Stroke>? = null
    }

    private val tiles = LinkedHashMap<Key, Tile>(32, 0.75f, true)
    private val pages = LinkedHashMap<Page, PageState>(8, 0.75f, true)
    private val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
    private val transform = Matrix()
    private val bounds = RectF()
    private val highlightFamily = Tool.HIGHLIGHTER.brushFamily()
    private var layerBytes = 0
    private var recordings = 0L
    private var recordedStrokes = 0L
    private var layerDraws = 0L

    internal data class Stats(val recordings: Long, val recordedStrokes: Long,
        val layerDraws: Long, val tiles: Int, val layerBytes: Int, val missingLists: Int)

    fun stats() = Stats(recordings, recordedStrokes, layerDraws, tiles.size, layerBytes,
        tiles.values.count { it.hasInk && !it.base.hasDisplayList() })

    fun clear() {
        tiles.values.forEach(::discard)
        tiles.clear()
        pages.clear()
    }

    private fun discard(tile: Tile) {
        if (tile.hasInk) layerBytes -= TILE_BYTES
        tile.base.setUseCompositingLayer(false, null)
        tile.base.discardDisplayList()
        tile.delta.discardDisplayList()
    }

    private fun discardPage(page: Page) {
        val iterator = tiles.entries.iterator()
        while (iterator.hasNext()) {
            val tile = iterator.next().value
            if (tile.key.page === page) {
                discard(tile)
                iterator.remove()
            }
        }
    }

    private fun strokeBounds(stroke: Stroke): RectF? {
        val box = stroke.shape.computeBoundingBox() ?: return null
        bounds.set(box.xMin, box.yMin, box.xMax, box.yMax)
        return bounds
    }

    private fun touches(tile: Tile, box: RectF): Boolean {
        val left = (tile.key.x * TILE_PX - GUTTER_PX) / tile.scale
        val top = (tile.key.y * TILE_PX - GUTTER_PX) / tile.scale
        val right = left + EXTENT_PX / tile.scale
        val bottom = top + EXTENT_PX / tile.scale
        return box.right >= left && box.left <= right && box.bottom >= top && box.top <= bottom
    }

    private fun changedStroke(page: Page, stroke: Stroke, append: Boolean) {
        val box = strokeBounds(stroke) ?: return
        val highlight = stroke.brush.family == highlightFamily
        for (tile in tiles.values) {
            if (tile.key.page !== page || tile.key.highlight != highlight || !touches(tile, box)) continue
            if (tile.excluded?.contains(stroke) == true) continue
            if (append && !tile.dirty) {
                tile.appended += stroke
                tile.deltaDirty = true
            } else {
                tile.dirty = true
                tile.appended.clear()
            }
        }
    }

    /** Compare content once per edit, rather than scanning the page per tile. */
    private fun sync(page: Page, requestedScale: Float, interacting: Boolean, excluded: Set<Stroke>?): PageState {
        val state = pages.getOrPut(page) { PageState() }
        val scale = if (state.scale > 0f && interacting) state.scale else requestedScale
        if (state.scale == 0f || abs(state.scale / scale - 1f) >= 0.001f ||
            state.meshRevision != page.meshRevision || state.excluded !== excluded) {
            discardPage(page)
            state.scale = scale
            state.excluded = excluded
        }
        val current = page.strokes
        if (state.revision != page.revision || state.meshRevision != page.meshRevision ||
            state.strokes.size != current.size) {
            val old = state.strokes
            val appendOnly = current.size >= old.size && old.indices.all { old[it] === current[it] }
            if (appendOnly) {
                val hasCachedTiles = tiles.keys.any { it.page === page }
                for (i in old.size until current.size) {
                    if (hasCachedTiles) changedStroke(page, current[i], append = true)
                    state.positions[current[i]] = i
                }
            } else {
                // Erasing one stroke shifts every subsequent index. Compare
                // membership and relative order so unrelated tiles survive.
                val present = IdentityHashMap<Stroke, Boolean>()
                val added = ArrayList<Stroke>()
                var previous = -1
                var reordered = false
                for (stroke in current) {
                    present[stroke] = true
                    val position = state.positions[stroke]
                    if (position == null) added += stroke
                    else {
                        if (position < previous) reordered = true
                        previous = position
                    }
                }
                val removed = old.filter { !present.containsKey(it) }
                if (reordered || added.size + removed.size > 32) discardPage(page)
                else {
                    removed.forEach { changedStroke(page, it, append = false) }
                    added.forEach { changedStroke(page, it, append = false) }
                }
                state.positions.clear()
                current.forEachIndexed { i, stroke -> state.positions[stroke] = i }
            }
            state.strokes = current.toList()
            state.revision = page.revision
            state.meshRevision = page.meshRevision
        }
        while (pages.size > 6) {
            val oldest = pages.entries.iterator()
            discardPage(oldest.next().key)
            oldest.remove()
        }
        return state
    }

    private fun record(node: RenderNode, tile: Tile, strokes: List<Stroke>): Boolean {
        node.setPosition(0, 0, EXTENT_PX, EXTENT_PX)
        val canvas = node.beginRecording(EXTENT_PX, EXTENT_PX)
        val dx = GUTTER_PX - tile.key.x * TILE_PX
        val dy = GUTTER_PX - tile.key.y * TILE_PX
        transform.setScale(tile.scale, tile.scale)
        transform.postTranslate(dx.toFloat(), dy.toFloat())
        var hasInk = false
        try {
            canvas.concat(transform)
            for (stroke in strokes) {
                if (tile.excluded?.contains(stroke) == true) continue
                if ((stroke.brush.family == highlightFamily) != tile.key.highlight) continue
                renderer.draw(canvas, screenStroke(stroke), transform)
                recordedStrokes++
                hasInk = true
            }
        } finally { node.endRecording() }
        recordings++
        return hasInk
    }

    private fun tileFor(key: Key, state: PageState, consolidate: Boolean): Tile {
        val scale = state.scale
        val tile = tiles.getOrPut(key) { Tile(key, scale, state.excluded) }
        // Android can discard a display list when its node leaves the drawing
        // tree. A Java cache entry can therefore outlive the GPU recording.
        if (tile.hasInk && !tile.base.hasDisplayList()) tile.dirty = true
        if (tile.appended.isNotEmpty() && !tile.delta.hasDisplayList()) tile.deltaDirty = true
        if (tile.dirty || tile.appended.isNotEmpty() && (consolidate || tile.appended.size >= 32)) {
            val left = (key.x * TILE_PX - GUTTER_PX) / scale
            val top = (key.y * TILE_PX - GUTTER_PX) / scale
            val strokes = query(key.page, left, top, left + EXTENT_PX / scale, top + EXTENT_PX / scale)
            val hasInk = record(tile.base, tile, strokes)
            if (tile.hasInk != hasInk) layerBytes += if (hasInk) TILE_BYTES else -TILE_BYTES
            tile.hasInk = hasInk
            tile.base.setUseCompositingLayer(hasInk, null)
            tile.dirty = false
            tile.appended.clear()
            tile.delta.discardDisplayList()
            tile.deltaDirty = false
        } else if (tile.deltaDirty) {
            record(tile.delta, tile, tile.appended)
            tile.deltaDirty = false
        }
        return tile
    }

    fun draw(canvas: Canvas, page: Page, scale: Float, viewport: RectF,
        interacting: Boolean, consolidate: Boolean, hasHighlighter: Boolean,
        highlighterAbove: Boolean, multiply: Paint, excluded: Set<Stroke>? = null) {
        if (viewport.isEmpty) return
        val state = sync(page, scale, interacting, excluded)
        val recordedScale = state.scale
        val firstX = floor(viewport.left * recordedScale / TILE_PX).toInt()
        val firstY = floor(viewport.top * recordedScale / TILE_PX).toInt()
        val lastX = ceil(viewport.right * recordedScale / TILE_PX).toInt() - 1
        val lastY = ceil(viewport.bottom * recordedScale / TILE_PX).toInt() - 1
        for (pass in 0 until if (hasHighlighter) 2 else 1) {
            val highlight = hasHighlighter && if (pass == 0) !highlighterAbove else highlighterAbove
            // Multiply is applied against the paper/ink outside the cached
            // layers. Applying it inside an isolated tile loses the backdrop.
            val layer = if (highlight) canvas.saveLayer(viewport, multiply) else -1
            for (y in firstY..lastY) for (x in firstX..lastX) {
                val tile = tileFor(Key(page, x, y, highlight), state, consolidate)
                if (!tile.hasInk && !tile.delta.hasDisplayList()) continue
                canvas.save()
                canvas.scale(1f / recordedScale, 1f / recordedScale)
                // Each tile owns its central pixels. The gutter supplies mesh
                // antialiasing at the edges without double-blending neighbours.
                canvas.clipRect((x * TILE_PX).toFloat(), (y * TILE_PX).toFloat(),
                    ((x + 1) * TILE_PX).toFloat(), ((y + 1) * TILE_PX).toFloat())
                canvas.translate((x * TILE_PX - GUTTER_PX).toFloat(), (y * TILE_PX - GUTTER_PX).toFloat())
                if (tile.hasInk) { canvas.drawRenderNode(tile.base); layerDraws++ }
                if (tile.delta.hasDisplayList()) canvas.drawRenderNode(tile.delta)
                canvas.restore()
            }
            if (layer >= 0) canvas.restoreToCount(layer)
        }
        val iterator = tiles.entries.iterator()
        while ((layerBytes > byteBudget || tiles.size > 128) && iterator.hasNext()) {
            discard(iterator.next().value)
            iterator.remove()
        }
    }

    companion object {
        private const val TILE_PX = 512
        private const val GUTTER_PX = 2
        private const val EXTENT_PX = TILE_PX + 2 * GUTTER_PX
        private const val TILE_BYTES = EXTENT_PX * EXTENT_PX * 4
    }
}
