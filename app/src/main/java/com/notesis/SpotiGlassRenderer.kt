package com.notesis

import android.content.Context
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorFilter
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// The surface behind a nested bar, without that bar or its moving lens.
internal val LocalSpotiGlassHostBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/** Ported continuous outline; the path and the upstream shader share a curve. */
internal fun spotiGlassPath(size: Size, radius: Float): Path {
    if (size.width <= 0f || size.height <= 0f) return Path()
    val w = size.width; val h = size.height
    val r = radius.coerceIn(0f, minOf(w, h) / 2)
    if (r < 0.5f) return Path().apply { addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }
    fun shoulder(half: Float): Double {
        val t = ((half - r) / r).coerceIn(0f, 1f).toDouble()
        return minOf(0.2893 * t.pow(0.6), t)
    }
    val sx = shoulder(w / 2); val sy = shoulder(h / 2)
    val rx = r * (1 + sx); val ry = r * (1 + sy)
    val nx = 2 / (2 + 0.7198 * sx / 0.2893)
    val ny = 2 / (2 + 0.7198 * sy / 0.2893)
    val steps = ceil(sqrt(0.103 * maxOf(rx, ry) / 0.05)).toInt().coerceIn(3, 40) * 2
    return Path().apply {
        var first = true
        listOf(Triple(-1, -1, true), Triple(1, -1, false), Triple(1, 1, true), Triple(-1, 1, false))
            .forEach { (xSign, ySign, forward) ->
                for (i in 0..steps) {
                    val t = i.toDouble() / steps
                    val angle = (if (forward) t else 1 - t) * PI / 2
                    val x = (w / 2 + xSign * (w / 2 - rx + rx * cos(angle).coerceIn(0.0, 1.0).pow(nx))).toFloat()
                    val y = (h / 2 + ySign * (h / 2 - ry + ry * sin(angle).coerceIn(0.0, 1.0).pow(ny))).toFloat()
                    if (first) { moveTo(x, y); first = false } else lineTo(x, y)
                }
            }
        close()
    }
}

internal class SpotiGlassShape(private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(spotiGlassPath(size, with(density) { radius.toPx() }))
}

internal data class SpotiGlassLensFrame(
    val center: Offset,
    val size: Size,
    val radius: Float,
    val scale: Offset = Offset(1f, 1f),
    val progress: Float = 1f,
    val presence: Float = 1f,
    val fill: Color = Color.Transparent,
    val distortion: Float = 0.04f,
    val band: Float = 12f,
    val dispersion: Float = 0.002f,
    val borderWidth: Float = 0.5f,
)

/** Uniform packing follows packLiquidGlassUniforms, with pixel-space sampling. */
@RequiresApi(33)
internal class SpotiGlassShader(context: Context) {
    val shader = RuntimeShader(context.assets.open("spotiglass/liquid_glass.agsl").bufferedReader().use { it.readText() })
    // Uniform changes reuse this effect; allocating it on every frame stalls Skia.
    val effect = RenderEffect.createRuntimeShaderEffect(shader, "u_texture_input").asComposeRenderEffect()
    fun update(resolution: Size, frame: SpotiGlassLensFrame, density: Float) {
        val p = frame.progress.coerceIn(0f, 1f)
        val presence = frame.presence.coerceIn(0f, 1f)
        val rim = ((presence - 0.45f) / 0.55f).coerceIn(0f, 1f)
        shader.setFloatUniform("u_resolution", resolution.width, resolution.height)
        shader.setFloatUniform("u_touch", frame.center.x - frame.size.width / 2, frame.center.y - frame.size.height / 2)
        shader.setFloatUniform("u_lensGeom", frame.size.width, frame.size.height, frame.radius, 2f)
        shader.setFloatUniform("u_warp", 1f, frame.distortion * p, frame.band * density * p * presence / frame.scale.x, 0f)
        shader.setFloatUniform("u_diagonalFlip", 0f)
        shader.setFloatUniform("u_borderWidth", frame.borderWidth * 2 * density * p * rim)
        shader.setFloatUniform("u_borderSoftness", 0f)
        shader.setFloatUniform("u_borderColor", 0f, 0f, 0f, 0f)
        shader.setFloatUniform("u_borderAlpha", 1f)
        shader.setFloatUniform("u_lightIntensity", 1f)
        shader.setFloatUniform("u_lightColor", 1f, 1f, 1f, 178f / 255)
        shader.setFloatUniform("u_shadowColor", 0f, 0f, 0f, 0f)
        shader.setFloatUniform("u_lightDirection", 0f)
        shader.setFloatUniform("u_lensColor", frame.fill.red, frame.fill.green, frame.fill.blue, frame.fill.alpha * (1 - p))
        shader.setFloatUniform("u_packA", 0f, frame.dispersion * p, 1f, 0f)
        shader.setFloatUniform("u_packB", 0f, 0f, 1f, 1f)
        shader.setFloatUniform("u_packC", 0f, 1f, 0f, 1f)
        shader.setFloatUniform("u_lightSpread", 0.5f)
        shader.setFloatUniform("u_imageOffset", 0f, 0f)
        shader.setFloatUniform("u_imageSize", resolution.width, resolution.height)
        shader.setFloatUniform("u_honorBackdropAlpha", 0f)
        shader.setFloatUniform("u_shapeAaPx", density)
        shader.setFloatUniform("u_shapeScale", frame.scale.x, frame.scale.y)
        shader.setFloatUniform("u_xformRow", 1f, 0f, 0f, 1f)
        shader.setFloatUniform("u_xformOff", 0f, 0f)
    }
}

@Composable
internal fun Modifier.spotiGlassLens(enabled: Boolean, active: () -> Boolean = { true },
    frame: (Size, Float) -> SpotiGlassLensFrame): Modifier {
    if (Build.VERSION.SDK_INT < 33) return this
    val context = LocalContext.current
    // A settled bar draws no capture/effect, but retains its compiled program
    // for the next gesture. Reduced effects never initialize this lazy value.
    val compiled = remember(context) { lazy { SpotiGlassShader(context) } }
    if (!enabled) return this
    val capture = rememberGraphicsLayer()
    return drawWithContent {
        if (size.width <= 0f || size.height <= 0f || !active()) { drawContent(); return@drawWithContent }
        val shader = compiled.value
        shader.update(size, frame(size, density), density)
        capture.renderEffect = shader.effect
        capture.record { this@drawWithContent.drawContent() }
        drawLayer(capture)
    }
}

/** MornyeGlassSurface + MornyeGlassRim from SpotiFLAC, with its tint/blur rules. */
@Composable
internal fun Modifier.spotiGlassSurface(
    shape: Shape,
    settings: SkinSettings,
    navigation: Boolean = false,
    elevation: Dp = 3.dp,
    rim: Boolean = true,
    exportedBackdrop: LayerBackdrop? = null,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val backdrop = LocalLiquidGlassBackdrop.current
    val clarity = settings.spotiglassClarity.coerceIn(0f, 1f)
    val useBlur = rememberLiquidGlassEffectsAllowed() && !settings.highContrast && clarity > 0 && backdrop != null
    val sigma = when {
        clarity <= 0.25f -> 4f
        clarity <= 0.50f -> 8f
        clarity <= 0.75f -> 12f
        else -> 18f
    } * (settings.blur / 20f)
    val baseOpacity = if (navigation) { if (dark) 0.44f else 0.54f } else { if (dark) 0.60f else 0.70f }
    val baseTint = scheme.surfaceContainerHigh.copy(alpha = baseOpacity * if (dark) 0.82f else 0.88f)
    val tint = if (useBlur) scheme.surfaceContainerHigh.copy(alpha = 1 - clarity).compositeOver(baseTint)
        else scheme.surfaceContainerHigh
    val dimFilter = remember {
        ColorMatrixColorFilter(floatArrayOf(
            0.92559f, -0.25032f, -0.02527f, 0f, 0f,
            -0.07441f, 0.74968f, -0.02527f, 0f, 0f,
            -0.07441f, -0.25032f, 0.97473f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }
    var result = this.shadow(elevation, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = if (dark) 0.14f else 0.08f),
        spotColor = Color.Black.copy(alpha = if (dark) 0.14f else 0.08f))
    result = if (useBlur) result.drawBackdrop(backdrop!!, shape = { shape }, effects = {
        blur(sigma * density)
        if (dark) colorFilter(dimFilter)
    }, highlight = null, shadow = null, exportedBackdrop = exportedBackdrop, onDrawSurface = { drawRect(tint) })
    else result.background(tint, shape)
    if (rim) result = result.drawWithContent {
        drawContent()
        fun edge(insetDp: Float, widthDp: Float, brush: Brush) {
            val inset = insetDp * density
            if (size.width <= inset * 2 || size.height <= inset * 2) return
            val outline = shape.createOutline(Size(size.width - inset * 2, size.height - inset * 2), layoutDirection, this)
            withTransform({ translate(inset, inset) }) {
                when (outline) {
                    is Outline.Generic -> drawPath(outline.path, brush, style = Stroke(widthDp * density))
                    is Outline.Rounded -> drawRoundRect(brush, cornerRadius = androidx.compose.ui.geometry.CornerRadius(outline.roundRect.topLeftCornerRadius.x), style = Stroke(widthDp * density))
                    is Outline.Rectangle -> drawRect(brush, style = Stroke(widthDp * density))
                }
            }
        }
        edge(0.25f, 0.5f, androidx.compose.ui.graphics.SolidColor(Color.Black.copy(alpha = if (useBlur) 0.10f else 0.22f)))
        if (useBlur) edge(0.8f, 0.6f, Brush.verticalGradient(
            0f to Color(0x2EFFFFFF), 0.14f to Color(0x08FFFFFF), 0.5f to Color(0x0A000000),
            0.86f to Color(0x08FFFFFF), 1f to Color(0x1FFFFFFF)))
    }
    return result
}
