package com.notesis

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class InkScalingTest(private val count: Int, private val partial: Boolean) {
    private data class FixtureStroke(val id: Int, val bounds: InkRect, val layer: InkLayer)

    @Test fun preparedOverlappingPageHasTileBoundedInteraction() {
        var forbidCollectionReads = false
        var reads = 0
        val fullCollection = object : AbstractList<FixtureStroke>() {
            override val size: Int get() { check(!forbidCollectionReads); return count }
            override fun get(index: Int): FixtureStroke {
                check(!forbidCollectionReads); reads++
                // Every stroke intersects every visible cell; a spatial query cannot hide the cost.
                return FixtureStroke(index, InkRect(0f, 0f, 1024f, 1024f),
                    if (index % 3 == 0) InkLayer.HIGHLIGHTER else InkLayer.PEN)
            }
        }
        val store = InkStrokeStore(fullCollection) { InkStrokeRecord(it, it.bounds, it.layer) }
        assertTrue(reads >= count)
        val viewport = InkRect(0f, 0f, 1024f, 1024f)
        val addresses = InkLayer.entries.flatMap { InkRenderPolicy.addresses(1, it, 0, viewport) }
        var preparedCandidates = 0
        // This is the actual prepared spatial index path used by the Android raster worker.
        val cells = addresses.mapIndexed { i, address ->
            val candidates = store.query(address.bounds(), address.layer)
            preparedCandidates += candidates.size
            val bitmap = candidates.fold(0L) { sum, record -> sum + record.value.id + 1 }
            InkCell(address, address.bounds(), InkTileVersion(0, 0),
                if (partial && i == 0) emptyList() else listOf(InkSource(address,
                    InkTileVersion(0, 0), address.bounds(), 0, bitmap)))
        }
        assertEquals(count * 4, preparedCandidates)
        val preparedReads = reads
        forbidCollectionReads = true
        repeat(100) {
            val plan = InkRenderPolicy.plan<Long, FixtureStroke>(cells, true)
            assertTrue(plan.vectors.isEmpty())
            assertTrue(plan.sources.size <= 4 * 2)
            assertEquals(if (partial) 1 else 0, plan.missing.size)
            assertTrue(plan.sources.all { it.source.value > 0 })
        }
        assertEquals(preparedReads, reads)
        println("INK fixture strokes=$count partial=$partial candidates=$preparedCandidates cells=4 sources=${if (partial) 7 else 8} interactionVectors=0 fallbackVectors=0 collectionReads=0 uiCandidateCopies=0")
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "strokes={0}, missing={1}")
        fun data() = listOf(500, 1500, 5000, 10000).flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
    }
}
