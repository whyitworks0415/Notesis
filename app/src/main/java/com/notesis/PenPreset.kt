package com.notesis

import android.content.Context
import org.json.JSONObject

/**
 * How one tool is set: what it draws with, in what colour, how thick. Colour
 * keeps its alpha, which is what makes a highlighter a highlighter rather than a
 * pen with a special case attached to it.
 */
data class PenPreset(
    val tool: Tool,
    val colorArgb: Int,
    val width: Float,
    /** Whether the stylus's pressure reaches the width. Pens only. */
    val pressure: Boolean = false,
) {
    /** The tool as it actually draws, which is where pressure is decided. */
    fun drawingTool(): Tool =
        if (pressure && tool == Tool.PEN) Tool.PRESSURE_PEN else tool
}

/**
 * Each tool's own settings, kept in preferences rather than in a note - how a
 * pen is set belongs to the person, not to the page they happen to have open.
 * Picking up a tool picks up the colour and thickness it was left at, so there
 * is no tray to curate and nothing to lose track of.
 */
class PenStore(context: Context) {

    private val prefs = context.getSharedPreferences("pens", Context.MODE_PRIVATE)

    /** How the chrome is dressed. Material until someone says otherwise. */
    var skin: Skin
        get() = runCatching { Skin.valueOf(prefs.getString(SKIN, "")!!) }
            // The liquid glass skin is gone. Anyone who had it chosen wanted
            // glass, not the default, so they land on the one that is left.
            .getOrDefault(
                if (prefs.getString(SKIN, "") == "LIQUID_GLASS") {
                    Skin.GLASSMORPHISM
                } else {
                    Skin.MATERIAL
                },
            )
        set(value) = prefs.edit().putString(SKIN, value.name).apply()

    /** Whether the toolbar sits flush along the top edge rather than floating. */
    var docked: Boolean
        get() = prefs.getBoolean(DOCKED, false)
        set(value) = prefs.edit().putBoolean(DOCKED, value).apply()

    fun load(): Map<EditMode, PenPreset> {
        val raw = prefs.getString(KEY, null) ?: return DEFAULTS
        val saved = runCatching {
            val json = JSONObject(raw)
            DEFAULTS.keys.mapNotNull { mode ->
                val item = json.optJSONObject(mode.name) ?: return@mapNotNull null
                val fallback = DEFAULTS.getValue(mode)
                mode to PenPreset(
                    tool = fallback.tool,
                    colorArgb = item.optInt("color", fallback.colorArgb),
                    width = item.optDouble("width", fallback.width.toDouble()).toFloat(),
                    pressure = item.optBoolean("pressure", fallback.pressure),
                )
            }.toMap()
        }.getOrNull().orEmpty()
        // Defaults underneath, so a tool added in a later version arrives set up
        // rather than missing.
        return DEFAULTS + saved
    }

    fun save(settings: Map<EditMode, PenPreset>) {
        val json = JSONObject()
        for ((mode, pen) in settings) {
            json.put(
                mode.name,
                JSONObject()
                    .put("color", pen.colorArgb)
                    .put("width", pen.width.toDouble())
                    .put("pressure", pen.pressure),
            )
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    companion object {
        private const val KEY = "tools"
        private const val DOCKED = "docked"
        private const val SKIN = "skin"

        /** The thickness slider's range depends on what is being made thick. */
        fun widthRange(mode: EditMode): ClosedFloatingPointRange<Float> = when (mode) {
            EditMode.ERASE -> 8f..96f
            EditMode.HIGHLIGHTER -> 4f..60f
            else -> 1f..24f
        }

        /** Highlighters come pre-faded; that alpha is the tool's own, not a rule. */
        val DEFAULTS: Map<EditMode, PenPreset> = mapOf(
            // Pressure on by default: the stylus has been reporting it all
             // along, and a pen that ignores it reads as a marker.
            EditMode.PEN to PenPreset(Tool.PEN, 0xFF000000.toInt(), 5f, pressure = true),
            EditMode.HIGHLIGHTER to PenPreset(Tool.HIGHLIGHTER, 0x66F9A825, 20f),
            EditMode.MASK to PenPreset(Tool.MASK, PageMask.DEFAULT_MASK_COLOR, 20f),
            EditMode.SHAPE to PenPreset(Tool.PEN, 0xFF1976D2.toInt(), 5f),
            EditMode.ERASE to PenPreset(Tool.ERASER, 0xFF000000.toInt(), 24f),
        )
    }
}
