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
    /** Which pen or pencil: one of [nibsFor] the tool. Pens and pencils only. */
    val nib: Tool = Tool.PEN,
    /**
     * How far ahead of the pen this tool draws: [LEAD_AUTO] to follow the
     * display, 0 for no prediction, or a fixed 1-[MAX_LEAD_MS] ms. Per tool,
     * like everything below: a pencil sketched slowly and a pen written fast
     * want different hands.
     */
    val leadMs: Int = LEAD_AUTO,
    /** Stroke smoothing, 0-100. */
    val stabilizer: Int = 0,
    /** 0 solid, 1 dotted, 2 dashed, 3 dash-dot. */
    val linePattern: Int = 0,
    /** Whether a drawn shape is straightened into a clean one. */
    val autoShapes: Boolean = false,
    /** Every stroke a straight line from where the pen lands to where it lifts. */
    val straightLine: Boolean = false,
) {
    /** The tool as it actually draws, which is where pressure and the nib are decided. */
    fun drawingTool(): Tool = when {
        tool == Tool.PENCIL -> nib.takeIf { kind -> PENCIL_NIBS.any { it.first == kind } } ?: Tool.PENCIL
        tool != Tool.PEN -> tool
        nib != Tool.PEN -> nib
        pressure -> Tool.PRESSURE_PEN
        else -> Tool.PEN
    }
}

/** [PenPreset.leadMs] for following the display's refresh rate. */
const val LEAD_AUTO = -1

/** The longest fixed prediction offered. */
const val MAX_LEAD_MS = 12

/**
 * Whether a tool's colour may be see-through. A pen and tape are solid by
 * nature; a pencil and a highlighter are partly about how much shows through.
 */
fun allowsOpacity(mode: EditMode): Boolean =
    mode == EditMode.PENCIL || mode == EditMode.HIGHLIGHTER || mode == EditMode.SHAPE

/** The pens the pen tool can be, in the order the settings offer them. */
val PEN_NIBS = listOf(
    Tool.PEN to "볼펜", Tool.FOUNTAIN to "만년필", Tool.SPEED_FOUNTAIN to "만년필(속도)", Tool.CALLIGRAPHY to "캘리그라피",
    Tool.VANISHING_PEN to "기화펜",
)

/** The looks the pencil tool can draw with, in the order the settings offer them. */
val PENCIL_NIBS = listOf(
    Tool.PENCIL to "흑연", Tool.SOFT_PENCIL to "소프트", Tool.STAMP to "스탬프", Tool.BUBBLE to "버블", Tool.SPRAY to "스프레이",
)

/** The kinds offered for a tool, or none when it has only the one. */
fun nibsFor(tool: Tool): List<Pair<Tool, String>> = when (tool) {
    Tool.PEN -> PEN_NIBS
    Tool.PENCIL -> PENCIL_NIBS
    else -> emptyList()
}

// What one finger, two fingers and multi-finger double taps do. See InkCanvasView.
const val FINGER_SCROLL = 0
const val FINGER_IGNORED = 1
const val FINGER_DRAW = 2
const val TWO_ZOOM_PAN = 0
const val TWO_SCROLL = 1
const val TWO_IGNORED = 2
const val TAP_NONE = 0
const val TAP_UNDO = 1
const val TAP_REDO = 2
const val PEN_BUTTON_ERASE = 0
const val PEN_BUTTON_LASER = 1
const val PEN_BUTTON_LASSO = 2

/** What the eraser takes and which pen gestures do more than write. See InkCanvasView. */
data class CanvasGestures(
    val eraseInk: Boolean = true,
    val eraseHighlighter: Boolean = true,
    val eraseTape: Boolean = true,
    val scribbleErase: Boolean = false,
    val circleToLasso: Boolean = false,
    val lassoWholeOnly: Boolean = false,
    val lassoInk: Boolean = true,
    val lassoHighlighter: Boolean = true,
    val lassoPictures: Boolean = true,
    val lassoText: Boolean = true,
    val selectLocked: Boolean = false,
    val eraseLocked: Boolean = false,
    val eraseImages: Boolean = false,
    val eraseText: Boolean = false,
    val holdToDraw: Boolean = false,
    val snapToAlign: Boolean = true,
    val keepAspect: Boolean = true,
    val oneFinger: Int = FINGER_SCROLL,
    val twoFingers: Int = TWO_ZOOM_PAN,
    val zoomLocked: Boolean = false,
    val doubleTapZoom: Boolean = false,
    val twoFingerTap: Int = TAP_UNDO,
    val threeFingerTap: Int = TAP_REDO,
    val longPressMenu: Boolean = true,
    val linkOverlay: Boolean = true,
    val eraserReturns: Boolean = false,
    val penButton: Int = PEN_BUTTON_ERASE,
    /** The eraser's size follows how hard the pen presses. */
    val pressureEraser: Boolean = false,
    /** How strongly pressure reaches the ink, 0-100; [PRESSURE_RESPONSE_DEFAULT] is the stylus as it reports. */
    val pressureResponse: Int = PRESSURE_RESPONSE_DEFAULT,
    /** Stroke smoothing as a sliding-window average instead of the adaptive filter. */
    val windowedStabilizer: Boolean = false,
    /** The lasso drawn as a rectangle from corner to corner rather than freehand. */
    val lassoRect: Boolean = false,
    /** The pen's 480 Hz in-between travel shown ahead of the tip: [SUBFRAME_OFF], [SUBFRAME_BLUR] or [SUBFRAME_GHOST]. */
    val subframe: Int = SUBFRAME_OFF,
    /**
     * Whether three fingers on the page do anything: the three-finger double
     * tap, opening and closing the reference panel, moving and resizing it,
     * and turning the note. Off, three fingers are simply ignored.
     */
    val threeFingerGestures: Boolean = true,
)

const val PRESSURE_RESPONSE_DEFAULT = 10

/** How far the pressure eraser grows past its set size at full pressure. */
const val PRESSURE_ERASER_MAX_SCALE = 2.5f

/** The pressure eraser's size as a multiple of the set size: 1 at the lightest touch, [PRESSURE_ERASER_MAX_SCALE] pressed hard. */
fun pressureEraserScale(pressure: Float): Float =
    1f + (PRESSURE_ERASER_MAX_SCALE - 1f) * pressure.coerceIn(0f, 1f)

/**
 * Pressure through the response curve: [PRESSURE_RESPONSE_DEFAULT] leaves it
 * alone, 0 ignores it (always full), and higher values thin out a light touch
 * more, so only a firm press reaches the full width.
 */
fun pressureCurve(pressure: Float, response: Int): Float {
    val p = pressure.coerceIn(0f, 1f)
    if (response == PRESSURE_RESPONSE_DEFAULT) return p
    if (response <= 0) return 1f
    return Math.pow(p.toDouble(), response / PRESSURE_RESPONSE_DEFAULT.toDouble()).toFloat()
}

fun InkCanvasView.applyGestures(gestures: CanvasGestures) {
    eraseInk = gestures.eraseInk
    eraseHighlighter = gestures.eraseHighlighter
    eraseTape = gestures.eraseTape
    scribbleErase = gestures.scribbleErase
    circleToLasso = gestures.circleToLasso
    lassoWholeOnly = gestures.lassoWholeOnly
    lassoInk = gestures.lassoInk
    lassoHighlighter = gestures.lassoHighlighter
    lassoPictures = gestures.lassoPictures
    lassoText = gestures.lassoText
    selectLocked = gestures.selectLocked
    eraseLocked = gestures.eraseLocked
    eraseImages = gestures.eraseImages
    eraseText = gestures.eraseText
    holdToDraw = gestures.holdToDraw
    snapToAlign = gestures.snapToAlign
    keepAspect = gestures.keepAspect
    oneFinger = gestures.oneFinger
    twoFingers = gestures.twoFingers
    zoomLocked = gestures.zoomLocked
    doubleTapZoom = gestures.doubleTapZoom
    twoFingerTap = gestures.twoFingerTap
    threeFingerTap = gestures.threeFingerTap
    penButton = gestures.penButton
    pressureEraser = gestures.pressureEraser
    pressureResponse = gestures.pressureResponse.coerceIn(0, 100)
    windowedStabilizer = gestures.windowedStabilizer
    lassoRect = gestures.lassoRect
    subframeMode = gestures.subframe
    threeFingerGestures = gestures.threeFingerGestures
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

    /** How wide the settings sidebar was left, in dp; clamped to the screen when it opens. */
    var settingsSidebarWidth: Float
        get() = prefs.getFloat("settingsSidebarWidth", SETTINGS_SIDEBAR_DEFAULT_DP)
            .takeIf { it.isFinite() && it > 0f } ?: SETTINGS_SIDEBAR_DEFAULT_DP
        set(value) = prefs.edit().putFloat("settingsSidebarWidth", value).apply()

    /** What the appearance preview sits on: an ordinal of [PreviewBackground]. */
    var previewBackground: Int
        get() = prefs.getInt("appearancePreviewBackground", 0)
        set(value) = prefs.edit().putInt("appearancePreviewBackground", value).apply()

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

    /** Zero follows display refresh; 4/6/9 are fixed diagnostic comparison points. */
    var predictionLeadMs: Int
        get() = prefs.getInt(PREDICTION_LEAD_MS, 0).let {
            if (it == 4 || it == 6 || it == 9) it else 0
        }
        set(value) = prefs.edit().putInt(
            PREDICTION_LEAD_MS,
            if (value == 4 || value == 6 || value == 9) value else 0,
        ).apply()

    /** Whether sharpening waits for the pinch to end. See InkCanvasView. */
    var deferDetail: Boolean
        get() = prefs.getBoolean(DEFER_DETAIL, true)
        set(value) = prefs.edit().putBoolean(DEFER_DETAIL, value).apply()

    var stabilizer: Int
        get() = prefs.getInt("stabilizer", 0).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("stabilizer", value.coerceIn(0, 100)).apply()

    var autoShapes: Boolean
        get() = prefs.getBoolean("autoShapes", false)
        set(value) = prefs.edit().putBoolean("autoShapes", value).apply()

    /** Which of the colour pickers the tool settings open on. See ColorPickerMode. */
    var colorPickerMode: Int
        get() = prefs.getInt("colorPickerMode", 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt("colorPickerMode", value).apply()

    /** Whether the colour pickers' bars and fields sit joined in one block, or apart. */
    var colorPickerJoined: Boolean
        get() = prefs.getBoolean("colorPickerJoined", true)
        set(value) = prefs.edit().putBoolean("colorPickerJoined", value).apply()

    var axisSnap: Boolean
        get() = prefs.getBoolean("axisSnap", true)
        set(value) = prefs.edit().putBoolean("axisSnap", value).apply()

    /** 0 solid, 1 dotted, 2 dashed, 3 dash-dot. */
    var dottedPattern: Int
        get() = prefs.getInt("dottedPattern", 0).coerceIn(0, 3)
        set(value) = prefs.edit().putInt("dottedPattern", value.coerceIn(0, 3)).apply()

    /** This note's own way of laying pages out, or the app-wide one it has not been given. */
    fun pageLayoutOf(noteId: String): PageLayoutMode =
        prefs.getString("layout:$noteId", null)?.let { name -> PageLayoutMode.entries.firstOrNull { it.name == name } }
            ?: pageLayout

    fun setPageLayoutOf(noteId: String, mode: PageLayoutMode) {
        prefs.edit().putString("layout:$noteId", mode.name).apply()
    }

    var pageLayout: PageLayoutMode
        get() = runCatching { PageLayoutMode.valueOf(
            prefs.getString("pageLayout", PageLayoutMode.VERTICAL.name)!!,
        ) }.getOrDefault(PageLayoutMode.VERTICAL)
        set(value) = prefs.edit().putString("pageLayout", value.name).apply()

    /** Writing orientation of the page surface, independent of device rotation. */
    var noteRotation: Int
        get() = prefs.getInt("noteRotation", 0).let { ((it % 360) + 360) % 360 }
        set(value) = prefs.edit().putInt("noteRotation", ((value % 360) + 360) % 360).apply()

    var highlighterAboveInk: Boolean
        get() = prefs.getBoolean("highlighterAboveInk", false)
        set(value) = prefs.edit().putBoolean("highlighterAboveInk", value).apply()

    var librarySort: Int
        get() = prefs.getInt("librarySort", 0)
        set(value) = prefs.edit().putInt("librarySort", value).apply()

    var partialEraser: Boolean
        get() = prefs.getBoolean("partialEraser", false)
        set(value) = prefs.edit().putBoolean("partialEraser", value).apply()

    var shapeCornerRadius: Float
        get() = prefs.getFloat("shapeCornerRadius", 0f)
        set(value) = prefs.edit().putFloat("shapeCornerRadius", value).apply()

    /** The swatches on the bar, in the order they sit; opaque RGB, the tool keeps its alpha. */
    var quickColors: List<Int>
        get() = prefs.getString("quickColors", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?: listOf(0xFF000000.toInt(), 0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt())
        set(value) = prefs.edit().putString("quickColors", value.distinct().take(MAX_QUICK_COLORS).joinToString(",")).apply()


    /** How pages are coloured on screen; see [InkCanvasView.pageTone]. */
    var pageTone: Int
        get() = prefs.getInt("pageTone", PAGE_TONE_NORMAL).coerceIn(PAGE_TONE_NORMAL, PAGE_TONE_NIGHT)
        set(value) = prefs.edit().putInt("pageTone", value).apply()

    /** Zooms, in percent, saved beside the fixed ones in the zoom menu. */
    var zoomPresets: List<Int>
        get() = prefs.getString("zoomPresets", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?.filter { it in ZOOM_PERCENT_RANGE }?.distinct()?.sorted() ?: emptyList()
        set(value) = prefs.edit().putString("zoomPresets", value.distinct().sorted().joinToString(",")).apply()

    /** A recording that finishes starts the next one in the note's list. Kept for [playMode]'s default. */
    var autoPlayNext: Boolean
        get() = prefs.getBoolean("autoPlayNext", false)
        set(value) = prefs.edit().putBoolean("autoPlayNext", value).apply()

    /** What a recording does at its end: [PLAY_STOP], [PLAY_NEXT] or [PLAY_LOOP_ONE]. */
    var playMode: Int
        get() = prefs.getInt("playMode", if (autoPlayNext) PLAY_NEXT else PLAY_STOP).coerceIn(PLAY_STOP, PLAY_LOOP_ONE)
        set(value) = prefs.edit().putInt("playMode", value).apply()

    var playbackSpeed: Float
        get() = prefs.getFloat("playbackSpeed", 1f).takeIf { it in PLAYBACK_SPEEDS } ?: 1f
        set(value) = prefs.edit().putFloat("playbackSpeed", value).apply()

    var gestures: CanvasGestures
        get() = CanvasGestures(
            eraseInk = prefs.getBoolean("eraseInk", true),
            eraseHighlighter = prefs.getBoolean("eraseHighlighter", true),
            eraseTape = prefs.getBoolean("eraseTape", true),
            scribbleErase = prefs.getBoolean("scribbleErase", false),
            circleToLasso = prefs.getBoolean("circleToLasso", false),
            lassoWholeOnly = prefs.getBoolean("lassoWholeOnly", false),
            lassoInk = prefs.getBoolean("lassoInk", true),
            lassoHighlighter = prefs.getBoolean("lassoHighlighter", true),
            lassoPictures = prefs.getBoolean("lassoPictures", true),
            lassoText = prefs.getBoolean("lassoText", true),
            selectLocked = prefs.getBoolean("selectLocked", false),
            eraseLocked = prefs.getBoolean("eraseLocked", false),
            eraseImages = prefs.getBoolean("eraseImages", false),
            eraseText = prefs.getBoolean("eraseText", false),
            holdToDraw = prefs.getBoolean("holdToDraw", false),
            snapToAlign = prefs.getBoolean("snapToAlign", true),
            keepAspect = prefs.getBoolean("keepAspect", true),
            oneFinger = prefs.getInt("oneFinger", FINGER_SCROLL),
            twoFingers = prefs.getInt("twoFingers", TWO_ZOOM_PAN),
            zoomLocked = prefs.getBoolean("zoomLocked", false),
            doubleTapZoom = prefs.getBoolean("doubleTapZoom", false),
            twoFingerTap = prefs.getInt("twoFingerTap", TAP_UNDO),
            threeFingerTap = prefs.getInt("threeFingerTap", TAP_REDO),
            longPressMenu = prefs.getBoolean("longPressMenu", true),
            linkOverlay = prefs.getBoolean("linkOverlay", true),
            eraserReturns = prefs.getBoolean("eraserReturns", false),
            penButton = prefs.getInt("penButton", PEN_BUTTON_ERASE),
            pressureEraser = prefs.getBoolean("pressureEraser", false),
            pressureResponse = prefs.getInt("pressureResponse", PRESSURE_RESPONSE_DEFAULT).coerceIn(0, 100),
            windowedStabilizer = prefs.getBoolean("windowedStabilizer", false),
            lassoRect = prefs.getBoolean("lassoRect", false),
            subframe = prefs.getInt("subframe", SUBFRAME_OFF).coerceIn(SUBFRAME_OFF, SUBFRAME_GHOST),
            threeFingerGestures = prefs.getBoolean("threeFingerGestures", true),
        )
        set(value) = prefs.edit()
            .putBoolean("eraseInk", value.eraseInk)
            .putBoolean("eraseHighlighter", value.eraseHighlighter)
            .putBoolean("eraseTape", value.eraseTape)
            .putBoolean("scribbleErase", value.scribbleErase)
            .putBoolean("circleToLasso", value.circleToLasso)
            .putBoolean("lassoWholeOnly", value.lassoWholeOnly)
            .putBoolean("lassoInk", value.lassoInk)
            .putBoolean("lassoHighlighter", value.lassoHighlighter)
            .putBoolean("lassoPictures", value.lassoPictures)
            .putBoolean("lassoText", value.lassoText)
            .putBoolean("selectLocked", value.selectLocked)
            .putBoolean("eraseLocked", value.eraseLocked)
            .putBoolean("eraseImages", value.eraseImages)
            .putBoolean("eraseText", value.eraseText)
            .putBoolean("holdToDraw", value.holdToDraw)
            .putBoolean("snapToAlign", value.snapToAlign)
            .putBoolean("keepAspect", value.keepAspect)
            .putInt("oneFinger", value.oneFinger)
            .putInt("twoFingers", value.twoFingers)
            .putBoolean("zoomLocked", value.zoomLocked)
            .putBoolean("doubleTapZoom", value.doubleTapZoom)
            .putInt("twoFingerTap", value.twoFingerTap)
            .putInt("threeFingerTap", value.threeFingerTap)
            .putBoolean("longPressMenu", value.longPressMenu)
            .putBoolean("linkOverlay", value.linkOverlay)
            .putBoolean("eraserReturns", value.eraserReturns)
            .putInt("penButton", value.penButton)
            .putBoolean("pressureEraser", value.pressureEraser)
            .putInt("pressureResponse", value.pressureResponse.coerceIn(0, 100))
            .putBoolean("windowedStabilizer", value.windowedStabilizer)
            .putBoolean("lassoRect", value.lassoRect)
            .putInt("subframe", value.subframe)
            .putBoolean("threeFingerGestures", value.threeFingerGestures)
            .apply()

    /** See [InkCanvasView.compatWetInk]; on by default where the front buffer is known to fail. */
    var compatWetInk: Boolean
        get() = prefs.getBoolean("compatWetInk", frontBufferInkUnreliable())
        set(value) = prefs.edit().putBoolean("compatWetInk", value).apply()

    /** Screen rendering only - see [InkCanvasView.meshInk]. */
    var meshInk: Boolean
        get() = prefs.getBoolean("meshInk", true)
        set(value) = prefs.edit().putBoolean("meshInk", value).apply()

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
        // Before these were per tool they were one set for every tool; a tool
        // that has never had its own starts from what that set was.
        val legacy = PenPreset(
            Tool.PEN, 0, 1f,
            leadMs = when {
                !prediction -> 0
                predictionLeadMs == 0 -> LEAD_AUTO
                else -> predictionLeadMs
            },
            stabilizer = stabilizer,
            linePattern = dottedPattern,
            autoShapes = autoShapes,
        )
        fun PenPreset.withLegacy() = copy(leadMs = legacy.leadMs, stabilizer = legacy.stabilizer,
            linePattern = legacy.linePattern, autoShapes = legacy.autoShapes)
        val defaults = DEFAULTS.mapValues { (_, pen) -> pen.withLegacy() }
        val raw = prefs.getString(KEY, null) ?: return defaults.solidWhereRequired()
        val saved = runCatching {
            val json = JSONObject(raw)
            DEFAULTS.keys.mapNotNull { mode ->
                val item = json.optJSONObject(mode.name) ?: return@mapNotNull null
                val fallback = defaults.getValue(mode)
                mode to PenPreset(
                    tool = fallback.tool,
                    colorArgb = item.optInt("color", fallback.colorArgb),
                    width = item.optDouble("width", fallback.width.toDouble()).toFloat()
                        .takeIf { it.isFinite() }?.coerceIn(widthRange(mode)) ?: fallback.width,
                    pressure = item.optBoolean("pressure", fallback.pressure),
                    maxWidth = item.optDouble("maxWidth", fallback.maxWidth.toDouble()).toFloat()
                        .takeIf { it.isFinite() }?.coerceIn(0f, widthRange(mode).endInclusive) ?: 0f,
                    nib = runCatching { Tool.valueOf(item.optString("nib", fallback.nib.name)) }
                        .getOrDefault(fallback.nib)
                        .takeIf { nib -> nibsFor(fallback.tool).any { it.first == nib } } ?: fallback.nib,
                    leadMs = item.optInt("leadMs", fallback.leadMs).let {
                        if (it == LEAD_AUTO) it else it.coerceIn(0, MAX_LEAD_MS)
                    },
                    stabilizer = item.optInt("stabilizer", fallback.stabilizer).coerceIn(0, 100),
                    linePattern = item.optInt("linePattern", fallback.linePattern).coerceIn(0, 3),
                    autoShapes = item.optBoolean("autoShapes", fallback.autoShapes),
                    straightLine = item.optBoolean("straightLine", fallback.straightLine),
                )
            }.toMap()
        }.getOrNull().orEmpty()
        // Defaults underneath, so a tool added in a later version arrives set up
        // rather than missing.
        return (defaults + saved).solidWhereRequired()
    }

    /** A pen or tape saved see-through before that stopped being offered draws solid. */
    private fun Map<EditMode, PenPreset>.solidWhereRequired(): Map<EditMode, PenPreset> =
        mapValues { (mode, pen) ->
            if (allowsOpacity(mode) || mode == EditMode.ERASE) pen
            else pen.copy(colorArgb = pen.colorArgb or OPAQUE)
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
                    .put("maxWidth", pen.maxWidth.toDouble())
                    .put("nib", pen.nib.name)
                    .put("leadMs", pen.leadMs)
                    .put("stabilizer", pen.stabilizer)
                    .put("linePattern", pen.linePattern)
                    .put("autoShapes", pen.autoShapes)
                    .put("straightLine", pen.straightLine),
            )
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    companion object {
        private const val OPAQUE = -0x1000000
        const val MAX_QUICK_COLORS = 8
        private const val KEY = "tools"
        private const val DOCKED = "docked"
        private const val PREDICTION = "prediction"
        private const val PREDICTION_LEAD_MS = "predictionLeadMs"
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
            // Dabs and spray read as a band, not a line, and want room to be broad.
            EditMode.PENCIL -> 0.25f..80f
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
            EditMode.PENCIL to PenPreset(Tool.PENCIL, 0xFFD32F2F.toInt(), 24f, nib = Tool.STAMP),
            EditMode.HIGHLIGHTER to PenPreset(Tool.HIGHLIGHTER, 0x66F9A825, 20f),
            EditMode.MASK to PenPreset(Tool.MASK, PageMask.DEFAULT_MASK_COLOR, 20f),
            EditMode.SHAPE to PenPreset(Tool.PEN, 0xFF1976D2.toInt(), 5f),
            EditMode.ERASE to PenPreset(Tool.ERASER, 0xFF000000.toInt(), 24f),
        )
    }
}
