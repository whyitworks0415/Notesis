package com.notesis

/** Runtime-only state for a page's generated drawing resources. */
enum class RenderState {
    Dirty,
    Rendering,
    ViewportCached,
    Complete,
}

/** A stale request must neither suppress its replacement nor publish its result. */
internal fun shouldEnqueueRender(pendingGeneration: Int?, currentGeneration: Int): Boolean =
    pendingGeneration != currentGeneration

internal fun shouldPublishRender(
    requestGeneration: Int,
    currentGeneration: Int,
    closed: Boolean,
): Boolean = !closed && requestGeneration == currentGeneration

internal fun completedRenderState(loaded: Boolean, allStrokesAtTarget: Boolean): RenderState =
    when {
        !loaded -> RenderState.Dirty
        allStrokesAtTarget -> RenderState.Complete
        else -> RenderState.ViewportCached
    }

internal fun shouldDeferDetail(
    enabled: Boolean,
    viewportInteracting: Boolean,
    zooming: Boolean,
    flinging: Boolean,
): Boolean = enabled && (viewportInteracting || zooming || flinging)

/** A bitmap may cover an unchanged prefix while newer ink is drawn as vectors. */
internal class InkRenderPrefix<T : Any>(val strokes: List<T>) {
    private val included = java.util.IdentityHashMap<T, Boolean>().apply {
        for (stroke in strokes) put(stroke, true)
    }
    private var checkedRevision = Long.MIN_VALUE
    private var checkedSize = -1
    private var valid = false

    fun matches(current: List<T>, revision: Long): Boolean {
        if (checkedRevision != revision || checkedSize != current.size) {
            valid = current.size >= strokes.size &&
                strokes.indices.all { strokes[it] === current[it] }
            checkedRevision = revision
            checkedSize = current.size
        }
        return valid
    }

    fun contains(stroke: T): Boolean = included.containsKey(stroke)
}

/** Keep the pattern anchored to the page when culling off-screen rules/dots. */
internal fun firstVisiblePaperRule(minimum: Float, spacing: Float): Float =
    maxOf(1f, kotlin.math.ceil((minimum - 4f) / spacing)) * spacing
