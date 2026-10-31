package com.notesis

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import org.json.JSONObject

/**
 * Which recipe Liquid glass is drawn with, each taken from where it comes from:
 *
 * - [NOTESIS] is this app's own glass, tuned in the settings above it.
 * - [IOS] is the iOS material as liquid_glass_widgets (MIT) measured it
 *   against the native control - its iOS 27 regular presets - drawn through
 *   a lens with its paraxial rim, with its springs for the press, the
 *   selection and the menus (see [IosGlass]).
 * - [KYANT] follows the catalog app of Kyant0/AndroidLiquidGlass (Apache 2.0),
 *   the library the app's glass is built on (see [KyantGlass]).
 * - [QWEA] is QWEA0/Liquid-Glass-Android's LiquidGlassView (MIT) as its demo
 *   app starts it, through its own lens shader (see [QweaGlass], [liquidLens]).
 *
 * Saved by [name].
 */
enum class LiquidStyle(val label: String, val blurb: String) {
    NOTESIS("Liquid glass", "A lens that bends the page. The selection glides after your fingertip."),
    IOS("Liquid glass · iOS", "The iOS material as liquid_glass_widgets measured it: a frosted body, a thin bright rim, and the native springs."),
    KYANT("Liquid glass · Kyant0", "As Kyant0/AndroidLiquidGlass draws it: vivid colour, a deep lens and a lit rim."),
    QWEA("Liquid glass · QWEA0", "As QWEA0/Liquid-Glass-Android's demo starts it: a wide bevel, light blur and light lit from the top left."),
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
    val motion: ProjectMotion = ProjectMotion(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("thickness", thickness.toDouble()).put("refractiveIndex", refractiveIndex.toDouble())
        .put("blur", blur.toDouble()).put("frost", frost.toDouble()).put("frostOpacity", frostOpacity.toDouble())
        .put("saturation", saturation.toDouble()).put("bodyOpacity", bodyOpacity.toDouble())
        .put("rimLight", rimLight.toDouble()).put("rimShade", rimShade.toDouble())
        .put("edgeAbsorption", edgeAbsorption.toDouble()).put("dispersion", dispersion.toDouble())
        .put("corner", corner.toDouble()).put("motion", motion.toJson())

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
            return if (read.copy(motion = PRESET.motion) == PRESET) d.copy(motion = read.motion) else read
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
                motion = ProjectMotion.fromJson(json.optJSONObject("motion")),
            )
        }
    }
}

/**
 * Kyant0/AndroidLiquidGlass's glass, as its catalog sets it. The defaults are
 * the catalog's LiquidBottomTabs: vibrancy() (saturation 1.5), blur 8 dp,
 * lens(24 dp, 24 dp), a #FAFAFA surface at 40% (#121212 at 40% in the dark)
 * and Highlight.Default. Panels take its DialogContent instead, which is the
 * same glass with twice the blur and lens and a brighter surface: blur 16 dp
 * (8 in the dark), lens(24 dp, 48 dp, depthEffect), brightness +0.2 and the
 * surface at 60% in the light, Highlight.Plain, in a 48 dp corner. Small round
 * buttons take its LiquidButton: blur 2 dp, lens(12 dp, 24 dp). Its motion:
 * a button swells by 4 dp of its height on spring(0.5, 300) with a glow under
 * the finger (InteractiveHighlight); the tab indicator follows on
 * spring(1, 1000) and lifts to 78/56 on spring(0.6, 250) across and
 * spring(0.7, 250) down; the control centre comes down 48 dp, fading in, on
 * spring(0.5, 300) and goes on spring(1, 300).
 *
 * Those are kept as [CATALOG]. A fresh install starts from values set by eye
 * on a device: refraction height 10 dp and amount 35 dp, depth effect and
 * chromatic aberration on, blur 13 dp, vibrancy 2, surface 97%, highlight
 * full; the panels' and buttons' ratios to them are the catalog's still.
 */
data class KyantGlass(
    /** blur(), in dp. */
    val blur: Float = 13f,
    /** lens()'s refractionHeight: how far in from the edge the bending reaches, in dp. */
    val refractionHeight: Float = 10f,
    /** lens()'s refractionAmount: how far the page is bent, in dp. */
    val refractionAmount: Float = 35f,
    /** vibrancy()'s saturation. */
    val saturation: Float = 2f,
    /** The surface colour's alpha over the bent page. */
    val surface: Float = 0.97f,
    /** The highlight's alpha. */
    val highlight: Float = 1f,
    /** lens()'s chromaticAberration; off in the catalog. */
    val chromaticAberration: Boolean = true,
    /** lens()'s depthEffect on bars and buttons too; the catalog keeps it for panels. */
    val depthEffect: Boolean = true,
    /** DialogContent's RoundedRectangle(48 dp). */
    val corner: Float = 48f,
    val motion: ProjectMotion = ProjectMotion(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("blur", blur.toDouble()).put("refractionHeight", refractionHeight.toDouble())
        .put("refractionAmount", refractionAmount.toDouble()).put("saturation", saturation.toDouble())
        .put("surface", surface.toDouble()).put("highlight", highlight.toDouble())
        .put("chromaticAberration", chromaticAberration).put("depthEffect", depthEffect)
        .put("corner", corner.toDouble()).put("motion", motion.toJson())

    companion object {
        val BLUR_RANGE = 0f..32f
        val HEIGHT_RANGE = 0f..64f
        val AMOUNT_RANGE = 0f..64f
        val SATURATION_RANGE = 1f..2f

        /**
         * The catalog's LiquidBottomTabs, as 0.76 started from them. Saved
         * settings still at them take the present defaults, set by eye on the
         * device: a shallower, stronger lens, more blur and colour, the
         * surface nearly solid, depth and chromatic aberration on.
         */
        val CATALOG = KyantGlass(blur = 8f, refractionHeight = 24f, refractionAmount = 24f, saturation = 1.5f,
            surface = 0.4f, chromaticAberration = false, depthEffect = false)

        fun fromJson(json: JSONObject?): KyantGlass {
            val d = KyantGlass()
            if (json == null) return d
            val read = readJson(json, d)
            return if (read.copy(motion = CATALOG.motion) == CATALOG) d.copy(motion = read.motion) else read
        }

        private fun readJson(json: JSONObject, d: KyantGlass): KyantGlass {
            return KyantGlass(
                blur = json.f("blur", d.blur, BLUR_RANGE),
                refractionHeight = json.f("refractionHeight", d.refractionHeight, HEIGHT_RANGE),
                refractionAmount = json.f("refractionAmount", d.refractionAmount, AMOUNT_RANGE),
                saturation = json.f("saturation", d.saturation, SATURATION_RANGE),
                surface = json.f("surface", d.surface, 0f..1f),
                highlight = json.f("highlight", d.highlight, 0f..1f),
                chromaticAberration = json.optBoolean("chromaticAberration", d.chromaticAberration),
                depthEffect = json.optBoolean("depthEffect", d.depthEffect),
                corner = json.f("corner", d.corner, SkinSettings.CORNER_RANGE),
                motion = ProjectMotion.fromJson(json.optJSONObject("motion")),
            )
        }
    }
}

/**
 * QWEA0/Liquid-Glass-Android's two materials, as its GlassMaterial has them:
 * how much of the blur is kept, a dimming layer, the tint when it does not
 * follow the page, and how bright the rim is.
 */
enum class QweaMaterial(val label: String, val blurScale: Float, val dim: Float, val baseTint: Float, val specBoost: Float) {
    /** Readability first: full blur, a tint that may follow the page. */
    REGULAR("Regular", 1f, 0f, 0x24 / 255f, 1f),
    /** For glass over pictures: a third of the blur, a fixed 16% dimming layer. */
    CLEAR("Clear", 0.35f, 0.16f, 0x14 / 255f, 1.2f),
    ;

    companion object {
        fun fromName(name: String?): QweaMaterial = entries.firstOrNull { it.name == name } ?: REGULAR
    }
}

/**
 * QWEA0/Liquid-Glass-Android's LiquidGlassView as its demo app starts it -
 * the view's own defaults, every one of which the demo's drawer can change:
 * backdrop blur on at blurAmount 0.0625 (a blur of 4 + 32 × amount px; 12 + it
 * over light), saturation 140%, regular material, the edge highlight on at
 * 100%, adaptive tint and the gravity-lit highlight off, refraction inwards
 * and folding, adaptive lens scale on, bevel 48 px, refraction 160 px,
 * falloff 2, dispersion 0.10, no edge softness, no glass tint (the demo's
 * swatch starts at 30% when one is picked), no shadow; a press shrinks it to
 * 0.95 over 200 ms. Its view is a capsule (cornerRadius 999 px); panels take
 * its dialog builder's 28 dp corner and blurAmount 0.6, which it gives dialogs
 * so their text has something under it, opening from 0.86 over 250 ms and
 * closing to 0.88 over 150 ms. Its tab bar's droplet travels for 380 ms on an
 * overshoot of 1.1. Pixels are the device's, as the view's are.
 */
data class QweaGlass(
    val material: QweaMaterial = QweaMaterial.REGULAR,
    val blurOn: Boolean = true,
    /** blurAmount on bars and buttons. */
    val blurAmount: Float = 0.0625f,
    /** blurAmount on panels, menus and dialogs: the dialog builder's DEFAULT_BLUR. */
    val panelBlurAmount: Float = 0.6f,
    /** saturation, as a factor (140% is 1.4). */
    val saturation: Float = 1.4f,
    /** overLight: the glass sits over a light backdrop - more blur, a dark tint, a deeper shadow. */
    val overLight: Boolean = false,
    val edgeHighlight: Boolean = true,
    /** edgeHighlightOpacity, 0 to 1. */
    val highlight: Float = 1f,
    /** enableSensorHighlight: the light follows gravity as the device tilts. */
    val sensorHighlight: Boolean = false,
    /** enableAdaptiveTint: dark over a light page, light over a dark one, pixel by pixel. */
    val adaptiveTint: Boolean = false,
    /** refractionNoFold: the bending held below where the rim would fold into a mirror image. */
    val noFold: Boolean = false,
    /** refractionOutward: the rim draws in the page from outside the glass. */
    val outward: Boolean = false,
    val adaptiveLensScale: Boolean = true,
    /** bevelWidth, px. */
    val bevelWidth: Float = 48f,
    /** refractionHeight, px. */
    val refractionHeight: Float = 160f,
    val falloff: Float = 2f,
    /** dispersionStrength. */
    val dispersion: Float = 0.10f,
    /** edgeSoftness, px. */
    val edgeSoftness: Float = 0f,
    /** glassTint's hue, as ARGB; transparent is none. */
    val tintColor: Int = 0,
    /** glassTint's strength. */
    val tintStrength: Float = 0.30f,
    /** enableShadow. */
    val shadow: Boolean = false,
    /** pressScale: < 1 a press shrinks the glass, > 1 grows it. */
    val pressScale: Float = 0.95f,
    /** The dialog builder's corner, dp; bars and buttons are capsules. */
    val corner: Float = 28f,
    val motion: ProjectMotion = ProjectMotion(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("material", material.name).put("blurOn", blurOn).put("blurAmount", blurAmount.toDouble())
        .put("panelBlurAmount", panelBlurAmount.toDouble()).put("saturation", saturation.toDouble())
        .put("overLight", overLight).put("edgeHighlight", edgeHighlight).put("highlight", highlight.toDouble())
        .put("sensorHighlight", sensorHighlight).put("adaptiveTint", adaptiveTint)
        .put("noFold", noFold).put("outward", outward).put("adaptiveLensScale", adaptiveLensScale)
        .put("bevelWidth", bevelWidth.toDouble()).put("refractionHeight", refractionHeight.toDouble())
        .put("falloff", falloff.toDouble()).put("dispersion", dispersion.toDouble())
        .put("edgeSoftness", edgeSoftness.toDouble()).put("tintColor", tintColor)
        .put("tintStrength", tintStrength.toDouble()).put("shadow", shadow)
        .put("pressScale", pressScale.toDouble()).put("corner", corner.toDouble()).put("motion", motion.toJson())

    companion object {
        val SATURATION_RANGE = 1f..2f
        val BEVEL_RANGE = 2f..120f
        val REFRACTION_RANGE = 0f..200f
        val FALLOFF_RANGE = 0f..4f
        val SOFTNESS_RANGE = 0f..40f
        val PRESS_RANGE = 0.8f..1.2f

        fun fromJson(json: JSONObject?): QweaGlass {
            val d = QweaGlass()
            if (json == null) return d
            return QweaGlass(
                material = QweaMaterial.fromName(json.optString("material", d.material.name)),
                blurOn = json.optBoolean("blurOn", d.blurOn),
                blurAmount = json.f("blurAmount", d.blurAmount, 0f..1f),
                panelBlurAmount = json.f("panelBlurAmount", d.panelBlurAmount, 0f..1f),
                saturation = json.f("saturation", d.saturation, SATURATION_RANGE),
                overLight = json.optBoolean("overLight", d.overLight),
                edgeHighlight = json.optBoolean("edgeHighlight", d.edgeHighlight),
                highlight = json.f("highlight", d.highlight, 0f..1f),
                sensorHighlight = json.optBoolean("sensorHighlight", d.sensorHighlight),
                adaptiveTint = json.optBoolean("adaptiveTint", d.adaptiveTint),
                noFold = json.optBoolean("noFold", d.noFold),
                outward = json.optBoolean("outward", d.outward),
                adaptiveLensScale = json.optBoolean("adaptiveLensScale", d.adaptiveLensScale),
                bevelWidth = json.f("bevelWidth", d.bevelWidth, BEVEL_RANGE),
                refractionHeight = json.f("refractionHeight", d.refractionHeight, REFRACTION_RANGE),
                falloff = json.f("falloff", d.falloff, FALLOFF_RANGE),
                dispersion = json.f("dispersion", d.dispersion, 0f..1f),
                edgeSoftness = json.f("edgeSoftness", d.edgeSoftness, SOFTNESS_RANGE),
                tintColor = json.optInt("tintColor", d.tintColor),
                tintStrength = json.f("tintStrength", d.tintStrength, 0f..1f),
                shadow = json.optBoolean("shadow", d.shadow),
                pressScale = json.f("pressScale", d.pressScale, PRESS_RANGE),
                corner = json.f("corner", d.corner, SkinSettings.CORNER_RANGE),
                motion = ProjectMotion.fromJson(json.optJSONObject("motion")),
            )
        }
    }
}

/** The corner Liquid glass's chrome takes under the recipe in use. */
val SkinSettings.glassCorner: Float
    get() = when (liquidStyle) {
        LiquidStyle.NOTESIS -> spotiglassCorner
        LiquidStyle.IOS -> ios.corner
        LiquidStyle.KYANT -> kyant.corner
        LiquidStyle.QWEA -> qwea.corner
    }

/** The recipe's motion switches; null for this app's own glass, which has only its own. */
val SkinSettings.projectMotion: ProjectMotion?
    get() = when (liquidStyle) {
        LiquidStyle.NOTESIS -> null
        LiquidStyle.IOS -> ios.motion
        LiquidStyle.KYANT -> kyant.motion
        LiquidStyle.QWEA -> qwea.motion
    }

/** Sets [SkinSettings.liquidStyle] and keeps it; provided where the settings live. */
val LocalLiquidStyleChange = staticCompositionLocalOf<(LiquidStyle) -> Unit> { {} }

/**
 * One of the looks a picker offers: a skin, and for Liquid glass which recipe.
 * Six in all - Material, Glassmorphism and Liquid glass's four.
 */
data class Look(val skin: Skin, val style: LiquidStyle = LiquidStyle.NOTESIS) {
    val label: String get() = if (skin == Skin.SPOTIGLASS) style.label else skin.label
    val blurb: String get() = if (skin == Skin.SPOTIGLASS) style.blurb else skin.blurb

    companion object {
        val all: List<Look> = Skin.entries.flatMap { skin ->
            if (skin == Skin.SPOTIGLASS) LiquidStyle.entries.map { Look(skin, it) } else listOf(Look(skin))
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
internal fun Modifier.styledLiquidGlass(
    style: LiquidStyle,
    settings: SkinSettings,
    shape: Shape,
    backdrop: Backdrop,
    panel: Boolean,
    dark: Boolean,
    shadows: Boolean,
    rim: Boolean = true,
    exportedBackdrop: LayerBackdrop? = null,
    amount: Float = 1f,
    light: Pair<Float, Float> = GravityLight.DEFAULT_X to GravityLight.DEFAULT_Y,
): Modifier = when (style) {
    LiquidStyle.IOS -> iosGlass(settings.ios, shape, backdrop, dark, shadows, rim, exportedBackdrop, amount)
    LiquidStyle.KYANT -> kyantGlass(settings.kyant, shape, backdrop, panel, dark, shadows, rim, exportedBackdrop, amount)
    LiquidStyle.QWEA -> qweaGlass(settings.qwea, shape, backdrop, panel, dark, shadows, rim, exportedBackdrop, amount, light)
    LiquidStyle.NOTESIS -> this
}

/**
 * The corner of [shape] at this size, for a shader that draws its own outline.
 * Before the pane is measured Backdrop's size is unspecified - NaN - and a
 * rounded shape asked for its outline at that size throws, so there is none.
 */
private fun cornerOf(shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density): Float {
    if (size.isUnspecified || !(size.width > 0f) || !(size.height > 0f)) return 0f
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
private fun Modifier.iosGlass(
    g: IosGlass,
    shape: Shape,
    backdrop: Backdrop,
    dark: Boolean,
    shadows: Boolean,
    rim: Boolean,
    exportedBackdrop: LayerBackdrop?,
    amount: Float,
): Modifier {
    val saturation = 1f + ((if (dark) g.saturation * IosGlass.DARK_SATURATION else g.saturation) - 1f) * amount
    val frostOpacity = ((if (dark) g.frostOpacity * IosGlass.DARK_FROST else g.frostOpacity) * amount).coerceIn(0f, 1f)
    val body = if (dark) Color.White.copy(alpha = (g.bodyOpacity * IosGlass.DARK_BODY * amount).coerceIn(0f, 1f))
        else Color(0xFFF8F8F8).copy(alpha = (g.bodyOpacity * amount).coerceIn(0f, 1f))
    val rimLight = if (rim) (if (dark) g.rimLight * IosGlass.DARK_RIM_LIGHT else g.rimLight) * amount else 0f
    // ios27Light lights from the top (pi / 2), ios27Dark from the bottom; the
    // shader takes the way the light travels.
    val lightY = if (dark) -1f else 1f
    fun glassLens(scope: com.kyant.backdrop.BackdropEffectScope, opacity: Float, spec: Float) = with(scope) {
        liquidLens(LiquidLensParams(
            cornerRadius = cornerOf(shape, size, layoutDirection, this),
            bevel = g.bevelDp.dp.toPx() * amount,
            refract = g.bendDp.dp.toPx() * amount,
            falloff = 0f,
            dispersion = g.dispersion,
            lightX = 0f, lightY = lightY,
            spec = spec,
            saturation = saturation,
            edgeAbsorb = g.edgeAbsorption,
            bodyShade = if (dark) IosGlass.DARK_BODY_SHADE * amount else 0f,
            opacity = opacity,
        ))
    }
    val base = drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(g.blur.dp.toPx() * amount)
            glassLens(this, 1f, 0f)
        },
        highlight = null,
        // ios27Light's BoxShadow: black at 0x04, 10 soft, 7 down; none in the dark.
        shadow = if (shadows && !dark) {
            { Shadow(radius = 10.dp, offset = DpOffset(0.dp, 7.dp), color = Color.Black.copy(alpha = 4f / 255f), alpha = amount) }
        } else null,
        exportedBackdrop = exportedBackdrop,
    )
    return base
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                blur(g.frost.dp.toPx() * amount)
                glassLens(this, frostOpacity, rimLight)
            },
            highlight = null,
            shadow = null,
            onDrawSurface = { drawRect(body) },
        )
        .then(if (rim && g.rimShade > 0f) Modifier.rimShade(shape, g.rimShade * amount, dark) else Modifier)
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

private fun Modifier.kyantGlass(
    k: KyantGlass,
    shape: Shape,
    backdrop: Backdrop,
    panel: Boolean,
    dark: Boolean,
    shadows: Boolean,
    rim: Boolean,
    exportedBackdrop: LayerBackdrop?,
    amount: Float,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        val button = size.minDimension < BUTTON_SIZE.toPx()
        val asPanel = panel && !button
        colorControls(brightness = if (asPanel && !dark) 0.2f * amount else 0f, saturation = 1f + (k.saturation - 1f) * amount)
        // Dialog 16 dp (8 in the dark), tabs 8, button 2: the setting is the tabs'.
        val blurDp = when {
            asPanel -> if (dark) k.blur else k.blur * 2f
            button -> k.blur / 4f
            else -> k.blur
        }
        blur(blurDp.dp.toPx() * amount)
        // Dialog lens(24, 48), tabs lens(24, 24), button lens(12, 24).
        val height = if (button) k.refractionHeight / 2f else k.refractionHeight
        val bend = if (asPanel) k.refractionAmount * 2f else k.refractionAmount
        lens(
            refractionHeight = (height.dp.toPx() * amount).coerceAtMost(size.minDimension / 2f),
            refractionAmount = bend.dp.toPx() * amount,
            depthEffect = asPanel || k.depthEffect,
            chromaticAberration = k.chromaticAberration,
        )
    },
    highlight = if (rim && k.highlight > 0.001f) {
        { (if (panel) Highlight.Plain else Highlight.Default).copy(alpha = k.highlight * amount) }
    } else null,
    shadow = if (shadows) { { Shadow.Default.copy(alpha = amount) } } else null,
    exportedBackdrop = exportedBackdrop,
    onDrawSurface = {
        // DialogContent: #FAFAFA at 60% in the light, #121212 at 40% in the dark;
        // the tabs and buttons at 40% in both. The setting moves them together.
        val asPanel = panel && size.minDimension >= BUTTON_SIZE.toPx()
        drawRect(if (dark) Color(0xFF121212).copy(alpha = k.surface)
            else Color(0xFFFAFAFA).copy(alpha = (k.surface + if (asPanel) 0.2f else 0f).coerceIn(0f, 1f)))
    },
)

/** LiquidGlassView's lens, through the port of its shader (see [liquidLens]). */
private fun Modifier.qweaGlass(
    q: QweaGlass,
    shape: Shape,
    backdrop: Backdrop,
    panel: Boolean,
    dark: Boolean,
    shadows: Boolean,
    rim: Boolean,
    exportedBackdrop: LayerBackdrop?,
    amount: Float,
    light: Pair<Float, Float>,
): Modifier {
    val m = q.material
    // currentTintColor: over light, black at 0x33; else the material's white.
    // The adaptive tint is worked out in the shader, pixel by pixel.
    val tint = if (q.overLight) Color(0x33000000) else Color.White.copy(alpha = m.baseTint)
    val glassTint = if (android.graphics.Color.alpha(q.tintColor) == 0) Color.Transparent
        else Color(q.tintColor).copy(alpha = q.tintStrength)
    val spec = if (rim && q.edgeHighlight) q.highlight * m.specBoost * amount else 0f
    val (lx, ly) = if (q.sensorHighlight) light else GravityLight.DEFAULT_X to GravityLight.DEFAULT_Y
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (q.blurOn) {
                // calculatedBlurRadius = (overLight ? 12 : 4) + blurAmount × 32, in px, times the material's share.
                val asPanel = panel && size.minDimension >= BUTTON_SIZE.toPx()
                val amountOfBlur = if (asPanel) q.panelBlurAmount else q.blurAmount
                blur(((if (q.overLight) 12f else 4f) + amountOfBlur * 32f) * m.blurScale * amount)
            }
            liquidLens(LiquidLensParams(
                cornerRadius = cornerOf(shape, size, layoutDirection, this),
                bevel = q.bevelWidth * amount,
                refract = q.refractionHeight * amount,
                falloff = q.falloff,
                outward = q.outward,
                noFold = q.noFold,
                adaptiveScale = q.adaptiveLensScale,
                rimSoft = q.edgeSoftness,
                dispersion = q.dispersion,
                lightX = lx, lightY = ly,
                spec = spec,
                tint = tint.copy(alpha = tint.alpha * amount),
                adaptiveTint = q.adaptiveTint && m == QweaMaterial.REGULAR,
                glassTint = glassTint.copy(alpha = glassTint.alpha * amount),
                dim = m.dim * amount,
                saturation = 1f + (q.saturation - 1f) * amount,
            ))
        },
        highlight = null,
        // LiquidGlassView's shadow: (overLight ? 70 : 40) px soft, (16 : 12) px down, black at (75 : 25)%.
        shadow = if (shadows && q.shadow) {
            {
                Shadow(
                    radius = (if (q.overLight) 70f else 40f).toDpPx(),
                    offset = DpOffset(0.dp, (if (q.overLight) 16f else 12f).toDpPx()),
                    color = Color.Black.copy(alpha = if (q.overLight) 0.75f else 0.25f),
                    alpha = amount,
                )
            }
        } else null,
        exportedBackdrop = exportedBackdrop,
    )
}

/** [this] many px as dp at the screen's density, for numbers a library gave in px. */
private fun Float.toDpPx(): Dp = (this / android.content.res.Resources.getSystem().displayMetrics.density).dp
