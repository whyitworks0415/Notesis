package com.notesis

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject

/**
 * Every number the glass is made of, in one place, so the look can be tuned
 * from inside the app rather than from a rebuild. The defaults are the values
 * measured off the reference file - a fresh install looks the way the last
 * release did, and the settings screen is somewhere to go from there.
 */
data class SkinSettings(
    /**
     * How far the backdrop is blurred before the glass is drawn over it. Off by
     * default, as it is in the reference screenshots: blur erases the detail
     * that refraction has to bend, and a bend with nothing to act on looks like
     * nothing at all. It is here for frosting, not for glass.
     */
    val blur: Float = 0f,
    /** Saturation lift on the blurred backdrop; glass makes colour, not mud. */
    val vibrancy: Float = 0.42f,
    /** How far the edge bends what is behind it. The lens, in one number. */
    val refraction: Float = 19f,
    /** How thick the glass reads: the distance the bend falls off over. */
    val depth: Float = 97f,
    /** How far the colours split as they bend. Zero is plain glass. */
    val dispersion: Float = 0f,
    /** The body of the glass. Alpha is the point of this one. */
    val tint: Int = DEFAULT_TINT,
    /** The lit edge. */
    val border: Int = DEFAULT_BORDER,
    val content: Int = DEFAULT_CONTENT,
    /** Drives the theme's primary, so it reaches Material too. */
    val accent: Int = DEFAULT_ACCENT,
    val corner: Float = 26f,
) {
    fun toJson(): String = JSONObject()
        .put("blur", blur.toDouble())
        .put("vibrancy", vibrancy.toDouble())
        .put("refraction", refraction.toDouble())
        .put("depth", depth.toDouble())
        .put("dispersion", dispersion.toDouble())
        .put("tint", tint)
        .put("border", border)
        .put("content", content)
        .put("accent", accent)
        .put("corner", corner.toDouble())
        .toString()

    companion object {
        val DEFAULT_TINT = Color(0xFFFFFFFF).copy(alpha = 0.30f).toArgb()
        val DEFAULT_BORDER = Color.White.copy(alpha = 0.92f).toArgb()
        val DEFAULT_CONTENT = 0xFF1D1B20.toInt()
        val DEFAULT_ACCENT = 0xFF6750A4.toInt()

        fun fromJson(raw: String?): SkinSettings {
            val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return SkinSettings()
            val d = SkinSettings()
            return SkinSettings(
                blur = json.optDouble("blur", d.blur.toDouble()).toFloat(),
                vibrancy = json.optDouble("vibrancy", d.vibrancy.toDouble()).toFloat(),
                refraction = json.optDouble("refraction", d.refraction.toDouble()).toFloat(),
                depth = json.optDouble("depth", d.depth.toDouble()).toFloat(),
                dispersion = json.optDouble("dispersion", d.dispersion.toDouble()).toFloat(),
                tint = json.optInt("tint", d.tint),
                border = json.optInt("border", d.border),
                content = json.optInt("content", d.content),
                accent = json.optInt("accent", d.accent),
                corner = json.optDouble("corner", d.corner.toDouble()).toFloat(),
            )
        }

        /** What each control may be set to, so the screen and the shader agree. */
        val BLUR_RANGE = 0f..48f
        val VIBRANCY_RANGE = 0f..1f
        val REFRACTION_RANGE = 0f..40f
        val DEPTH_RANGE = 0f..160f
        val DISPERSION_RANGE = 0f..1f
        val CORNER_RANGE = 0f..48f
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
