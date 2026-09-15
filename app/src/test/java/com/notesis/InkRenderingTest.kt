package com.notesis

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class InkRenderingTest {
    private val address = InkTileAddress(1, InkLayer.PEN, 1, 0, 0)
    private val v = InkTileVersion(0, 0)
    private fun source(kind: InkSourceKind, edit: Long = 0) = InkSource(
        if (kind == InkSourceKind.COARSE) address.copy(level = 0) else address,
        if (kind == InkSourceKind.STALE) v.copy(generation = -1) else v,
        InkRect(0f, 0f, 512f, 512f), edit, kind.name, kind == InkSourceKind.SNAPSHOT)

    @Test fun sourceOrderAndPartialMiss() {
        val sources = InkSourceKind.entries.reversed().map { source(it) }
        for (kind in InkSourceKind.entries) {
            val cell = InkCell(address, address.bounds(), v,
                sources.filter { InkSourceKind.valueOf(it.value).ordinal >= kind.ordinal })
            val miss = cell.copy(address = address.copy(x = 5), bounds = address.copy(x = 5).bounds(), sources = emptyList())
            val plan = InkRenderPolicy.plan<String, Int>(listOf(cell, miss), true)
            assertEquals(kind, plan.sources.single().kind)
            assertEquals(1, plan.missing.size)
        }
    }

    @Test fun dirtyBoundsRespectBleedAllDensitiesAndLayer() {
        val tracker = InkDirtyRegionTracker()
        val addresses = (0..4).flatMap { level -> InkLayer.entries.flatMap { layer ->
            InkRenderPolicy.addresses(1, layer, level, InkRect(0f, 0f, 1024f, 1024f)) } }
        addresses.forEach(tracker::version)
        val dirty = tracker.change(InkChangeSet(listOf(InkChangedBounds(InkLayer.PEN, null,
            InkRect(512f, 256f, 512.1f, 256.1f)))))
        assertTrue(dirty.any { it.level == 0 && it.x == 0 })
        assertTrue(dirty.any { it.level == 0 && it.x == 1 })
        assertTrue(dirty.all { it.layer == InkLayer.PEN })
        assertEquals((0..4).toSet(), dirty.map { it.level }.toSet())
        assertTrue(dirty.size < addresses.size / 2)
    }

    @Test fun moveDoesNotDirtyTheSpaceBetweenEndpoints() {
        val tracker = InkDirtyRegionTracker()
        val keys = (0..9).map { address.copy(level = 0, x = it) }
        keys.forEach(tracker::version)
        val dirty = tracker.change(InkChangeSet(listOf(InkChangedBounds(InkLayer.PEN,
            InkRect(10f, 10f, 20f, 20f), InkRect(4700f, 10f, 4710f, 20f)))))
        assertEquals(setOf(keys.first(), keys.last()), dirty)
        assertEquals(v, tracker.version(keys[4]))
    }

    @Test fun imageOnlyChangeDoesNotAdvanceInkVersion() {
        val tracker = InkDirtyRegionTracker()
        val before = tracker.version(address)
        assertTrue(tracker.change(InkChangeSet(emptyList())).isEmpty())
        assertEquals(before, tracker.version(address))
        assertEquals(listOf(InkComposition.PAPER, InkComposition.HIGHLIGHTER, InkComposition.IMAGES_AND_TEXT,
            InkComposition.PEN, InkComposition.MASKS_AND_SELECTION, InkComposition.WET), InkRenderPolicy.composition)
    }

    @Test fun vectorFallbackIsBoundedAndNeverTruncates() {
        val cells = (0..2).map { InkCell<String>(address.copy(x = it), address.copy(x = it).bounds(), v, emptyList()) }
        val vectors = cells.map { InkVectorRegion(it.bounds, InkLayer.PEN, (0..15).toList(), 2048) }
        assertTrue(InkRenderPolicy.plan(cells, true, vectors).vectors.isEmpty())
        assertEquals(2, InkRenderPolicy.plan(cells, false, vectors).vectors.size)
        assertTrue(InkRenderPolicy.plan(cells, false, listOf(vectors[0].copy(outlineVertices = 4097))).vectors.isEmpty())
        assertTrue(InkRenderPolicy.plan(cells, false, listOf(vectors[0].copy(strokes = (0..32).toList()))).vectors.isEmpty())
    }

    @Test fun overlayClipsExcludePublishedInkAndSurviveCoarseFallback() {
        val left = InkRect(0f, 0f, 128f, 256f); val right = InkRect(128f, 0f, 256f, 256f)
        val sources = listOf(InkDrawSource(source(InkSourceKind.EXACT, 2), left, InkSourceKind.EXACT),
            InkDrawSource(source(InkSourceKind.COARSE, 1), right, InkSourceKind.COARSE))
        assertEquals(listOf(right), InkRenderPolicy.overlayClips(2, address.bounds(), InkLayer.PEN, sources))
        assertTrue(InkRenderPolicy.overlayClips(2, address.bounds(), InkLayer.HIGHLIGHTER, sources).isEmpty())
        val latest = sources.map { it.copy(source = it.source.copy(edit = 2)) }
        assertTrue(InkRenderPolicy.overlayClips(2, address.bounds(), InkLayer.PEN, latest).isEmpty())
    }

    @Test fun incompatibleStaleAndWrongSessionCannotHideAnEdit() {
        val cell = InkCell(address, address.bounds(), v,
            listOf(source(InkSourceKind.STALE), source(InkSourceKind.SNAPSHOT, 3)), 3)
        assertEquals(InkSourceKind.SNAPSHOT, InkRenderPolicy.plan<String, Int>(listOf(cell), true).sources.single().kind)
        assertFalse(InkRenderPolicy.plan<String, Int>(listOf(cell.copy(sources = listOf(
            source(InkSourceKind.EXACT, 3).copy(address = address.copy(session = 2))))), true).covered)
    }

    private class ManualExecutor : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work += command }
        fun run() { while (work.isNotEmpty()) work.removeFirst().run() }
    }

    @Test fun schedulerBoundsDedupeAndNewestViewportWins() {
        val executor = ManualExecutor(); val scheduler = InkTileScheduler(executor)
        val ran = mutableListOf<Int>()
        repeat(100) { i -> scheduler.request(InkWorkKey(address.copy(x = i), v), 1, 0f, i.toLong()) { ran += i } }
        assertEquals(32, scheduler.queued()); assertEquals(1, executor.work.size)
        assertFalse(scheduler.request(InkWorkKey(address.copy(x = 99), v), 1, 0f, 100) { error("duplicate") })
        executor.run()
        assertEquals(99, ran.first()); assertEquals(32, ran.size); assertEquals(1, scheduler.deduped)
    }

    @Test fun cancelledKeyCanBeRescheduledAndOldTokenCannotPublish() {
        val executor = ManualExecutor(); val scheduler = InkTileScheduler(executor)
        val key = InkWorkKey(address, v); var published = 0
        scheduler.request(key, 0, 0f, 0) { old ->
            scheduler.clear()
            assertTrue(old.cancelled)
            scheduler.request(key, 0, 0f, 1) { if (!it.cancelled) published++ }
            if (!old.cancelled) published++
        }
        executor.run(); assertEquals(1, published)
    }

    @Test fun trimDeleteDetachAndFailureDrainSafely() {
        for (mode in 0..3) {
            val executor = ManualExecutor(); val scheduler = InkTileScheduler(executor)
            var ran = false
            scheduler.request(InkWorkKey(address, v), 1, 0f, 0) { ran = true }
            when (mode) { 0 -> scheduler.clear(); 1 -> scheduler.cancelSession(1); 2 -> scheduler.close(); else -> scheduler.retain(emptySet()) }
            executor.run(); assertFalse(ran)
        }
        val executor = ManualExecutor(); val scheduler = InkTileScheduler(executor); var recovered = false
        scheduler.request(InkWorkKey(address, v), 0, 0f, 0) { error("raster failed") }
        scheduler.request(InkWorkKey(address.copy(x = 1), v), 1, 0f, 0) { recovered = true }
        executor.run(); assertTrue(recovered)
    }

    @Test fun memoryReservationsAndResourceOwnership() {
        val budget = InkMemoryBudget(1000)
        var recycled = 0
        val rejected = InkOwnedResource("unpublished", budget.reserve(250, InkMemoryPool.WORK)!!) { recycled++ }
        assertNull(budget.reserve(1, InkMemoryPool.WORK)); rejected.release(); assertEquals(1, recycled)
        val published = InkOwnedResource("published", budget.reserve(500, InkMemoryPool.DETAIL)!!) { recycled++ }
        published.publish(); published.retain(); published.release()
        assertEquals(500, budget.total()); published.release(); assertEquals(0, budget.total())
        assertEquals(1, recycled)
        assertNotNull(budget.reserve(250, InkMemoryPool.COVERAGE))
        assertNull(budget.reserve(501, InkMemoryPool.DETAIL))
    }

    @Test fun coveragePinsLastScreenAndCoordinates() {
        val gate = InkCoverageGate<Pair<String, Int>>()
        assertNull(gate.choose("initial" to 1, false))
        assertEquals("ready" to 1, gate.choose("ready" to 1, true))
        assertEquals("ready" to 1, gate.choose("next" to 2, false))
        assertTrue(gate.preparing)
        assertEquals("next" to 2, gate.choose("next" to 2, true))
        gate.clear(); assertNull(gate.last)
    }

    @Test fun incrementalIndexRetainsOrderAcrossUndoMoveAndProperties() {
        fun record(value: Int) = InkStrokeRecord(value, InkRect(value.toFloat(), 0f, value + 1f, 1f), InkLayer.PEN)
        val store = InkStrokeStore(listOf(1, 2, 3), ::record)
        store.removeAt(1); store.add(1, 2); store[2] = 4
        assertEquals(listOf(1, 2, 4), store.query(InkRect(0f, 0f, 10f, 10f)).map { it.value })
        val changes = store.drainChanges()
        assertNotNull(changes[0].before); assertNull(changes[0].after)
        assertNotNull(changes[2].before); assertNotNull(changes[2].after)
        assertTrue(store.drainChanges().isEmpty())
    }
}
