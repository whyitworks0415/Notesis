package com.notesis

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * The bend.
 *
 * Blurring what is behind a panel makes frosted plastic. What makes glass is
 * that the edge *moves* what is behind it: a thick lens pushes the background
 * outward as you approach the rim, and the further in you look the less it
 * moves, until the middle is simply the background again. That displacement is
 * this shader, and it is the difference between the two.
 *
 * Written against the rounded rectangle rather than sampled from a normal map,
 * because the shape is known: the distance to the edge of a rounded box has a
 * closed form, and its gradient is the direction to push.
 */
private const val REFRACTION_AGSL = """
uniform shader backdrop;
// Where this pane sits inside the recorded backdrop, and how big it is. The
// shader runs in the layer's coordinates, not the pane's.
uniform float2 origin;
uniform float2 size;
uniform float radius;
// How far the edge displaces, in pixels.
uniform float strength;
// The band the displacement falls off over. Thick glass has a wide one.
uniform float depth;
// How far the channels separate as they bend.
uniform float dispersion;

// Signed distance to a rounded box, negative inside.
float boxDistance(float2 p, float2 half, float r) {
    float2 q = abs(p) - half + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 half = size * 0.5;
    float2 p = coord - origin - half;
    float d = boxDistance(p, half, radius);

    // Inside the glass, d is negative and grows toward zero at the rim. The
    // bend is strongest at the rim and gone by the time it is `depth` deep.
    float into = clamp(1.0 + d / max(depth, 1.0), 0.0, 1.0);
    // Squared, so the middle stays flat and the bend gathers at the edge -
    // a lens, rather than a dome.
    float amount = into * into;

    // The direction to push: the gradient of the distance field, which for a
    // rounded box is just the direction away from the nearest edge.
    float2 dir = normalize(p + float2(0.0001, 0.0001));

    float2 offset = dir * amount * strength;
    if (dispersion <= 0.0) {
        return backdrop.eval(coord + offset);
    }
    // Colours bend by slightly different amounts, the way they do through a
    // prism. Red least, blue most.
    float spread = dispersion * amount * strength * 0.5;
    half r = backdrop.eval(coord + offset - dir * spread).r;
    half g = backdrop.eval(coord + offset).g;
    half b = backdrop.eval(coord + offset + dir * spread).b;
    return half4(r, g, b, backdrop.eval(coord + offset).a);
}
"""

/**
 * The refraction as a RenderEffect, or null where it cannot run. AGSL needs
 * Android 13; below that the glass keeps its edge and loses only the bend.
 */
fun refractionEffect(
    originX: Float,
    originY: Float,
    widthPx: Float,
    heightPx: Float,
    radiusPx: Float,
    strengthPx: Float,
    depthPx: Float,
    dispersion: Float,
    blurPx: Float,
    vibrancy: Float,
): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    if (widthPx < 1f || heightPx < 1f) return null
    val bending = strengthPx > 0.1f
    val lifting = vibrancy > 0.01f
    if (!bending && blurPx < 0.1f && !lifting) return null

    // The blur goes underneath: the shader samples an already-softened
    // backdrop, so the bend carries blurred pixels rather than sharp ones
    // smeared into streaks.
    val blur = if (blurPx >= 0.1f) {
        RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
    } else {
        null
    }
    // Blurring alone turns colour to mud. Real glass keeps it, so the blurred
    // backdrop gets its saturation lifted before anything else touches it.
    val lift = if (lifting) {
        val saturated = android.graphics.ColorMatrix().apply { setSaturation(1f + vibrancy) }
        val filter = android.graphics.ColorMatrixColorFilter(saturated)
        val effect = RenderEffect.createColorFilterEffect(filter)
        if (blur == null) effect else RenderEffect.createChainEffect(effect, blur)
    } else {
        blur
    }
    if (!bending) return lift?.asComposeRenderEffect()

    val shader = RuntimeShader(REFRACTION_AGSL).apply {
        setFloatUniform("origin", originX, originY)
        setFloatUniform("size", widthPx, heightPx)
        setFloatUniform("radius", radiusPx)
        setFloatUniform("strength", strengthPx)
        setFloatUniform("depth", depthPx)
        setFloatUniform("dispersion", dispersion)
    }
    val bend = RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
    val chained = if (lift == null) bend else RenderEffect.createChainEffect(bend, lift)
    return chained.asComposeRenderEffect()
}
