package com.notesis

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale

/** One response family: contact, selection, then a larger surface arriving. */
internal object MaterialMotion {
    const val PRESS_SCALE = 0.975f
    const val START_SCALE = 0.92f
    const val PRESS = 0.10f
    const val RELEASE = 0.18f
    const val EFFECTS = 0.16f
    const val SELECTION = 0.24f
    const val CONTAINER = 0.30f
    const val EXIT = 0.22f

    /** Raw for PopupGrowth/dialogs, which apply the global speed exactly once. */
    fun <T> rawContainer(): SpringSpec<T> = spring(1f, springStiffness(CONTAINER))
    fun <T> rawExit(): SpringSpec<T> = spring(1f, springStiffness(EXIT))
    fun <T> selection(): SpringSpec<T> = motionSpring(SELECTION, 1f)
    fun <T> effects(): SpringSpec<T> = motionSpring(EFFECTS, 1f)
    fun <T> press(held: Boolean): SpringSpec<T> = motionSpring(if (held) PRESS else RELEASE, 1f)
}

/** Scales rendering only; never delays input, changes layout or shrinks the hit area. */
@Composable
internal fun Modifier.materialPressFeedback(source: InteractionSource, enabled: Boolean = true): Modifier {
    if (LocalSkin.current != Skin.MATERIAL) return this
    val held by source.collectIsPressedAsState()
    val moves = enabled && rememberLiquidGlassEffectsAllowed() && !LocalSkinSettings.current.highContrast
    val scale = animateFloatAsState(if (held && moves) MaterialMotion.PRESS_SCALE else 1f,
        if (moves) MaterialMotion.press(held) else snap(), label = "Material contact")
    return drawWithContent { scale(scale.value) { this@drawWithContent.drawContent() } }
}

internal fun <T> sidebarOpenFor(skin: Skin): SpringSpec<T> =
    if (skin == Skin.MATERIAL) motionSpring(MaterialMotion.CONTAINER, 1f) else sidebarOpenSpring()

internal fun <T> sidebarCloseFor(skin: Skin): SpringSpec<T> =
    if (skin == Skin.MATERIAL) motionSpring(MaterialMotion.EXIT, 1f) else sidebarCloseSpring()

/** A short forward/backward transition, derived from navigation history rather than page order. */
internal fun materialPageDirection(oldDepth: Int, newDepth: Int): Int = if (newDepth >= oldDepth) 1 else -1
