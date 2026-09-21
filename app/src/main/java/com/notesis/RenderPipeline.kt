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
