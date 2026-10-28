package com.notesis

/**
 * Everything a pen's Brush is made from. Two strokes with equal keys draw
 * with the same Brush object, so going back to a pen, a colour or a zoom used
 * a moment ago finds its brush instead of building a native one as the pen
 * lands. Sizes and epsilons compare as written: a Brush made for 5.0 is not
 * reused for 5.0001.
 */
internal data class BrushKey(
    val tool: Int,
    val colorArgb: Int,
    val size: Float,
    val epsilon: Float,
    val pattern: Int,
    /** The nib's angle for a pen with one, else [Tool.NO_NIB]. */
    val nib: Int = Tool.NO_NIB,
)

/**
 * The last [capacity] values by key, the least recently used dropped first.
 * Small on purpose - a handful of pens and zoom levels - and confined to the
 * UI thread like the brushes it holds.
 */
internal class RecentCache<K : Any, V : Any>(private val capacity: Int) {
    private val entries = object : LinkedHashMap<K, V>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
    }

    operator fun get(key: K): V? = entries[key]

    fun put(key: K, value: V) {
        entries[key] = value
    }

    /** [key]'s value, made by [create] and kept when there is none. */
    inline fun getOrPut(key: K, create: () -> V): V = get(key) ?: create().also { put(key, it) }

    fun clear() = entries.clear()

    val size: Int get() = entries.size
}
