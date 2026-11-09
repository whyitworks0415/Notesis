package com.notesis

import java.util.IdentityHashMap

internal data class ErasePatch<T>(val removed: List<T>, val removedAt: IntArray,
    val added: List<T>, val addedAt: IntArray)

/** Owns originals and live descendants only; intermediate meshes can be collected during a gesture. */
internal class EraseTransaction<T : Any> {
    private var baseline: List<T>? = null
    private val roots = IdentityHashMap<T,Boolean>()
    private val originals = ArrayList<Pair<T,Int>>()
    private val descendants = IdentityHashMap<T,T>()
    fun record(before: List<T>, old: T, pieces: List<T>) {
        if (baseline == null) baseline = before.toList()
        val root = descendants.remove(old) ?: old
        if (roots.put(root,true) == null) {
            val initial = baseline!!.indexOfFirst { it === root }
            originals += root to if (initial >= 0) initial else before.indexOfFirst { it === old }
        }
        for (piece in pieces) descendants[piece] = root
    }
    fun finish(current: List<T>): ErasePatch<T>? {
        if (originals.isEmpty()) { reset(); return null }
        val ordered = originals.sortedBy { it.second }
        val added = ArrayList<T>(); val slots = ArrayList<Int>()
        for (i in current.indices) if (descendants.containsKey(current[i])) { added += current[i]; slots += i }
        val patch = ErasePatch(ordered.map { it.first }, ordered.map { it.second }.toIntArray(), added, slots.toIntArray())
        reset()
        return patch
    }
    fun remap(replacements: Map<T,T>) {
        if (baseline == null || replacements.isEmpty()) return
        baseline = baseline!!.map { replacements[it] ?: it }
        for (i in originals.indices) {
            val (item,slot) = originals[i]
            originals[i] = (replacements[item] ?: item) to slot
        }
        roots.clear()
        for ((item,_) in originals) roots[item] = true
        val mapped = IdentityHashMap<T,T>()
        for ((piece,root) in descendants) mapped[replacements[piece] ?: piece] = replacements[root] ?: root
        descendants.clear()
        descendants.putAll(mapped)
    }
    fun reset() { baseline=null;roots.clear();originals.clear();descendants.clear() }
}
