package com.notesis

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** The 1/4 bar's top row, in the order a fresh install shows it. */
internal enum class TopRowItem(val label: String, val removable: Boolean = true) {
    BACK("Back to notes"),
    PAGES("Pages & search"),
    // The title is the slack between the row's two clusters; without it
    // everything huddles at one end.
    TITLE("Note title", removable = false),
    UNDO("Undo"),
    REDO("Redo"),
    MIC("Recordings"),
    ROTATE("Rotate note"),
    ZOOM("Zoom level"),
    SKIN("UI style"),
    RESET_BAR("Reset bar position"),
    PLANNER("Clock & planner"),
    GESTURES("Touch & gestures"),
    ADD_PAGE("Add page"),
    NOTE_MENU("Note & view"),
    OTHER_NOTES("Other notes"),
    LAYER("Layer"),
    SETTINGS("Settings"),
}

/** The 1/4 bar's row beside the tools: what is set on the pen in hand. */
internal enum class PenRowItem(val label: String) {
    TOOL("Pen, shape or text options"),
    QUICK_COLORS("Quick colors"),
    PALETTE("Color templates"),
    STRAIGHT_LINE("Straight line"),
    LAYER("Layer"),
    WIDTH("Width slider"),
    PAGE_LABEL("Page number"),
}

/**
 * Which tools each of the four bar sizes shows and in what order, and what
 * the full bar's two other rows hold. Plain data, so the editor works on a
 * copy and the bar only changes when that copy is committed.
 */
internal data class ToolbarConfig(
    val stages: List<List<SpotiToolbarTool>> = DEFAULT_STAGES,
    val topRow: List<TopRowItem> = DEFAULT_TOP_ROW,
    val penRow: List<PenRowItem> = DEFAULT_PEN_ROW,
) {
    fun tools(level: Int): List<SpotiToolbarTool> = stages.getOrElse(level) { emptyList() }

    fun withTools(level: Int, tools: List<SpotiToolbarTool>): ToolbarConfig =
        copy(stages = List(STAGES) { if (it == level) tools else tools(it) })

    /**
     * Ctrl+1..9: the tools the bar shows at [level], in the order shown. The
     * button-only size shows none, so it keeps the full bar's order.
     */
    fun shortcutModes(level: Int): List<EditMode> =
        tools(level).ifEmpty { tools(0) }.mapNotNull { it.mode }

    /**
     * What can never be lost: every size but the button-only one keeps at
     * least one tool, the button-only size holds none, nothing is listed
     * twice, and the top row keeps its title. The function button, and the
     * Settings inside its menu, are not in these lists at all.
     */
    fun normalized(): ToolbarConfig {
        val fixed = List(STAGES) { level ->
            if (level == BUTTON_ONLY_STAGE) emptyList()
            else tools(level).distinct().ifEmpty { DEFAULT_STAGES[level] }
        }
        val top = topRow.distinct().let { row -> if (TopRowItem.TITLE in row) row else listOf(TopRowItem.TITLE) + row }
        return ToolbarConfig(fixed, top, penRow.distinct())
    }

    fun encode(): String = buildList {
        for (level in 0 until STAGES) add("s$level=" + tools(level).joinToString(",") { it.name })
        add("top=" + topRow.joinToString(",") { it.name })
        add("pen=" + penRow.joinToString(",") { it.name })
    }.joinToString(";")

    companion object {
        const val STAGES = 4
        /** 4/4 is the function button and nothing else. */
        const val BUTTON_ONLY_STAGE = 3
        val STAGE_NAMES = listOf("Full", "One row", "Compact", "Button")

        val DEFAULT_STAGES: List<List<SpotiToolbarTool>> = listOf(
            SpotiToolbarTool.entries.toList(),
            SpotiToolbarTool.entries.toList(),
            listOf(SpotiToolbarTool.READ, SpotiToolbarTool.PEN, SpotiToolbarTool.HIGHLIGHTER, SpotiToolbarTool.MASK),
            emptyList(),
        )
        val DEFAULT_TOP_ROW = listOf(
            TopRowItem.BACK, TopRowItem.PAGES, TopRowItem.TITLE, TopRowItem.UNDO, TopRowItem.REDO,
            TopRowItem.MIC, TopRowItem.ROTATE, TopRowItem.ZOOM, TopRowItem.SKIN, TopRowItem.RESET_BAR,
            TopRowItem.PLANNER, TopRowItem.GESTURES, TopRowItem.ADD_PAGE, TopRowItem.NOTE_MENU,
            TopRowItem.OTHER_NOTES,
        )
        val DEFAULT_PEN_ROW = PenRowItem.entries.toList()

        /** Null for nothing stored; a section missing or unreadable falls back to its default. */
        fun decode(raw: String?): ToolbarConfig? {
            if (raw.isNullOrBlank()) return null
            val sections = raw.split(';').mapNotNull { part ->
                val at = part.indexOf('=')
                if (at <= 0) null else part.substring(0, at).trim() to part.substring(at + 1)
            }.toMap()
            val defaults = ToolbarConfig()
            return ToolbarConfig(
                stages = List(STAGES) { level ->
                    sections["s$level"]?.let { parseTools(it) } ?: defaults.tools(level)
                },
                topRow = sections["top"]?.let { names<TopRowItem>(it) } ?: defaults.topRow,
                penRow = sections["pen"]?.let { names<PenRowItem>(it) } ?: defaults.penRow,
            ).normalized()
        }

        fun parseTools(raw: String): List<SpotiToolbarTool> = names(raw)

        /** The one list the bar used to keep served both the full and the one-row size. */
        fun fromLegacy(tools: List<SpotiToolbarTool>): ToolbarConfig = ToolbarConfig(
            stages = List(STAGES) { level -> if (level <= 1) tools else DEFAULT_STAGES[level] },
        ).normalized()

        private inline fun <reified T : Enum<T>> names(raw: String): List<T> =
            raw.split(',').mapNotNull { name -> enumValues<T>().firstOrNull { it.name == name.trim() } }
    }
}

/**
 * The bar's arrangement for every size. Snapshot state, so the bar redraws
 * the moment it is committed; kept in preferences between runs. The keyboard's
 * Ctrl+1..9 follows the size in use.
 */
internal object ToolbarLayout {
    var config by mutableStateOf(ToolbarConfig())
        private set

    /** The full bar's tools. */
    val tools: List<SpotiToolbarTool> get() = config.tools(0)

    private fun prefs(context: Context) = context.getSharedPreferences("pens", Context.MODE_PRIVATE)

    fun load(context: Context) {
        val prefs = prefs(context)
        val stored = ToolbarConfig.decode(prefs.getString(KEY, null))
        if (stored != null) {
            config = stored
            return
        }
        val saved = prefs.getString(LEGACY_KEY, null)
        if (saved == null) {
            // The default bar already has the pencil; nothing to put back later.
            prefs.edit().putBoolean(PENCIL_RETURNED, true).apply()
            return
        }
        var tools = ToolbarConfig.parseTools(saved).ifEmpty { SpotiToolbarTool.entries.toList() }
        // 0.35.1 took the pencil off the bar, so a bar arranged since then has
        // no place for it. Put it back beside the pen once; after that, taking
        // it off again in the editor is the person's own choice and sticks.
        if (!prefs.getBoolean(PENCIL_RETURNED, false) && SpotiToolbarTool.PENCIL !in tools) {
            val at = tools.indexOf(SpotiToolbarTool.PEN)
            tools = tools.toMutableList().apply { add(if (at >= 0) at + 1 else size, SpotiToolbarTool.PENCIL) }
        }
        prefs.edit().putBoolean(PENCIL_RETURNED, true).apply()
        set(context, ToolbarConfig.fromLegacy(tools))
    }

    fun set(context: Context, value: ToolbarConfig) {
        config = value.normalized()
        // The old single list is still written, so an older build reading
        // these preferences finds the full bar where it always was.
        prefs(context).edit()
            .putString(KEY, config.encode())
            .putString(LEGACY_KEY, config.tools(0).joinToString(",") { it.name })
            .apply()
    }

    private const val KEY = "toolbarStages"
    private const val LEGACY_KEY = "toolbarTools"
    private const val PENCIL_RETURNED = "toolbarPencilReturned"
}

/** A draft arrangement for a preview to draw instead of the committed one. */
internal val LocalToolbarConfig = compositionLocalOf<ToolbarConfig?> { null }

@Composable
internal fun currentToolbarConfig(): ToolbarConfig = LocalToolbarConfig.current ?: ToolbarLayout.config

/**
 * The four bar sizes, each drawn as a little picture of the bar, so the row
 * cannot be mistaken for page numbers.
 */
@Composable
internal fun ToolbarStageSelector(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(Ui.Shape.control)
            .background(scheme.onSurface.copy(alpha = 0.06f))
            .padding(Ui.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs),
    ) {
        repeat(ToolbarConfig.STAGES) { level ->
            val on = level == selected
            val tint = if (on) scheme.onPrimaryContainer else scheme.onSurfaceVariant
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(Ui.Shape.chip)
                    .background(if (on) scheme.primaryContainer else Color.Transparent)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Toolbar size ${level + 1} of 4, ${ToolbarConfig.STAGE_NAMES[level]}"
                        this.selected = on
                    }
                    .clickable(role = Role.Tab) { onSelect(level) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                StagePictogram(level, tint, Modifier.size(width = 30.dp, height = 14.dp))
                Spacer(Modifier.height(3.dp))
                Text("${level + 1}/4", style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
            }
        }
    }
}

/** Two rows, one row, a short row, a dot: how much bar each size leaves. */
@Composable
private fun StagePictogram(level: Int, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val radius = CornerRadius(size.height / 4f)
        when (level) {
            0 -> {
                drawRoundRect(color, Offset.Zero, Size(size.width, size.height * 0.38f), radius)
                drawRoundRect(color.copy(alpha = color.alpha * 0.55f), Offset(0f, size.height * 0.52f),
                    Size(size.width, size.height * 0.48f), radius)
            }
            1 -> drawRoundRect(color, Offset(0f, size.height * 0.22f), Size(size.width, size.height * 0.56f), radius)
            2 -> drawRoundRect(color, Offset(size.width * 0.22f, size.height * 0.22f),
                Size(size.width * 0.56f, size.height * 0.56f), radius)
            else -> drawCircle(color, size.height * 0.34f, center)
        }
    }
}

/**
 * Lays its content out at [logicalWidth], the width it has on the page, and
 * shrinks it to fit. A preview of a 940dp bar in a 360dp panel is the same
 * bar, smaller, rather than a bar squeezed into rows it never has.
 */
internal fun Modifier.previewFit(logicalWidth: Dp): Modifier = layout { measurable, constraints ->
    val logical = logicalWidth.roundToPx().coerceAtLeast(1)
    val available = if (constraints.hasBoundedWidth) constraints.maxWidth else logical
    val scale = (available.toFloat() / logical).coerceAtMost(1f)
    val child = measurable.measure(Constraints(minWidth = logical, maxWidth = logical))
    val width = (child.width * scale).roundToInt()
    val height = (child.height * scale).roundToInt()
    layout(width, height) {
        child.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

private enum class CustomizeList(val label: String) { TOOLS("Tools"), TOP_ROW("Top row"), PEN_ROW("Pen row") }

/**
 * Which tools each bar size shows and in what order, with the bar itself
 * above as it will look. Everything here edits a draft: Done keeps it,
 * Cancel (and Back) drops it, Reset puts the defaults in the draft.
 */
@Composable
internal fun ToolbarCustomizeScreen(
    initialStage: Int,
    /** The real bar at the given size; drawn under the draft, never touched. */
    preview: @Composable (level: Int) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val committed = ToolbarLayout.config
    var draft by remember { mutableStateOf(committed) }
    var stage by remember { mutableIntStateOf(initialStage.coerceIn(0, ToolbarConfig.STAGES - 1)) }
    var list by remember { mutableStateOf(CustomizeList.TOOLS) }
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
        ) {
            Text("Toolbar size to edit", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolbarStageSelector(stage, onSelect = { level ->
                stage = level
                if (level != 0) list = CustomizeList.TOOLS
            })
            ToolbarPreviewStage(PreviewBackground.PAGE, null, Modifier.fillMaxWidth().height(112.dp)) {
                CompositionLocalProvider(LocalToolbarConfig provides draft) {
                    Box(Modifier.padding(8.dp).previewFit(spotiToolbarWidth(stage))) { preview(stage) }
                }
            }
            if (stage == 0) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                    CustomizeList.entries.forEach { option ->
                        SettingsChoiceChip(selected = list == option, onClick = { list = option }, label = option.label)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val footer: @Composable ColumnScope.() -> Unit = {
                Spacer(Modifier.height(8.dp))
                Text(
                    "The function button is always on the bar and Settings is always in its menu, " +
                        "so neither can be lost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("Switching between sizes", style = MaterialTheme.typography.titleSmall)
                Text("Applies right away, without Done.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                ToolbarMotionSettings()
            }
            when {
                stage == ToolbarConfig.BUTTON_ONLY_STAGE -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    Text(
                        "4/4 is the function button alone. Its menu also shows the 3/4 tools, " +
                            "so a tool is never more than two taps away.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    footer()
                }
                list == CustomizeList.TOP_ROW -> key("top") {
                    ReorderList(
                        shown = draft.topRow,
                        hidden = TopRowItem.entries.filter { it !in draft.topRow },
                        idOf = { it.name },
                        label = { it.label },
                        icon = { it.icon() },
                        badge = { null },
                        lockedReason = { if (it.removable) null else "Always shown" },
                        onChange = { draft = draft.copy(topRow = it) },
                        footer = footer,
                    )
                }
                list == CustomizeList.PEN_ROW -> key("pen") {
                    ReorderList(
                        shown = draft.penRow,
                        hidden = PenRowItem.entries.filter { it !in draft.penRow },
                        idOf = { it.name },
                        label = { it.label },
                        icon = { it.icon() },
                        badge = { null },
                        lockedReason = { null },
                        onChange = { draft = draft.copy(penRow = it) },
                        footer = footer,
                    )
                }
                else -> key("tools$stage") {
                    val shown = draft.tools(stage)
                    val withModes = shown.filter { it.mode != null }
                    ReorderList(
                        shown = shown,
                        hidden = SpotiToolbarTool.entries.filter { it !in shown },
                        idOf = { it.name },
                        label = { it.label },
                        icon = { it.icon() },
                        badge = { tool ->
                            withModes.indexOf(tool).takeIf { it in 0..8 }?.let { "Ctrl+${it + 1}" }
                        },
                        lockedReason = { if (shown.size <= 1) "Keep at least one tool" else null },
                        onChange = { draft = draft.withTools(stage, it) },
                        footer = footer,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (draft != committed) {
            Text("Unsaved changes", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { draft = ToolbarConfig() },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { contentDescription = "Reset every size to the default tools" },
            ) { Text("Reset", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            OutlinedButton(onClick = onFinished, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Cancel", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = {
                ToolbarLayout.set(context, draft)
                onFinished()
            }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Done", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Shown items with a drag handle each, then the hidden ones to add back.
 * Dragging near either edge scrolls the list, so a long one can be crossed
 * in one go. The order is worked on locally while a finger is down and handed
 * up once when it lifts.
 */
@Composable
private fun <T> ReorderList(
    shown: List<T>,
    hidden: List<T>,
    idOf: (T) -> String,
    label: (T) -> String,
    icon: (T) -> ImageVector?,
    badge: (T) -> String?,
    /** Why an item cannot be hidden, or null when it can. */
    lockedReason: (T) -> String?,
    onChange: (List<T>) -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    // Plain maps: written from layout, read from gestures, never from composition.
    val tops = remember { HashMap<String, Float>() }
    val heights = remember { HashMap<String, Float>() }
    val windowTops = remember { HashMap<String, Float>() }
    val viewport = remember { FloatArray(2) } // top in window, height
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var order by remember { mutableStateOf(shown) }
    val latestShown by rememberUpdatedState(shown)
    val latestChange by rememberUpdatedState(onChange)
    val latestId by rememberUpdatedState(idOf)
    val display = if (dragging != null) order else shown
    val edge = with(density) { 56.dp.toPx() }
    val speed = with(density) { 12.dp.toPx() }

    fun move(from: Int, to: Int) {
        val items = latestShown
        if (from !in items.indices || to !in items.indices) return
        latestChange(items.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun trySwap() {
        val id = dragging ?: return
        val items = order
        val index = items.indexOfFirst { latestId(it) == id }
        if (index < 0) return
        val top = tops[id] ?: return
        val height = heights[id] ?: return
        val middle = top + dragOffset + height / 2f
        if (dragOffset > 0f && index < items.lastIndex) {
            val next = latestId(items[index + 1])
            val nextTop = tops[next] ?: return
            val nextHeight = heights[next] ?: return
            if (middle > nextTop + nextHeight / 2f) {
                val newTop = nextTop + nextHeight - height
                dragOffset = top + dragOffset - newTop
                tops[id] = newTop
                tops[next] = top
                order = items.toMutableList().apply { add(index + 1, removeAt(index)) }
            }
        } else if (dragOffset < 0f && index > 0) {
            val previous = latestId(items[index - 1])
            val previousTop = tops[previous] ?: return
            val previousHeight = heights[previous] ?: return
            if (middle < previousTop + previousHeight / 2f) {
                dragOffset = top + dragOffset - previousTop
                tops[id] = previousTop
                tops[previous] = top + height - previousHeight
                order = items.toMutableList().apply { add(index - 1, removeAt(index)) }
            }
        }
    }

    // Held near an edge, the list moves under the finger and the item with it.
    LaunchedEffect(dragging) {
        val id = dragging ?: return@LaunchedEffect
        while (isActive && dragging == id) {
            withFrameNanos { }
            val windowTop = windowTops[id] ?: continue
            val height = heights[id] ?: continue
            val top = windowTop + dragOffset - viewport[0]
            val bottom = top + height
            val delta = when {
                top < edge -> -speed * ((edge - top) / edge).coerceIn(0.2f, 1f)
                bottom > viewport[1] - edge -> speed * ((bottom - viewport[1] + edge) / edge).coerceIn(0.2f, 1f)
                else -> 0f
            }
            if (delta != 0f) {
                val used = scroll.scrollBy(delta)
                if (used != 0f) {
                    dragOffset += used
                    trySwap()
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                viewport[0] = it.positionInWindow().y
                viewport[1] = it.size.height.toFloat()
            }
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Shown, in order", style = MaterialTheme.typography.titleSmall)
        Text("Drag the handle to move an item.", style = MaterialTheme.typography.bodySmall,
            color = scheme.outline)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            display.forEachIndexed { index, item ->
                val id = idOf(item)
                key(id) {
                    val lifted = dragging == id
                    val reason = lockedReason(item)
                    val name = label(item)
                    Row(
                        Modifier
                            .zIndex(if (lifted) 1f else 0f)
                            .onGloballyPositioned {
                                tops[id] = it.positionInParent().y
                                heights[id] = it.size.height.toFloat()
                                windowTops[id] = it.positionInWindow().y
                            }
                            .graphicsLayer {
                                translationY = if (dragging == id) dragOffset else 0f
                                shadowElevation = if (dragging == id) 8.dp.toPx() else 0f
                            }
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(Ui.Shape.control)
                            .background(if (lifted) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh)
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction("Move up") { move(index, index - 1); index > 0 },
                                    CustomAccessibilityAction("Move down") {
                                        move(index, index + 1); index < latestShown.lastIndex
                                    },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(Ui.Size.touch)
                                .pointerInput(id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            order = latestShown
                                            dragOffset = 0f
                                            dragging = id
                                        },
                                        onDragEnd = {
                                            val finished = order
                                            dragging = null
                                            dragOffset = 0f
                                            if (finished != latestShown) latestChange(finished)
                                        },
                                        onDragCancel = {
                                            val finished = order
                                            dragging = null
                                            dragOffset = 0f
                                            if (finished != latestShown) latestChange(finished)
                                        },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        trySwap()
                                    }
                                }
                                .semantics { contentDescription = "Drag to move $name" },
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIcon(AppIcons.dragHandle, null, Modifier.size(Ui.Icon.small), tint = scheme.onSurfaceVariant)
                        }
                        icon(item)?.let {
                            AppIcon(it, null, Modifier.size(Ui.Icon.small))
                            Spacer(Modifier.width(Ui.Space.m))
                        }
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            val note = listOfNotNull(badge(item), reason).joinToString(" · ")
                            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelSmall,
                                color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        SmallActionButton(
                            icon = AppIcons.hide,
                            description = "Hide $name",
                            disabledReason = reason,
                        ) { latestChange(latestShown.filter { idOf(it) != id }) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Hidden", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (hidden.isEmpty()) {
            Text("Everything is shown.", style = MaterialTheme.typography.bodySmall, color = scheme.outline)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            hidden.forEach { item ->
                key(idOf(item)) {
                    val name = label(item)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(Ui.Shape.control)
                            .background(scheme.onSurface.copy(alpha = 0.04f))
                            .padding(start = Ui.Space.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        icon(item)?.let {
                            AppIcon(it, null, Modifier.size(Ui.Icon.small), tint = scheme.onSurfaceVariant)
                            Spacer(Modifier.width(Ui.Space.m))
                        }
                        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        SmallActionButton(icon = AppIcons.show, description = "Show $name") {
                            latestChange(latestShown + item)
                        }
                    }
                }
            }
        }
        footer()
    }
}

/** A 48dp icon button that, when it cannot act, says why instead of only greying out. */
@Composable
internal fun SmallActionButton(
    icon: ImageVector,
    description: String,
    disabledReason: String? = null,
    onClick: () -> Unit,
) {
    val enabled = disabledReason == null
    Box(
        Modifier
            .size(Ui.Size.touch)
            .clip(Ui.Shape.pill)
            .semantics {
                contentDescription = description
                if (disabledReason != null) stateDescription = disabledReason
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, null, Modifier.size(Ui.Icon.small),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

internal fun TopRowItem.icon(): ImageVector = when (this) {
    TopRowItem.BACK -> AppIcons.back
    TopRowItem.PAGES -> AppIcons.search
    TopRowItem.TITLE -> AppIcons.note
    TopRowItem.UNDO -> AppIcons.undo
    TopRowItem.REDO -> AppIcons.redo
    TopRowItem.MIC -> AppIcons.mic
    TopRowItem.ROTATE -> AppIcons.rotate
    TopRowItem.ZOOM -> AppIcons.zoom
    TopRowItem.SKIN -> AppIcons.skin
    TopRowItem.RESET_BAR -> AppIcons.resetBar
    TopRowItem.PLANNER -> AppIcons.tools
    TopRowItem.GESTURES -> AppIcons.touch
    TopRowItem.ADD_PAGE -> AppIcons.addPage
    TopRowItem.NOTE_MENU -> AppIcons.noteMenu
    TopRowItem.OTHER_NOTES -> AppIcons.otherNotes
    TopRowItem.LAYER -> AppIcons.layers
    TopRowItem.SETTINGS -> AppIcons.settings
}

internal fun PenRowItem.icon(): ImageVector? = when (this) {
    PenRowItem.TOOL -> AppIcons.penSettings
    PenRowItem.QUICK_COLORS -> AppIcons.palette
    PenRowItem.PALETTE -> AppIcons.palette
    PenRowItem.STRAIGHT_LINE -> null
    PenRowItem.LAYER -> AppIcons.layers
    PenRowItem.WIDTH -> null
    PenRowItem.PAGE_LABEL -> AppIcons.note
}
