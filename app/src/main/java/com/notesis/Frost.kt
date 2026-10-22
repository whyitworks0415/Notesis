package com.notesis

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Frosted glass: the backdrop softened, with its colour kept.
 *
 * Blur on its own turns whatever is behind a panel to mud, because averaging
 * neighbouring pixels averages their colour too. Real frosted glass does not do
 * that - it scatters the light without draining it - so the saturation is
 * lifted back up before the blur, and the panel reads as glass rather than as a
 * grey card.
 */
fun frostEffect(blurPx: Float, vibrancy: Float): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < 31) return null
    return frostAndroidEffect(blurPx, vibrancy)?.asComposeRenderEffect()
}

@RequiresApi(31)
private fun frostAndroidEffect(blurPx: Float, vibrancy: Float): RenderEffect? {
    val blurring = blurPx >= 0.1f
    val lifting = vibrancy > 0.01f
    if (!blurring && !lifting) return null

    val blur = if (blurring) {
        RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
    } else {
        null
    }
    if (!lifting) return blur

    val filter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1f + vibrancy) })
    val lift = RenderEffect.createColorFilterEffect(filter)
    // The lift goes underneath, so the blur averages colour that is already
    // strong rather than trying to rescue it afterwards.
    return if (blur == null) lift else RenderEffect.createChainEffect(blur, lift)
}
