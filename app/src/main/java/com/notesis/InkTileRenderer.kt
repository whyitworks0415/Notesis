package com.notesis

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max

/** UI owns plans/cache; the single worker owns candidate lists and detailed geometry. */
internal class InkTileRenderer(executor: Executor, bytes: Int, private val resetFrame: () -> Unit = {},
    private val invalidate: () -> Unit) : MemoryTrimmable {
    internal class PageState(val page: Page, val session: Long) {
        val tracker = InkDirtyRegionTracker()
        @Volatile var edit = 0L
        @Volatile var alive = true
        var snapshots = emptyList<InkSource<InkOwnedResource<Bitmap>>>()
        val minimumEdits = HashMap<InkTileAddress, Long>()
        val dirty = ArrayList<InkChangedBounds>()
        val ready = ArrayList<Pair<Long, () -> Unit>>()
        var centerX = 0f
        var centerY = 0f
        var directionX = 0f
        var directionY = 0f
    }
    private val handler = Handler(Looper.getMainLooper())
    private val scheduler = InkTileScheduler(executor)
    private val budget = InkMemoryBudget(bytes.toLong())
    private val pages = IdentityHashMap<Page, PageState>()
    private val cache = LinkedHashMap<InkTileAddress, InkSource<InkOwnedResource<Bitmap>>>(16, .75f, true)
    private var frameOwners = emptySet<InkOwnedResource<Bitmap>>()
    private data class Selection(val state: PageState, val edit: Long,
        val background: List<InkSource<InkOwnedResource<Bitmap>>>,
        val foreground: List<InkSource<InkOwnedResource<Bitmap>>>)
    private var selection: Selection? = null
    private var selectionKey: InkTileAddress? = null
    private var selectionSerial = 0L
    private var wanted = linkedSetOf<InkTileAddress>()
    private var viewportSerial = 0L
    @Volatile private var closed = false
    @Volatile private var lifecycle = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var preparing = true; private set
    init { RuntimeMemory.register(this) }

    fun state(page: Page): PageState = pages.getOrPut(page) { PageState(page, sessions.incrementAndGet()) }
    fun beginFrame() { wanted = linkedSetOf(); viewportSerial++ }

    /** Edits arrive from list mutations, so image/text/mask-only edits produce no ink invalidation. */
    fun changed(page: Page) {
        val changes = page.inkStore.drainChanges()
        if (changes.isEmpty()) return
        val state = state(page)
        if (selection?.state === state) clearSelection()
        synchronized(state) {
        state.edit++
        val regions = changes.flatMap { change -> buildList {
            change.before?.let { add(InkChangedBounds(it.layer, it.bounds, null)) }
            change.after?.let { add(InkChangedBounds(it.layer,
                if (change.appended) null else it.bounds, it.bounds)) }
        } }
        state.dirty += regions
        state.tracker.change(InkChangeSet(regions)).forEach { state.minimumEdits[it] = state.edit }
        // Appends can update the floor immediately using only changed strokes. These bitmap
        // patches replace the floor atomically; they never alpha-compose twice with old tiles.
        if (changes.all { it.appended } && state.snapshots.size == 2 &&
            state.snapshots.all { it.edit == state.edit - 1 }) {
            val additions = changes.mapNotNull { it.after }
            val replacement = createSnapshots(state, state.edit, state.snapshots, emptyList(), additions, null)
            if (replacement != null) publishSnapshots(state, replacement)
        }
        }
        invalidate()
    }

    fun loaded(page: Page) {
        val callbacks = pages[page]?.ready?.map { it.second }.orEmpty()
        pages[page]?.ready?.clear()
        drop(page)
        page.inkStore.drainChanges()
        state(page).ready.addAll(callbacks.map { 0L to it })
        invalidate()
    }

    fun whenReady(page: Page, action: () -> Unit) {
        val state = state(page)
        if (state.snapshots.size == 2 && state.snapshots.all { it.edit >= state.edit }) action()
        else state.ready += state.edit to action
    }

    fun plan(page: Page, viewport: InkRect, scale: Float, deferDetail: Boolean,
        interacting: Boolean): InkDrawPlan<InkOwnedResource<Bitmap>, Stroke> {
        val state = state(page)
        val bounds = InkRect(0f, 0f, page.width, page.height)
        val visible = viewport.intersection(bounds) ?: return InkRenderPolicy.plan(emptyList(), interacting)
        val centerX = (visible.left + visible.right) / 2
        val centerY = (visible.top + visible.bottom) / 2
        if (centerX != state.centerX) state.directionX = kotlin.math.sign(centerX - state.centerX)
        if (centerY != state.centerY) state.directionY = kotlin.math.sign(centerY - state.centerY)
        state.centerX = centerX; state.centerY = centerY
        if (page.loaded) requestSnapshots(state)
        var level = 0
        while ((1 shl level) < scale && level < InkRenderPolicy.MAX_LEVEL) level++
        // Fit the entire visible working set, rather than generating detail that will evict itself.
        while (level > 0 && InkRenderPolicy.addresses(state.session, InkLayer.PEN, level, visible).size *
            TILE_BYTES * 2 > budget.cap(InkMemoryPool.DETAIL)) level--
        val cells = ArrayList<InkCell<InkOwnedResource<Bitmap>>>()
        for (layer in InkLayer.entries) {
            val addresses = InkRenderPolicy.addresses(state.session, layer, level, visible)
            for (address in addresses) {
                val clip = address.bounds().intersection(bounds) ?: continue
                val version = state.tracker.version(address)
                val candidates = ArrayList<InkSource<InkOwnedResource<Bitmap>>>()
                cache[address]?.let(candidates::add)
                for (coarse in level - 1 downTo 0) {
                    val units = InkRenderPolicy.TILE_PX / (1 shl coarse).toFloat()
                    cache[InkTileAddress(state.session, layer, coarse, (clip.left / units).toInt(),
                        (clip.top / units).toInt())]?.let(candidates::add)
                }
                val floorEdit = state.snapshots.minOfOrNull { it.edit } ?: -1
                candidates.removeAll { source ->
                    val required = state.minimumEdits[source.address] ?: 0
                    floorEdit >= required && source.edit < required
                }
                candidates.addAll(state.snapshots.filter { it.address.layer == layer })
                val minimum = state.minimumEdits[address] ?: 0
                cells += InkCell(address, clip, version, candidates, if (floorEdit >= minimum) minimum else 0)
                wanted += address
                if (page.loaded && !deferDetail && cache[address]?.version != version)
                    requestTile(state, address, version, if (minimum > 0) 0 else 1, clip)
            }
            if (!interacting && !deferDetail) {
                val halo = visible.expand(InkRenderPolicy.TILE_PX / (1 shl level).toFloat()).intersection(bounds)!!
                for (address in InkRenderPolicy.addresses(state.session, layer, level, halo)) {
                    if (address in wanted) continue
                    wanted += address
                    val version = state.tracker.version(address)
                    if (cache[address]?.version != version) requestTile(state, address, version, 2,
                        address.bounds().intersection(bounds)!!)
                }
            }
        }
        return InkRenderPolicy.plan<InkOwnedResource<Bitmap>, Stroke>(cells, interacting).also {
            InkRenderStats.plan(it.sources.size, it.missing.size)
        }
    }

    fun finishFrame(plans: List<InkDrawPlan<InkOwnedResource<Bitmap>, Stroke>>, keepLast: Boolean) {
        selectionKey?.let { wanted += it }
        scheduler.retain(wanted)
        if (!keepLast) {
            val next = plans.flatMap { plan -> plan.sources.map { it.source.value } }.toSet()
            (next - frameOwners).forEach { it.retain() }
            (frameOwners - next).forEach { it.release() }
            frameOwners = next
        }
        cache.values.forEach { it.value.moveTo(InkMemoryPool.DETAIL) }
        pages.values.forEach { state -> state.snapshots.forEach { it.value.moveTo(InkMemoryPool.COVERAGE) } }
        val sessions = wanted.map { it.session }.toSet()
        pages.values.filter { it.session !in sessions && it.snapshots.none { source -> source.value in frameOwners } }
            .map { it.page }.forEach(::drop)
        preparing = keepLast
        InkRenderStats.memory(budget.total(), scheduler.queued(), scheduler.cancelled, scheduler.deduped)
    }

    /** Four prepared bitmaps make lasso motion independent of the number of selected strokes. */
    fun prepareSelection(page: Page, selected: Set<Stroke>) {
        clearSelection()
        if (selected.isEmpty()) return
        val state = state(page)
        val serial = selectionSerial
        val life = lifecycle
        val edit = state.edit
        val key = InkTileAddress(state.session, InkLayer.PEN, -17, 0, 0)
        selectionKey = key
        scheduler.request(InkWorkKey(key, InkTileVersion(life, serial)), 0, 0f, viewportSerial) { token ->
            val bounds = InkRect(0f, 0f, page.width, page.height)
            val density = snapshotDensity(bounds)
            val created = ArrayList<InkSource<InkOwnedResource<Bitmap>>>()
            var posted = false
            try {
                val renderer = CanvasStrokeRenderer.create()
                val matrix = transform(bounds, density)
                for (foreground in listOf(false, true)) for (layer in InkLayer.entries) {
                    if (token.cancelled) return@request
                    val owned = allocate(bounds, density, InkMemoryPool.WORK) ?: return@request
                    created += InkSource(key.copy(layer = layer), InkTileVersion(0, edit), bounds, edit, owned, true)
                    val canvas = Canvas(owned.value)
                    for (record in page.inkStore.query(bounds, layer)) {
                        if (token.cancelled) return@request
                        if ((record.value in selected) == foreground) renderer.draw(canvas, record.value, matrix)
                    }
                }
                posted = handler.post {
                    if (valid(state, life, token) && selectionSerial == serial && state.edit == edit) {
                        created.forEach { it.value.publish() }
                        selection = Selection(state, edit, created.take(2), created.drop(2))
                        invalidate()
                    } else created.forEach { it.value.release() }
                }
            } finally { if (!posted) created.forEach { it.value.release() } }
        }
    }

    fun drawSelection(canvas: Canvas, page: Page, layer: InkLayer, dx: Float, dy: Float): Boolean {
        val preview = selection?.takeIf { it.state.page === page && it.state.edit == it.edit } ?: return false
        fun drawSources(sources: List<InkSource<InkOwnedResource<Bitmap>>>) {
            draw(canvas, InkDrawPlan<InkOwnedResource<Bitmap>, Stroke>(sources.map {
                InkDrawSource(it, it.bounds, InkSourceKind.SNAPSHOT)
            }, emptyList(), emptyList()), layer)
        }
        drawSources(preview.background)
        canvas.save(); canvas.translate(dx, dy); drawSources(preview.foreground); canvas.restore()
        return true
    }

    fun clearSelection() {
        selectionSerial++
        selection?.let { (it.background + it.foreground).forEach { source -> source.value.release() } }
        selection = null; selectionKey = null
    }

    fun draw(canvas: Canvas, plan: InkDrawPlan<InkOwnedResource<Bitmap>, Stroke>, layer: InkLayer) {
        for (draw in plan.sources) {
            if (draw.source.address.layer != layer) continue
            val source = draw.source
            val density = if (source.snapshot) snapshotDensity(source.bounds) else source.address.density
            canvas.save()
            canvas.clipRect(draw.clip.left, draw.clip.top, draw.clip.right, draw.clip.bottom)
            val bleed = InkRenderPolicy.BLEED_PX / density
            canvas.drawBitmap(source.value.value, null, RectF(source.bounds.left - bleed,
                source.bounds.top - bleed, source.bounds.left - bleed + source.value.value.width / density,
                source.bounds.top - bleed + source.value.value.height / density), paint)
            canvas.restore()
        }
    }

    private fun requestSnapshots(state: PageState) {
        val key = InkTileAddress(state.session, InkLayer.PEN, SNAPSHOT_LEVEL, 0, 0)
        wanted += key
        if (state.snapshots.size == 2 && state.snapshots.all { it.edit == state.edit }) return
        val edit = state.edit
        val life = lifecycle
        val version = InkTileVersion(life, edit)
        // Retain sources only when the task actually starts; queued work carries addresses/versions.
        if (scheduler.request(InkWorkKey(key, version), 0, 0f, viewportSerial) { token ->
            val captured = onUiSnapshot(state, edit, life) ?: return@request
            try {
                val result = createSnapshots(state, edit, captured.first, captured.second, null, token)
                    ?: run { if (!token.cancelled) handler.postDelayed({ invalidate() }, 100); return@request }
                val posted = handler.post {
                    if (valid(state, life, token) && state.edit == edit) publishSnapshots(state, result)
                    else result.forEach { it.value.release() }
                }
                if (!posted) result.forEach { it.value.release() }
            } finally { captured.first.forEach { it.value.release() } }
        }) InkRenderStats.scheduled()
    }

    /** A short lock captures metadata and retained bitmap references, never a page stroke list. */
    private fun onUiSnapshot(state: PageState, edit: Long, life: Long): Pair<List<InkSource<InkOwnedResource<Bitmap>>>, List<InkChangedBounds>>? =
        synchronized(state) {
            if (!state.alive || life != lifecycle || state.edit != edit) null
            else (state.snapshots.toList().onEach { it.value.retain() }) to state.dirty.toList()
        }

    private fun createSnapshots(state: PageState, edit: Long, old: List<InkSource<InkOwnedResource<Bitmap>>>,
        dirty: List<InkChangedBounds>, additions: List<InkStrokeRecord<Stroke>>?, token: InkWorkToken?): List<InkSource<InkOwnedResource<Bitmap>>>? {
        val bounds = InkRect(0f, 0f, state.page.width, state.page.height)
        val density = snapshotDensity(bounds)
        val result = ArrayList<InkSource<InkOwnedResource<Bitmap>>>()
        try {
            for (layer in InkLayer.entries) {
                if (token?.cancelled == true) return null
                val owned = allocate(bounds, density, InkMemoryPool.COVERAGE) ?: return null
                val address = InkTileAddress(state.session, layer, SNAPSHOT_LEVEL, 0, 0)
                result += InkSource(address, state.tracker.version(address), bounds, edit, owned, true)
                val canvas = Canvas(owned.value)
                val previous = old.firstOrNull { it.address.layer == layer }
                if (previous != null) canvas.drawBitmap(previous.value.value, 0f, 0f, null)
                val renderer = CanvasStrokeRenderer.create()
                val transform = transform(bounds, density)
                if (additions != null) {
                    additions.filter { it.layer == layer }.forEach { renderer.draw(canvas, it.value, transform) }
                } else {
                    // Clip the union once: overlapping dirty rectangles must not double highlighter alpha.
                    val region = android.graphics.Region()
                    val areas = if (previous == null) listOf(bounds) else dirty.filter { it.layer == layer }
                        .flatMap { listOfNotNull(it.before, it.after) }.map { it.expand(3f / density) }
                    for (area in areas) region.op(android.graphics.Rect(
                        kotlin.math.floor(area.left * density).toInt() + 2,
                        kotlin.math.floor(area.top * density).toInt() + 2,
                        ceil(area.right * density).toInt() + 2, ceil(area.bottom * density).toInt() + 2),
                        android.graphics.Region.Op.UNION)
                    val path = region.boundaryPath
                    canvas.save(); canvas.clipPath(path); canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                    val records = areas.flatMap { state.page.inkStore.query(it, layer) }
                        .distinctBy { it.value }.sortedBy { it.order }
                    for (record in records) {
                        if (token?.cancelled == true) return null
                        renderer.draw(canvas, record.value, transform)
                    }
                    canvas.restore()
                }
            }
            return result.toList().also { result.clear() }
        } catch (_: RuntimeException) { return null
        } catch (_: OutOfMemoryError) { return null
        } finally { result.forEach { it.value.release() } }
    }

    private fun publishSnapshots(state: PageState, result: List<InkSource<InkOwnedResource<Bitmap>>>) {
        synchronized(state) {
            result.forEach { it.value.publish() }
            val old = state.snapshots
            state.snapshots = result
            state.dirty.clear()
            old.forEach { it.value.release() }
        }
        val edit = result.minOf { it.edit }
        state.ready.filter { it.first <= edit }.toList().forEach { state.ready.remove(it); it.second() }
        invalidate()
    }

    private fun requestTile(state: PageState, address: InkTileAddress, version: InkTileVersion,
        priority: Int, bounds: InkRect) {
        val life = lifecycle
        val edit = state.edit
        // Admission happens before enqueue. Eviction is confined to detail, preserving the floor.
        if (!makeRoom(TILE_BYTES)) return
        val dx = (bounds.left + bounds.right) / 2 - state.centerX
        val dy = (bounds.top + bounds.bottom) / 2 - state.centerY
        val distance = kotlin.math.abs(dx) + kotlin.math.abs(dy) -
            .15f * (dx * state.directionX + dy * state.directionY)
        if (scheduler.request(InkWorkKey(address, version), priority, distance, viewportSerial) { token ->
            val owned = allocate(bounds, address.density, InkMemoryPool.DETAIL) ?: return@request
            val start = InkRenderStats.rasterStart()
            var handedOff = false
            try {
                if (token.cancelled) return@request
                val canvas = Canvas(owned.value)
                val renderer = CanvasStrokeRenderer.create()
                val matrix = transform(bounds, address.density)
                val records = state.page.inkStore.query(bounds.expand(3f / address.density), address.layer)
                for (record in records) {
                    if (token.cancelled) return@request
                    val stroke = record.value
                    val epsilon = strokeEpsilon(stroke.brush.size, epsilonFor(address.density))
                    // Detailed geometry belongs to this raster, never to Page or undo history.
                    val detailed = if (kotlin.math.abs(stroke.brush.epsilon - epsilon) < .0001f) stroke
                        else Stroke(stroke.brush.copy(epsilon = epsilon), stroke.inputs)
                    renderer.draw(canvas, detailed, matrix)
                }
                handedOff = handler.post {
                    if (valid(state, life, token) && state.tracker.version(address) == version) {
                        owned.publish()
                        cache.put(address, InkSource(address, version, bounds, edit, owned))?.value?.release()
                        invalidate()
                    } else owned.release()
                }
            } catch (_: RuntimeException) { /* Failed unpublished result is worker-owned. */
            } catch (_: OutOfMemoryError) { /* Keep existing coverage. */
            } finally { if (!handedOff) owned.release(); InkRenderStats.rasterEnd(start) }
        }) InkRenderStats.scheduled()
    }

    private fun valid(state: PageState, life: Long, token: InkWorkToken) =
        !closed && state.alive && lifecycle == life && !token.cancelled && pages[state.page] === state

    private fun makeRoom(bytes: Long): Boolean {
        if (bytes > budget.cap(InkMemoryPool.DETAIL)) return false
        val iterator = cache.entries.iterator()
        while (budget.used(InkMemoryPool.DETAIL) + bytes > budget.cap(InkMemoryPool.DETAIL) && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in wanted || entry.value.value in frameOwners) continue
            entry.value.value.release(); iterator.remove(); InkRenderStats.evicted()
        }
        return budget.used(InkMemoryPool.DETAIL) + bytes <= budget.cap(InkMemoryPool.DETAIL) ||
            budget.used(InkMemoryPool.WORK) + bytes <= budget.cap(InkMemoryPool.WORK)
    }

    private fun allocate(bounds: InkRect, density: Float, pool: InkMemoryPool): InkOwnedResource<Bitmap>? {
        val width = ceil(bounds.width * density).toInt().coerceAtLeast(1) + 4
        val height = ceil(bounds.height * density).toInt().coerceAtLeast(1) + 4
        val byteCount = width.toLong() * height * 4
        val lease = budget.reserve(byteCount, pool) ?: budget.reserve(byteCount, InkMemoryPool.WORK) ?: return null
        return try { InkOwnedResource(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), lease) { it.recycle() } }
        catch (_: OutOfMemoryError) { lease.release(); null }
        catch (_: RuntimeException) { lease.release(); null }
    }
    private fun transform(bounds: InkRect, density: Float) = Matrix().apply {
        setScale(density, density); postTranslate(2 - bounds.left * density, 2 - bounds.top * density)
    }
    private fun snapshotDensity(bounds: InkRect) = 256f / max(bounds.width, bounds.height).coerceAtLeast(1f)

    fun drop(page: Page) {
        val state = pages.remove(page) ?: return
        synchronized(state) { state.alive = false; state.snapshots.forEach { it.value.release() }; state.snapshots = emptyList() }
        scheduler.cancelSession(state.session)
        cache.keys.filter { it.session == state.session }.forEach { cache.remove(it)?.value?.release() }
        state.ready.forEach { it.second() }; state.ready.clear()
    }
    fun clear() {
        lifecycle++; scheduler.clear()
        clearSelection(); resetFrame()
        pages.keys.toList().forEach(::drop)
        frameOwners.forEach { it.release() }; frameOwners = emptySet()
        preparing = true
    }
    override fun trimMemory(level: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { trimMemory(level) }; return }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { clear(); invalidate() }
    }
    fun close() { closed = true; clear(); scheduler.close(); RuntimeMemory.unregister(this) }
    companion object {
        private val sessions = AtomicLong()
        private const val SNAPSHOT_LEVEL = -16
        private const val TILE_BYTES = 516L * 516 * 4
    }
}
