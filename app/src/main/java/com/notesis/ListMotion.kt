package com.notesis

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * How a card in a grid moves when the cards round it change (Todo.md, A3,
 * A7): deleted, the ones after it slide up into its place; added, a card
 * fades in where it goes and the others make room; sorted or moved, each
 * slides to where it now is. Nothing at all where the chrome holds still, or
 * in Light mode. A scroll is not a change: cards only move for these.
 */
@Composable
internal fun LazyGridItemScope.itemMotion(): Modifier {
    if (!rememberMotionAllowed() || InteractionGovernor.lightMode) return Modifier
    return Modifier.animateItem(
        fadeInSpec = tween(ITEM_FADE_MS),
        placementSpec = Ui.Motion.spring(dampingRatio = 1f, visibilityThreshold = IntOffset.VisibilityThreshold),
        fadeOutSpec = tween(ITEM_FADE_MS),
    )
}

private const val ITEM_FADE_MS = 160
