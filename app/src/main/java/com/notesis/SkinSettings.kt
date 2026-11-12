package com.notesis

import android.content.Context
import org.json.JSONObject

/** Light or dark, as chosen in the app whatever the system is set to. */
enum class AppThemeMode(val label: String) {
    LIGHT("Light mode"),
    DARK("Dark mode"),
}

/**
 * How the app looks, in one place, so it can be set from inside the app
 * rather than from a rebuild.
 *
 * Saved as JSON beside the pens. The glass looks kept many more numbers
 * there (frost, lens, rim, each recipe's own); they are not read while those
 * looks are out of the build, and [SkinSettingsStore.save] leaves them where
 * they are, so a tuned glass is still there when the looks come back.
 */
data class SkinSettings(
    val themeMode: AppThemeMode = AppThemeMode.LIGHT,
    /** Text and icons: what the theme's onSurface and outlines are made from. */
    val content: Int = DEFAULT_CONTENT,
    /** Drives the theme's primary where the wallpaper's colours are not used. */
    val accent: Int = DEFAULT_ACCENT,
    /** The corner of the chrome: the toolbar, its menus and the panels. */
    val corner: Float = 32f,
    /**
     * Everything legible before everything pretty: darkens the accent,
     * thickens every edge and gives the outlines their weight back.
     */
    val highContrast: Boolean = false,
    /** Whether bars, panels, buttons and dialogs cast shadows at all. */
    val shadows: Boolean = true,
    /**
     * Colours from the wallpaper (Android 12 and up); off, from [accent]. A
     * setting saved before there was a choice keeps the accent its owner picked.
     */
    val dynamicColor: Boolean = true,
    /** Dark mode on a black page, with near-black panels. */
    val amoled: Boolean = false,
    /** How far the page is dimmed behind a tool panel. */
    val popupDim: Float = DEFAULT_POPUP_DIM,
) {
    /** These settings as JSON, written over [kept] so that keys this version does not read stay as they were. */
    fun toJson(kept: String? = null): String = (runCatching { JSONObject(kept.orEmpty()) }.getOrNull() ?: JSONObject())
        .put("themeMode", themeMode.name)
        .put("content", content)
        .put("accent", accent)
        // The key keeps the name it was first saved under, so saved looks still load.
        .put(CORNER_KEY, corner.toDouble())
        .put("highContrast", highContrast)
        .put("shadows", shadows)
        .put("dynamicColor", dynamicColor)
        .put("amoled", amoled)
        .put("popupDim", popupDim.toDouble())
        .toString()

    companion object {
        val DEFAULT_CONTENT = 0xFF1D1B20.toInt()
        val DEFAULT_ACCENT = 0xFF6750A4.toInt()

        fun fromJson(raw: String?): SkinSettings {
            val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return SkinSettings()
            val d = SkinSettings()
            return SkinSettings(
                themeMode = runCatching {
                    AppThemeMode.valueOf(json.optString("themeMode", d.themeMode.name))
                }.getOrDefault(d.themeMode),
                content = json.optInt("content", d.content),
                accent = json.optInt("accent", d.accent),
                corner = json.optDouble(CORNER_KEY, d.corner.toDouble())
                    .toFloat().takeIf { it.isFinite() }?.coerceIn(CORNER_RANGE) ?: d.corner,
                highContrast = json.optBoolean("highContrast", d.highContrast),
                shadows = json.optBoolean("shadows", d.shadows),
                // Saved before the switch: on only if the accent was never changed.
                dynamicColor = json.optBoolean("dynamicColor", json.optInt("accent", d.accent) == d.accent),
                amoled = json.optBoolean("amoled", d.amoled),
                popupDim = json.optDouble("popupDim", d.popupDim.toDouble()).toFloat()
                    .takeIf { it.isFinite() }?.coerceIn(POPUP_DIM_RANGE) ?: d.popupDim,
            )
        }

        private const val CORNER_KEY = "spotiglassCorner"

        /** What each control may be set to. */
        val CORNER_RANGE = 0f..48f
        /** The dim tool panels always had: enough to set the panel off, the page still readable. */
        const val DEFAULT_POPUP_DIM = 0.12f
        val POPUP_DIM_RANGE = 0f..0.6f
    }
}

/** Kept beside the pens, because both are how the person likes their tools. */
class SkinSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pens", Context.MODE_PRIVATE)

    fun load(): SkinSettings = SkinSettings.fromJson(prefs.getString(KEY, null))

    /** Saves [settings] over what is there, so what the glass looks saved is not lost (see [SkinSettings]). */
    fun save(settings: SkinSettings) {
        prefs.edit().putString(KEY, settings.toJson(kept = prefs.getString(KEY, null))).apply()
    }

    private companion object {
        const val KEY = "skinSettings"
    }
}
