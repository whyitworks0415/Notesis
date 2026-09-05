package com.notesis

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
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

/**
 * A clearer glass whose edge bends the recorded content underneath it.
 *
 * Android has no system Liquid Glass material, so API 33+'s AGSL shader supplies
 * the optical part: pixels close to the boundary are pulled gently toward the
 * middle, with a sub-pixel RGB split where the bend is strongest. Blur remains
 * underneath the lens and is intentionally lighter than ordinary frost.
 */
fun liquidGlassEffect(
    blurPx: Float,
    vibrancy: Float,
    widthPx: Int,
    heightPx: Int,
    bendPx: Float,
): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < 31) return null
    val frost = frostAndroidEffect(blurPx, vibrancy)
    if (Build.VERSION.SDK_INT < 33 || widthPx <= 0 || heightPx <= 0) {
        return frost?.asComposeRenderEffect()
    }

    val shader = RuntimeShader(LIQUID_GLASS_SHADER).apply {
        setFloatUniform("size", widthPx.toFloat(), heightPx.toFloat())
        setFloatUniform("bend", bendPx.coerceAtLeast(0f))
    }
    val lens = RenderEffect.createRuntimeShaderEffect(shader, "content")
    val combined = if (frost == null) lens else RenderEffect.createChainEffect(lens, frost)
    return combined.asComposeRenderEffect()
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

/**
 * Edge refraction only. Surface tint, specular light and bevel are drawn by the
 * composable so labels never pass through this shader and remain razor sharp.
 */
private const val LIQUID_GLASS_SHADER = """
    uniform shader content;
    uniform float2 size;
    uniform float bend;

    half4 main(float2 p) {
        float2 safeSize = max(size, float2(1.0));
        float2 uv = p / safeSize;
        float2 d = uv * 2.0 - 1.0;
        float radial = length(d * float2(0.92, 1.0));
        float edge = smoothstep(0.50, 1.02, radial);
        float invLength = 1.0 / max(length(d), 0.001);
        float2 direction = d * invLength;
        float2 shift = direction * edge * edge * bend;
        float2 sampleAt = clamp(p - shift, float2(0.0), safeSize);

        // Physical glass separates wavelengths very slightly at a steep edge.
        // Kept below a pixel so it reads as energy, never as a colour fringe.
        float chroma = edge * min(bend * 0.07, 0.75);
        half4 base = content.eval(sampleAt);
        half red = content.eval(clamp(sampleAt + direction * chroma, float2(0.0), safeSize)).r;
        half blue = content.eval(clamp(sampleAt - direction * chroma, float2(0.0), safeSize)).b;
        return half4(red, base.g, blue, base.a);
    }
"""
