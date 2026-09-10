package com.notesis

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray

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
    /**
     * The top of this tool's thickness slider, or zero for the tool's own.
     *
     * A single range has to cover a hairline and a highlighter's broadest
     * stroke, and a slider that does both gives the useful part of a pen -
     * everything under six - about a fifth of its travel. Setting the ceiling
     * gives that fifth the whole track back.
     */
    val maxWidth: Float = 0f,
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

    var toolbarSize: Int
        get() = prefs.getInt("toolbarSize", 0).coerceIn(0, 3)
        set(value) = prefs.edit().putInt("toolbarSize", value.coerceIn(0, 3)).apply()

    var homeColor: Int?
        get() = if (prefs.contains("homeColor")) prefs.getInt("homeColor", 0) else null
        set(value) { if (value == null) prefs.edit().remove("homeColor").apply()
            else prefs.edit().putInt("homeColor", value).apply() }

    var homePhoto: String?
        get() = prefs.getString("homePhoto", null)
        set(value) = prefs.edit().putString("homePhoto", value).apply()

    fun favoriteWidths(mode: EditMode): List<Float> = runCatching {
        val array = JSONArray(prefs.getString("widths:$mode", "[]"))
        (0 until array.length()).map { array.getDouble(it).toFloat() }
            .filter { it.isFinite() && it in widthRange(mode) }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun saveFavoriteWidths(mode: EditMode, widths: List<Float>) {
        prefs.edit().putString("widths:$mode", JSONArray(widths.distinct().sorted()).toString()).apply()
    }

    fun colorTemplates(): List<Pair<String, List<Int>>> = runCatching {
        val array = JSONArray(prefs.getString("colorTemplates", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val colors = item.getJSONArray("colors")
            item.getString("name") to (0 until colors.length()).map { colors.getInt(it) }
        }
    }.getOrDefault(emptyList())

    fun saveColorTemplates(templates: List<Pair<String, List<Int>>>) {
        val array = JSONArray()
        templates.forEach { (name, colors) ->
            array.put(JSONObject().put("name", name).put("colors", JSONArray(colors)))
        }
        prefs.edit().putString("colorTemplates", array.toString()).apply()
    }

    /** How the chrome is dressed. Material until someone says otherwise. */
    var skin: Skin
        get() = runCatching { Skin.valueOf(prefs.getString(SKIN, "")!!) }
            .getOrDefault(Skin.MATERIAL)
        set(value) = prefs.edit().putString(SKIN, value.name).apply()

    /** Whether the toolbar sits flush along the top edge rather than floating. */
    var docked: Boolean
        get() = prefs.getBoolean(DOCKED, false)
        set(value) = prefs.edit().putBoolean(DOCKED, value).apply()

    /** Whether the tip is drawn ahead of the pen. See InkCanvasView. */
    var prediction: Boolean
        get() = prefs.getBoolean(PREDICTION, true)
        set(value) = prefs.edit().putBoolean(PREDICTION, value).apply()

    /** Whether sharpening waits for the pinch to end. See InkCanvasView. */
    var deferDetail: Boolean
        get() = prefs.getBoolean(DEFER_DETAIL, true)
        set(value) = prefs.edit().putBoolean(DEFER_DETAIL, value).apply()

    /**
     * The page a note was left on, so opening it again carries on from there
     * rather than from the top. Per note, and in preferences rather than in the
     * note: where somebody stopped reading is not part of the document.
     */
    fun lastPage(noteId: String): Int = prefs.getInt("$LAST_PAGE$noteId", 0)

    fun setLastPage(noteId: String, page: Int) =
        prefs.edit().putInt("$LAST_PAGE$noteId", page).apply()

    /** Which note the reference panel was last pointed at. */
    var referenceNote: String?
        get() = prefs.getString(REFERENCE_NOTE, null)
        set(value) = prefs.edit().putString(REFERENCE_NOTE, value).apply()

    /** Whether the reference panel refits its page whenever it is resized. */
    var referenceFit: Boolean
        get() = prefs.getBoolean(REFERENCE_FIT, true)
        set(value) = prefs.edit().putBoolean(REFERENCE_FIT, value).apply()

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
                    width = item.optDouble("width", fallback.width.toDouble()).toFloat()
                        .takeIf { it.isFinite() }?.coerceIn(widthRange(mode)) ?: fallback.width,
                    pressure = item.optBoolean("pressure", fallback.pressure),
                    maxWidth = item.optDouble("maxWidth", fallback.maxWidth.toDouble()).toFloat()
                        .takeIf { it.isFinite() }?.coerceIn(0f, widthRange(mode).endInclusive) ?: 0f,
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
                    .put("pressure", pen.pressure)
                    .put("maxWidth", pen.maxWidth.toDouble()),
            )
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    companion object {
        private const val KEY = "tools"
        private const val DOCKED = "docked"
        private const val PREDICTION = "prediction"
        private const val DEFER_DETAIL = "deferDetail"
        private const val SKIN = "skin"
        private const val LAST_PAGE = "lastPage:"
        private const val REFERENCE_NOTE = "referenceNote"
        private const val REFERENCE_FIT = "referenceFit"

        /** The thickness slider's range depends on what is being made thick. */
        fun widthRange(mode: EditMode): ClosedFloatingPointRange<Float> = when (mode) {
            // The low end is deliberately a real hairline. Erasing at the low
            // end is still reliable because InkCanvasView tests the point under
            // the pen on ACTION_DOWN instead of waiting for a move segment.
            EditMode.ERASE -> 1f..240f
            EditMode.HIGHLIGHTER, EditMode.MASK -> 1f..180f
            else -> 0.25f..12f
        }

        /** The same range with the tool's own ceiling, when one has been set. */
        fun widthRange(mode: EditMode, pen: PenPreset?): ClosedFloatingPointRange<Float> {
            val base = widthRange(mode)
            val top = pen?.maxWidth ?: 0f
            if (!top.isFinite() || top <= base.start) return base
            return base.start..top.coerceAtMost(base.endInclusive)
        }

        /**
         * The ceilings offered, as a fraction of the tool's own. Named rather
         * than numbered, because "얇게" is what somebody wants and 8.0 is not.
         */
        fun widthCeilings(mode: EditMode): List<Pair<String, Float>> {
            val full = widthRange(mode).endInclusive
            return listOf(
                "얇게" to full / 3f,
                "중간" to full * 2f / 3f,
                "최대" to full,
            )
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
