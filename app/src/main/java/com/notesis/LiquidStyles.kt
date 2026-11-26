package com.notesis

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import org.json.JSONObject

/**
 * Which recipe Liquid glass is drawn with, each taken from where it comes from:
 *
 * - [NOTESIS] is this app's own glass, tuned in the settings above it.
 * - [IOS] is the iOS material as liquid_glass_widgets (MIT) measured it
 *   against the native control - its iOS 27 regular presets - drawn through
 *   a lens with its paraxial rim, with its springs for the press, the
 *   selection and the menus (see [IosGlass]).
 * - [MORNYE] is the glass of the Mornye music player (LGPL-3.0): its tint,
 *   clarity, rim and press, written again here from its numbers rather than
 *   copied (see [MornyeGlass]). It brings its own iOS palette and accent.
 *
 * Saved by [name]. Kyant0's and QWEA0's recipes were here until 0.90.10; a
 * setting saved with either comes back as [NOTESIS].
 */
enum class LiquidStyle(val label: String, val blurb: String) {
    NOTESIS("Liquid glass", "A lens that bends the page. The selection glides after your fingertip."),
    IOS("iOS", "iOS's glass as liquid_glass_widgets measured it, with Mornye's colours, continuous corners and pouring menus."),
    MORNYE("Mornye", "Mornye's glass: a clear tinted body, a fine lit rim, iOS colours and an accent of your choice."),
    ;

    companion object {
        fun fromName(name: String?): LiquidStyle = entries.firstOrNull { it.name == name } ?: NOTESIS
    }
}

/**
 * Whether a recipe's own motion is used - on, the project's; off, this app's
 * as it always was: [press] how a button answers a finger, [slide] how the
 * toolbar's selection travels between tools, [open] how a menu or panel
 * comes out of the button that opened it and goes back into it.
 */
data class ProjectMotion(val press: Boolean = true, val slide: Boolean = true, val open: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("press", press).put("slide", slide).put("open", open)

    companion object {
        fun fromJson(json: JSONObject?): ProjectMotion {
            val d = ProjectMotion()
            if (json == null) return d
            return ProjectMotion(json.optBoolean("press", d.press), json.optBoolean("slide", d.slide), json.optBoolean("open", d.open))
        }
    }
}

private fun JSONObject.f(key: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
    optDouble(key, default.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(range) ?: default

/**
 * liquid_glass_widgets 1.16.1's iOS 27 `glassEffect(.regular)`, light
 * (LiquidGlassSettings.ios27Light): body #F8F8F8 at 0x87, saturation 2.1,
 * blur 0.6, a frost of 14 at 73% over it, thickness 32 with refractive index
 * 1.24 through the paraxial lens, light from the top, no light intensity or
 * Fresnel, no chromatic aberration, edge absorption 0.035, rim shade 1 and
 * rim light 1, and a shadow of black at 0x04, 10 soft, 7 down. The dark
 * appearance (ios27Dark) is the same glass with the body white at 0x1F,
 * saturation 1.4, the frost at 85%, the light from below, rim light 1.15,
 * body shade 0.28 and no shadow; what is set here moves both in proportion.
 *
 * Those are kept as [PRESET]. A fresh install starts from values set by eye
 * on a device against that: no light blur, a frost of 3 at 58%, saturation
 * 3, the body at 58%, rim light 2, rim shade 0.85, edge absorption 0.02 and
 * a 29 dp corner - the same lens, thickness 32 and index 1.24.
 *
 * Thickness is in pixels at 3x, as the presets give it: the bevel is a
 * quarter circle that deep (a third of it in dp), and the paraxial lens bends
 * the page at the rim by thickness × 8 × (index − 1) of those pixels, held to
 * half the pane's short side. Corners are its GlassMenu's: 32 for a menu.
 * Motion is its own: a press grows a button by 17 pt along its longest side
 * (LiquidStretch.nativePressGrowth) on a snappy 250 ms spring and lets go on
 * a bouncy 280 ms one; the selection follows the finger on the interactive
 * spring and lands on a snappy 350 ms one; a menu opens on GlassMorph's
 * spring - stiffness 120, damping 16 - and closes on stiffness 200, damping
 * 21, thrown back at −3.2.
 */
data class IosGlass(
    val thickness: Float = 32f,
    val refractiveIndex: Float = 1.24f,
    /** blur: the light blur of the page under the frost, as a Gaussian sigma in dp. */
    val blur: Float = 0f,
    /** frost: the heavy blur, as a sigma in dp. */
    val frost: Float = 3f,
    val frostOpacity: Float = 0.58f,
    val saturation: Float = 3f,
    /** glassColor's alpha over #F8F8F8. */
    val bodyOpacity: Float = 0.58f,
    val rimLight: Float = 2f,
    val rimShade: Float = 0.85f,
    val edgeAbsorption: Float = 0.02f,
    /** chromaticAberration; 0 in the presets. */
    val dispersion: Float = 0f,
    val corner: Float = 29f,
    /**
     * Where the rim's light comes from, in degrees round the pane: 90 is
     * the top, as ios27Light has it, which lights the top and bottom edges
     * and leaves the two ends faint; 0 and 180 light the ends instead, 45
     * and 135 the corners. The dark preset's light comes from the opposite side.
     */
    val lightAngle: Float = 90f,
    /** The rim lit evenly all the way round rather than where it faces the light. */
    val rimAllRound: Boolean = false,
    /** The colour its theme is built from, as Mornye's: iOS's own blue unless chosen. */
    val accent: MornyeAccent = MornyeAccent.BLUE,
    /** Mornye's continuous corners (see [MornyeSquircleShape]) rather than round ones. */
    val smoothCorners: Boolean = true,
    val motion: ProjectMotion = ProjectMotion(),
    /** Which parts of the glass are drawn at all; see [IosParts]. */
    val parts: IosParts = IosParts(),
    /**
     * The resolution the glass is worked out at, as a share of the screen's:
     * 1, a half or a quarter (see [lowResGlass]). A half by default, the
     * frost being a blur either way.
     */
    val resolution: Float = 0.5f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("parts", parts.toJson()).put("resolution", resolution.toDouble())
        .put("thickness", thickness.toDouble()).put("refractiveIndex", refractiveIndex.toDouble())
        .put("blur", blur.toDouble()).put("frost", frost.toDouble()).put("frostOpacity", frostOpacity.toDouble())
        .put("saturation", saturation.toDouble()).put("bodyOpacity", bodyOpacity.toDouble())
        .put("rimLight", rimLight.toDouble()).put("rimShade", rimShade.toDouble())
        .put("edgeAbsorption", edgeAbsorption.toDouble()).put("dispersion", dispersion.toDouble())
        .put("corner", corner.toDouble()).put("motion", motion.toJson())
        .put("lightAngle", lightAngle.toDouble()).put("rimAllRound", rimAllRound)
        .put("accent", accent.name).put("smoothCorners", smoothCorners)

    /** The bevel's depth in dp. */
    val bevelDp: Float get() = thickness / 3f
    /** How far the rim bends the page, in dp, before it is held to half the short side. */
    val bendDp: Float get() = thickness * 8f * (refractiveIndex - 1f) / 3f

    companion object {
        val THICKNESS_RANGE = 0f..80f
        val INDEX_RANGE = 1f..1.6f
        val BLUR_RANGE = 0f..10f
        val FROST_RANGE = 0f..40f
        val SATURATION_RANGE = 1f..3f
        val LIGHT_ANGLE_RANGE = 0f..180f

        /** ios27Dark against ios27Light, applied to whatever is set. */
        const val DARK_BODY = (0x1F / 255f) / (0x87 / 255f)
        const val DARK_SATURATION = 1.4f / 2.1f
        const val DARK_FROST = 0.85f / 0.73f
        const val DARK_RIM_LIGHT = 1.15f
        const val DARK_BODY_SHADE = 0.28f

        /**
         * 0.76's starting point, the ios27Light preset as it stands. Saved
         * settings still at it take the present defaults: set by eye on the
         * device, clearer and brighter-rimmed than the preset.
         */
        val PRESET = IosGlass(blur = 0.6f, frost = 14f, frostOpacity = 0.73f, saturation = 2.1f,
            bodyOpacity = 0x87 / 255f, rimLight = 1f, rimShade = 1f, edgeAbsorption = 0.035f, corner = 32f)

        fun fromJson(json: JSONObject?): IosGlass {
            val d = IosGlass()
            if (json == null) return d
            val read = readJson(json, d)
            val own = PRESET.copy(motion = read.motion, parts = read.parts, resolution = read.resolution)
            return if (read == own) d.copy(motion = read.motion, parts = read.parts, resolution = read.resolution) else read
        }

        private fun readJson(json: JSONObject, d: IosGlass): IosGlass {
            return IosGlass(
                thickness = json.f("thickness", d.thickness, THICKNESS_RANGE),
                refractiveIndex = json.f("refractiveIndex", d.refractiveIndex, INDEX_RANGE),
                blur = json.f("blur", d.blur, BLUR_RANGE),
                frost = json.f("frost", d.frost, FROST_RANGE),
                frostOpacity = json.f("frostOpacity", d.frostOpacity, 0f..1f),
                saturation = json.f("saturation", d.saturation, SATURATION_RANGE),
                bodyOpacity = json.f("bodyOpacity", d.bodyOpacity, 0f..1f),
                rimLight = json.f("rimLight", d.rimLight, 0f..2f),
                rimShade = json.f("rimShade", d.rimShade, 0f..2f),
                edgeAbsorption = json.f("edgeAbsorption", d.edgeAbsorption, 0f..0.5f),
                dispersion = json.f("dispersion", d.dispersion, 0f..1f),
                corner = json.f("corner", d.corner, SkinSettings.CORNER_RANGE),
                lightAngle = json.f("lightAngle", d.lightAngle, LIGHT_ANGLE_RANGE),
                rimAllRound = json.optBoolean("rimAllRound", d.rimAllRound),
                accent = MornyeAccent.fromName(json.optString("accent", d.accent.name)),
                smoothCorners = json.optBoolean("smoothCorners", d.smoothCorners),
                motion = ProjectMotion.fromJson(json.optJSONObject("motion")),
                parts = IosParts.fromJson(json.optJSONObject("parts")),
                resolution = iosResolution(json.optDouble("resolution", d.resolution.toDouble()).toFloat()),
            )
        }
    }
}

/** The resolutions iOS's glass can be worked out at: the nearest of full, a half and a quarter. */
internal val IOS_RESOLUTIONS = floatArrayOf(1f, 0.5f, 0.25f)

internal fun iosResolution(value: Float): Float =
    if (!value.isFinite()) 0.5f else IOS_RESOLUTIONS.minByOrNull { kotlin.math.abs(it - value) } ?: 0.5f

/**
 * Every part iOS's glass is made of, each one on or off, so the glass can be
 * pared down to what the tablet keeps up with. A part that is off is not
 * drawn at all - not drawn at nothing - so it costs nothing. All on is the
 * glass as it has always been.
 */
data class IosParts(
    /** The page through the lens, unblurred, under the frost. Off, the page shows through as it is. */
    val clearLayer: Boolean = true,
    /** The frost: the lens over a heavy blur, at its opacity. */
    val frostLayer: Boolean = true,
    /** The lens shader itself - the bending at the rim and everything drawn in it. Off, plain blur. */
    val lens: Boolean = true,
    /** The colour lift through the glass. */
    val saturation: Boolean = true,
    /** The bevel darkening towards the edge. */
    val edgeAbsorption: Boolean = true,
    /** The light caught along the rim. */
    val rimLight: Boolean = true,
    /** The fine dark line just inside the edge. */
    val rimShade: Boolean = true,
    /** The shadow the pane casts. */
    val shadow: Boolean = true,
    /** The pale glass colour over it all. */
    val body: Boolean = true,
    /** Round buttons standing on their own (beside the bar, the page's corners) as glass of their own. */
    val buttonGlass: Boolean = true,
    /** Sliders and switches as glass controls that turn to a lens while held. */
    val controlGlass: Boolean = true,
    /** The floating clock as glass. */
    val clockGlass: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("clearLayer", clearLayer).put("frostLayer", frostLayer).put("lens", lens)
        .put("saturation", saturation).put("edgeAbsorption", edgeAbsorption)
        .put("rimLight", rimLight).put("rimShade", rimShade).put("shadow", shadow).put("body", body)
        .put("buttonGlass", buttonGlass).put("controlGlass", controlGlass).put("clockGlass", clockGlass)

    /** Whether the page is sampled at all: with both layers off the pane is a plain tinted one. */
    val samplesPage: Boolean get() = clearLayer || frostLayer

    companion object {
        fun fromJson(json: JSONObject?): IosParts {
            val d = IosParts()
            if (json == null) return d
            fun b(key: String, default: Boolean) = json.optBoolean(key, default)
            return IosParts(
                clearLayer = b("clearLayer", d.clearLayer), frostLayer = b("frostLayer", d.frostLayer),
                lens = b("lens", d.lens), saturation = b("saturation", d.saturation),
                edgeAbsorption = b("edgeAbsorption", d.edgeAbsorption), rimLight = b("rimLight", d.rimLight),
                rimShade = b("rimShade", d.rimShade), shadow = b("shadow", d.shadow), body = b("body", d.body),
                buttonGlass = b("buttonGlass", d.buttonGlass), controlGlass = b("controlGlass", d.controlGlass),
                clockGlass = b("clockGlass", d.clockGlass),
            )
        }
    }
}

/**
 * Whether a part of the chrome is glass: always, unless the look is iOS's
 * and that part has been switched off in its [IosParts].
 */
internal fun SkinSettings.iosPartOn(skin: Skin, part: (IosParts) -> Boolean): Boolean =
    skin != Skin.LIQUID_GLASS || liquidStyle != LiquidStyle.IOS || part(ios.parts)

/** The corner Liquid glass's chrome takes under the recipe in use. */
val SkinSettings.glassCorner: Float
    get() = when (liquidStyle) {
        LiquidStyle.NOTESIS -> corner
        LiquidStyle.IOS -> ios.corner
        LiquidStyle.MORNYE -> mornye.corner
    }

/** The recipe's motion switches; null for this app's own glass, which has only its own. */
val SkinSettings.projectMotion: ProjectMotion?
    get() = when (liquidStyle) {
        LiquidStyle.NOTESIS -> null
        LiquidStyle.IOS -> ios.motion
        LiquidStyle.MORNYE -> mornye.motion
    }

/** Sets [SkinSettings.liquidStyle] and keeps it; provided where the settings live. */
val LocalLiquidStyleChange = staticCompositionLocalOf<(LiquidStyle) -> Unit> { {} }

/**
 * One of the looks a picker offers: a skin, and for Liquid glass which recipe.
 * Five in all - Material, Glassmorphism and Liquid glass's three.
 */
data class Look(val skin: Skin, val style: LiquidStyle = LiquidStyle.NOTESIS) {
    val label: String get() = if (skin == Skin.LIQUID_GLASS) style.label else skin.label
    val blurb: String get() = if (skin == Skin.LIQUID_GLASS) style.blurb else skin.blurb

    companion object {
        val all: List<Look> = Skin.entries.flatMap { skin ->
            if (skin == Skin.LIQUID_GLASS) LiquidStyle.entries.map { Look(skin, it) } else listOf(Look(skin))
        }
    }
}

/** Below this, a pane is a button and takes the button's recipe, where there is one. */
private val BUTTON_SIZE = 64.dp

/**
 * The glass of [style] (not [LiquidStyle.NOTESIS]) on [shape], over
 * [backdrop]. [panel] is a surface with text on it - a menu, a panel, a
 * dialog - rather than a bar; anything under [BUTTON_SIZE] is a button
 * whatever it is called. [shadows] is the app's switch; [rim] whether the
 * edge is lit at all; [amount] fades the whole effect in, as the lens does
 * while a pane morphs. Only called where the lens may run (API 33 and up,
 * effects allowed).
 */
@Composable
internal fun Modifier.styledLiquidGlass(
    style: LiquidStyle,
    settings: SkinSettings,
    shape: Shape,
    backdrop: Backdrop,
    panel: Boolean,
    dark: Boolean,
    shadows: Boolean,
    rim: Boolean = true,
    fullResolution: Boolean = false,
    amount: Float = 1f,
): Modifier = when (style) {
    LiquidStyle.IOS -> iosGlass(settings.ios, if (settings.ios.smoothCorners) mornyeSquircle(shape) else shape,
        backdrop, panel, dark, shadows, rim, fullResolution, amount)
    LiquidStyle.MORNYE -> mornyeGlass(settings.mornye, shape, backdrop, panel, dark, shadows, rim, amount)
    LiquidStyle.NOTESIS -> this
}

/**
 * The corner of [shape] at this size, for a shader that draws its own outline.
 * Before the pane is measured Backdrop's size is unspecified - NaN - and a
 * rounded shape asked for its outline at that size throws, so there is none.
 */
internal fun cornerOf(shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density): Float {
    if (size.isUnspecified || !(size.width > 0f) || !(size.height > 0f)) return 0f
    // A squircle draws as a path but still names its corner, which the lens bends along.
    if (shape is androidx.compose.foundation.shape.CornerBasedShape) {
        return runCatching { shape.topStart.toPx(size, density) }.getOrNull()?.takeIf { it.isFinite() } ?: 0f
    }
    return when (val outline = runCatching { shape.createOutline(size, layoutDirection, density) }.getOrNull()) {
        is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x.takeIf { it.isFinite() } ?: 0f
        else -> 0f
    }
}

/**
 * Two layers, as liquid_glass_widgets composes its iOS 27 material: the page
 * through the lens under a light blur, then the frost - the same lens over a
 * heavy blur - at its opacity, so the page shows through as pale ghosts; the
 * body colour over both, the light caught along the rim, and the rim shade
 * as a hairline just inside the edge.
 */
@Composable
private fun Modifier.iosGlass(
    g: IosGlass,
    shape: Shape,
    backdrop: Backdrop,
    panel: Boolean,
    dark: Boolean,
    shadows: Boolean,
    rim: Boolean,
    fullResolution: Boolean,
    amount: Float,
): Modifier {
    val p = g.parts
    val saturation = if (!p.saturation) 1f
        else 1f + ((if (dark) g.saturation * IosGlass.DARK_SATURATION else g.saturation) - 1f) * amount
    val frostOpacity = ((if (dark) g.frostOpacity * IosGlass.DARK_FROST else g.frostOpacity) * amount).coerceIn(0f, 1f)
    // A white-only dark preset washed out over white paper. Keep a charcoal
    // reading floor; the existing optical passes still draw the curved rim.
    val body = when {
        !p.body -> Color.Transparent
        dark -> iosDarkReadingMaterial(panel, amount, g.bodyOpacity)
        else -> Color(0xFFF8F8F8).copy(alpha = (g.bodyOpacity * amount).coerceIn(0f, 1f))
    }
    val rimLight = if (rim && p.rimLight) (if (dark) g.rimLight * IosGlass.DARK_RIM_LIGHT else g.rimLight) * amount else 0f
    // ios27Light lights from the top (pi / 2), ios27Dark from the bottom; the
    // shader takes the way the light travels: from the angle set, towards
    // the opposite side, turned round in the dark.
    val angle = Math.toRadians(g.lightAngle.toDouble())
    val travelX = (-kotlin.math.cos(angle)).toFloat() * (if (dark) -1f else 1f)
    val lightY = kotlin.math.sin(angle).toFloat() * (if (dark) -1f else 1f)
    val rimEven = if (g.rimAllRound) 1f else 0f
    fun lensParams(scope: com.kyant.backdrop.BackdropEffectScope, opacity: Float, spec: Float) = with(scope) {
        LiquidLensParams(
            cornerRadius = cornerOf(shape, size, layoutDirection, this),
            bevel = g.bevelDp.dp.toPx() * amount,
            refract = g.bendDp.dp.toPx() * amount,
            falloff = 0f,
            dispersion = g.dispersion,
            lightX = travelX, lightY = lightY,
            spec = spec,
            rimEven = rimEven,
            saturation = saturation,
            edgeAbsorb = if (p.edgeAbsorption) g.edgeAbsorption else 0f,
            bodyShade = if (dark) IosGlass.DARK_BODY_SHADE * amount else 0f,
            opacity = opacity,
        )
    }
    // One layer of the glass over a blur of [blurPx]: through the lens, or
    // with the lens off a plain colour filter for its saturation and opacity.
    // Null is the page as it is.
    fun com.kyant.backdrop.BackdropEffectScope.layer(blurPx: Float, opacity: Float, spec: Float): RenderEffect? {
        val blurred = blurEffect(blurPx)
        if (p.lens) lensEffect(lensParams(this, opacity, spec), blurred)?.let { return it }
        return colourEffect(saturation, opacity, blurred)
    }
    // ios27Light's BoxShadow: black at 0x04, 10 soft, 7 down; none in the dark.
    val shadow = if (shadows && !dark && p.shadow)
        Modifier.paneShadow(shape, 10.dp, 7.dp, Color.Black.copy(alpha = 4f / 255f), amount) else Modifier
    val rimShadeMod = if (rim && p.rimShade && g.rimShade > 0f) Modifier.rimShade(shape, g.rimShade * amount, dark) else Modifier
    // The light along the rim, drawn on its own at the screen's resolution
    // (see [iosRimLight]) rather than inside the lens: it stays where the
    // light falls - bright where the edge faces it, faint along the sides -
    // however the glass under it is drawn, whole, blended or at a lower
    // resolution. The lens is then given no light of its own.
    val ownRim = rimLight > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val shade = if (ownRim) rimShadeMod.then(Modifier.iosRimLight(shape, travelX, lightY, rimLight, rimEven,
        g.bevelDp * amount)) else rimShadeMod
    val lensLight = if (ownRim) 0f else rimLight
    if (!p.samplesPage) {
        // Neither layer: a tinted pane that never looks at the page.
        return this.then(shadow).background(body, shape).then(shade)
    }
    // Both layers blended as one effect, whichever way the pane is drawn.
    fun combine(clear: RenderEffect?, frost: RenderEffect?): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return clear
        return when {
            p.clearLayer && p.frostLayer -> if (frost == null) clear else RenderEffect.createBlendModeEffect(
                clear ?: RenderEffect.createOffsetEffect(0f, 0f), frost, android.graphics.BlendMode.SRC_OVER)
            p.frostLayer -> frost
            else -> clear
        }
    }
    if (g.resolution < 1f && !fullResolution && !LabPerformance.efficientNow &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Worked out at a half or a quarter of the screen's resolution (see [lowResGlass]):
        // every length the lens and the blur take is that much shorter in the layer.
        fun paramsAt(f: GlassFrame, opacity: Float, spec: Float) = LiquidLensParams(
            cornerRadius = f.corner * f.scale,
            bevel = g.bevelDp * f.density * amount * f.scale,
            refract = g.bendDp * f.density * amount * f.scale,
            falloff = 0f,
            dispersion = g.dispersion,
            lightX = travelX, lightY = lightY,
            spec = spec,
            rimEven = rimEven,
            saturation = saturation,
            edgeAbsorb = if (p.edgeAbsorption) g.edgeAbsorption else 0f,
            bodyShade = if (dark) IosGlass.DARK_BODY_SHADE * amount else 0f,
            opacity = opacity,
        )
        fun layerAt(f: GlassFrame, blurDp: Float, opacity: Float, spec: Float): RenderEffect? {
            val blurred = blurEffect(blurDp * f.density * amount * f.scale)
            if (p.lens) lensEffectSized(paramsAt(f, opacity, spec), f.width, f.height, 0f,
                110f * f.density * f.scale, blurred)?.let { return it }
            return colourEffect(saturation, opacity, blurred)
        }
        // Keep the effect factory stable when the pane is recomposed for an
        // unrelated reason, so its draw node can keep the installed effect.
        val effect = remember(g, dark, amount, lensLight) {
            { f: GlassFrame ->
                combine(
                    if (p.clearLayer) layerAt(f, g.blur, 1f, 0f) else null,
                    if (p.frostLayer) layerAt(f, g.frost, frostOpacity, lensLight) else null,
                )
            }
        }
        return this.then(shadow).lowResGlass(backdrop, shape, g.resolution, body, effect).then(shade)
    }
    if (LabPerformance.efficientNow) {
        // One sampling/lens pass instead of a clear pass followed by a frosted pass.
        return this.then(shadow).drawBackdrop(backdrop = backdrop, shape = { shape }, effects = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@drawBackdrop
            renderEffect = layer(labBlurDp(g.frost, true).dp.toPx() * amount, 1f, lensLight)
        }, highlight = null, shadow = null, onDrawSurface = { drawRect(body) })
            .then(shade)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // The same two layers, the frost over the clear glass, blended in one
        // pane: the page is recorded, clipped and kept once instead of twice.
        return this.then(shadow).drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@drawBackdrop
                val clear = if (p.clearLayer) layer(g.blur.dp.toPx() * amount, 1f, 0f) else null
                val frost = if (p.frostLayer) layer(g.frost.dp.toPx() * amount, frostOpacity, lensLight) else null
                renderEffect = combine(clear, frost)
            },
            highlight = null,
            shadow = null,
            onDrawSurface = { drawRect(body) },
        ).then(shade)
    }
    // Keep the separate draws on older Android, where the lens cannot run.
    var result = this.then(shadow)
    if (p.clearLayer) result = result.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@drawBackdrop
            renderEffect = layer(g.blur.dp.toPx() * amount, 1f, 0f)
        },
        highlight = null,
        shadow = null,
    )
    if (p.frostLayer) result = result.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@drawBackdrop
            renderEffect = layer(g.frost.dp.toPx() * amount, frostOpacity, lensLight)
        },
        highlight = null,
        shadow = null,
        onDrawSurface = { drawRect(body) },
    ) else result = result.background(body, shape)
    return result.then(shade)
}

/**
 * A colour filter for [saturation] and [opacity] over [input], or [input]
 * itself where neither changes anything: the lens's own colour work, for
 * glass drawn without the lens.
 */
private fun colourEffect(saturation: Float, opacity: Float, input: RenderEffect?): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return input
    if (saturation == 1f && opacity >= 1f) return input
    val matrix = android.graphics.ColorMatrix().apply { setSaturation(saturation) }
    if (opacity < 1f) matrix.postConcat(android.graphics.ColorMatrix().apply { setScale(1f, 1f, 1f, opacity.coerceIn(0f, 1f)) })
    val filter = android.graphics.ColorMatrixColorFilter(matrix)
    return if (input != null) RenderEffect.createColorFilterEffect(filter, input) else RenderEffect.createColorFilterEffect(filter)
}

/** A Gaussian blur of [radius] px as Backdrop's blur makes it, or none for nothing. */
private fun blurEffect(radius: Float): RenderEffect? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radius > 0f)
        RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP) else null

/**
 * The shadow a glass pane casts, drawn round it and never under it, on the
 * pane's round-rectangle outline rather than its own: a blurred round
 * rectangle the GPU draws by itself, where Backdrop's shadow blurred the
 * squircle's path as a mask on the CPU every time the pane was drawn. Under a
 * blur this soft the two corners cannot be told apart.
 */
internal fun Modifier.paneShadow(shape: Shape, radius: Dp, offsetY: Dp, color: Color, alpha: Float): Modifier =
    drawWithCache {
        val corner = cornerOf(shape, size, layoutDirection, this)
        val blur = radius.toPx()
        val dy = offsetY.toPx()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.copy(alpha = (color.alpha * alpha).coerceIn(0f, 1f)).toArgb()
            if (blur > 0f) maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val inside = android.graphics.Path().apply {
            addRoundRect(0f, 0f, size.width, size.height, corner, corner, android.graphics.Path.Direction.CW)
        }
        onDrawWithContent {
            if (paint.alpha > 0 && size.width > 0f && size.height > 0f) drawIntoCanvas { c ->
                val canvas = c.nativeCanvas
                canvas.save()
                canvas.clipOutPath(inside)
                canvas.drawRoundRect(0f, dy, size.width, size.height + dy, corner, corner, paint)
                canvas.restore()
            }
            drawContent()
        }
    }

/** The rim shade: a hairline of shadow just inside the outline, [strength] 1 at the presets. */
private fun Modifier.rimShade(shape: Shape, strength: Float, dark: Boolean): Modifier = drawWithCache {
    val stroke = 0.75.dp.toPx()
    val inner = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f))
    val outline = shape.createOutline(inner, layoutDirection, this)
    val shade = Color.Black.copy(alpha = ((if (dark) 0.28f else 0.10f) * strength).coerceIn(0f, 1f))
    onDrawWithContent {
        drawContent()
        translate(stroke / 2f, stroke / 2f) { drawOutline(outline, shade, style = Stroke(stroke)) }
    }
}
