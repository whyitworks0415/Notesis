package com.notesis

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.graphics.RenderEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.effect
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A lens drawn the way QWEA0/Liquid-Glass-Android's GlassLensRenderer draws
 * it, in one AGSL pass over the page Backdrop has already blurred: a rounded
 * rectangle's signed distance gives the bevel and its normal; the page is
 * pulled in along the normal by a profile across the bevel (inverse power,
 * or a square slope), each colour channel by a little more or less for the
 * dispersion; then the saturation curve, the tint, the dimming layer, the
 * body colour and two lobes of light along the rim.
 *
 * Shader adapted from QWEA0/Liquid-Glass-Android (MIT, Copyright (c) 2025-2026
 * pandadog): the merge with a second shape and the touch bulge are left out;
 * edge absorption, body shade and an overall opacity are added so the same
 * pass draws liquid_glass_widgets' iOS material too (see [LiquidStyle.IOS]).
 */
private const val LIQUID_LENS_AGSL = """
uniform shader content;
uniform float  margin;
uniform float2 viewSize;
uniform float4 radii;
uniform float  rimSoft;
uniform float  bevel;
uniform float  refractPx;
uniform float  falloff;
uniform float  refractDir;
uniform float2 sampleLo;
uniform float2 sampleHi;
uniform float  dispersion;
uniform float2 lightDir;
uniform float  specStrength;
uniform float  rimBandMax;
uniform float4 tintColor;
uniform float  adaptiveTint;
uniform float4 glassTint;
uniform float  dimAmount;
uniform float  satFactor;
uniform float  edgeAbsorb;
uniform float  bodyShade;
uniform float  outAlpha;

float sdRoundedBox4(float2 p, float2 b, float4 r) {
    float rx = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);
    float2 q = abs(p) - b + rx;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rx;
}

float lensSDF(float2 p) {
    return sdRoundedBox4(p - viewSize * 0.5, viewSize * 0.5, radii);
}

half4 main(float2 coord) {
    float2 p = coord - float2(margin, margin);
    if (p.x < 0.0 || p.y < 0.0 || p.x > viewSize.x || p.y > viewSize.y) {
        return half4(0.0);
    }
    float d = lensSDF(p);
    float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
    if (cov <= 0.004) {
        return half4(0.0);
    }
    float2 n = float2(
        lensSDF(p + float2(1.0, 0.0)) - lensSDF(p - float2(1.0, 0.0)),
        lensSDF(p + float2(0.0, 1.0)) - lensSDF(p - float2(0.0, 1.0))
    );
    float nLen = length(n);
    n = nLen > 0.0001 ? n / nLen : float2(0.0, -1.0);

    float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
    float edge = 1.0 - t;
    float slope;
    if (falloff > 0.001) {
        float gB = pow(5.0, -falloff);
        slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB);
    } else {
        slope = edge * edge;
    }
    float2 offset = n * (refractDir * slope * refractPx);

    float2 cR = clamp(coord + offset * (1.0 - dispersion * slope), sampleLo, sampleHi);
    float2 cG = clamp(coord + offset, sampleLo, sampleHi);
    float2 cB = clamp(coord + offset * (1.0 + dispersion * slope), sampleLo, sampleHi);
    float3 col;
    if (rimSoft > 0.01 && slope > 0.001) {
        float2 sm = n * (rimSoft * slope);
        col = float3(
            (content.eval(cR).r + content.eval(clamp(cR - sm, sampleLo, sampleHi)).r + content.eval(clamp(cR + sm, sampleLo, sampleHi)).r) / 3.0,
            (content.eval(cG).g + content.eval(clamp(cG - sm, sampleLo, sampleHi)).g + content.eval(clamp(cG + sm, sampleLo, sampleHi)).g) / 3.0,
            (content.eval(cB).b + content.eval(clamp(cB - sm, sampleLo, sampleHi)).b + content.eval(clamp(cB + sm, sampleLo, sampleHi)).b) / 3.0
        );
    } else {
        col = float3(content.eval(cR).r, content.eval(cG).g, content.eval(cB).b);
    }

    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    if (satFactor <= 1.0) {
        col = mix(float3(lum), col, satFactor);
    } else {
        float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
        float room = 1.0 - smoothstep(0.2, 0.85, satNow);
        float hl = 1.0 - smoothstep(0.75, 0.98, lum);
        float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);
        col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));
    }

    if (adaptiveTint > 0.5) {
        float lumT = dot(col, float3(0.2126, 0.7152, 0.0722));
        float e = smoothstep(0.35, 0.75, lumT);
        col = mix(col, float3(1.0 - e), 0.14 + 0.08 * e);
    } else {
        col = mix(col, tintColor.rgb, tintColor.a);
    }
    col = col * (1.0 - dimAmount) * (1.0 - bodyShade);
    col = col * (1.0 - edgeAbsorb * slope);

    if (glassTint.a > 0.002) {
        float lumTint = dot(col, float3(0.2126, 0.7152, 0.0722));
        float3 absorbed = col * mix(float3(1.0), glassTint.rgb, 0.85);
        float3 scattered = glassTint.rgb * (0.38 * (1.0 - lumTint));
        col = mix(col, clamp(absorbed + scattered, float3(0.0), float3(1.0)), glassTint.a);
    }

    float facing = dot(n, -lightDir);
    float lobeF = pow(max(facing, 0.0), 4.5);
    float lobeB = pow(max(-facing, 0.0), 4.5);
    float bandW = clamp(bevel * 0.3, 2.0, rimBandMax);
    float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
    float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5) * cov;
    float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0) * cov;
    float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF) * specStrength;

    col = clamp(col, float3(0.0), float3(1.0));
    // The page through the glass at [outAlpha]; the light on the rim added over it in full.
    return half4(half3(col * cov * outAlpha + float3(spec)), half(cov * outAlpha));
}
"""

/**
 * One frame's worth of the lens, in pixels. [adaptiveScale] is
 * LiquidGlassView's adaptiveLensScale: on a pane under 110 dp the bevel is
 * held to 0.3 of its short side and the bending to 0.7 of it, less again the
 * smaller it is. Without it the bending is still held to half the short side,
 * as liquid_glass_widgets holds it, so no sample comes from past the far edge.
 */
internal class LiquidLensParams(
    val cornerRadius: Float,
    val bevel: Float,
    val refract: Float,
    val falloff: Float = 0f,
    val outward: Boolean = false,
    val noFold: Boolean = false,
    val adaptiveScale: Boolean = false,
    val rimSoft: Float = 0f,
    val dispersion: Float = 0f,
    val lightX: Float = 0.866f,
    val lightY: Float = 0.5f,
    val spec: Float = 0f,
    val tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    val adaptiveTint: Boolean = false,
    val glassTint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    val dim: Float = 0f,
    val saturation: Float = 1f,
    val edgeAbsorb: Float = 0f,
    val bodyShade: Float = 0f,
    val opacity: Float = 1f,
)

/** The lens of [p] over what Backdrop has drawn so far; nothing below Android 13. */
internal fun BackdropEffectScope.liquidLens(p: LiquidLensParams) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val w = size.width
    val h = size.height
    if (!(w > 1f) || !(h > 1f)) return
    val short = min(w, h)
    var bevel = p.bevel
    var refract = p.refract
    var band = 6f
    if (p.adaptiveScale) {
        val ref = 110.dp.toPx()
        bevel = min(bevel, short * 0.3f).coerceAtLeast(2f)
        refract = min(refract, 0.7f * short * min(1f, short / ref))
        band = min(6f, short * 0.05f).coerceAtLeast(2f)
    }
    refract = min(refract, 0.5f * short)
    if (p.noFold) {
        // The largest bending that keeps the samples in order across the bevel.
        val cap = if (p.falloff > 0.001f) {
            val gB = 5f.pow(-p.falloff)
            bevel * (1f - gB) / (4f * p.falloff)
        } else bevel * 0.5f
        refract = min(refract, cap)
    }
    // Bent outwards, the lens reads past its own edge: keep that much of the page.
    if (p.outward && padding < refract) padding = refract
    val margin = padding
    val r = p.cornerRadius.coerceIn(0f, short / 2f)
    val lightLen = sqrt(p.lightX * p.lightX + p.lightY * p.lightY).takeIf { it > 1e-4f } ?: 1f
    // A shader that fails to build on some GPU leaves the page unbent rather than the app closed.
    val shader = runCatching { obtainRuntimeShader("NotesisLiquidLens", LIQUID_LENS_AGSL).apply {
        setFloatUniform("margin", margin)
        setFloatUniform("viewSize", w, h)
        setFloatUniform("radii", r, r, r, r)
        setFloatUniform("rimSoft", p.rimSoft)
        setFloatUniform("bevel", bevel)
        setFloatUniform("refractPx", refract)
        setFloatUniform("falloff", p.falloff)
        setFloatUniform("refractDir", if (p.outward) 1f else -1f)
        setFloatUniform("sampleLo", 0f, 0f)
        setFloatUniform("sampleHi", w + 2f * margin - 1f, h + 2f * margin - 1f)
        setFloatUniform("dispersion", p.dispersion.coerceIn(0f, 0.9f))
        setFloatUniform("lightDir", p.lightX / lightLen, p.lightY / lightLen)
        setFloatUniform("specStrength", p.spec)
        setFloatUniform("rimBandMax", band)
        setFloatUniform("tintColor", p.tint.red, p.tint.green, p.tint.blue, p.tint.alpha)
        setFloatUniform("adaptiveTint", if (p.adaptiveTint) 1f else 0f)
        setFloatUniform("glassTint", p.glassTint.red, p.glassTint.green, p.glassTint.blue, p.glassTint.alpha)
        setFloatUniform("dimAmount", p.dim)
        setFloatUniform("satFactor", p.saturation)
        setFloatUniform("edgeAbsorb", p.edgeAbsorb)
        setFloatUniform("bodyShade", p.bodyShade)
        setFloatUniform("outAlpha", p.opacity.coerceIn(0f, 1f))
    } }.getOrNull() ?: return
    effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
}

/**
 * Where the light comes from, as LiquidGlassView's LightSourceController
 * works it out: from the top left at rest - (0.866, 0.5), pointing the way
 * the light travels, y down - and, while [listen] is on, from wherever "up"
 * is as the device tilts, low-pass filtered. Shared by every pane.
 */
internal object GravityLight : SensorEventListener {
    const val DEFAULT_X = 0.866f
    const val DEFAULT_Y = 0.5f
    private const val SMOOTHING = 0.14f
    private const val NOTIFY = 0.004f

    val direction = mutableStateOf(DEFAULT_X to DEFAULT_Y)
    private var x = DEFAULT_X
    private var y = DEFAULT_Y
    private var manager: SensorManager? = null
    private var users = 0
    private var rotation: () -> Int = { android.view.Surface.ROTATION_0 }

    fun listen(context: Context, display: () -> Int) {
        users++
        rotation = display
        if (users > 1) return
        val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        manager = sm
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        manager?.unregisterListener(this)
        manager = null
        x = DEFAULT_X; y = DEFAULT_Y
        direction.value = DEFAULT_X to DEFAULT_Y
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        val (ux, uy) = when (rotation()) {
            android.view.Surface.ROTATION_90 -> -gy to gx
            android.view.Surface.ROTATION_180 -> -gx to -gy
            android.view.Surface.ROTATION_270 -> gy to -gx
            else -> gx to gy
        }
        val planar = sqrt(ux * ux + uy * uy)
        val tilt = (planar / SensorManager.GRAVITY_EARTH).coerceIn(0f, 1f)
        var tx = DEFAULT_X
        var ty = DEFAULT_Y
        if (planar > 0.5f) {
            tx = -ux / planar * tilt + DEFAULT_X * (1f - tilt)
            ty = uy / planar * tilt + DEFAULT_Y * (1f - tilt)
        }
        val len = sqrt(tx * tx + ty * ty)
        if (len > 1e-4f) { tx /= len; ty /= len }
        var nx = x + (tx - x) * SMOOTHING
        var ny = y + (ty - y) * SMOOTHING
        val nLen = sqrt(nx * nx + ny * ny)
        if (nLen > 1e-4f) { nx /= nLen; ny /= nLen }
        x = nx; y = ny
        val (ox, oy) = direction.value
        if (kotlin.math.abs(nx - ox) > NOTIFY || kotlin.math.abs(ny - oy) > NOTIFY) direction.value = nx to ny
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
