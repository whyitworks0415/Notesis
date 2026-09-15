package com.notesis

import java.util.IdentityHashMap
import java.math.BigDecimal
import kotlin.math.floor

internal class InkStrokeRecord<T>(val value: T, val bounds: InkRect, val layer: InkLayer,
    var order: BigDecimal = BigDecimal.ZERO)
internal data class InkStrokeChange<T>(val before: InkStrokeRecord<T>?, val after: InkStrokeRecord<T>?,
    val appended: Boolean = false)

/** Prepared at load time, maintained by mutations. Query never synchronizes with a Page collection. */
internal class InkSpatialIndex<T> {
    private val cells = HashMap<Long, MutableSet<InkStrokeRecord<T>>>()
    private val spanning = linkedSetOf<InkStrokeRecord<T>>()
    private fun keys(bounds: InkRect): List<Long> {
        val x0 = floor(bounds.left / 256f).toInt(); val x1 = floor(bounds.right / 256f).toInt()
        val y0 = floor(bounds.top / 256f).toInt(); val y1 = floor(bounds.bottom / 256f).toInt()
        if ((x1.toLong() - x0 + 1) * (y1.toLong() - y0 + 1) > 64) return emptyList()
        return buildList { for (y in y0..y1) for (x in x0..x1) add((x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)) }
    }
    fun add(record: InkStrokeRecord<T>) {
        val keys = keys(record.bounds)
        if (keys.isEmpty()) spanning += record else keys.forEach { cells.getOrPut(it) { linkedSetOf() }.add(record) }
    }
    fun remove(record: InkStrokeRecord<T>) {
        spanning.remove(record)
        keys(record.bounds).forEach { key -> cells[key]?.let { it.remove(record); if (it.isEmpty()) cells.remove(key) } }
    }
    fun query(bounds: InkRect, layer: InkLayer? = null): List<InkStrokeRecord<T>> {
        val found = HashSet<InkStrokeRecord<T>>()
        val x0 = floor(bounds.left / 256f).toInt(); val x1 = floor(bounds.right / 256f).toInt()
        val y0 = floor(bounds.top / 256f).toInt(); val y1 = floor(bounds.bottom / 256f).toInt()
        for (y in y0..y1) for (x in x0..x1) cells[(x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)]?.let(found::addAll)
        found.addAll(spanning)
        return found.filter { (layer == null || layer == it.layer) && it.bounds.intersects(bounds) }.sortedBy { it.order }
    }
}

/** MutableList compatibility for storage/edit code; bounds and change events are captured once per edit. */
internal class InkStrokeStore<T>(initial: Collection<T>, private val describe: (T) -> InkStrokeRecord<T>) : AbstractMutableList<T>() {
    private val values = ArrayList<T>(initial)
    private val records = IdentityHashMap<T, InkStrokeRecord<T>>()
    private val index = InkSpatialIndex<T>()
    private val changes = ArrayList<InkStrokeChange<T>>()
    init { values.forEachIndexed { i, value -> describe(value).also { it.order = i.toBigDecimal(); records[value] = it; index.add(it) } } }
    override val size: Int @Synchronized get(): Int { InkRenderStats.collectionRead(); return values.size }
    @Synchronized override fun get(index: Int): T { InkRenderStats.collectionRead(); return values[index] }
    @Synchronized override fun add(index: Int, element: T) {
        val appended = index == values.size
        val before = values.getOrNull(index - 1)?.let { records.getValue(it).order }
        val after = values.getOrNull(index)?.let { records.getValue(it).order }
        val order = when { before == null -> (after ?: BigDecimal.ZERO) - BigDecimal.ONE; after == null -> before + BigDecimal.ONE; else -> (before + after).divide(BigDecimal(2)) }
        val record = describe(element).also { it.order = order }
        values.add(index, element); records[element] = record; this.index.add(record)
        changes += InkStrokeChange(null, record, appended)
        modCount++
    }
    @Synchronized override fun removeAt(index: Int): T {
        val value = values.removeAt(index)
        val record = records.remove(value)!!; this.index.remove(record)
        changes += InkStrokeChange(record, null); modCount++
        return value
    }
    @Synchronized override fun set(index: Int, element: T): T {
        val old = values.set(index, element)
        val before = records.remove(old)!!; this.index.remove(before)
        val after = describe(element).also { it.order = before.order }
        records[element] = after; this.index.add(after); changes += InkStrokeChange(before, after)
        return old
    }
    @Synchronized fun query(bounds: InkRect, layer: InkLayer? = null): List<InkStrokeRecord<T>> {
        InkRenderStats.candidateQuery()
        return index.query(bounds, layer)
    }
    @Synchronized fun drainChanges(): List<InkStrokeChange<T>> = changes.toList().also { changes.clear() }
    /** Called only while installing a disk load; current values are edits made during that load. */
    @Synchronized fun appendEditsTo(prepared: InkStrokeStore<T>) { values.forEach { prepared.add(it) }; prepared.drainChanges() }
}
