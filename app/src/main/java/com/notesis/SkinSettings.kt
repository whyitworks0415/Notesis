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
    /** How far the backdrop is scattered behind a panel. This is the frost. */
    val blur: Float = 20f,
    /** Saturation lift on the blurred backdrop; glass makes colour, not mud. */
    val vibrancy: Float = 0.42f,
    /** The body of the glass. Alpha is the point of this one. */
    val tint: Int = DEFAULT_TINT,
    /** The lit edge. */
    val border: Int = DEFAULT_BORDER,
    /** Text and icons: what the theme's onSurface and outlines are made from. */
    val content: Int = DEFAULT_CONTENT,
    /** Drives the theme's primary, so it reaches Material too. */
    val accent: Int = DEFAULT_ACCENT,
    val corner: Float = 26f,
    /**
     * Everything legible before everything pretty. Darkens the accent, makes
     * the glass solid, thickens every edge and gives the outlines their weight
     * back - the same app with the contrast turned up rather than a mode of
     * its own with its own set of surprises.
     */
    val highContrast: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("blur", blur.toDouble())
        .put("vibrancy", vibrancy.toDouble())
        .put("tint", tint)
        .put("border", border)
        .put("content", content)
        .put("accent", accent)
        .put("corner", corner.toDouble())
        .put("highContrast", highContrast)
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
                tint = json.optInt("tint", d.tint),
                border = json.optInt("border", d.border),
                content = json.optInt("content", d.content),
                accent = json.optInt("accent", d.accent),
                corner = json.optDouble("corner", d.corner.toDouble()).toFloat(),
                highContrast = json.optBoolean("highContrast", d.highContrast),
            )
        }

        /** What each control may be set to, so the screen and the shader agree. */
        val BLUR_RANGE = 0f..60f
        val VIBRANCY_RANGE = 0f..1f
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
