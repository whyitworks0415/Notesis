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
) {
    val top: Color get() = stops.first().second

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
internal fun lookOn(stops: List<Pair<Float, Color>>, direction: Int = GroundDirection.DOWN): HomeLookColors {
    val average = averageColor(stops.map { it.second })
    return if (average.luminance() >= 0.32f) HomeLookColors(
        stops, direction,
        card = lerp(average, Color.White, 0.84f),
        paper = lerp(average, Color.White, 0.95f),
        ink = lerp(average, Color(0xFF101010), 0.9f),
        quiet = lerp(average, Color(0xFF1A1A1A), 0.62f),
        line = lerp(average, Color(0xFF000000), 0.1f),
    ) else HomeLookColors(
        stops, direction,
        card = lerp(average, Color.White, 0.1f),
        paper = lerp(average, Color.White, 0.16f),
        ink = lerp(average, Color.White, 0.92f),
        quiet = lerp(average, Color.White, 0.62f),
        line = lerp(average, Color.White, 0.18f),
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
        CUSTOM -> lookOn(HomeLooks.custom.colorStops(groundBase(dark)), HomeLooks.custom.direction)
        else -> if (dark) this.dark else light
    }
}

/** What a colour at no strength is: the plain ground under every look. */
internal fun groundBase(dark: Boolean): Color = if (dark) Color(0xFF151515) else Color(0xFFF7F6F3)

/** One colour of a ground of the user's own: which, where along it (0 to 1), and how strongly (0 to 1). */
internal data class GroundStop(val color: Int, val position: Float, val strength: Float)

/** A ground of the user's own: one to [MAX_STOPS] colours, running [direction]. */
internal data class CustomGround(val stops: List<GroundStop>, val direction: Int = GroundDirection.DOWN) {
    /** The colours as drawn on [base]: each mixed into it by its strength, in order along the ground. */
    fun colorStops(base: Color): List<Pair<Float, Color>> =
        stops.ifEmpty { listOf(GroundStop(base.toArgb(), 0f, 1f)) }
            .sortedBy { it.position }
            .map { it.position.coerceIn(0f, 1f) to lerp(base, Color(it.color), it.strength.coerceIn(0f, 1f)) }

    fun toJson(): JSONObject = JSONObject().put("direction", direction).put("stops", JSONArray(stops.map {
        JSONObject().put("color", it.color).put("position", it.position.toDouble()).put("strength", it.strength.toDouble())
    }))

    companion object {
        const val MAX_STOPS = 4

        val DEFAULT = CustomGround(listOf(GroundStop(0xFFB9D4F5.toInt(), 0f, 0.6f), GroundStop(0xFFF2C6DE.toInt(), 1f, 0.5f)))

        fun fromJson(json: JSONObject): CustomGround? = runCatching {
            val array = json.getJSONArray("stops")
            val stops = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                GroundStop(o.getInt("color"), o.getDouble("position").toFloat().coerceIn(0f, 1f),
                    o.getDouble("strength").toFloat().coerceIn(0f, 1f))
            }.take(MAX_STOPS)
            if (stops.isEmpty()) null else CustomGround(stops, json.optInt("direction", GroundDirection.DOWN).coerceIn(0, 3))
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

/** The look's ground, or null for the theme's own plain surface. */
@Composable
internal fun homeLookGround(): Brush? = HomeLooks.look.colors(isDarkTheme())?.brush()

/** The colour at the top of the look's ground, for what fades into it; null for the theme's surface. */
@Composable
internal fun homeLookTop(): Color? = HomeLooks.look.colors(isDarkTheme())?.top

/** A small desk with two cards on it, in [colors]; the theme's surface for none. */
@Composable
private fun LookSwatch(colors: HomeLookColors?, chosen: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ground = colors?.brush() ?: Brush.verticalGradient(listOf(scheme.surface, scheme.surface))
    val card = colors?.card ?: scheme.surfaceContainerLowest
    Box(
        modifier
            .clip(Ui.Shape.chip)
            .background(ground)
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
 * Puts a ground together: one to four colours, each placed along the ground
 * by dragging its handle on the bar, mixed in as strongly as its slider says,
 * and running down, across, corner to corner or out from the middle. The
 * preview above is the library as it will look, cards and all.
 */
@Composable
internal fun HomeGradientEditor(onDismiss: () -> Unit, onSave: (CustomGround) -> Unit) {
    val dark = isDarkTheme()
    var ground by remember { mutableStateOf(HomeLooks.custom) }
    var picked by remember { mutableIntStateOf(0) }
    val stops = ground.stops
    val index = picked.coerceIn(0, stops.lastIndex)
    fun update(i: Int, change: (GroundStop) -> GroundStop) {
        ground = ground.copy(stops = stops.mapIndexed { j, s -> if (j == i) change(s) else s })
    }
    val look = lookOn(ground.colorStops(groundBase(dark)), ground.direction)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your own look") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                // The library as it will be: its ground, two cards and a line of words on it.
                Box(Modifier.fillMaxWidth().height(140.dp).clip(Ui.Shape.card).background(look.brush()).padding(Ui.Space.m)) {
                    Column {
                        Text("Library", style = MaterialTheme.typography.titleMedium, color = look.ink)
                        Text("Notes on this ground", style = MaterialTheme.typography.bodySmall, color = look.quiet)
                        Spacer(Modifier.height(Ui.Space.s))
                        Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                            repeat(3) {
                                Box(Modifier.width(52.dp).height(62.dp).clip(Ui.Shape.chip).background(look.card).padding(Ui.Space.xs)) {
                                    Box(Modifier.fillMaxWidth().height(40.dp).clip(Ui.Shape.badge).background(look.paper))
                                }
                            }
                        }
                    }
                }
                Text("Direction", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(GroundDirection.DOWN to "Top to bottom", GroundDirection.ACROSS to "Left to right",
                        GroundDirection.DIAGONAL to "Corner to corner", GroundDirection.CENTRE to "From the middle").forEach { (d, label) ->
                        SettingsChoiceChip(selected = ground.direction == d, onClick = { ground = ground.copy(direction = d) },
                            label = label, modifier = Modifier.padding(end = Ui.Space.s))
                    }
                }
                Text("Colours", style = MaterialTheme.typography.labelLarge)
                Text("Drag a handle along the bar to move its colour; tap one to change it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                GroundStopBar(ground, groundBase(dark), index, onPick = { picked = it },
                    onMove = { i, position -> update(i) { it.copy(position = position) } })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    stops.forEachIndexed { i, stop ->
                        Box(
                            Modifier.padding(end = Ui.Space.s).size(32.dp).clip(CircleShape)
                                .background(Color(stop.color))
                                .border(if (i == index) 3.dp else 1.dp,
                                    if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable(role = Role.RadioButton) { picked = i }
                                .semantics { contentDescription = "Colour ${i + 1}"; selected = i == index },
                        )
                    }
                    if (stops.size < CustomGround.MAX_STOPS) TextButton(onClick = {
                        // A new colour in the widest gap, so it shows straight away.
                        val sorted = stops.map { it.position }.sorted()
                        val gaps = (listOf(0f) + sorted + listOf(1f)).zipWithNext()
                        val (a, b) = gaps.maxBy { (x, y) -> y - x }
                        ground = ground.copy(stops = stops + GroundStop(stops[index].color, (a + b) / 2f, 0.5f))
                        picked = stops.size
                    }) { Text("+ Add colour") }
                    if (stops.size > 1) TextButton(onClick = {
                        ground = ground.copy(stops = stops.filterIndexed { i, _ -> i != index })
                        picked = 0
                    }) { Text("Remove") }
                }
                val stop = stops[index]
                Text("Place · ${(stop.position * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                SkinSlider(stop.position, { v -> update(index) { it.copy(position = v) } }, 0f..1f, step = 0.01f)
                Text("Strength · ${(stop.strength * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                SkinSlider(stop.strength, { v -> update(index) { it.copy(strength = v) } }, 0f..1f, step = 0.01f)
                ColorInput(stop.color) { c -> update(index) { it.copy(color = c or 0xFF000000.toInt()) } }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ground) }) { Text("Use this look") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The ground as a bar, each colour's handle on it where the colour sits; drag one to move it. */
@Composable
private fun GroundStopBar(
    ground: CustomGround,
    base: Color,
    picked: Int,
    onPick: (Int) -> Unit,
    onMove: (Int, Float) -> Unit,
) {
    val latestGround by rememberUpdatedState(ground)
    val latestPick by rememberUpdatedState(onPick)
    val latestMove by rememberUpdatedState(onMove)
    val ring = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier.fillMaxWidth().height(44.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val inset = size.height / 2f
                    fun at(x: Float) = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                    // The handle nearest the finger is the one taken.
                    val stops = latestGround.stops
                    val hit = stops.indices.minByOrNull { abs(stops[it].position - at(down.position.x)) } ?: return@awaitEachGesture
                    latestPick(hit)
                    down.consume()
                    drag(down.id) { change ->
                        change.consume()
                        latestMove(hit, at(change.position.x))
                    }
                }
            }
            .semantics { contentDescription = "Where each colour sits" },
    ) {
        val inset = size.height / 2f
        val track = size.height * 0.42f
        val stops = ground.colorStops(base)
        val drawn = (if (stops.size == 1) listOf(0f to stops[0].second, 1f to stops[0].second) else stops).toTypedArray()
        drawRoundRect(
            Brush.horizontalGradient(*drawn, startX = inset, endX = size.width - inset),
            topLeft = Offset(0f, (size.height - track) / 2f),
            size = androidx.compose.ui.geometry.Size(size.width, track),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(track / 2f),
        )
        ground.stops.forEachIndexed { i, stop ->
            val x = inset + stop.position.coerceIn(0f, 1f) * (size.width - 2 * inset)
            val c = Offset(x, size.height / 2f)
            drawCircle(Color.White, radius = inset * 0.78f, center = c)
            drawCircle(lerp(base, Color(stop.color), stop.strength), radius = inset * 0.6f, center = c)
            drawCircle(if (i == picked) ring else Color(0x33000000), radius = inset * 0.78f, center = c,
                style = Stroke(width = if (i == picked) 3.dp.toPx() else 1.dp.toPx()))
        }
    }
}
