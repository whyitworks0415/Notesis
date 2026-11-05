package com.notesis

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which way a ground's colours run. */
internal object GroundDirection {
    const val DOWN = 0
    const val ACROSS = 1
    const val DIAGONAL = 2
    const val CENTRE = 3
}

/**
 * The colours the library is laid out in: the ground's colours, where each
 * sits along it and which way they run, the cards on it, and the quiet
 * words and lines between them. The accent stays the user's.
 */
internal data class HomeLookColors(
    /** Each colour of the ground and its place along it, 0 to 1, in order. */
    val stops: List<Pair<Float, Color>>,
    val direction: Int,
    /** A card's body, and a page's paper inside it. */
    val card: Color,
    val paper: Color,
    /** Words, quieter words, and the faint lines between things. */
    val ink: Color,
    val quiet: Color,
    val line: Color,
    /** For a ground of the user's own: its colours where they were put, over [base]. */
    val points: List<GroundPoint> = emptyList(),
    val base: Color = Color.Unspecified,
) {
    /** The colour along the top edge, for what fades into the ground there. */
    val top: Color get() = if (points.isEmpty()) stops.first().second else sampleGround(points, base, 0.5f, 0f)

    /** Draws the ground over the whole of [scope]. */
    fun drawGround(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        if (points.isEmpty()) {
            drawRect(brush())
            return@with
        }
        drawRect(base)
        val reach = maxOf(size.width, size.height)
        for (p in points) {
            val centre = Offset(p.x * size.width, p.y * size.height)
            val radius = (p.spread * reach).coerceAtLeast(1f)
            val c = Color(p.color)
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = p.strength.coerceIn(0f, 1f)), c.copy(alpha = 0f)), centre, radius),
                radius = radius, center = centre,
            )
        }
    }

    fun brush(): Brush {
        val s = (if (stops.size == 1) listOf(0f to stops[0].second, 1f to stops[0].second) else stops).toTypedArray()
        return when (direction) {
            GroundDirection.ACROSS -> Brush.horizontalGradient(*s)
            GroundDirection.DIAGONAL -> Brush.linearGradient(*s)
            GroundDirection.CENTRE -> Brush.radialGradient(*s)
            else -> Brush.verticalGradient(*s)
        }
    }
}

/**
 * Cards, paper, words and lines that sit on a ground of [stops]: a light
 * ground gets near-white cards and dark words, a dark one the other way
 * round, each tinted with the ground so they belong to it.
 */
internal fun lookOn(stops: List<Pair<Float, Color>>, direction: Int = GroundDirection.DOWN): HomeLookColors =
    lookAround(averageColor(stops.map { it.second }), stops, direction)

/** The cards, words and lines for a ground whose colours come to [average] on the whole. */
internal fun lookAround(
    average: Color,
    stops: List<Pair<Float, Color>>,
    direction: Int = GroundDirection.DOWN,
    points: List<GroundPoint> = emptyList(),
    base: Color = Color.Unspecified,
): HomeLookColors {
    return if (average.luminance() >= 0.32f) HomeLookColors(
        stops, direction,
        card = lerp(average, Color.White, 0.84f),
        paper = lerp(average, Color.White, 0.95f),
        ink = lerp(average, Color(0xFF101010), 0.9f),
        quiet = lerp(average, Color(0xFF1A1A1A), 0.62f),
        line = lerp(average, Color(0xFF000000), 0.1f),
        points = points, base = base,
    ) else HomeLookColors(
        stops, direction,
        card = lerp(average, Color.White, 0.1f),
        paper = lerp(average, Color.White, 0.16f),
        ink = lerp(average, Color.White, 0.92f),
        quiet = lerp(average, Color.White, 0.62f),
        line = lerp(average, Color.White, 0.18f),
        points = points, base = base,
    )
}

internal fun averageColor(colors: List<Color>): Color {
    if (colors.isEmpty()) return Color.White
    return Color(colors.map { it.red }.average().toFloat(), colors.map { it.green }.average().toFloat(),
        colors.map { it.blue }.average().toFloat())
}

private fun evenly(vararg colors: Long): List<Pair<Float, Color>> =
    if (colors.size == 1) listOf(0f to Color(colors[0]))
    else colors.mapIndexed { i, c -> i / (colors.size - 1f) to Color(c) }

/**
 * The library's ready-made looks. [DEFAULT] is the app as it was, the
 * theme's own surface; [CUSTOM] is the one put together in
 * [HomeGradientEditor]. The rest are each one idea of a desk, light and
 * dark, chosen to sit under a page without arguing with it.
 */
internal enum class HomeLook(val label: String, private val light: HomeLookColors?, private val dark: HomeLookColors?) {
    DEFAULT("Default", null, null),
    PAPER(
        "Warm paper",
        HomeLookColors(evenly(0xFFF6F2EA, 0xFFEEE8DC), GroundDirection.DOWN, Color(0xFFFFFDF8), Color(0xFFFFFEFA),
            Color(0xFF2B2722), Color(0xFF6E665A), Color(0xFFE3DCCD)),
        HomeLookColors(evenly(0xFF1E1B17, 0xFF161411), GroundDirection.DOWN, Color(0xFF2A2621), Color(0xFF332E28),
            Color(0xFFEDE6DA), Color(0xFFB3A99A), Color(0xFF3D372F)),
    ),
    MIST(
        "Mist",
        HomeLookColors(evenly(0xFFF0F3F8, 0xFFE5E9F3), GroundDirection.DOWN, Color(0xFFFFFFFF), Color(0xFFFFFFFF),
            Color(0xFF1F2430), Color(0xFF5F6779), Color(0xFFDADFEA)),
        HomeLookColors(evenly(0xFF141922, 0xFF0E1118), GroundDirection.DOWN, Color(0xFF1D2330), Color(0xFF252C3A),
            Color(0xFFE4E8F1), Color(0xFFA3ABBD), Color(0xFF323A4A)),
    ),
    SAGE(
        "Sage",
        HomeLookColors(evenly(0xFFF0F3EE, 0xFFE5EBE2), GroundDirection.DOWN, Color(0xFFFBFDF9), Color(0xFFFFFFFC),
            Color(0xFF1F261E), Color(0xFF5C6758), Color(0xFFD6DFD2)),
        HomeLookColors(evenly(0xFF141914, 0xFF0F130F), GroundDirection.DOWN, Color(0xFF1E251E), Color(0xFF263026),
            Color(0xFFE2EADF), Color(0xFFA3B09E), Color(0xFF323C31)),
    ),
    DUSK(
        "Dusk",
        HomeLookColors(evenly(0xFFF8F0EB, 0xFFEFE7F0), GroundDirection.DOWN, Color(0xFFFFFBFA), Color(0xFFFFFDFC),
            Color(0xFF2A2124), Color(0xFF6F6064), Color(0xFFEADDDA)),
        HomeLookColors(evenly(0xFF1E171A, 0xFF17121A), GroundDirection.DOWN, Color(0xFF2A2126), Color(0xFF33292F),
            Color(0xFFF0E4E8), Color(0xFFB8A6AD), Color(0xFF3E3237)),
    ),
    OCEAN("Ocean", lookOn(evenly(0xFFE7F4F7, 0xFFDCE8F6, 0xFFE6E4F5), GroundDirection.DIAGONAL),
        lookOn(evenly(0xFF0E1E26, 0xFF101A2C, 0xFF16142A), GroundDirection.DIAGONAL)),
    LAVENDER("Lavender", lookOn(evenly(0xFFF1EDF9, 0xFFE9E3F6)), lookOn(evenly(0xFF1B1726, 0xFF14111E))),
    PEACH("Peach", lookOn(evenly(0xFFFDF1E9, 0xFFF9E6DC, 0xFFF6EAE6)), lookOn(evenly(0xFF241A15, 0xFF1E1512))),
    MINT("Mint", lookOn(evenly(0xFFEAF7F1, 0xFFE2F2EE), GroundDirection.ACROSS),
        lookOn(evenly(0xFF0F1D19, 0xFF0D1816), GroundDirection.ACROSS)),
    AURORA("Aurora", lookOn(evenly(0xFFE4F5F1, 0xFFE8F0E2, 0xFFEDE6F6), GroundDirection.DIAGONAL),
        lookOn(evenly(0xFF0C1F1E, 0xFF13201A, 0xFF1A1428), GroundDirection.DIAGONAL)),
    SUNSET("Sunset", lookOn(evenly(0xFFFFEBDD, 0xFFFBE0E6, 0xFFEADFF4), GroundDirection.DIAGONAL),
        lookOn(evenly(0xFF2A1810, 0xFF26121C, 0xFF1A1328), GroundDirection.DIAGONAL)),
    GRAPHITE("Graphite", lookOn(evenly(0xFFEDEDEE, 0xFFE2E3E5)), lookOn(evenly(0xFF1A1B1D, 0xFF111214))),
    SAND("Sand", lookOn(evenly(0xFFF4EDE1, 0xFFEADFCB), GroundDirection.CENTRE), lookOn(evenly(0xFF221D15, 0xFF18140E), GroundDirection.CENTRE)),
    CUSTOM("Your own", null, null),
    ;

    fun colors(dark: Boolean): HomeLookColors? = when (this) {
        CUSTOM -> HomeLooks.custom.look(dark)
        else -> if (dark) this.dark else light
    }
}

/** What a colour at no strength is: the plain ground under every look. */
internal fun groundBase(dark: Boolean): Color = if (dark) Color(0xFF151515) else Color(0xFFF7F6F3)

/**
 * One colour of a ground of the user's own: which, where it is put on the
 * screen (0 to 1 across and down), how strongly it shows at that spot
 * (0 to 1), and how far it reaches before it has faded into the ground, as a
 * share of the screen's longer side.
 */
internal data class GroundPoint(val color: Int, val x: Float, val y: Float, val strength: Float, val spread: Float = 0.7f)

/**
 * The ground at ([x], [y]), each 0 to 1: [base] with every point laid over
 * it in turn, at its strength where it sits and fading to nothing at its
 * reach - what [HomeLookColors.drawGround] paints, on a square.
 */
internal fun sampleGround(points: List<GroundPoint>, base: Color, x: Float, y: Float): Color {
    var colour = base
    for (p in points) {
        val distance = kotlin.math.hypot(x - p.x, y - p.y)
        val reach = p.spread.coerceAtLeast(0.001f)
        val weight = p.strength.coerceIn(0f, 1f) * (1f - distance / reach).coerceIn(0f, 1f)
        colour = lerp(colour, Color(p.color), weight)
    }
    return colour
}

/**
 * A ground of the user's own: one to [MAX_POINTS] colours, each put where it
 * was dragged, over a [base] colour (null for the plain ground of the theme).
 */
internal data class CustomGround(val points: List<GroundPoint>, val base: Int? = null) {
    fun baseColor(dark: Boolean): Color = base?.let { Color(it) } ?: groundBase(dark)

    /** The look this ground makes: the ground itself, and the cards and words that sit on it. */
    fun look(dark: Boolean): HomeLookColors {
        val ground = baseColor(dark)
        // The ground on the whole, from a grid of spots across it.
        val samples = (0..4).flatMap { i -> (0..4).map { j -> sampleGround(points, ground, i / 4f, j / 4f) } }
        return lookAround(averageColor(samples), listOf(0f to ground), GroundDirection.DOWN, points, ground)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        base?.let { put("base", it) }
        put("points", JSONArray(points.map {
            JSONObject().put("color", it.color).put("x", it.x.toDouble()).put("y", it.y.toDouble())
                .put("strength", it.strength.toDouble()).put("spread", it.spread.toDouble())
        }))
    }

    companion object {
        const val MAX_POINTS = 4

        val DEFAULT = CustomGround(listOf(
            GroundPoint(0xFFB9D4F5.toInt(), 0.15f, 0.1f, 0.7f, 0.8f),
            GroundPoint(0xFFF2C6DE.toInt(), 0.9f, 0.85f, 0.6f, 0.8f),
        ))

        fun fromJson(json: JSONObject): CustomGround? = runCatching {
            val base = if (json.has("base")) json.getInt("base") else null
            val points = if (json.has("points")) {
                val array = json.getJSONArray("points")
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    GroundPoint(o.getInt("color"), o.getDouble("x").toFloat().coerceIn(0f, 1f),
                        o.getDouble("y").toFloat().coerceIn(0f, 1f), o.getDouble("strength").toFloat().coerceIn(0f, 1f),
                        o.optDouble("spread", 0.7).toFloat().coerceIn(0.05f, 1.5f))
                }
            } else {
                // 0.90.3 kept colours along one line: each becomes a point on it.
                val direction = json.optInt("direction", GroundDirection.DOWN)
                val array = json.getJSONArray("stops")
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    val at = o.getDouble("position").toFloat().coerceIn(0f, 1f)
                    val (x, y) = when (direction) {
                        GroundDirection.ACROSS -> at to 0.5f
                        GroundDirection.DIAGONAL -> at to at
                        GroundDirection.CENTRE -> 0.5f to 0.5f
                        else -> 0.5f to at
                    }
                    GroundPoint(o.getInt("color"), x, y, o.getDouble("strength").toFloat().coerceIn(0f, 1f))
                }
            }.take(MAX_POINTS)
            if (points.isEmpty()) null else CustomGround(points, base)
        }.getOrNull()
    }
}

/** The look chosen, and the ground of the user's own, kept on this tablet. */
internal object HomeLooks {
    var look by mutableStateOf(HomeLook.DEFAULT)
        private set
    var custom by mutableStateOf(CustomGround.DEFAULT)
        private set

    fun load(context: Context) {
        val saved = prefs(context).getString(KEY, null)
        look = HomeLook.entries.firstOrNull { it.name == saved } ?: HomeLook.DEFAULT
        custom = prefs(context).getString(CUSTOM_KEY, null)
            ?.let { runCatching { CustomGround.fromJson(JSONObject(it)) }.getOrNull() } ?: CustomGround.DEFAULT
    }

    fun set(context: Context, value: HomeLook) {
        look = value
        prefs(context).edit().putString(KEY, value.name).apply()
    }

    fun setCustom(context: Context, ground: CustomGround) {
        custom = ground
        prefs(context).edit().putString(CUSTOM_KEY, ground.toJson().toString()).apply()
        set(context, HomeLook.CUSTOM)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("pens", Context.MODE_PRIVATE)
    private const val KEY = "homeLook"
    private const val CUSTOM_KEY = "homeGround"
}

/**
 * The library in its look: its surfaces, words and lines taken from the
 * look, everything else - the accent above all - left as the theme has it.
 */
@Composable
internal fun HomeLookTheme(content: @Composable () -> Unit) {
    val colors = HomeLooks.look.colors(isDarkTheme())
    if (colors == null) {
        content()
        return
    }
    val base = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = base.copy(
            background = colors.top,
            onBackground = colors.ink,
            surface = colors.card,
            onSurface = colors.ink,
            onSurfaceVariant = colors.quiet,
            outlineVariant = colors.line,
            surfaceContainerLowest = colors.paper,
            surfaceContainerLow = colors.card,
            surfaceContainer = colors.card,
        ),
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}

/** The look in use, or null for the theme's own plain surface. */
@Composable
internal fun homeLookColors(): HomeLookColors? = HomeLooks.look.colors(isDarkTheme())

/** The colour at the top of the look's ground, for what fades into it; null for the theme's surface. */
@Composable
internal fun homeLookTop(): Color? = HomeLooks.look.colors(isDarkTheme())?.top

/** A small desk with two cards on it, in [colors]; the theme's surface for none. */
@Composable
private fun LookSwatch(colors: HomeLookColors?, chosen: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val card = colors?.card ?: scheme.surfaceContainerLowest
    Box(
        modifier
            .clip(Ui.Shape.chip)
            .background(scheme.surface)
            .drawBehind { colors?.drawGround(this) }
            .border(if (chosen) 2.5.dp else 1.dp, if (chosen) scheme.primary else scheme.outlineVariant, Ui.Shape.chip)
            .padding(Ui.Space.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
            repeat(2) { Box(Modifier.width(22.dp).height(32.dp).clip(Ui.Shape.badge).background(card)) }
        }
    }
}

/** Each look as a little desk, the one in use ringed; the last makes or changes the user's own. */
@Composable
internal fun HomeLookPicker(onPicked: (HomeLook) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = isDarkTheme()
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        HomeLook.entries.forEach { look ->
            val chosen = HomeLooks.look == look
            Column(
                Modifier
                    .clip(Ui.Shape.control)
                    .clickable(role = Role.RadioButton) {
                        if (look == HomeLook.CUSTOM) editing = true
                        else { HomeLooks.set(context, look); onPicked(look) }
                    }
                    .semantics { contentDescription = look.label; selected = chosen }
                    .padding(Ui.Space.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LookSwatch(look.colors(dark), chosen, Modifier.size(72.dp, 54.dp))
                Text(if (look == HomeLook.CUSTOM && chosen) "Edit yours" else look.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Ui.Space.xs))
            }
        }
    }
    if (editing) HomeGradientEditor(
        onDismiss = { editing = false },
        onSave = { ground ->
            HomeLooks.setCustom(context, ground)
            editing = false
            onPicked(HomeLook.CUSTOM)
        },
    )
}

/**
 * Puts a ground together on a picture of the screen: each colour is a spot
 * put where it is dragged, shown as strongly as its slider says and reaching
 * as far as the other one says, over a base colour. Tap a spot to choose it;
 * the cards drawn on the picture show how the library will read on it.
 */
@Composable
internal fun HomeGradientEditor(onDismiss: () -> Unit, onSave: (CustomGround) -> Unit) {
    val dark = isDarkTheme()
    var ground by remember { mutableStateOf(HomeLooks.custom) }
    // Which spot the controls below are for; -1 for the base colour.
    var picked by remember { mutableIntStateOf(0) }
    val points = ground.points
    val index = if (picked < 0) -1 else picked.coerceIn(0, points.lastIndex)
    fun update(i: Int, change: (GroundPoint) -> GroundPoint) {
        ground = ground.copy(points = points.mapIndexed { j, p -> if (j == i) change(p) else p })
    }
    val look = ground.look(dark)
    val screen = androidx.compose.ui.platform.LocalConfiguration.current
    val aspect = (screen.screenWidthDp.toFloat() / screen.screenHeightDp.coerceAtLeast(1)).coerceIn(0.5f, 2f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your own look") },
        text = {
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                Text("Drag a colour to where you want it on the screen; tap one to change it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                GroundCanvas(ground, look, index, aspect, onPick = { picked = it },
                    onMove = { i, x, y -> update(i) { it.copy(x = x, y = y) } })
                // The spots, the base under them, and a new one.
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.padding(end = Ui.Space.s).size(36.dp).clip(Ui.Shape.chip)
                            .background(ground.baseColor(dark))
                            .border(if (index == -1) 3.dp else 1.dp,
                                if (index == -1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, Ui.Shape.chip)
                            .clickable(role = Role.RadioButton) { picked = -1 }
                            .semantics { contentDescription = "Base colour"; selected = index == -1 },
                    )
                    points.forEachIndexed { i, p ->
                        Box(
                            Modifier.padding(end = Ui.Space.s).size(32.dp).clip(CircleShape)
                                .background(Color(p.color))
                                .border(if (i == index) 3.dp else 1.dp,
                                    if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable(role = Role.RadioButton) { picked = i }
                                .semantics { contentDescription = "Colour ${i + 1}"; selected = i == index },
                        )
                    }
                    if (points.size < CustomGround.MAX_POINTS) TextButton(onClick = {
                        // Where nothing is yet: the corner furthest from every spot.
                        val corners = listOf(0.15f to 0.15f, 0.85f to 0.15f, 0.15f to 0.85f, 0.85f to 0.85f, 0.5f to 0.5f)
                        val (x, y) = corners.maxBy { (cx, cy) -> points.minOfOrNull { kotlin.math.hypot(cx - it.x, cy - it.y) } ?: 1f }
                        val colour = points.getOrNull(index)?.color ?: 0xFFB9D4F5.toInt()
                        ground = ground.copy(points = points + GroundPoint(colour, x, y, 0.6f, 0.7f))
                        picked = points.size
                    }) { Text("+ Add colour") }
                    if (points.size > 1 && index >= 0) TextButton(onClick = {
                        ground = ground.copy(points = points.filterIndexed { i, _ -> i != index })
                        picked = 0
                    }) { Text("Remove") }
                }
                if (index == -1) {
                    Text("Base colour", style = MaterialTheme.typography.labelLarge)
                    ColorInput(ground.baseColor(dark).toArgb()) { c -> ground = ground.copy(base = c or 0xFF000000.toInt()) }
                    TextButton(onClick = { ground = ground.copy(base = null) }) { Text("Use the plain ground") }
                } else {
                    val p = points[index]
                    Text("Strength · ${(p.strength * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                    SkinSlider(p.strength, { v -> update(index) { it.copy(strength = v) } }, 0f..1f, step = 0.01f)
                    Text("Spread · ${(p.spread * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                    SkinSlider(p.spread, { v -> update(index) { it.copy(spread = v) } }, 0.1f..1.5f, step = 0.01f)
                    ColorInput(p.color) { c -> update(index) { it.copy(color = c or 0xFF000000.toInt()) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ground) }) { Text("Use this look") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The screen in small, the ground painted on it, two rows of cards and a
 * title for how the library reads, and each colour's spot as a handle: the
 * spot nearest the finger follows it.
 */
@Composable
private fun GroundCanvas(
    ground: CustomGround,
    look: HomeLookColors,
    picked: Int,
    aspect: Float,
    onPick: (Int) -> Unit,
    onMove: (Int, Float, Float) -> Unit,
) {
    val latestGround by rememberUpdatedState(ground)
    val latestPick by rememberUpdatedState(onPick)
    val latestMove by rememberUpdatedState(onMove)
    val ring = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect).clip(Ui.Shape.card)
            .drawBehind { look.drawGround(this) }
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Ui.Shape.card),
    ) {
        // A sketch of the library, so the colours are judged with something on them.
        Column(Modifier.padding(Ui.Space.m)) {
            Text("Library", style = MaterialTheme.typography.titleMedium, color = look.ink)
            Text("Notes on this ground", style = MaterialTheme.typography.bodySmall, color = look.quiet)
            Spacer(Modifier.height(Ui.Space.s))
            repeat(2) {
                Row(Modifier.padding(bottom = Ui.Space.s), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                    repeat(4) {
                        Box(Modifier.width(44.dp).height(56.dp).clip(Ui.Shape.chip).background(look.card).padding(Ui.Space.xs)) {
                            Box(Modifier.fillMaxWidth().height(36.dp).clip(Ui.Shape.badge).background(look.paper))
                        }
                    }
                }
            }
        }
        Canvas(
            Modifier.matchParentSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val points = latestGround.points
                        // The spot nearest the finger is the one taken.
                        val hit = points.indices.minByOrNull { i ->
                            kotlin.math.hypot(points[i].x * w - down.position.x, points[i].y * h - down.position.y)
                        } ?: return@awaitEachGesture
                        latestPick(hit)
                        down.consume()
                        drag(down.id) { change ->
                            change.consume()
                            latestMove(hit, (change.position.x / w).coerceIn(0f, 1f), (change.position.y / h).coerceIn(0f, 1f))
                        }
                    }
                }
                .semantics { contentDescription = "Where each colour is" },
        ) {
            val handle = 14.dp.toPx()
            ground.points.forEachIndexed { i, p ->
                val c = Offset(p.x * size.width, p.y * size.height)
                drawCircle(Color.White, radius = handle, center = c)
                drawCircle(Color(p.color), radius = handle * 0.72f, center = c)
                drawCircle(if (i == picked) ring else Color(0x55000000), radius = handle, center = c,
                    style = Stroke(width = if (i == picked) 3.dp.toPx() else 1.dp.toPx()))
            }
        }
    }
}
