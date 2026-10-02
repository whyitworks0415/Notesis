package com.notesis

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.PI
import kotlin.math.sin

internal enum class SpotiToolbarTool(val mode: EditMode?, val label: String) {
    READ(EditMode.READ, "읽기"), PEN(EditMode.PEN, "펜"), PENCIL(EditMode.PENCIL, "연필"),
    HIGHLIGHTER(EditMode.HIGHLIGHTER, "형광펜"), MASK(EditMode.MASK, "마스킹"),
    ERASE(EditMode.ERASE, "지우개"), SHAPE(EditMode.SHAPE, "도형"), TEXT(EditMode.TEXT, "텍스트"),
    LASSO(EditMode.LASSO, "올가미"), CAPTURE(EditMode.CAPTURE, "캡쳐"), AI(null, "AI"),
}

internal fun spotiToolbarTools(level: Int): List<SpotiToolbarTool> = when (level) {
    2 -> listOf(SpotiToolbarTool.READ, SpotiToolbarTool.PEN, SpotiToolbarTool.HIGHLIGHTER, SpotiToolbarTool.MASK)
    3 -> emptyList()
    else -> SpotiToolbarTool.entries
}

internal fun spotiToolbarWidth(level: Int) = when (level) {
    0 -> 940.dp
    1 -> 792.dp
    2 -> 400.dp
    else -> 88.dp
}

private fun SpotiToolbarTool.icon(): ImageVector = when (this) {
    SpotiToolbarTool.READ -> Icons.Default.TouchApp
    SpotiToolbarTool.PEN -> Icons.Default.Create
    SpotiToolbarTool.PENCIL -> Icons.Outlined.Brush
    SpotiToolbarTool.HIGHLIGHTER -> Icons.Default.Highlight
    SpotiToolbarTool.MASK -> Icons.Default.VisibilityOff
    SpotiToolbarTool.ERASE -> Icons.Default.Delete
    SpotiToolbarTool.SHAPE -> Icons.Default.Category
    SpotiToolbarTool.TEXT -> Icons.Default.TextFields
    SpotiToolbarTool.LASSO -> Icons.Default.Gesture
    SpotiToolbarTool.CAPTURE -> Icons.Default.CropFree
    SpotiToolbarTool.AI -> Icons.Default.AutoAwesome
}

internal data class SpotiToolbarAction(
    val label: String, val icon: ImageVector, val enabled: Boolean = true,
    val selected: Boolean = false, val slot: Int? = null, val onClick: () -> Unit,
)

/** Apply the original optics during a size/menu change, then hand back to frost.
 * The lens captures the surface and its contents; it never records the page. */
@Composable
internal fun Modifier.spotiGlassMorph(key: Any, enter: Boolean = false): Modifier {
    val look = LocalSkinSettings.current
    val effects = rememberLiquidGlassEffectsAllowed() && !look.highContrast &&
        look.spotiglassResponse > 0f && look.spotiglassClarity > 0f
    val phase = remember { Animatable(if (enter) 0f else 1f) }
    var initial by remember { mutableStateOf(true) }
    LaunchedEffect(key, effects) {
        if (effects && (enter || !initial)) {
            phase.snapTo(0f)
            phase.animateTo(1f, tween(460))
        } else phase.snapTo(1f)
        initial = false
    }
    val raised = sin(PI * phase.value).toFloat().coerceIn(0f, 1f)
    return spotiGlassLens(effects && raised > 0.002f) { size, density ->
        SpotiGlassLensFrame(center = Offset(size.width / 2, size.height / 2), size = size,
            radius = look.spotiglassCorner * density, progress = raised, presence = raised,
            distortion = 0.04f * (look.refraction / 24) * look.spotiglassResponse,
            band = look.depth, dispersion = 0.002f * (look.dispersion / 0.35f))
    }
}

@Composable
private fun SpotiActionButton(
    label: String, icon: ImageVector? = null, modifier: Modifier = Modifier,
    selected: Boolean = false, enabled: Boolean = true, iconOnly: Boolean = false, onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    val shape = SpotiGlassShape(22.dp)
    val look = LocalSkinSettings.current
    val effects = rememberLiquidGlassEffectsAllowed() && !look.highContrast && look.spotiglassResponse > 0f
    // A trigger uses the same rest -> lifted -> rest cycle as the tool pill.
    val grow by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && effects) 1f + 0.08f * look.spotiglassResponse else 1f,
        if (effects) spring(0.78f, 430f) else tween(0), label = "기능 버튼 들림")
    Box(modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().spotiGlassMorph(pressed)
            .graphicsLayer { scaleX = grow; scaleY = grow }
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else scheme.onSurface.copy(alpha = 0.06f), shape)
            .then(if (pressed && effects) Modifier.liquidGlass(shape = shape, intensity = grow - 0.08f,
                surfaceColor = Color.White.copy(alpha = 0.08f), shadowElevation = 2.dp) else Modifier))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            val color = (if (selected) scheme.primary else scheme.onSurface).copy(alpha = if (enabled) 1f else 0.38f)
            icon?.let { Icon(it, null, Modifier.size(20.dp), tint = color) }
            if (!iconOnly) Text(label, color = color, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private enum class ToolbarMenu { FUNCTIONS, SIZES, NOTES, AI, TOOLS, MORE }

internal class SpotiToolbarPopupPosition(private val atStart: Boolean, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = atStart == (layoutDirection == LayoutDirection.Ltr)
        val desiredX = if (left) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val maxX = (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - popupContentSize.height - gap
        val maxY = (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap)
        val y = when {
            below <= maxY -> below
            above >= gap -> above
            else -> below.coerceIn(gap, maxY)
        }
        return IntOffset(desiredX.coerceIn(gap, maxX), y)
    }
}

@Composable
internal fun SpotiGlassToolbar(
    sizeLevel: Int, onSizeLevel: (Int) -> Unit, mode: EditMode, inkColor: Color?,
    docked: Boolean, modifier: Modifier, onDrag: (Offset) -> Unit,
    onTool: (SpotiToolbarTool) -> Unit, actions: List<SpotiToolbarAction>,
    notes: List<SpotiToolbarAction>, ai: List<SpotiToolbarAction>,
    topRow: @Composable () -> Unit, penOptions: @Composable RowScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(ToolbarMenu.FUNCTIONS) }
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = open
    fun menu(next: ToolbarMenu) { page = next; open = true }
    fun pick(tool: SpotiToolbarTool) {
        if (tool == SpotiToolbarTool.AI) menu(ToolbarMenu.AI)
        else { open = false; onTool(tool) }
    }
    val effects = rememberLiquidGlassEffectsAllowed() && !LocalSkinSettings.current.highContrast
    val popupBackdrop = LocalSpotiPopupBackdrop.current ?: LocalLiquidGlassBackdrop.current
    SkinSurface(modifier.spotiGlassMorph(sizeLevel)
        .animateContentSize(if (effects) spring(0.86f, 430f) else tween(0)), flush = docked && sizeLevel == 0) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified) {
            Column(Modifier.then(if (docked && sizeLevel == 0) Modifier.windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) else Modifier)
                .padding(horizontal = 8.dp, vertical = 2.dp)) {
                if (sizeLevel == 0) {
                    topRow()
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Box {
                    if (sizeLevel == 3) {
                        SpotiActionButton("도구·기능 메뉴", Icons.Default.Menu,
                            Modifier.pointerInput(Unit) {
                                detectDragGestures { change, delta -> change.consume(); onDrag(delta) }
                            }, iconOnly = true, onClick = { menu(ToolbarMenu.FUNCTIONS) })
                    } else {
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SpotiActionButton("${sizeLevel + 1}/4", onClick = { menu(ToolbarMenu.SIZES) })
                            val tools = spotiToolbarTools(sizeLevel)
                            SpotiGlassBar(tools.map { SpotiGlassItem(it.label, it.icon()) },
                                selectedIndex = if (open && page == ToolbarMenu.AI) tools.indexOf(SpotiToolbarTool.AI)
                                    else tools.indexOfFirst { it.mode == mode },
                                onSelected = { pick(tools[it]) },
                                modifier = Modifier.width((tools.size * 56 + 12).dp),
                                selectedContentColor = inkColor)
                            SpotiActionButton("기능", Icons.Default.Tune, selected = open,
                                onClick = { menu(ToolbarMenu.FUNCTIONS) })
                            if (sizeLevel == 0) penOptions()
                        }
                    }
                    if (visible.currentState || visible.targetState) {
                        val maxHeight = (LocalConfiguration.current.screenHeightDp - 140).coerceAtLeast(180).dp
                        val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
                        val position = remember(page == ToolbarMenu.SIZES, gap) {
                            SpotiToolbarPopupPosition(page == ToolbarMenu.SIZES, gap)
                        }
                        Popup(popupPositionProvider = position,
                            onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                          androidx.compose.animation.AnimatedVisibility(visibleState = visible,
                            enter = if (effects) fadeIn(tween(140)) + scaleIn(tween(220), initialScale = 0.86f) else EnterTransition.None,
                            exit = if (effects) fadeOut(tween(150)) + scaleOut(tween(170), targetScale = 0.9f) else ExitTransition.None) {
                            // This surface has its own page capture; do not sample
                            // the parent toolbar's exported frost across windows.
                            CompositionLocalProvider(LocalSpotiGlassHostBackdrop provides null,
                                LocalLiquidGlassBackdrop provides popupBackdrop) {
                                SkinSurface(Modifier.width(minOf(340.dp,
                                    (LocalConfiguration.current.screenWidthDp - 24).coerceAtLeast(140).dp)).heightIn(max = maxHeight)
                                    .spotiGlassMorph(page to open, enter = true)
                                    .animateContentSize(if (effects) spring(0.86f, 430f) else tween(0))) {
                                    BlurBehind()
                                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(when (page) {
                                                ToolbarMenu.FUNCTIONS -> "기능"
                                                ToolbarMenu.SIZES -> "상단 바"
                                                ToolbarMenu.NOTES -> "노트 바꾸기"
                                                ToolbarMenu.AI -> "AI"
                                                ToolbarMenu.TOOLS -> "모든 도구"
                                                ToolbarMenu.MORE -> "더보기"
                                            }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                            IconButton(onClick = { open = false }, Modifier.size(32.dp)) {
                                                Icon(Icons.Default.Close, "기능 메뉴 닫기")
                                            }
                                        }
                                        if (page == ToolbarMenu.FUNCTIONS || page == ToolbarMenu.SIZES) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                repeat(4) { level ->
                                                    SpotiActionButton("${level + 1}/4", modifier = Modifier.weight(1f),
                                                        selected = sizeLevel == level, onClick = { open = false; onSizeLevel(level) })
                                                }
                                            }
                                        }
                                        if (page == ToolbarMenu.FUNCTIONS) {
                                            if (sizeLevel >= 2) {
                                                SpotiMenuGrid(spotiToolbarTools(2).map { tool ->
                                                    SpotiToolbarAction(tool.label, tool.icon(), selected = tool.mode == mode) { pick(tool) }
                                                })
                                                HorizontalDivider()
                                                TextButton(onClick = { page = ToolbarMenu.TOOLS }) { Text("모든 도구") }
                                            }
                                            SpotiMenuGrid((actions.filter { it.slot != null } + listOf(
                                                SpotiToolbarAction("노트 바꾸기", Icons.Default.Description, slot = 3) { page = ToolbarMenu.NOTES },
                                                SpotiToolbarAction("AI", Icons.Default.AutoAwesome, slot = 9) { page = ToolbarMenu.AI },
                                            )).sortedBy { it.slot }, onAction = { action ->
                                                if (action.label != "노트 바꾸기" && action.label != "AI") open = false
                                                action.onClick()
                                            })
                                            TextButton(onClick = { page = ToolbarMenu.MORE }) { Text("더보기") }
                                        } else if (page == ToolbarMenu.TOOLS) {
                                            SpotiMenuGrid(SpotiToolbarTool.entries.filter { it != SpotiToolbarTool.AI }.map { tool ->
                                                SpotiToolbarAction(tool.label, tool.icon(), selected = tool.mode == mode) { pick(tool) }
                                            })
                                            TextButton(onClick = { page = ToolbarMenu.FUNCTIONS }) { Text("← 기능") }
                                        } else if (page == ToolbarMenu.MORE) {
                                            SpotiMenuGrid(actions.filter { it.slot == null }, onAction = { open = false; it.onClick() })
                                            TextButton(onClick = { page = ToolbarMenu.FUNCTIONS }) { Text("← 기능") }
                                        } else if (page == ToolbarMenu.NOTES || page == ToolbarMenu.AI) {
                                            val entries = if (page == ToolbarMenu.NOTES) notes else ai
                                            if (entries.isEmpty()) Text("다른 노트가 없습니다")
                                            SpotiMenuGrid(entries, onAction = { open = false; it.onClick() })
                                            TextButton(onClick = { page = ToolbarMenu.FUNCTIONS }) { Text("← 기능") }
                                        }
                                    }
                                }
                            }
                          }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotiMenuGrid(actions: List<SpotiToolbarAction>, onAction: (SpotiToolbarAction) -> Unit = { it.onClick() }) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        actions.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { action ->
                    SpotiActionButton(action.label, action.icon, Modifier.weight(1f), action.selected, action.enabled) { onAction(action) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
