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
 *
 * A translucent note layer is composited inside each tile (see [drawLayered]):
 * a tile holds a page's ink, and a layer's run of strokes within it is one
 * offscreen group. A change of opacity re-records the page's tiles in view,
 * with no geometry rebuilt; notes whose layers are all opaque never pay for it.
 */
internal class InkTileCache(
    private val byteBudget: Int = 48 * 1024 * 1024,
    private val screenStroke: (Stroke) -> Stroke,
    private val query: (Page, Float, Float, Float, Float) -> List<Stroke>,
    /**
     * Runs a finished background picture on the UI thread and asks for a frame.
     * Null keeps every tile on the GPU path (and tests off threads).
     */
    private val onRasterReady: ((Runnable) -> Unit)? = null,
) {
    private data class Key(val page: Page, val x: Int, val y: Int, val highlight: Boolean)

    private class Tile(val key: Key, val scale: Float, var excluded: Set<Stroke>?, val look: LayerLook?) {
        val base = RenderNode("ink tile")
        val delta = RenderNode("ink delta")
        val appended = ArrayList<Stroke>()
        var dirty = true
        /**
         * Dirty, but its old recording may go on standing in while the frame's
         * budget is spent: for a change that touches the whole page at once (a
         * reorder), where recording every tile in one frame is a stall and the
         * picture from a moment ago is still the right ink.
         */
        var mayWait = false
        var deltaDirty = false
        var hasInk = false
        /** Its picture is being painted on a worker; see [TileRaster]. */
        var job: TileRaster.Job? = null
        /** Raised by every change that makes a picture being painted out of date. */
        var version = 0
    }

    private class PageState {
        /** Every tile this page showed last frame was recorded; nothing waited on the budget. */
        var complete = false
        var scale = 0f
        var revision = Long.MIN_VALUE
        var meshRevision = Long.MIN_VALUE
        val strokes = ArrayList<Stroke>()
        val positions = IdentityHashMap<Stroke, Int>()
        var excluded: Set<Stroke>? = null
        /** The translucent layer look the tiles were recorded under; null when all layers are opaque. */
        var look: LayerLook? = null
    }

    private val tiles = LinkedHashMap<Key, Tile>(32, 0.75f, true)
    /** Tiles held per page, so an edit on a page with none costs nothing to look up. */
    private val tilesPerPage = IdentityHashMap<Page, Int>()

    private fun countRemoved(page: Page) {
        val left = (tilesPerPage[page] ?: return) - 1
        if (left <= 0) tilesPerPage.remove(page) else tilesPerPage[page] = left
    }
    private val pages = LinkedHashMap<Page, PageState>(8, 0.75f, true)
    private val frameTiles = HashSet<Key>()
    private val framePages = HashSet<Page>()

    /**
     * A page's last complete set of tiles, kept for a few frames after a new
     * zoom or rebuilt meshes made them out of date. The new tiles are
     * recorded a frame's budget at a time, and until each one is, this older
     * picture of the same ink is drawn in its place - slightly soft for a
     * frame or two, instead of every tile on screen recorded in one frame
     * right after the fingers lift. Only for changes of resolution or mesh:
     * an edit, a lifted lasso or a layer's opacity is a different picture.
     */
    private class StaleTiles(val scale: Float, val revision: Long, val size: Int, val tiles: HashMap<Key, Tile>)

    private val stale = IdentityHashMap<Page, StaleTiles>()
    /** Pages whose stale tiles were drawn this frame; those stay until a frame no longer needs them. */
    private val staleDrawn = HashSet<Page>()

    /** Recording time this frame may still take; see [beginFrame]. */
    private var recordBudgetNanos = Long.MAX_VALUE
    private var recordSpentNanos = 0L
    private var recordedThisFrame = 0
    private var frameStarted = 0L
    private var previousRecordNanos = 0L
    var cleanupNanos = 0L
        private set
    val recordingNanos: Long get() = recordSpentNanos

    /**
     * True after a frame left tiles unrecorded for want of budget: the caller
     * asks for another frame, and the backlog is worked off one frame's share
     * at a time. False once nothing waits, so an idle canvas draws nothing.
     */
    var backlog = false
        private set
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

    /**
     * Android wants memory back while the page is in view: everything but the
     * tiles the last frame drew goes. Clearing those too left the page bare
     * and every tile recorded again at once - a blink and a stall each time
     * memory ran short, which it does with other heavy apps open.
     */
    fun trimToVisible() {
        val iterator = tiles.entries.iterator()
        while (iterator.hasNext()) {
            val tile = iterator.next().value
            if (tile.key in frameTiles) continue
            discard(tile)
            iterator.remove()
            countRemoved(tile.key.page)
        }
        for (page in stale.keys.toList()) if (page !in staleDrawn) stale.remove(page)?.tiles?.values?.forEach(::discard)
    }

    /** Whether every tile of [page] the last frame wanted was there; true for a page not drawn from tiles. */
    fun isComplete(page: Page): Boolean = pages[page]?.complete ?: true

    fun clear() {
        tiles.values.forEach(::discard)
        tiles.clear()
        tilesPerPage.clear()
        pages.clear()
        frameTiles.clear()
        framePages.clear()
        for (old in stale.values) old.tiles.values.forEach(::discard)
        stale.clear()
        staleDrawn.clear()
        backlog = false
    }

    /**
     * [recordBudget]: nanoseconds this frame may spend recording tiles that
     * have nothing recorded yet. A tile an edit changed is always recorded at
     * once - it is what the pen just did - and one tile always goes ahead.
     */
    fun beginFrame(recordBudget: Long = Long.MAX_VALUE) {
        frameTiles.clear()
        framePages.clear()
        staleDrawn.clear()
        recordBudgetNanos = recordBudget
        frameStarted = System.nanoTime()
        cleanupNanos = 0L
        recordSpentNanos = 0L
        recordedThisFrame = 0
        strokesThisFrame = 0
        // Scaled down while the render thread has been running over its frames.
        strokeCap = (strokeCapFor(InteractionGovernor.state) * RenderLoad.share).toInt()
        backlog = false
    }

    /**
     * Strokes this frame's new GPU tiles may hold. The time budget above sees
     * only the recording, which is quick; painting those strokes into the
     * tiles' layers is the render thread's, in the same frame, and it was
     * that - several dense tiles in one frame - that took a frame to hundreds
     * of milliseconds as a page came on screen.
     */
    private var strokeCap = Int.MAX_VALUE
    private var strokesThisFrame = 0

    private fun mayRecord(): Boolean = optionalTileWork(recordedThisFrame,
        System.nanoTime() - frameStarted, recordBudgetNanos, previousRecordNanos) &&
        (recordedThisFrame == 0 || strokesThisFrame < strokeCap)

    /** Recorded canvas commands still reference these nodes until GPU playback. */
    fun endFrame() {
        val cleanupStarted = System.nanoTime()
        // A picture still being painted for a tile that has left the screen is not waited for.
        if (rastering > 0) for (tile in tiles.values) {
            val job = tile.job ?: continue
            if (tile.key in frameTiles) continue
            job.cancelled = true
            tile.job = null
            rastering--
        }
        // Stale tiles go once a frame has drawn without them: the page is
        // complete again, or no longer on screen. Never in a frame that drew
        // them - their nodes are in this frame's recording.
        if (stale.isNotEmpty()) {
            val olds = stale.entries.iterator()
            while (olds.hasNext()) {
                val entry = olds.next()
                if (entry.key in staleDrawn) continue
                entry.value.tiles.values.forEach(::discard)
                olds.remove()
            }
        }
        val oldPages = pages.entries.iterator()
        while (pages.size > 6 && oldPages.hasNext()) {
            val page = oldPages.next().key
            if (page in framePages) continue
            discardPage(page)
            oldPages.remove()
        }
        val oldTiles = tiles.entries.iterator()
        while ((layerBytes > byteBudget || tiles.size > MAX_TILES) && oldTiles.hasNext()) {
            val tile = oldTiles.next().value
            if (tile.key in frameTiles) continue
            discard(tile)
            oldTiles.remove()
            countRemoved(tile.key.page)
        }
        // The visible frame wins over the cache budget. Only off-screen entries
        // may be discarded; deleting a queued node makes that ink disappear.
        cleanupNanos = System.nanoTime() - cleanupStarted
    }

    private fun visibleTiles(viewport: RectF, scale: Float): Long {
        val columns = ceil(viewport.right * scale / TILE_PX).toLong() -
            floor(viewport.left * scale / TILE_PX).toLong()
        val rows = ceil(viewport.bottom * scale / TILE_PX).toLong() -
            floor(viewport.top * scale / TILE_PX).toLong()
        return columns.coerceAtLeast(0) * rows.coerceAtLeast(0)
    }

    private fun discard(tile: Tile) {
        tile.job?.let { it.cancelled = true; rastering-- }
        tile.job = null
        tile.version++
        if (tile.hasInk) layerBytes -= TILE_BYTES
        tile.base.setUseCompositingLayer(false, null)
        tile.base.discardDisplayList()
        tile.delta.discardDisplayList()
    }

    /** Drops a page's tiles and its stroke list; it is read in again from scratch if it comes back. */
    fun forgetPage(page: Page) {
        discardPage(page)
        dropStale(page)
        pages.remove(page)
    }

    private fun dropStale(page: Page) {
        // Not while this frame still draws them; endFrame lets them go after.
        if (page in staleDrawn) return
        stale.remove(page)?.tiles?.values?.forEach(::discard)
    }

    /**
     * The page's tiles are about to be replaced by ones at a new resolution
     * or from rebuilt meshes. A complete set is set aside to stand in while
     * the new tiles are recorded; a set that was itself still filling in is
     * dropped in favour of the complete one already aside.
     */
    private fun retire(page: Page, state: PageState) {
        if ((tilesPerPage[page] ?: 0) == 0 || (!state.complete && stale[page] != null)) {
            discardPage(page)
            return
        }
        dropStale(page)
        if (stale[page] != null) {
            discardPage(page)
            return
        }
        val moved = HashMap<Key, Tile>()
        val iterator = tiles.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.page === page) {
                moved[entry.key] = entry.value
                iterator.remove()
            }
        }
        tilesPerPage.remove(page)
        stale[page] = StaleTiles(state.scale, state.revision, state.strokes.size, moved)
    }

    /**
     * Draws the stale picture over the area of the tile at ([x], [y]) of a
     * grid at [scale], for a tile not recorded yet. The canvas is in page units.
     */
    private fun drawStale(canvas: Canvas, page: Page, x: Int, y: Int, scale: Float, highlight: Boolean) {
        val old = stale[page] ?: return
        // Only while it is still the same ink: an edit since makes it wrong.
        if (old.revision != page.revision || old.size != page.strokes.size) return
        val left = x * TILE_PX / scale
        val top = y * TILE_PX / scale
        val right = (x + 1) * TILE_PX / scale
        val bottom = (y + 1) * TILE_PX / scale
        val firstX = floor(left * old.scale / TILE_PX).toInt()
        val firstY = floor(top * old.scale / TILE_PX).toInt()
        val lastX = ceil(right * old.scale / TILE_PX).toInt() - 1
        val lastY = ceil(bottom * old.scale / TILE_PX).toInt() - 1
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        for (sy in firstY..lastY) for (sx in firstX..lastX) {
            val tile = old.tiles[Key(page, sx, sy, highlight)] ?: continue
            val hasBase = tile.hasInk && tile.base.hasDisplayList()
            val hasDelta = tile.delta.hasDisplayList()
            if (!hasBase && !hasDelta) continue
            canvas.save()
            canvas.scale(1f / old.scale, 1f / old.scale)
            canvas.clipRect((sx * TILE_PX).toFloat(), (sy * TILE_PX).toFloat(),
                ((sx + 1) * TILE_PX).toFloat(), ((sy + 1) * TILE_PX).toFloat())
            canvas.translate((sx * TILE_PX - GUTTER_PX).toFloat(), (sy * TILE_PX - GUTTER_PX).toFloat())
            if (hasBase) canvas.drawRenderNode(tile.base)
            if (hasDelta) canvas.drawRenderNode(tile.delta)
            canvas.restore()
        }
        canvas.restore()
        staleDrawn += page
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
        tilesPerPage.remove(page)
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

    private fun changedStroke(page: Page, stroke: Stroke, append: Boolean, look: LayerLook? = null, evenExcluded: Boolean = false) {
        if ((tilesPerPage[page] ?: 0) == 0) return
        val box = strokeBounds(stroke) ?: return
        val highlight = stroke.brush.family == highlightFamily
        // A delta list draws on top of the tile, outside its layer's group:
        // fine for an opaque layer, a darker overlap for a translucent one.
        val deltaOk = append && (look == null || look.alphaOf(StrokeLayers.of(stroke)) >= 255)
        for (tile in tiles.values) {
            if (tile.key.page !== page || tile.key.highlight != highlight || !touches(tile, box)) continue
            if (!evenExcluded && tile.excluded?.contains(stroke) == true) continue
            // A tile whose picture is being painted, or has none yet, takes a new
            // stroke over whatever stands in for it: waiting for the picture
            // would leave the stroke just written off the screen until it came.
            if (deltaOk && (!tile.dirty || tile.job != null || !tile.hasInk)) {
                tile.appended += stroke
                tile.deltaDirty = true
            } else {
                tile.dirty = true
                tile.mayWait = false
                tile.appended.clear()
                tile.version++
            }
        }
    }

    /** Every tile of [page] to be recorded again, each showing its old picture until it is. */
    private fun allDirty(page: Page) {
        for (tile in tiles.values) if (tile.key.page === page) {
            if (!tile.dirty) tile.mayWait = true
            tile.dirty = true
            tile.appended.clear()
            tile.version++
        }
    }

    /** Compare content once per edit, rather than scanning the page per tile. */
    private fun sync(page: Page, requestedScale: Float, interacting: Boolean, excluded: Set<Stroke>?,
        viewport: RectF, passes: Int, look: LayerLook?): PageState {
        val state = pages.getOrPut(page) { PageState() }
        // Reusing the old resolution is cheap for a small pinch. After a deep
        // zoom-out it can require hundreds of oversized tiles in one frame.
        val keepScale = state.scale > 0f && interacting && requestedScale >= state.scale / 2f &&
            visibleTiles(viewport, state.scale) * passes <=
            minOf(MAX_TILES, (byteBudget / TILE_BYTES).coerceAtLeast(1))
        // A pinch that settles a hair away from where the tiles were drawn is
        // the same picture: re-recording every tile of the page for a 0.1%
        // difference was a full redraw after nearly every gesture.
        val nearlySame = state.scale > 0f && abs(state.scale / requestedScale - 1f) < SETTLED_SCALE_SLACK
        val scale = if (keepScale || nearlySame) state.scale else requestedScale
        // The lasso lifting strokes off (or putting them down) changes only the tiles under them. Throwing
        // every tile of a dense page away for it left the page bare until each was recorded again: the ink
        // blinked at every lift and drop.
        if (state.excluded !== excluded && sameLook(state.look, look) && state.scale > 0f &&
            abs(state.scale / scale - 1f) < 0.001f && state.meshRevision == page.meshRevision) {
            val before = state.excluded.orEmpty()
            val after = excluded.orEmpty()
            for (stroke in before) if (stroke !in after) changedStroke(page, stroke, append = false, evenExcluded = true)
            for (stroke in after) if (stroke !in before) changedStroke(page, stroke, append = false, evenExcluded = true)
            for (tile in tiles.values) if (tile.key.page === page) tile.excluded = excluded
            state.excluded = excluded
        }
        val newPicture = state.excluded !== excluded || !sameLook(state.look, look)
        if (state.scale == 0f || abs(state.scale / scale - 1f) >= 0.001f ||
            state.meshRevision != page.meshRevision || newPicture) {
            // Same ink at another resolution, or the same strokes with finer
            // meshes: the old tiles can stand in while the new ones come.
            val sameInk = !newPicture && state.scale > 0f && state.revision == page.revision &&
                state.strokes.size == page.strokes.size
            if (sameInk) retire(page, state) else {
                discardPage(page)
                dropStale(page)
            }
            state.complete = false
            state.scale = scale
            state.excluded = excluded
            state.look = look
        }
        val current = page.strokes
        if (state.revision != page.revision || state.meshRevision != page.meshRevision ||
            state.strokes.size != current.size) {
            val old = state.strokes
            // An append leaves every old stroke where it was. Checked at the ends
            // and the middle rather than stroke by stroke: comparing the whole
            // page and copying it on every pen-up grew with the page. Edits that
            // keep the size (a move, a recolour) or shift it (an erase) fail the
            // size or the end check and take the full comparison below.
            val appendOnly = current.size > old.size && (old.isEmpty() ||
                (old[0] === current[0] && old[old.size / 2] === current[old.size / 2] &&
                    old[old.size - 1] === current[old.size - 1]))
            if (appendOnly) {
                val hasCachedTiles = (tilesPerPage[page] ?: 0) > 0
                for (i in old.size until current.size) {
                    if (hasCachedTiles) changedStroke(page, current[i], append = true, look = look)
                    state.positions[current[i]] = i
                    state.strokes += current[i]
                }
            } else if (current.size != old.size || current.indices.any { old[it] !== current[it] }) {
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
                // Each changed stroke marks only the tiles it touches, however many there are: an eraser
                // drawn through dense writing cuts dozens of strokes, and throwing the page's tiles away
                // for it left them bare until recorded again - the ink blinked under the eraser. A
                // reorder (a layer moved) keeps every tile's picture until its new one is in.
                if (reordered) allDirty(page)
                else {
                    removed.forEach { changedStroke(page, it, append = false) }
                    added.forEach { changedStroke(page, it, append = false) }
                }
                state.positions.clear()
                current.forEachIndexed { i, stroke -> state.positions[stroke] = i }
                state.strokes.clear()
                state.strokes.addAll(current)
            }
            state.revision = page.revision
            state.meshRevision = page.meshRevision
        }
        return state
    }

    private fun record(node: RenderNode, tile: Tile, strokes: List<Stroke>): Boolean {
        WorkStats.tilesRecorded.incrementAndGet()
        node.setPosition(0, 0, EXTENT_PX, EXTENT_PX)
        val canvas = node.beginRecording(EXTENT_PX, EXTENT_PX)
        val dx = GUTTER_PX - tile.key.x * TILE_PX
        val dy = GUTTER_PX - tile.key.y * TILE_PX
        transform.setScale(tile.scale, tile.scale)
        transform.postTranslate(dx.toFloat(), dy.toFloat())
        var hasInk = false
        try {
            canvas.concat(transform)
            // The tile's own extent bounds each translucent layer's group.
            drawLayered(canvas, strokes, tile.look, null, include = { stroke ->
                tile.excluded?.contains(stroke) != true &&
                    (stroke.brush.family == highlightFamily) == tile.key.highlight
            }) { stroke ->
                renderer.draw(canvas, screenStroke(stroke), transform)
                recordedStrokes++
                strokesThisFrame++
                hasInk = true
            }
        } finally { node.endRecording() }
        recordings++
        return hasInk
    }

    /** The tile for [key], recorded if it needs to be; null when it has nothing yet and the frame's budget is spent. */
    private fun tileFor(key: Key, state: PageState, consolidate: Boolean): Tile? {
        val scale = state.scale
        val tile = tiles.getOrPut(key) {
            tilesPerPage[key.page] = (tilesPerPage[key.page] ?: 0) + 1
            Tile(key, scale, state.excluded, state.look)
        }
        // Android can discard a display list when its node leaves the drawing
        // tree. A Java cache entry can therefore outlive the GPU recording.
        if (tile.hasInk && !tile.base.hasDisplayList()) { tile.dirty = true; tile.version++ }
        if (tile.appended.isNotEmpty() && !tile.delta.hasDisplayList()) tile.deltaDirty = true
        // A tile with no picture yet - a page coming on screen, a new zoom - or a whole-page change is painted
        // on a worker when its ink allows; the GPU path is kept for edits, which have to show at once.
        // Folding strokes written since into the picture goes the same way, the picture and its delta shown meanwhile.
        val foldOffThread = !tile.dirty && tile.appended.isNotEmpty() && (consolidate || tile.appended.size >= 32)
        if (tile.job == null && (tile.dirty && (!tile.hasInk || tile.mayWait) || foldOffThread)) when (startRaster(tile)) {
            Raster.STARTED -> Unit
            // Workers full: its old picture meanwhile, or none yet; asked again next frame.
            Raster.WAIT -> return tile.takeIf { it.hasInk }
            Raster.GPU -> Unit
        }
        // Being painted: what was written since the picture was asked for is drawn over its stand-in.
        if (tile.job != null) {
            if (tile.deltaDirty) {
                if (tile.appended.isEmpty()) tile.delta.discardDisplayList() else record(tile.delta, tile, tile.appended)
                tile.deltaDirty = false
            }
            return tile
        }
        // A tile never recorded is the burst after a zoom or a page coming
        // in: it waits its turn. One an edit made dirty still has its old
        // recording and is the change just made, and one whose recording the
        // system let go was showing ink a moment ago; neither is held back.
        if (tile.dirty && !tile.hasInk && !tile.base.hasDisplayList() && !mayRecord()) return null
        // A whole-page change waits its turn behind the budget, its old picture drawn meanwhile.
        if (tile.dirty && tile.mayWait && tile.hasInk && tile.base.hasDisplayList() && !mayRecord()) {
            backlog = true
            return tile
        }
        // Folding the delta into the base is tidying, and waits for room;
        // past 32 strokes it is due regardless.
        val fold = tile.appended.isNotEmpty() &&
            (tile.appended.size >= 32 || consolidate && mayRecord())
        if (tile.dirty || fold) {
            val started = System.nanoTime()
            val left = (key.x * TILE_PX - GUTTER_PX) / scale
            val top = (key.y * TILE_PX - GUTTER_PX) / scale
            val strokes = query(key.page, left, top, left + EXTENT_PX / scale, top + EXTENT_PX / scale)
            val hasInk = record(tile.base, tile, strokes)
            previousRecordNanos = System.nanoTime() - started
            recordSpentNanos += previousRecordNanos
            recordedThisFrame++
            if (tile.hasInk != hasInk) layerBytes += if (hasInk) TILE_BYTES else -TILE_BYTES
            tile.hasInk = hasInk
            tile.base.setUseCompositingLayer(hasInk, null)
            tile.dirty = false
            tile.mayWait = false
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
        highlighterAbove: Boolean, multiply: Paint, excluded: Set<Stroke>? = null, look: LayerLook? = null) {
        if (viewport.isEmpty) return
        framePages += page
        val passes = if (hasHighlighter) 2 else 1
        val state = sync(page, scale, interacting, excluded, viewport, passes, look)
        val recordedScale = state.scale
        var complete = true
        var waitsOnBudget = false
        val firstX = floor(viewport.left * recordedScale / TILE_PX).toInt()
        val firstY = floor(viewport.top * recordedScale / TILE_PX).toInt()
        val lastX = ceil(viewport.right * recordedScale / TILE_PX).toInt() - 1
        val lastY = ceil(viewport.bottom * recordedScale / TILE_PX).toInt() - 1
        for (pass in 0 until passes) {
            val highlight = hasHighlighter && if (pass == 0) !highlighterAbove else highlighterAbove
            // Multiply is applied against the paper/ink outside the cached
            // layers. Applying it inside an isolated tile loses the backdrop.
            val layer = if (highlight) canvas.saveLayer(viewport, multiply) else -1
            for (y in firstY..lastY) for (x in firstX..lastX) {
                val key = Key(page, x, y, highlight)
                frameTiles += key
                val tile = tileFor(key, state, consolidate)
                if (tile == null || tile.job != null) complete = false
                // A frame is asked for again only for work the budget held back; a worker's
                // picture asks for its own frame when it is done.
                if (tile == null || tile.dirty && tile.job == null) waitsOnBudget = true
                if (tile == null || tile.job != null && !tile.hasInk) {
                    if (tile == null) WorkStats.tilesDeferred.incrementAndGet()
                    drawStale(canvas, page, x, y, recordedScale, highlight)
                    if (tile == null) continue
                }
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
        state.complete = complete
        if (waitsOnBudget) backlog = true
    }

    /** Tiles whose pictures are on a worker now. */
    private var rastering = 0
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Sends [tile]'s picture to be painted on a worker, if its ink can be
     * painted there; false leaves it to the GPU path. The strokes and their
     * on-screen twins are taken here, on the UI thread, so the worker reads
     * nothing that changes under it.
     */
    private enum class Raster { STARTED, WAIT, GPU }

    private fun startRaster(tile: Tile): Raster {
        val ready = onRasterReady ?: return Raster.GPU
        if (rastering >= MAX_RASTERING) {
            backlog = true
            return Raster.WAIT
        }
        val scale = tile.scale
        val left = (tile.key.x * TILE_PX - GUTTER_PX) / scale
        val top = (tile.key.y * TILE_PX - GUTTER_PX) / scale
        val strokes = ArrayList<Stroke>()
        val twins = IdentityHashMap<Stroke, Stroke>()
        for (stroke in query(tile.key.page, left, top, left + EXTENT_PX / scale, top + EXTENT_PX / scale)) {
            if (tile.excluded?.contains(stroke) == true) continue
            if ((stroke.brush.family == highlightFamily) != tile.key.highlight) continue
            // The pencil's graphite and the particle brushes come out blank off the GPU.
            if (needsGpuPicture(stroke)) return Raster.GPU
            strokes += stroke
            val twin = screenStroke(stroke)
            if (twin !== stroke) twins[stroke] = twin
        }
        val version = tile.version
        val dx = (GUTTER_PX - tile.key.x * TILE_PX).toFloat()
        val dy = (GUTTER_PX - tile.key.y * TILE_PX).toFloat()
        val job = TileRaster.Job(strokes, twins, tile.look, scale, dx, dy, EXTENT_PX)
        tile.job = job
        rastering++
        TileRaster.paint(job) { bitmap -> ready.invoke(Runnable { finishRaster(tile, job, version, bitmap) }) }
        return Raster.STARTED
    }

    /** On the UI thread: the worker's picture becomes [tile]'s, unless the tile changed or went meanwhile. */
    private fun finishRaster(tile: Tile, job: TileRaster.Job, version: Int, bitmap: android.graphics.Bitmap?) {
        if (tile.job !== job) return
        tile.job = null
        rastering--
        if (bitmap == null || tile.version != version || tiles[tile.key] !== tile) {
            // Out of date before it arrived: painted again, from the ink as it is now.
            tile.dirty = true
            backlog = true
            return
        }
        val hasInk = job.strokes.isNotEmpty()
        if (hasInk) {
            tile.base.setPosition(0, 0, EXTENT_PX, EXTENT_PX)
            val canvas = tile.base.beginRecording(EXTENT_PX, EXTENT_PX)
            try { canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint) } finally { tile.base.endRecording() }
        } else tile.base.discardDisplayList()
        // A picture already: no layer of its own to keep.
        tile.base.setUseCompositingLayer(false, null)
        if (tile.hasInk != hasInk) layerBytes += if (hasInk) TILE_BYTES else -TILE_BYTES
        tile.hasInk = hasInk
        tile.dirty = false
        tile.mayWait = false
        // Strokes written before the picture was asked for are in it now; those written since stay over it.
        if (tile.appended.isNotEmpty()) {
            val inPicture = java.util.Collections.newSetFromMap(IdentityHashMap<Stroke, Boolean>())
            inPicture.addAll(job.strokes)
            tile.appended.removeAll { it in inPicture }
            if (tile.appended.isEmpty()) tile.delta.discardDisplayList() else tile.deltaDirty = true
        }
        WorkStats.tilesRastered.incrementAndGet()
    }

    companion object {
        /** Tiles on workers at once, across the view; the rest wait a frame. */
        private const val MAX_RASTERING = 12
        private const val MAX_TILES = 128
        /** Settled zoom within this of the tiles' own is drawn from them as they are. */
        private const val SETTLED_SCALE_SLACK = 0.025f
        private const val TILE_PX = 512
        private const val GUTTER_PX = 2
        private const val EXTENT_PX = TILE_PX + 2 * GUTTER_PX
        private const val TILE_BYTES = EXTENT_PX * EXTENT_PX * 4
    }
}
