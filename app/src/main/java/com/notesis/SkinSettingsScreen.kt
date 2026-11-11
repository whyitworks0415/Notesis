package com.notesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn

/** The name the appearance screens use for each skin: the one it has everywhere else. */
internal val Skin.displayName: String
    get() = label

private val RefractionDirection.displayName: String
    get() = when (this) {
        RefractionDirection.RAISED -> "Raised"
        RefractionDirection.INSET -> "Pressed in"
    }

/** What the toolbar preview is laid over. */
internal enum class PreviewBackground(val label: String) {
    /** A written page: pen, pencil and highlighter, the thing the bar sits on all day. */
    PAGE("Page"),
    /** Soft shapes of colour, for judging glass on something busy but quiet. */
    CALM("Calm"),
    /** A photo the person picked; kept as a small copy in the app's files. */
    PHOTO("Your photo"),
}

/** Room for the bar to try and the switch and slider under it. */
private val PREVIEW_STAGE_HEIGHT = 196.dp
/** The preview kept above the controls on a narrow screen: enough to see the bar, no more. */
private val COMPACT_PREVIEW_HEIGHT = 150.dp

/** How long a slider must rest before its value becomes the app's look. */
private const val APPEARANCE_COMMIT_MS = 250L

private const val PREVIEW_PHOTO = "appearance_preview.jpg"
private const val PREVIEW_PHOTO_MAX_PX = 1600

private fun previewPhotoFile(context: Context) = File(context.filesDir, PREVIEW_PHOTO)

/**
 * A stage for the real toolbar: its own background, recorded for the glass
 * on it to look through, so the bar is judged against something without the
 * note behind the panel being recorded or redrawn for it. Unless it is
 * [interactive], nothing in it can be touched: it is a picture of the bar,
 * not a second bar.
 */
@Composable
internal fun ToolbarPreviewStage(
    background: PreviewBackground,
    photo: ImageBitmap?,
    modifier: Modifier = Modifier,
    /** A bar and controls to try: they take touches, and the stage only draws behind them. */
    interactive: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberBackdrop(active = true)
    val liquid = rememberLiquidGlassBackdrop()
    // The bar is drawn shrunk to fit the stage, so its glass reads the stage
    // by screen position and undoes that scale, as a popup's glass does.
    val stageOrigin = remember { mutableStateOf(Offset.Zero) }
    val aligned = remember(liquid) { GlassPopupBackdrop(liquid) { stageOrigin.value } }
    Box(modifier.clip(Ui.Shape.card)) {
        // The background goes inside the recording; the bar is outside it,
        // or the glass would be looking at itself.
        Box(
            Modifier
                .matchParentSize()
                .onGloballyPositioned { stageOrigin.value = it.positionOnScreen() }
                .recordBackdrop(backdrop)
                .captureLiquidGlassBackdrop(liquid)
                .previewBackdrop(background, photo),
        )
        CompositionLocalProvider(
            LocalBackdrop provides backdrop,
            LocalLiquidGlassBackdrop provides aligned,
            LocalLiquidGlassHostBackdrop provides null,
            LocalGlassPopupBackdrop provides null,
        ) {
            Box(
                Modifier.matchParentSize().then(
                    if (interactive) Modifier
                    else Modifier.clearAndSetSemantics { contentDescription = "Toolbar preview" },
                ),
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
        // On top of the bar: it takes every touch, so the preview's buttons
        // never act, and leaves the events unconsumed for a scrolling parent.
        if (!interactive) Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            },
        )
    }
}

/**
 * The three grounds, built once per size: the paths, brushes and crop are
 * made in the cache block, and a redraw only replays them.
 */
private fun Modifier.previewBackdrop(kind: PreviewBackground, photo: ImageBitmap?): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    if (kind == PreviewBackground.PHOTO && photo != null && w > 0f && h > 0f) {
        // Centre crop: fill the stage, keep the photo's proportions.
        val scale = maxOf(w / photo.width, h / photo.height)
        val srcW = (w / scale).roundToInt().coerceIn(1, photo.width)
        val srcH = (h / scale).roundToInt().coerceIn(1, photo.height)
        val src = IntOffset((photo.width - srcW) / 2, (photo.height - srcH) / 2)
        val dst = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
        onDrawBehind {
            drawImage(photo, srcOffset = src, srcSize = IntSize(srcW, srcH), dstSize = dst)
        }
    } else if (kind == PreviewBackground.CALM) {
        val reach = maxOf(w, h)
        val blobs = listOf(
            Triple(Color(0xFF7FB7BE), Offset(w * 0.18f, h * 0.30f), reach * 0.55f),
            Triple(Color(0xFFE8C9A0), Offset(w * 0.72f, h * 0.20f), reach * 0.45f),
            Triple(Color(0xFF8E7DBE), Offset(w * 0.58f, h * 0.95f), reach * 0.50f),
            Triple(Color(0xFFA3C4A8), Offset(w * 0.95f, h * 0.70f), reach * 0.35f),
        ).map { (color, center, radius) ->
            Triple(Brush.radialGradient(listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0f)), center, radius), center, radius)
        }
        onDrawBehind {
            drawRect(Color(0xFFB9C7CF))
            blobs.forEach { (brush, center, radius) -> drawCircle(brush, radius, center) }
        }
    } else {
        val rule = 26.dp.toPx()
        val margin = 34.dp.toPx()
        // Handwriting in pen: two lines of loops.
        val pen = Path().apply {
            for (line in 0..1) {
                val y = h * (0.30f + 0.22f * line)
                var x = margin + 10.dp.toPx()
                moveTo(x, y)
                val end = w * (if (line == 0) 0.86f else 0.58f)
                val step = 14.dp.toPx()
                var up = true
                while (x < end) {
                    val next = x + step
                    cubicTo(x + step * 0.3f, y - (if (up) 9.dp else 3.dp).toPx(),
                        next - step * 0.3f, y + (if (up) 2.dp else 7.dp).toPx(), next, y)
                    x = next
                    up = !up
                }
            }
        }
        // A pencil sketch: a box and a few hatches, drawn twice for the grain.
        val pencil = Path().apply {
            val left = w * 0.64f
            val top = h * 0.58f
            val side = minOf(w * 0.24f, h * 0.30f)
            moveTo(left, top)
            lineTo(left + side, top + 2.dp.toPx())
            lineTo(left + side - 3.dp.toPx(), top + side)
            lineTo(left + 2.dp.toPx(), top + side - 2.dp.toPx())
            close()
            for (i in 1..4) {
                val t = side * i / 5f
                moveTo(left + t, top + 4.dp.toPx())
                lineTo(left + t - side * 0.25f, top + side - 4.dp.toPx())
            }
        }
        val grain = Path().apply { addPath(pencil, Offset(0.6.dp.toPx(), 0.8.dp.toPx())) }
        // A highlighter pass over the second line.
        val highlight = Path().apply {
            val y = h * 0.52f
            moveTo(margin + 6.dp.toPx(), y + 1.dp.toPx())
            quadraticTo(w * 0.32f, y - 2.dp.toPx(), w * 0.56f, y)
        }
        val ruleWidth = 1.dp.toPx()
        val penWidth = 2.2.dp.toPx()
        val pencilWidth = 1.4.dp.toPx()
        val highlightWidth = 16.dp.toPx()
        onDrawBehind {
            drawRect(Color(0xFFFAF8F2))
            var y = rule
            while (y < h) {
                drawLine(Color(0xFFD5DFEA), Offset(0f, y), Offset(w, y), ruleWidth)
                y += rule
            }
            drawLine(Color(0x66E57373), Offset(margin, 0f), Offset(margin, h), ruleWidth)
            drawPath(highlight, Color(0x66FFE24D), style = Stroke(highlightWidth, cap = StrokeCap.Butt))
            drawPath(pen, Color(0xFF1F3A93), style = Stroke(penWidth, cap = StrokeCap.Round))
            drawPath(grain, Color(0x445E5E5E), style = Stroke(pencilWidth, cap = StrokeCap.Round))
            drawPath(pencil, Color(0xAA5E5E5E), style = Stroke(pencilWidth, cap = StrokeCap.Round))
        }
    }
}

/**
 * The bar to try, in the look being set: tools that pick (the highlight
 * slides to them), Functions that opens from where it was tapped, the pen
 * row's swatches and width at the full size, and under it a switch and a
 * slider in the same skin. Only the preview changes; the note's own tool
 * and pen are left alone. [sizeLevel] is the bar's real size.
 */
@Composable
private fun InteractiveToolbarPreview(sizeLevel: Int, onSizeLevel: (Int) -> Unit) {
    var mode by remember { mutableStateOf(EditMode.PEN) }
    var swatch by remember { mutableIntStateOf(0) }
    var width by remember { mutableFloatStateOf(0.35f) }
    var toggle by remember { mutableStateOf(true) }
    var level by remember { mutableFloatStateOf(0.6f) }
    val swatches = PREVIEW_SWATCHES
    val toolColors = remember(swatch) {
        PenStore.DEFAULTS.filterKeys { it.tints }.mapValues { Color(it.value.colorArgb or 0xFF000000.toInt()) } +
            (EditMode.PEN to swatches[swatch])
    }
    Column(
        Modifier.padding(Ui.Space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
    ) {
        // Down the side the bar is as long as its tools, so it is shrunk to the stage both ways.
        val vertical = ToolbarOrientation.vertical && sizeLevel > 0
        Box(if (vertical) Modifier.weight(1f, fill = false).fitInside() else Modifier.previewFit(glassToolbarWidth(sizeLevel, LocalChromeScale.current, currentToolbarConfig().tools(sizeLevel).size))) {
            LiquidGlassToolbar(
                sizeLevel = sizeLevel, onSizeLevel = onSizeLevel, mode = mode,
                toolColors = toolColors,
                targetWidth = glassToolbarWidth(sizeLevel, LocalChromeScale.current, currentToolbarConfig().tools(sizeLevel).size), docked = false, modifier = Modifier, onDrag = {},
                onTool = { tool -> tool.mode?.let { mode = it } },
                functions = listOf(
                    listOf(
                        GlassToolbarAction("Picture", AppIcons.picture) {},
                        GlassToolbarAction("Layers", AppIcons.layers) {},
                        GlassToolbarAction("Text", AppIcons.text) {},
                    ),
                    listOf(GlassToolbarAction("Settings", AppIcons.settings) {}),
                ),
                notes = emptyList(),
                ai = listOf(GlassToolbarAction("Gemini", AppIcons.ai) {}, GlassToolbarAction("Claude", AppIcons.ai) {}),
                topRow = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIconButton(onClick = {}, icon = AppIcons.back, contentDescription = "Back")
                        AppIconButton(onClick = {}, icon = AppIcons.undo, contentDescription = "Undo")
                        AppIconButton(onClick = {}, icon = AppIcons.redo, contentDescription = "Redo")
                        Text("100%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Ui.Space.s))
                    }
                },
                penOptions = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                        swatches.forEachIndexed { i, c ->
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .then(if (i == swatch) Modifier.border(2.dp, c, CircleShape).padding(4.dp) else Modifier)
                                    .clip(CircleShape)
                                    .background(c)
                                    .clickable { swatch = i },
                            )
                        }
                        Box(Modifier.weight(1f)) { SkinSlider(width, { width = it }, 0f..1f) }
                    }
                },
            )
        }
        // A switch and a slider in the same skin, to try beside the bar.
        Row(
            Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ui.Space.m),
        ) {
            SkinSurface(corner = Ui.Corner.chip) {
                Box(Modifier.padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs)) {
                    SkinSwitch(checked = toggle, onCheckedChange = { toggle = it })
                }
            }
            SkinSurface(Modifier.weight(1f), corner = Ui.Corner.chip) {
                Box(Modifier.padding(horizontal = Ui.Space.m, vertical = Ui.Space.xs)) {
                    SkinSlider(level, { level = it }, 0f..1f)
                }
            }
        }
    }
}

private val PREVIEW_SWATCHES = listOf(Color(0xFF23252B), Color(0xFF2F6FD6), Color(0xFFD94B3D), Color(0xFF3C9A5F))

@Composable
private fun rememberPreviewPhoto(revision: Int, wanted: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val photo by produceState<ImageBitmap?>(null, revision, wanted) {
        value = if (!wanted) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(previewPhotoFile(context).path)?.asImageBitmap() }.getOrNull()
        }
    }
    return photo
}

/** Copies a picked photo, shrunk, into the app's own files; the picker's grant does not last. */
private suspend fun copyPreviewPhoto(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = (PREVIEW_PHOTO_MAX_PX.toFloat() / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val file = previewPhotoFile(context)
        val partial = File(file.parentFile, "$PREVIEW_PHOTO.part")
        try {
            partial.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
        } finally {
            bitmap.recycle()
        }
        if (!partial.renameTo(file)) {
            file.delete()
            check(partial.renameTo(file))
        }
        true
    }.getOrDefault(false)
}

private enum class AppearanceGroup(val label: String) {
    DISPLAY("Display"), STYLE("Style"), PANELS("Panels"), GLASS("Glass"), COLORS("Colors"), ICONS("Icons"), MOTION("Motion"),
}

/**
 * One group of settings at a time under a preview of the real toolbar - the
 * style and toolbar first, then panels, glass, colours and icons. The preview
 * and the group chips stay put and the group's controls have the rest of the
 * screen; on a wide screen the preview sits beside them instead.
 */
@Composable
internal fun AppearanceSettings(
    skin: Skin,
    settings: SkinSettings,
    onSkin: (Skin) -> Unit,
    onChange: (SkinSettings) -> Unit,
    modifier: Modifier = Modifier,
    /** The bar's size, 0 (the whole bar, 1/4) to 3 (one button, 4/4): chosen here, kept everywhere. */
    toolbarSize: Int = 1,
    onToolbarSize: (Int) -> Unit = {},
) {
    // A slider reports every frame of a drag. Handed straight up, each frame
    // replaced the app's look: every screen under the sidebar recomposed - the
    // whole note, its page host and toolbar - and the look was written to
    // preferences, sixty times a second. Here the drag moves a draft that
    // only this screen and its preview see, and the draft is handed up once
    // the hand rests (or the screen goes away).
    var draft by remember { mutableStateOf<SkinSettings?>(null) }
    val latestOnChange by rememberUpdatedState(onChange)
    LaunchedEffect(draft) {
        val pending = draft ?: return@LaunchedEffect
        delay(APPEARANCE_COMMIT_MS)
        latestOnChange(pending)
        draft = null
    }
    DisposableEffect(Unit) {
        onDispose { draft?.let { latestOnChange(it) } }
    }
    val committed = settings
    @Suppress("NAME_SHADOWING")
    val settings = draft ?: committed
    val edit: (SkinSettings) -> Unit = { draft = it }
    @Suppress("NAME_SHADOWING")
    val onChange = edit
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    val scope = rememberCoroutineScope()
    var background by remember {
        mutableStateOf(PreviewBackground.entries.getOrElse(store.previewBackground) { PreviewBackground.PAGE })
    }
    var photoRevision by remember { mutableIntStateOf(0) }
    val hasPhoto = remember(photoRevision) { previewPhotoFile(context).isFile }
    val photo = rememberPreviewPhoto(photoRevision, background == PreviewBackground.PHOTO && hasPhoto)
    fun choose(next: PreviewBackground) {
        background = next
        store.previewBackground = next.ordinal
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (copyPreviewPhoto(context, uri)) {
                photoRevision++
                choose(PreviewBackground.PHOTO)
            } else {
                KToast.makeText(context, "Couldn't read that photo", Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun launchPicker() = pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    var group by remember { mutableStateOf(AppearanceGroup.DISPLAY) }

    // The preview and the choice of group stay in view; the group's own
    // controls get the rest of the screen to themselves.
    val preview: @Composable (Modifier) -> Unit = { stage ->
        ToolbarPreviewStage(background, photo, stage, interactive = true) {
            // The preview shows the draft while the note behind keeps the
            // committed look until the hand rests.
            CompositionLocalProvider(LocalSkinSettings provides settings, LocalChromeScale provides ToolbarScale.scale) {
                InteractiveToolbarPreview(toolbarSize, onToolbarSize)
            }
        }
    }
    val groups: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            AppearanceGroup.entries.forEach { option ->
                SettingsChoiceChip(selected = group == option, onClick = { group = option }, label = option.label)
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
            when (group) {
                AppearanceGroup.STYLE -> {
                    StyleGroup(
                        skin, onSkin, settings, onChange, toolbarSize, onToolbarSize,
                        background, hasPhoto, ::choose, ::launchPicker,
                    )
                }
                AppearanceGroup.DISPLAY -> DisplayGroup()
                AppearanceGroup.ICONS -> IconsGroup()
                AppearanceGroup.MOTION -> MotionGroup()
                AppearanceGroup.PANELS -> PanelsGroup(skin, settings, onChange)
                AppearanceGroup.GLASS -> {
                    SettingsToggle("Efficient glass", LabPerformance.efficientGlass,
                        { LabPerformance.setEfficientGlass(context, it) },
                        hint = "Uses one iOS glass pass and less surface blur. Off keeps the original glass.")
                    GlassGroup(skin, settings, onChange, onSkin)
                }
                AppearanceGroup.COLORS -> ColorsGroup(skin, settings, onChange)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedButton(onClick = { onChange(SkinSettings()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Reset all appearance settings")
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        when {
            // Wide: the preview on the left, the controls the whole height on the right.
            maxWidth >= 760.dp -> Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
                    preview(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = PREVIEW_STAGE_HEIGHT))
                    groups()
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { controls() }
            }
            // Tall enough: the preview and the group chips stay put, the controls scroll under them.
            maxHeight >= 520.dp -> Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                    preview(Modifier.fillMaxWidth().height(COMPACT_PREVIEW_HEIGHT))
                    groups()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { controls() }
            }
            // Short (a phone on its side, a large font): everything scrolls together.
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                    preview(Modifier.fillMaxWidth().height(COMPACT_PREVIEW_HEIGHT))
                    groups()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                controls()
            }
        }
    }
}

/**
 * The toolbar's size, direction and scale, what the preview stands on, light
 * or dark, and the UI style: the choices that change everything at once.
 */
@Composable
private fun StyleGroup(
    skin: Skin, onSkin: (Skin) -> Unit,
    settings: SkinSettings, onChange: (SkinSettings) -> Unit,
    toolbarSize: Int, onToolbarSize: (Int) -> Unit,
    background: PreviewBackground, hasPhoto: Boolean,
    choose: (PreviewBackground) -> Unit, launchPicker: () -> Unit,
) {
    SettingsSectionHeader("UI style")
    SkinChoiceTiles(skin, onSkin)
    LiquidSegmentedControl(
        segments = listOf("Light", "Dark"),
        selectedIndex = settings.themeMode.ordinal,
        onSelected = { index -> onChange(settings.copy(themeMode = AppThemeMode.entries[index])) },
        modifier = Modifier.fillMaxWidth(),
        // The control sits beside a recorded preview, not over one; a
        // plain indicator reads in light and dark alike.
        useLiquidGlass = false,
    )
    SettingsSectionHeader("Toolbar")
    ToolbarStageSelector(toolbarSize, onSelect = onToolbarSize)
    ToolbarOrientationChoice(toolbarSize)
    SettingsSectionHeader("Smoothness")
    SettingsToggle(
        "Light mode", InteractionGovernor.lightMode, InteractionGovernor::updateLightMode,
        hint = (if (InteractionGovernor.lightMode)
            "The glass is drawn plain and solid, the lens stays still, fewer pages are read ahead and background work takes fewer cores, so the pen and scrolling keep their speed."
        else "Glass, lens and reading ahead as set. Turn on if writing or scrolling stutters.") +
            if (InteractionGovernor.slowDevice) " On by default on this device." else "",
    )
    SettingsToggle(
        "Highest refresh rate", InteractionGovernor.highRefresh, InteractionGovernor::updateHighRefresh,
        hint = if (InteractionGovernor.highRefresh)
            "Asks the screen for its fastest rate, such as 120 Hz, while the app is open. Some phones keep apps at 60 Hz unless they ask. Not while battery saver makes the app save power."
        else "The system picks the rate, which on some phones is 60 Hz for this app.",
    )
    SettingsSectionHeader("Battery")
    SettingsToggle(
        "Battery optimization", InteractionGovernor.batteryOptimization, InteractionGovernor::updateBatteryOptimization,
        hint = (if (InteractionGovernor.batteryOptimization)
            "In battery saver the glass goes plain and solid, the lens stops moving and fewer pages are read ahead."
        else "Battery saver changes nothing here: the glass, its motion and reading ahead stay as set. The system's own limits still apply.") +
            if (InteractionGovernor.systemPowerSave) " Battery saver is on now." else "",
    )
    SettingsSectionHeader("Preview")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Preview on", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PreviewBackground.entries.forEach { option ->
            SettingsChoiceChip(
                selected = background == option,
                onClick = {
                    if (option == PreviewBackground.PHOTO && !hasPhoto) launchPicker() else choose(option)
                },
                label = option.label,
            )
        }
        if (background == PreviewBackground.PHOTO && hasPhoto) {
            TextButton(onClick = launchPicker, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Change photo")
            }
        }
    }
}

/**
 * What the app is shown in and how big: its language, its brightness and the
 * size of everything it draws. The first two were on the settings' first
 * screen; they live here with the rest of how the app looks.
 */
@Composable
private fun DisplayGroup() {
    val context = LocalContext.current
    // Named in both languages, so it can be found whichever one is showing.
    SettingsSectionHeader("Language · 언어")
    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        AppLanguage.entries.forEach { language ->
            SettingsChoiceChip(selected = L10n.language == language, onClick = { L10n.choose(context, language) },
                label = language.label)
        }
    }
    SettingsSectionHeader("Brightness")
    AppBrightnessSetting()
    SettingsSectionHeader("Interface size")
    // Every step resizes the whole app, so the size is taken once the hand
    // rests, not on every frame of the drag.
    var draft by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(draft) {
        val pending = draft ?: return@LaunchedEffect
        delay(UI_SCALE_COMMIT_MS)
        UiScale.choose(pending)
        draft = null
    }
    val shown = draft ?: UiScale.scale
    AppearanceSlider("Size", "%.0f%%".format(shown * 100), shown, UiScale.MIN..UiScale.MAX,
        step = UiScale.STEP, default = UiScale.DEFAULT,
        note = "Everything the app draws, its words included, from 50% to 150%. 100% is the system's own size; the page keeps its own zoom.",
    ) { draft = it }
}

private const val UI_SCALE_COMMIT_MS = 450L

/** How the chrome moves: its speed, the light taps under the finger and the blur along fast motion. */
@Composable
private fun MotionGroup() {
    val context = LocalContext.current
    // Frames are counted only while this is on screen: a listener on every
    // frame of the app, and a state write for each, cost the motion it measured.
    DisposableEffect(context) {
        var host: android.content.Context? = context
        while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
        val activity = host as? android.app.Activity
        activity?.let { MotionStats.start(it) }
        onDispose { activity?.let { MotionStats.stop(it) } }
    }
    SettingsSectionHeader("Motion")
    SettingsNote("Everything moves on springs, as on iOS: a motion caught half way turns from where it is, " +
        "and a flick carries its speed.")
    AppearanceSlider("Speed", "%.0f%%".format(MotionPrefs.speed * 100), MotionPrefs.speed, MotionPrefs.SPEED_RANGE,
        step = 0.05f, default = MotionPrefs.DEFAULT_SPEED,
        note = "Up to 300%. Higher is quicker; 130% is the default.",
    ) { MotionPrefs.setSpeed(context, it) }
    SettingsToggle("Haptics", MotionPrefs.haptics, { MotionPrefs.setHaptics(context, it) },
        hint = "A light tick where something snaps, a switch flips or a slider lands on a step. " +
            "The tablet's own vibration setting still applies.")
    AppearanceSlider("Motion blur", "%.0f%%".format(MotionPrefs.blurStrength * 100), MotionPrefs.blurStrength, 0f..1f,
        step = 0.05f, default = MotionPrefs.DEFAULT_BLUR,
        note = "A trace of blur along popups and scrolling lists at their fastest, so they read as motion rather than as steps. " +
            "0% is off. Only on 60 Hz screens, and off in Light mode.",
    ) { MotionPrefs.setBlurStrength(context, it) }
    SettingsSectionHeader("While the chrome moves")
    DiagnosticRow("Frames", MotionStats.frames.toString())
    DiagnosticRow("Late frames", MotionStats.late.toString())
    DiagnosticRow("Longest frame", "%.1f ms".format(MotionStats.worstMs))
    OutlinedButton(onClick = { MotionStats.reset() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset") }
}

@Composable
private fun IconsGroup() {
    val context = LocalContext.current
    SettingsSectionHeader("Icon set")
    SettingsNote("Static icons in every look. Motion belongs to controls and panels.")
    LiquidSegmentedControl(
        segments = StaticIconSet.entries.map { it.label },
        selectedIndex = AppIconStyle.selected.ordinal,
        onSelected = { index -> AppIconStyle.select(context, StaticIconSet.entries[index]) },
        modifier = Modifier.fillMaxWidth(),
        useLiquidGlass = false,
    )
}

@Composable
private fun PanelsGroup(skin: Skin, settings: SkinSettings, onChange: (SkinSettings) -> Unit) {
    val defaults = SkinSettings()
    SettingsSectionHeader("Panels, menus and the toolbar")
    // Each kind of chrome on its own, over whatever glass the style draws.
    val solidStyle = if (skin == Skin.MATERIAL) "Material panels are solid" else null
    ChromeRoleControls(ChromeRole.TOOLBAR, "Toolbar",
        "The toolbar, its strip of tools and its round buttons.", settings, onChange, solidStyle)
    ChromeRoleControls(ChromeRole.POPUP, "Popups",
        "The Functions menu, the pen panel, Tools and the other panels that open from the bar, and a page shown large.",
        settings, onChange, solidStyle)
    ChromeRoleControls(ChromeRole.SIDEBAR, "Sidebars",
        "Settings, the page panel and the layer panel along the window's edge.", settings, onChange, solidStyle)
    AppearanceSlider("Dim behind popups", "%.0f%%".format(settings.popupDim * 100), settings.popupDim,
        SkinSettings.POPUP_DIM_RANGE, step = 0.01f, default = defaults.popupDim,
        note = "How much the page darkens behind a tool or pen panel.",
    ) { onChange(settings.copy(popupDim = it)) }
    val ownRecipe = skin == Skin.LIQUID_GLASS && settings.liquidStyle != LiquidStyle.NOTESIS
    if (ownRecipe) {
        SettingsNote("${settings.liquidStyle.label} sets its corner, blur, surface, rim and motion in Glass, " +
            "with the numbers its project uses.")
        return
    }
    // One corner for every style: the toolbar, its menus and the panels take
    // the same curve whichever style is on (see [chromeCorner]).
    AppearanceSlider("Corner radius", "%.0f dp".format(settings.liquidGlassCorner), settings.liquidGlassCorner,
        SkinSettings.CORNER_RANGE, step = 1f, default = defaults.liquidGlassCorner,
        note = "Shared by Material, Glassmorphism and Liquid glass. Liquid glass's other recipes keep their own.",
    ) { onChange(settings.copy(liquidGlassCorner = it)) }
    AppearanceSlider("Frost", "%.0f%%".format(settings.frost * 100), settings.frost, 0f..1f,
        step = 0.01f, default = defaults.frost,
        note = "How much the page behind is blurred, and how much colour comes through the blur.",
        disabledReason = if (skin == Skin.MATERIAL) "Material panels are solid" else null,
    ) { onChange(settings.withFrost(it)) }
    if (skin == Skin.LIQUID_GLASS) {
        AppearanceSlider("Transparency", "%.0f%%".format(settings.transparency * 100), settings.transparency, 0f..1f,
            step = 0.01f, default = defaults.transparency,
            note = "0% is solid, 100% clear glass - the panels and the toolbar together. " +
                "Menus and the pen panel stay solid enough to read.",
        ) { onChange(settings.withTransparency(it)) }
        AppearanceSlider("Reflection", "%.0f%%".format(settings.reflection * 100), settings.reflection,
            SkinSettings.REFLECTION_RANGE, step = 0.01f, default = defaults.reflection,
            note = "The light the glass catches: its bright rim and the sheen across its top.",
        ) { onChange(settings.copy(reflection = it)) }
    }
}

@Composable
private fun GlassGroup(skin: Skin, settings: SkinSettings, onChange: (SkinSettings) -> Unit, onSkin: (Skin) -> Unit) {
    if (!skin.isRefractive) {
        SettingsNote("Glass optics belong to the Liquid glass style; ${skin.displayName} has none.")
        OutlinedButton(onClick = { onSkin(Skin.LIQUID_GLASS) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Use Liquid glass")
        }
        return
    }
    when (settings.liquidStyle) {
        LiquidStyle.IOS -> {
            IosGlassControls(settings.ios, settings.accent, settings.themeMode == AppThemeMode.DARK) {
                onChange(settings.copy(ios = it))
            }
            return
        }
        LiquidStyle.MORNYE -> {
            MornyeGlassControls(settings.mornye, settings.accent, settings.themeMode == AppThemeMode.DARK) {
                onChange(settings.copy(mornye = it))
            }
            return
        }
        LiquidStyle.NOTESIS -> Unit
    }
    val defaults = SkinSettings()
    SettingsSectionHeader("Lens")
    AppearanceSlider("Depth", "%.0f dp".format(settings.depth), settings.depth, SkinSettings.DEPTH_RANGE,
        step = 1f, default = defaults.depth,
        note = "How far in from the edge the glass bends the page, and how strongly.",
    ) { onChange(settings.withDepth(it)) }
    Text("Lens direction", style = MaterialTheme.typography.bodyLarge)
    LiquidSegmentedControl(
        segments = RefractionDirection.entries.map { it.displayName },
        selectedIndex = settings.refractionDirection.ordinal,
        onSelected = { index -> onChange(settings.copy(refractionDirection = RefractionDirection.entries[index])) },
        modifier = Modifier.fillMaxWidth(),
        useLiquidGlass = false,
    )
    SettingsNote("Raised is a lens standing above the page, pressed in a lens sunk into it.")
    if (skin == Skin.LIQUID_GLASS) {
        SettingsSectionHeader("Touch")
        AppearanceSlider("Interaction strength", "%.0f%%".format(settings.liquidGlassResponse * 100),
            settings.liquidGlassResponse, 0f..1f, step = 0.01f, default = defaults.liquidGlassResponse,
            note = "How far the lens stretches and lifts on press and slide.",
        ) { onChange(settings.copy(liquidGlassResponse = it)) }
    }
    SettingsNote("Sliders, switches and segmented controls keep their own glass, whatever is set here.")
}

/** The three motion switches every recipe has: on, the project's; off, this app's as before. */
@Composable
private fun ProjectMotionControls(m: ProjectMotion, project: String, onChange: (ProjectMotion) -> Unit) {
    SettingsSectionHeader("Motion")
    SettingsNote("On, $project's own motion; off, this app's as before.")
    SettingsToggle("Press", m.press, { onChange(m.copy(press = it)) },
        hint = "How the toolbar's round buttons and glass buttons answer a finger.")
    SettingsToggle("Selection slide", m.slide, { onChange(m.copy(slide = it)) },
        hint = "How the toolbar's selection follows a finger and lands on a tool.")
    SettingsToggle("Menu opening", m.open, { onChange(m.copy(open = it)) },
        hint = "How Functions, its menus and the tool panels come out of the button that opened them, and go back.")
}

/** liquid_glass_widgets' iOS material, under the names its LiquidGlassSettings take. */
@Composable
private fun IosGlassControls(g: IosGlass, appAccent: Int, dark: Boolean, onChange: (IosGlass) -> Unit) {
    val d = IosGlass()
    SettingsNote("iOS together with Mornye: iOS's glass, with Mornye's colours, its continuous corners and its " +
        "menus that pour out of their button.")
    MornyeAccentPicker(g.accent, appAccent, dark) { onChange(g.copy(accent = it)) }
    SettingsToggle("Smooth corners", g.smoothCorners, { onChange(g.copy(smoothCorners = it)) },
        hint = "Mornye's squircle: the corner eases out of the straight edge instead of turning on a circle.")
    SettingsNote("From liquid_glass_widgets 1.16.1 (MIT), whose iOS 27 regular presets were measured against the " +
        "native control: a frosted body over a lightly blurred page, a paraxial rim and the native springs. " +
        "The defaults are set by eye from there. Dark mode takes its dark preset, moved in proportion to what is set here.")
    SettingsSectionHeader("Lens")
    AppearanceSlider("Thickness", "%.0f".format(g.thickness), g.thickness, IosGlass.THICKNESS_RANGE, step = 1f,
        default = d.thickness, note = "In pixels at 3x: a bevel of %.1f dp.".format(g.bevelDp),
    ) { onChange(g.copy(thickness = it)) }
    AppearanceSlider("Refractive index", "%.2f".format(g.refractiveIndex), g.refractiveIndex, IosGlass.INDEX_RANGE,
        step = 0.01f, default = d.refractiveIndex,
        note = "The rim bends the page by thickness × 8 × (index − 1): %.1f dp, at most half the pane.".format(g.bendDp),
    ) { onChange(g.copy(refractiveIndex = it)) }
    AppearanceSlider("Chromatic aberration", "%.2f".format(g.dispersion), g.dispersion, 0f..1f, step = 0.01f,
        default = d.dispersion, note = "0 in the presets and by default.",
    ) { onChange(g.copy(dispersion = it)) }
    SettingsSectionHeader("Material")
    AppearanceSlider("Blur", "%.1f".format(g.blur), g.blur, IosGlass.BLUR_RANGE, step = 0.1f, default = d.blur,
        note = "The page under the frost, barely softened.",
    ) { onChange(g.copy(blur = it)) }
    AppearanceSlider("Frost", "%.0f".format(g.frost), g.frost, IosGlass.FROST_RANGE, step = 1f, default = d.frost,
        note = "The cloud over it, as a blur.",
    ) { onChange(g.copy(frost = it)) }
    AppearanceSlider("Frost opacity", "%.0f%%".format(g.frostOpacity * 100), g.frostOpacity, 0f..1f, step = 0.01f,
        default = d.frostOpacity, note = "Below full, the page shows through the cloud as pale ghosts.",
    ) { onChange(g.copy(frostOpacity = it)) }
    AppearanceSlider("Saturation", "%.1f×".format(g.saturation), g.saturation, IosGlass.SATURATION_RANGE, step = 0.1f,
        default = d.saturation,
    ) { onChange(g.copy(saturation = it)) }
    AppearanceSlider("Body", "%.0f%%".format(g.bodyOpacity * 100), g.bodyOpacity, 0f..1f, step = 0.01f,
        default = d.bodyOpacity, note = "The #F8F8F8 glass colour over it all.",
    ) { onChange(g.copy(bodyOpacity = it)) }
    SettingsSectionHeader("Rim")
    AppearanceSlider("Rim light", "%.2f".format(g.rimLight), g.rimLight, 0f..2f, step = 0.05f, default = d.rimLight,
        note = "Light caught along the edge, where it faces the light.",
    ) { onChange(g.copy(rimLight = it)) }
    AppearanceSlider("Light from", lightAngleLabel(g.lightAngle), g.lightAngle, IosGlass.LIGHT_ANGLE_RANGE, step = 5f,
        default = d.lightAngle,
        note = "Where the rim is brightest. 90° lights the top and bottom and leaves the left and right ends faint; " +
            "0° or 180° lights the ends instead, 45° or 135° the corners.",
        disabledReason = if (g.rimAllRound) "The rim is lit all the way round" else null,
    ) { onChange(g.copy(lightAngle = it)) }
    SettingsToggle("Light all the way round", g.rimAllRound, { onChange(g.copy(rimAllRound = it)) },
        hint = "The same light along every edge, with no faint ends.")
    AppearanceSlider("Rim shade", "%.2f".format(g.rimShade), g.rimShade, 0f..2f, step = 0.05f, default = d.rimShade,
        note = "The fine dark line just inside the edge.",
    ) { onChange(g.copy(rimShade = it)) }
    AppearanceSlider("Edge absorption", "%.3f".format(g.edgeAbsorption), g.edgeAbsorption, 0f..0.5f, step = 0.005f,
        default = d.edgeAbsorption, note = "How much the bevel darkens towards the edge.",
    ) { onChange(g.copy(edgeAbsorption = it)) }
    AppearanceSlider("Corner radius", "%.0f dp".format(g.corner), g.corner, SkinSettings.CORNER_RANGE, step = 1f,
        default = d.corner, note = "29 by default; its GlassMenu's is 32.",
    ) { onChange(g.copy(corner = it)) }
    ProjectMotionControls(g.motion, "liquid_glass_widgets") { onChange(g.copy(motion = it)) }
    OutlinedButton(onClick = { onChange(d.copy(motion = g.motion, accent = g.accent)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to the defaults") }
    OutlinedButton(onClick = { onChange(IosGlass.PRESET.copy(motion = g.motion, accent = g.accent)) }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Use the ios27Light preset as measured")
    }
}

/**
 * One kind of chrome's own look over the glass: how clear it is, the
 * colour that shows as it goes solid, and how much darker it is drawn.
 */
@Composable
private fun ChromeRoleControls(
    role: ChromeRole,
    title: String,
    note: String,
    settings: SkinSettings,
    onChange: (SkinSettings) -> Unit,
    disabledReason: String?,
) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Ui.Space.s))
    SettingsNote(note)
    val clarity = settings.clarityOf(role)
    AppearanceSlider("Transparency", "%.0f%%".format(clarity * 100), clarity, 0f..1f, step = 0.01f, default = 1f,
        note = "100% is the glass as the style draws it; lower lays the colour below over it, 0% solid.",
        disabledReason = disabledReason,
    ) { onChange(settings.withRole(role, clarity = it)) }
    val chosen = settings.colorOf(role)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Color", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        (listOf(0, settings.accent) + CHROME_COLORS).distinct().forEach { color ->
            val picked = chosen == color
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (color == 0) MaterialTheme.colorScheme.surface else Color(color).copy(alpha = 1f))
                    .border(if (picked) 3.dp else 1.dp,
                        if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(enabled = disabledReason == null, onClickLabel = if (color == 0) "Theme color" else "Color") {
                        onChange(settings.withRole(role, color = color))
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (color == 0) Text("A", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (chosen != 0 && clarity >= 0.999f) {
        SettingsNote("The colour shows once the transparency is below 100%.")
    }
    val darken = settings.darkenOf(role)
    AppearanceSlider("Darken", "%.0f%%".format(darken * 100), darken, SkinSettings.DARKEN_RANGE, step = 0.01f,
        default = 0f, note = "Draws it a little darker, glass or solid alike.",
        disabledReason = disabledReason,
    ) { onChange(settings.withRole(role, darken = it)) }
}

/** The background colours offered for a kind of chrome, besides the theme's and the accent. */
private val CHROME_COLORS = listOf(
    0xFFFFFFFF.toInt(), 0xFFE5E5EA.toInt(), 0xFF8E8E93.toInt(), 0xFF2C2C2E.toInt(), 0xFF000000.toInt(),
    0xFFFFF4D6.toInt(), 0xFFE3F2E8.toInt(), 0xFFE3ECFF.toInt(), 0xFFFCE4EC.toInt(),
)

/** The light's angle as where on the pane it comes from. */
private fun lightAngleLabel(angle: Float): String = when (angle.roundToInt()) {
    0 -> "0° · right"
    45 -> "45° · top right"
    90 -> "90° · top"
    135 -> "135° · top left"
    180 -> "180° · left"
    else -> "%.0f°".format(angle)
}

/** The accent the Mornye and iOS looks build their colours from: iOS's system colours, or the app's own. */
@Composable
private fun MornyeAccentPicker(chosen: MornyeAccent, appAccent: Int, dark: Boolean, onPick: (MornyeAccent) -> Unit) {
    SettingsSectionHeader("Accent")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MornyeAccent.entries.forEach { accent ->
            val picked = chosen == accent
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(accent.argb(dark, appAccent)).copy(alpha = 1f))
                        .border(if (picked) 3.dp else 1.dp,
                            if (picked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable(onClickLabel = accent.label) { onPick(accent) },
                )
                Text(accent.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    SettingsNote("App accent follows the colour set in Colours below.")
}

/** Mornye's glass: how clear it is, its rim, its corner and the accent its colours are built from. */
@Composable
private fun MornyeGlassControls(m: MornyeGlass, appAccent: Int, dark: Boolean, onChange: (MornyeGlass) -> Unit) {
    val d = MornyeGlass()
    SettingsNote("From Mornye (LGPL-3.0), written again from its numbers: a white body that clears as the clarity " +
        "rises, a gentle lens, a fine lit rim and iOS's grouped colours. Bars take its clear tint, panels and menus " +
        "its stronger one so their words read.")
    MornyeAccentPicker(m.accent, appAccent, dark) { onChange(m.copy(accent = it)) }
    SettingsSectionHeader("Glass")
    AppearanceSlider("Clarity", "%.0f%%".format(m.clarity * 100), m.clarity, 0f..1f, step = 0.05f, default = d.clarity,
        note = "Higher shows more of the page and blurs it less. Below 20% the glass is a plain tinted surface.",
    ) { onChange(m.copy(clarity = it)) }
    SettingsToggle("Glass outlines", m.outlines, { onChange(m.copy(outlines = it)) },
        hint = "A hairline edge with a lit rim inside it.")
    SettingsToggle("Smooth corners", m.smoothCorners, { onChange(m.copy(smoothCorners = it)) },
        hint = "Mornye's squircle: the corner eases out of the straight edge instead of turning on a circle.")
    AppearanceSlider("Corner radius", "%.0f dp".format(m.corner), m.corner, SkinSettings.CORNER_RANGE, step = 1f,
        default = d.corner, note = "32 by default, as its panels.",
    ) { onChange(m.copy(corner = it)) }
    ProjectMotionControls(m.motion, "Mornye") { onChange(m.copy(motion = it)) }
    OutlinedButton(onClick = { onChange(d.copy(accent = m.accent)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to the defaults") }
}

@Composable
private fun ColorsGroup(skin: Skin, settings: SkinSettings, onChange: (SkinSettings) -> Unit) {
    var editing by remember { mutableStateOf<ColorSlot?>(null) }
    if (skin == Skin.MATERIAL) {
        SettingsSectionHeader("Material colours")
        val wallpaperAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        SettingsToggle("Wallpaper colours", settings.dynamicColor, { onChange(settings.copy(dynamicColor = it)) },
            hint = "The theme takes its colours from the wallpaper, as Android does. Off, from the accent below.",
            disabledReason = when {
                !wallpaperAvailable -> "Needs Android 12 or later"
                settings.highContrast -> "High contrast uses the accent"
                else -> null
            },
        )
        SettingsToggle("AMOLED black", settings.amoled, { onChange(settings.copy(amoled = it)) },
            hint = "In dark mode, a black page with near-black panels.",
            disabledReason = if (settings.themeMode != AppThemeMode.DARK) "Used in dark mode" else null,
        )
    }
    if (skin == Skin.LIQUID_GLASS && settings.liquidStyle != LiquidStyle.NOTESIS) {
        SettingsNote("${settings.liquidStyle.label} builds its colours from its own accent, chosen in Glass. The ink below still applies.")
    }
    SettingsSectionHeader("Cards and buttons")
    ColorSlot.entries.forEach { slot ->
        ColorRow(slot.label, slot.read(settings), selected = editing == slot,
            note = if (slot == ColorSlot.ACCENT) "The whole theme is made from this color" else null) {
            editing = if (editing == slot) null else slot
        }
        if (editing == slot) {
            InlineColorPicker(
                argb = slot.read(settings),
                onPick = { onChange(slot.write(settings, it)); editing = null },
                onCancel = { editing = null },
            )
        }
        if (slot == ColorSlot.ACCENT) AccentPresets(settings.accent) { onChange(settings.copy(accent = it)) }
    }
    SettingsSectionHeader("Readability")
    SettingsToggle(
        "High contrast", settings.highContrast, { onChange(settings.copy(highContrast = it)) },
        hint = "Darker text, solid glass, thicker edges. Colors stay; only the contrast goes up.",
    )
    SettingsToggle(
        "Shadows", settings.shadows, { onChange(settings.copy(shadows = it)) },
        hint = "Shadows under the toolbar, panels, menus, buttons and dialogs, in every look. Off, everything sits flat on the page.",
    )
}

/** Which colour a picker is for, so one picker serves all four. */
enum class ColorSlot(val label: String) {
    TINT("Glass tint"),
    BORDER("Edge"),
    CONTENT("Text and icons"),
    ACCENT("Accent"),
    ;

    fun read(s: SkinSettings): Int = when (this) {
        TINT -> s.tint
        BORDER -> s.border
        CONTENT -> s.content
        ACCENT -> s.accent
    }

    fun write(s: SkinSettings, argb: Int): SkinSettings = when (this) {
        TINT -> s.copy(tint = argb)
        BORDER -> s.copy(border = argb)
        CONTENT -> s.copy(content = argb)
        ACCENT -> s.copy(accent = argb)
    }
}

/**
 * The accent is now the whole theme, so it is worth being able to change it
 * without going through a colour wheel first. These are the hues Material's own
 * baseline is one of - the purple is that baseline, so the row starts where the
 * app has always been and every other swatch is a different app.
 */
@Composable
private fun AccentPresets(current: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
    ) {
        for (argb in ACCENTS) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .clickable { onPick(argb) }
                    .semantics { contentDescription = "Accent #%06X".format(argb and 0xFFFFFF) },
                contentAlignment = Alignment.Center,
            ) {
                if (argb == current) {
                    Icon(Reicons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Material's own primary, and eight more hues to leave it for. */
private val ACCENTS = listOf(
    0xFF6750A4, 0xFF0A84FF, 0xFF00A03C, 0xFF00897B,
    0xFF3F51B5, 0xFFEF6C00, 0xFFD8324B, 0xFFC2185B, 0xFF5A6472,
).map { it.toInt() }

/**
 * One setting as a card of its own: what it is and its value large enough to
 * read at a glance, the slider the card's full width, and a step down and up
 * either side of it for the last bit - easier than landing a finger on a
 * value. Tapping the value puts it back to its default.
 */
@Composable
private fun AppearanceSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    note: String? = null,
    disabledReason: String? = null,
    step: Float = if (range.endInclusive - range.start >= 20f) 1f else 0.01f,
    default: Float? = null,
    onChange: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(nestedCorner(Ui.Corner.card))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .disabledReason(disabledReason)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val resettable = default != null && disabledReason == null && kotlin.math.abs(current - default) > step / 2f
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(scheme.primaryContainer)
                    .then(if (resettable) Modifier.clickable(onClickLabel = "Reset to default") { onChange(default!!) } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (note != null) SettingsNote(note)
        if (disabledReason != null) {
            Text(disabledReason, style = MaterialTheme.typography.bodySmall, color = scheme.error)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                fun nudge(by: Float) = onChange((current + by).coerceIn(range))
                IconButton(onClick = { nudge(-step) }, modifier = Modifier.size(Ui.Size.touch)) {
                    Text("−", style = MaterialTheme.typography.titleLarge)
                }
                SkinSlider(
                    value = current.coerceIn(range),
                    onValueChange = onChange,
                    valueRange = range,
                    modifier = Modifier.weight(1f).heightIn(min = Ui.Size.touch),
                    step = step,
                )
                IconButton(onClick = { nudge(step) }, modifier = Modifier.size(Ui.Size.touch)) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun ColorRow(label: String, argb: Int, selected: Boolean, note: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(Ui.Shape.control)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(Ui.Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) SettingsNote(note)
        }
        // Chequered underneath, so a colour that is mostly transparent looks
        // transparent rather than looking like a slightly different grey.
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFDDDDDD), Color(0xFFF6F6F6))))
                .background(Color(argb)),
        )
    }
}

/** Alpha included, because the body colour of glass is mostly its alpha. Opens in place, under its row. */
@Composable
private fun InlineColorPicker(argb: Int, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val hsv = remember(argb) { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    var hue by remember(argb) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(argb) { mutableFloatStateOf(hsv[1]) }
    var value by remember(argb) { mutableFloatStateOf(hsv[2]) }
    var alpha by remember(argb) { mutableFloatStateOf(android.graphics.Color.alpha(argb) / 255f) }
    val picked = Color.hsv(hue, saturation, value, alpha)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Ui.Shape.control)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(Ui.Space.m),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.m),
    ) {
        SaturationValueField(hue, saturation, value) { s, v ->
            saturation = s
            value = v
        }
        GradientStrip(colors = (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }, position = hue / 360f) { hue = it * 360f }
        GradientStrip(
            colors = listOf(Color.hsv(hue, saturation, value, 0f), Color.hsv(hue, saturation, value)),
            position = alpha,
        ) { alpha = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#%08X".format(picked.toArgb()) + "  ·  opacity ${(alpha * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 44.dp)) { Text("Cancel") }
            TextButton(onClick = { onPick(picked.toArgb()) }, modifier = Modifier.heightIn(min = 44.dp)) { Text("Apply") }
        }
    }
}

/**
 * Where the look is tuned when no note is open: the same appearance settings
 * as the note's sidebar, on a screen of their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinSettingsScreen(
    skin: Skin,
    settings: SkinSettings,
    onSkin: (Skin) -> Unit,
    onChange: (SkinSettings) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Reicons.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Appearance") },
            )
        },
    ) { padding ->
        // Plain chrome here: the one piece of glass worth looking at is the
        // preview, and it brings its own background.
        CompositionLocalProvider(LocalBackdrop provides NoBackdrop, LocalLiquidGlassBackdrop provides null) {
            // No note open: the size is kept for the next one that is.
            val context = LocalContext.current
            val store = remember { PenStore(context) }
            var toolbarSize by remember { mutableIntStateOf(store.toolbarSize) }
            AppearanceSettings(
                skin, settings, onSkin, onChange,
                modifier = Modifier.padding(padding).background(MaterialTheme.colorScheme.surface),
                toolbarSize = toolbarSize,
                onToolbarSize = { toolbarSize = it; store.toolbarSize = it },
            )
        }
    }
}
