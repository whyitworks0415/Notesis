package com.notesis

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject

/** 앱 전체에 명시적으로 적용할 화면 밝기 모드입니다. */
enum class AppThemeMode(val label: String) {
    LIGHT("Light mode"),
    DARK("Dark mode"),
}

/** Liquid Glass가 페이지 위로 솟아 보일지, 아래로 눌려 보일지 정합니다. */
enum class RefractionDirection(val label: String) {
    RAISED("Raised"),
    INSET("Pressed in"),
}

/**
 * Every number the glass is made of, in one place, so the look can be tuned
 * from inside the app rather than from a rebuild. The defaults are what a
 * fresh install starts from: frost 12%, transparency 100%, reflection 100%,
 * depth 10 dp, a 32 dp corner, raised, full interaction strength.
 */
data class SkinSettings(
    /** 시스템 설정과 무관하게 앱에서 선택한 라이트/다크 모드입니다. */
    val themeMode: AppThemeMode = AppThemeMode.LIGHT,
    /** How far the backdrop is scattered behind a panel. This is the frost: 12% of [BLUR_RANGE]. */
    val blur: Float = 7.2f,
    /** Saturation lift on the blurred backdrop; glass makes colour, not mud. Goes with the frost (see [withFrost]). */
    val vibrancy: Float = 0.296f,
    /** 굴절이 가장자리에서 안쪽으로 파고드는 깊이(dp). A deeper band, as Apple's glass bends the page. */
    val depth: Float = 14f,
    /** 렌즈가 배경 픽셀을 휘게 하는 거리(dp). Twice the depth (see [withDepth]). */
    val refraction: Float = 28f,
    /** 가장자리의 배경 픽셀을 바깥/안쪽 어느 방향으로 굴절할지 정합니다. */
    val refractionDirection: RefractionDirection = RefractionDirection.RAISED,
    /** RGB 파장이 갈라지는 색수차의 체감 강도(0~1). */
    val dispersion: Float = LiquidGlassTokens.dispersion,
    /** The body of the glass. Alpha is the point of this one. */
    val tint: Int = DEFAULT_TINT,
    /** The lit edge. */
    val border: Int = DEFAULT_BORDER,
    /** Text and icons: what the theme's onSurface and outlines are made from. */
    val content: Int = DEFAULT_CONTENT,
    /** Drives the theme's primary, so it reaches Material too. */
    val accent: Int = DEFAULT_ACCENT,
    /**
     * Glassmorphism's own corner, from before every skin shared one. No
     * longer read - the chrome takes [spotiglassCorner] in every skin - but
     * kept so older settings and backups still parse as they were.
     */
    val corner: Float = 26f,
    /** Spotiglass의 투명도. 0은 불투명, 1은 가장 맑은 유리입니다. */
    val spotiglassClarity: Float = 1f,
    /** 도구바 배경의 불투명도. 렌즈 굴절과 아이콘에는 적용하지 않습니다. Follows the clarity (see [withTransparency]). */
    val spotiglassToolbarOpacity: Float = 0f,
    /** 누름/슬라이드에 따른 렌즈의 늘어남과 들림 강도입니다. */
    val spotiglassResponse: Float = 1f,
    /** The corner of every skin's chrome; the name is from when only Liquid glass had one. */
    val spotiglassCorner: Float = 32f,
    /**
     * How strongly the glass catches the light along its edge - the bright
     * rim and the sheen down from the top. 0 is a plain edge.
     */
    val reflection: Float = 1f,
    /**
     * Everything legible before everything pretty. Darkens the accent, makes
     * the glass solid, thickens every edge and gives the outlines their weight
     * back - the same app with the contrast turned up rather than a mode of
     * its own with its own set of surprises.
     */
    val highContrast: Boolean = false,
    /** Whether bars, panels, buttons and dialogs cast shadows at all. */
    val shadows: Boolean = true,
) {
    fun toJson(): String = JSONObject()
        .put("themeMode", themeMode.name)
        .put("blur", blur.toDouble())
        .put("vibrancy", vibrancy.toDouble())
        .put("depth", depth.toDouble())
        .put("refraction", refraction.toDouble())
        .put("refractionDirection", refractionDirection.name)
        .put("dispersion", dispersion.toDouble())
        .put("tint", tint)
        .put("border", border)
        .put("content", content)
        .put("accent", accent)
        .put("corner", corner.toDouble())
        .put("spotiglassClarity", spotiglassClarity.toDouble())
        .put("spotiglassToolbarOpacity", spotiglassToolbarOpacity.toDouble())
        .put("spotiglassResponse", spotiglassResponse.toDouble())
        .put("spotiglassCorner", spotiglassCorner.toDouble())
        .put("reflection", reflection.toDouble())
        .put("opticsVersion", OPTICS_VERSION)
        .put("highContrast", highContrast)
        .put("shadows", shadows)
        .toString()

    /** Whether the optics are still the ones a fresh install had before [OPTICS_VERSION] 2. */
    internal fun hasOriginalOptics(): Boolean {
        val o = ORIGINAL
        fun same(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.001f
        return same(blur, o.blur) && same(vibrancy, o.vibrancy) && same(depth, o.depth) &&
            same(refraction, o.refraction) && same(spotiglassClarity, o.spotiglassClarity) &&
            same(spotiglassToolbarOpacity, o.spotiglassToolbarOpacity)
    }

    /**
     * These settings with [ORIGINAL]'s optics: what sliders, switches and
     * segmented controls are drawn with, so tuning the panels never changes
     * how a control looks or feels. Colours, theme and contrast still follow.
     */
    fun controlOptics(): SkinSettings = ORIGINAL.copy(
        themeMode = themeMode, tint = tint, border = border, content = content, accent = accent,
        highContrast = highContrast, shadows = shadows,
    )

    /** The frost, 0 to 1: blur and the colour lift that goes with it, set as one. */
    val frost: Float get() = (blur / BLUR_RANGE.endInclusive).coerceIn(0f, 1f)
    fun withFrost(f: Float): SkinSettings {
        val x = f.coerceIn(0f, 1f)
        return copy(blur = BLUR_RANGE.endInclusive * x, vibrancy = 0.2f + 0.8f * x)
    }

    /** How clear the glass is, 0 to 1: the panels' clarity and the bar's own opacity, set as one. */
    val transparency: Float get() = spotiglassClarity
    fun withTransparency(t: Float): SkinSettings {
        val x = t.coerceIn(0f, 1f)
        return copy(spotiglassClarity = x, spotiglassToolbarOpacity = 0.8f * (1f - x))
    }

    /** The lens's depth in dp: how far in the bending reaches, and how far it bends with it. */
    fun withDepth(dp: Float): SkinSettings {
        val d = dp.coerceIn(DEPTH_RANGE)
        return copy(depth = d, refraction = (2f * d).coerceIn(REFRACTION_RANGE))
    }

    companion object {
        val DEFAULT_TINT = Color(0xFFFFFFFF).copy(alpha = 0.30f).toArgb()
        val DEFAULT_BORDER = Color.White.copy(alpha = 0.92f).toArgb()
        val DEFAULT_CONTENT = 0xFF1D1B20.toInt()
        val DEFAULT_ACCENT = 0xFF6750A4.toInt()

        fun fromJson(raw: String?): SkinSettings {
            val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return SkinSettings()
            val d = SkinSettings()
            val read = SkinSettings(
                themeMode = runCatching {
                    AppThemeMode.valueOf(json.optString("themeMode", d.themeMode.name))
                }.getOrDefault(d.themeMode),
                blur = json.optDouble("blur", d.blur.toDouble()).toFloat(),
                vibrancy = json.optDouble("vibrancy", d.vibrancy.toDouble()).toFloat(),
                depth = json.optDouble("depth", d.depth.toDouble()).toFloat(),
                refraction = json.optDouble("refraction", d.refraction.toDouble()).toFloat(),
                refractionDirection = runCatching {
                    RefractionDirection.valueOf(
                        json.optString("refractionDirection", d.refractionDirection.name),
                    )
                }.getOrDefault(d.refractionDirection),
                dispersion = json.optDouble("dispersion", d.dispersion.toDouble()).toFloat(),
                tint = json.optInt("tint", d.tint),
                border = json.optInt("border", d.border),
                content = json.optInt("content", d.content),
                accent = json.optInt("accent", d.accent),
                corner = json.optDouble("corner", d.corner.toDouble()).toFloat(),
                spotiglassClarity = json.optDouble("spotiglassClarity", d.spotiglassClarity.toDouble())
                    .toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: d.spotiglassClarity,
                spotiglassToolbarOpacity = json.optDouble("spotiglassToolbarOpacity", d.spotiglassToolbarOpacity.toDouble())
                    .toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: d.spotiglassToolbarOpacity,
                spotiglassResponse = json.optDouble("spotiglassResponse", d.spotiglassResponse.toDouble())
                    .toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: d.spotiglassResponse,
                spotiglassCorner = json.optDouble("spotiglassCorner", d.spotiglassCorner.toDouble())
                    .toFloat().takeIf { it.isFinite() }?.coerceIn(CORNER_RANGE) ?: d.spotiglassCorner,
                reflection = json.optDouble("reflection", LEGACY_REFLECTION.toDouble())
                    .toFloat().takeIf { it.isFinite() }?.coerceIn(REFLECTION_RANGE) ?: LEGACY_REFLECTION,
                highContrast = json.optBoolean("highContrast", d.highContrast),
                shadows = json.optBoolean("shadows", d.shadows),
            )
            // Saved before the glass took its present look: optics never moved
            // from the old defaults take the new ones; any the reader set are kept,
            // with the edge they were seen with.
            val version = json.optInt("opticsVersion", 1)
            if (version < OPTICS_VERSION && read.hasOriginalOptics()) {
                return read.copy(blur = d.blur, vibrancy = d.vibrancy, depth = d.depth, refraction = d.refraction,
                    spotiglassClarity = d.spotiglassClarity, spotiglassToolbarOpacity = d.spotiglassToolbarOpacity,
                    reflection = d.reflection)
            }
            // Version 2's lens, never moved, takes version 3's deeper one.
            fun same(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.001f
            if (version < 3 && same(read.depth, V2_DEPTH) && same(read.refraction, V2_REFRACTION)) {
                return read.copy(depth = d.depth, refraction = d.refraction)
            }
            return read
        }

        /** The look's version; 2 is the glass after the reference photo, 3 the deeper lens of Apple's. */
        const val OPTICS_VERSION = 3

        /** Version 2's lens, which a fresh install had until version 3. */
        private const val V2_DEPTH = 10f
        private const val V2_REFRACTION = 20f

        /** The edge as it was drawn before [reflection] could be set. */
        const val LEGACY_REFLECTION = 0.35f

        /**
         * The glass as it was before it took the reference photo's look: the
         * optics sliders and switches keep, whatever the panels are set to.
         */
        val ORIGINAL = SkinSettings(
            blur = 20f, vibrancy = 0.42f,
            depth = LiquidGlassTokens.refractionDepth.value, refraction = LiquidGlassTokens.refractionAmount.value,
            refractionDirection = RefractionDirection.RAISED, dispersion = LiquidGlassTokens.dispersion,
            spotiglassClarity = 0.75f, spotiglassToolbarOpacity = 0.205f, spotiglassResponse = 1f,
            spotiglassCorner = 32f, reflection = LEGACY_REFLECTION,
        )

        /** What each control may be set to, so the screen and the shader agree. */
        val BLUR_RANGE = 0f..60f
        val VIBRANCY_RANGE = 0f..1f
        val DEPTH_RANGE = 0f..28f
        val REFRACTION_RANGE = 0f..52f
        val DISPERSION_RANGE = 0f..1f
        val CORNER_RANGE = 0f..48f
        val REFLECTION_RANGE = 0f..1f
    }
}

/** Kept beside the pens, because both are how the person likes their tools. */
class SkinSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pens", Context.MODE_PRIVATE)

    fun load(): SkinSettings = SkinSettings.fromJson(prefs.getString(KEY, null))

    fun save(settings: SkinSettings) {
        prefs.edit().putString(KEY, settings.toJson()).apply()
    }

    private companion object {
        const val KEY = "skinSettings"
    }
}
