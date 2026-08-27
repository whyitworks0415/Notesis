package com.notesis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved pen: what it draws with, in what colour, how thick. Colour keeps its
 * alpha, which is what makes a highlighter a highlighter rather than a pen with
 * a special case attached to it.
 */
data class PenPreset(
    val tool: Tool,
    val colorArgb: Int,
    val width: Float,
)

/**
 * The pen tray, kept in preferences rather than in a note - a pen belongs to the
 * person, not to the page they happen to have open.
 */
class PenStore(context: Context) {

    private val prefs = context.getSharedPreferences("pens", Context.MODE_PRIVATE)

    fun load(): List<PenPreset> {
        val raw = prefs.getString(KEY, null) ?: return DEFAULTS
        val list = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                PenPreset(
                    tool = Tool.entries[item.optInt("tool").coerceIn(0, Tool.entries.size - 1)],
                    colorArgb = item.optInt("color"),
                    width = item.optDouble("width", 5.0).toFloat(),
                )
            }
        }.getOrNull().orEmpty()
        // An empty tray leaves nothing to draw with and no way back.
        return list.ifEmpty { DEFAULTS }
    }

    fun save(pens: List<PenPreset>) {
        val array = JSONArray()
        for (pen in pens) {
            array.put(
                JSONObject()
                    .put("tool", pen.tool.ordinal)
                    .put("color", pen.colorArgb)
                    .put("width", pen.width.toDouble()),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "presets"

        /** Highlighters come pre-faded; that alpha is now the pen's own, not a rule. */
        val DEFAULTS = listOf(
            PenPreset(Tool.PEN, 0xFF000000.toInt(), 5f),
            PenPreset(Tool.PEN, 0xFFD32F2F.toInt(), 5f),
            PenPreset(Tool.PEN, 0xFF1976D2.toInt(), 5f),
            PenPreset(Tool.HIGHLIGHTER, 0x66F9A825, 20f),
            PenPreset(Tool.HIGHLIGHTER, 0x6600C853, 20f),
        )
    }
}
