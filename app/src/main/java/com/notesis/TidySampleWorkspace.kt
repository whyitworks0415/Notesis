package com.notesis

/** Bounded per-worker storage; a nested operation gets its own arrays. */
internal class TidySampleWorkspace {
    var xs = FloatArray(0); private set
    var ys = FloatArray(0); private set
    var times = LongArray(0); private set
    internal var busy = false
    internal fun reserve(count: Int) {
        if (xs.size >= count) return
        val size = maxOf(count, (xs.size * 2).coerceAtLeast(128))
        xs = FloatArray(size); ys = FloatArray(size); times = LongArray(size)
    }
}

internal object TidySamples {
    private val local = ThreadLocal.withInitial { TidySampleWorkspace() }
    fun acquire(count: Int): TidySampleWorkspace {
        require(count >= 0)
        val cached = local.get()
        val workspace = if (cached.busy || count > 16_384) TidySampleWorkspace() else cached
        workspace.reserve(count)
        workspace.busy = true
        return workspace
    }
    fun release(workspace: TidySampleWorkspace) { workspace.busy = false }
}
