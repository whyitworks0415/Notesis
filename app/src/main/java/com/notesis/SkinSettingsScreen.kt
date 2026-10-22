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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

/** The names the appearance screens use for each skin. */
internal val Skin.displayName: String
    get() = when (this) {
        Skin.MATERIAL -> "Material"
        Skin.GLASSMORPHISM -> "Frosted glass"
        Skin.LIQUID_GLASS -> "Liquid glass"
        Skin.SPOTIGLASS -> "Glass bar"
    }

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

/** How long a slider must rest before its value becomes the app's look. */
private const val APPEARANCE_COMMIT_MS = 250L

private const val PREVIEW_PHOTO = "appearance_preview.jpg"
private const val PREVIEW_PHOTO_MAX_PX = 1600

private fun previewPhotoFile(context: Context) = File(context.filesDir, PREVIEW_PHOTO)

/**
 * A stage for the real toolbar: its own background, recorded for the glass
 * on it to look through, so the bar is judged against something without the
 * note behind the panel being recorded or redrawn for it. Nothing in it can
 * be touched; it is a picture of the bar, not a second bar.
 */
@Composable
internal fun ToolbarPreviewStage(
    background: PreviewBackground,
    photo: ImageBitmap?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberBackdrop(active = true)
    val liquid = rememberLiquidGlassBackdrop()
    Box(modifier.clip(Ui.Shape.card)) {
        // The background goes inside the recording; the bar is outside it,
        // or the glass would be looking at itself.
        Box(
            Modifier
                .matchParentSize()
                .recordBackdrop(backdrop)
                .captureLiquidGlassBackdrop(liquid)
                .previewBackdrop(background, photo),
        )
        CompositionLocalProvider(
            LocalBackdrop provides backdrop,
            LocalLiquidGlassBackdrop provides liquid,
            LocalSpotiGlassHostBackdrop provides null,
            LocalSpotiPopupBackdrop provides null,
        ) {
            Box(
                Modifier.matchParentSize().clearAndSetSemantics { contentDescription = "Toolbar preview" },
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
        // On top of the bar: it takes every touch, so the preview's buttons
        // never act, and leaves the events unconsumed for a scrolling parent.
        Box(
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

/** The bar as it stands when no note is open to lend its own: 2/4, with the pen in hand. */
@Composable
private fun StandaloneToolbarPreview() {
    SpotiGlassToolbar(
        sizeLevel = 1, onSizeLevel = {}, mode = EditMode.PEN,
        toolColors = PenStore.DEFAULTS.filterKeys { it.tints }
            .mapValues { Color(it.value.colorArgb or 0xFF000000.toInt()) },
        targetWidth = spotiToolbarWidth(1), docked = false, modifier = Modifier, onDrag = {},
        onTool = {}, functions = emptyList(), notes = emptyList(), ai = emptyList(),
        topRow = {}, penOptions = {},
    )
}

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
    ICONS("Icons"), BACKGROUND("Background"), GLASS("Glass"), COLORS("Colors"),
}

/**
 * Light or dark, the UI style, then one group of settings at a time under a
 * preview of the real toolbar. The preview and the switches above the chips
 * stay put; only the group below them scrolls, and only when it has to.
 */
@Composable
internal fun AppearanceSettings(
    skin: Skin,
    settings: SkinSettings,
    onSkin: (Skin) -> Unit,
    onChange: (SkinSettings) -> Unit,
    modifier: Modifier = Modifier,
    /** The note's own bar at 2/4, when there is a note to lend it. */
    preview: (@Composable () -> Unit)? = null,
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
                Toast.makeText(context, "Couldn't read that photo", Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun launchPicker() = pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    var group by remember { mutableStateOf(AppearanceGroup.ICONS) }

    val upper: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            ToolbarPreviewStage(background, photo, Modifier.fillMaxWidth().height(140.dp)) {
                // The preview shows the draft while the note behind keeps the
                // committed look until the hand rests.
                CompositionLocalProvider(LocalSkinSettings provides settings) {
                    Box(Modifier.padding(Ui.Space.s).previewFit(spotiToolbarWidth(1))) {
                        if (preview != null) preview() else StandaloneToolbarPreview()
                    }
                }
            }
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
                    TextButton(onClick = { launchPicker() }, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("Change photo")
                    }
                }
            }
            LiquidSegmentedControl(
                segments = listOf("Light", "Dark"),
                selectedIndex = settings.themeMode.ordinal,
                onSelected = { index -> onChange(settings.copy(themeMode = AppThemeMode.entries[index])) },
                modifier = Modifier.fillMaxWidth(),
                // The control sits beside a recorded preview, not over one; a
                // plain indicator reads in light and dark alike.
                useLiquidGlass = false,
            )
            Text("UI style", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                Skin.entries.forEach { option ->
                    SettingsChoiceChip(selected = option == skin, onClick = { onSkin(option) }, label = option.displayName)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                AppearanceGroup.entries.forEach { option ->
                    SettingsChoiceChip(selected = group == option, onClick = { group = option }, label = option.label)
                }
            }
        }
    }
    val lower: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
            when (group) {
                AppearanceGroup.ICONS -> IconsGroup()
                AppearanceGroup.BACKGROUND -> BackgroundGroup(skin, settings, onChange)
                AppearanceGroup.GLASS -> GlassGroup(skin, settings, onChange, onSkin)
                AppearanceGroup.COLORS -> ColorsGroup(settings, onChange)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedButton(onClick = { onChange(SkinSettings()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Reset all appearance settings")
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Tall enough, the top stays still and the group scrolls under it.
        // Short (a phone on its side, a large font), everything scrolls
        // together rather than squeezing the group to nothing.
        if (maxHeight >= 560.dp) {
            Column(Modifier.fillMaxSize()) {
                upper()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { lower() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                upper()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                lower()
            }
        }
    }
}

@Composable
private fun IconsGroup() {
    val context = LocalContext.current
    SettingsSectionHeader("Icon set")
    LiquidSegmentedControl(
        segments = listOf("Reicons", "AnimateIcons"),
        selectedIndex = if (AppIconStyle.animated) 1 else 0,
        onSelected = { index -> AppIconStyle.setAnimated(context, index == 1) },
        modifier = Modifier.fillMaxWidth(),
        useLiquidGlass = false,
    )
    SettingsToggle(
        "Icon animation", AppIconStyle.animate, { AppIconStyle.setAnimate(context, it) },
        hint = "Icons move once when tapped or chosen. Off keeps them still.",
        disabledReason = if (AppIconStyle.animated) null else "Only the AnimateIcons set moves",
    )
}

@Composable
private fun BackgroundGroup(skin: Skin, settings: SkinSettings, onChange: (SkinSettings) -> Unit) {
    SettingsSectionHeader("Panels and bars")
    if (skin == Skin.SPOTIGLASS) {
        AppearanceSlider(
            "Toolbar background opacity", "%.0f%%".format(settings.spotiglassToolbarOpacity * 100),
            settings.spotiglassToolbarOpacity, 0f..1f,
            note = "0% is clear, 100% solid. The icons and the lens on press stay as they are.",
        ) { onChange(settings.copy(spotiglassToolbarOpacity = it)) }
    }
    AppearanceSlider("Blur radius", "%.0f dp".format(settings.blur), settings.blur, SkinSettings.BLUR_RANGE,
        note = "How much the page behind a panel or popup is blurred.",
        disabledReason = if (skin == Skin.MATERIAL) "Material panels are solid" else null,
    ) { onChange(settings.copy(blur = it)) }
    AppearanceSlider("Blur vibrancy", "+%.0f%%".format(settings.vibrancy * 100), settings.vibrancy,
        SkinSettings.VIBRANCY_RANGE,
        disabledReason = if (skin == Skin.MATERIAL) "Material panels are solid" else null,
    ) { onChange(settings.copy(vibrancy = it)) }
    val corner = if (skin == Skin.SPOTIGLASS) settings.spotiglassCorner else settings.corner
    AppearanceSlider("Corner radius", "%.0f dp".format(corner), corner, SkinSettings.CORNER_RANGE) {
        onChange(if (skin == Skin.SPOTIGLASS) settings.copy(spotiglassCorner = it) else settings.copy(corner = it))
    }
}

@Composable
private fun GlassGroup(skin: Skin, settings: SkinSettings, onChange: (SkinSettings) -> Unit, onSkin: (Skin) -> Unit) {
    if (!skin.isRefractive) {
        SettingsNote("Glass optics belong to the Liquid glass and Glass bar styles; ${skin.displayName} has none.")
        Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            OutlinedButton(onClick = { onSkin(Skin.LIQUID_GLASS) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Use Liquid glass")
            }
            OutlinedButton(onClick = { onSkin(Skin.SPOTIGLASS) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Use Glass bar")
            }
        }
        return
    }
    if (skin == Skin.SPOTIGLASS) {
        SettingsSectionHeader("Glass bar")
        AppearanceSlider("Glass clarity", "%.0f%%".format(settings.spotiglassClarity * 100),
            settings.spotiglassClarity, 0f..1f, note = "0% is frosted solid, 100% clear glass.",
        ) { onChange(settings.copy(spotiglassClarity = it)) }
        AppearanceSlider("Interaction strength", "%.0f%%".format(settings.spotiglassResponse * 100),
            settings.spotiglassResponse, 0f..1f, note = "How far the lens stretches and lifts on press and slide.",
        ) { onChange(settings.copy(spotiglassResponse = it)) }
    }
    SettingsSectionHeader("Lens")
    Text("Refraction direction", style = MaterialTheme.typography.bodyLarge)
    LiquidSegmentedControl(
        segments = RefractionDirection.entries.map { it.displayName },
        selectedIndex = settings.refractionDirection.ordinal,
        onSelected = { index -> onChange(settings.copy(refractionDirection = RefractionDirection.entries[index])) },
        modifier = Modifier.fillMaxWidth(),
        useLiquidGlass = false,
    )
    SettingsNote("Raised is a lens standing above the page, pressed in a lens sunk into it.")
    AppearanceSlider("Lens depth", "%.0f dp".format(settings.depth), settings.depth, SkinSettings.DEPTH_RANGE,
        note = "How far the bending reaches in from the edge.") { onChange(settings.copy(depth = it)) }
    AppearanceSlider("Refraction", "%.0f dp".format(settings.refraction), settings.refraction,
        SkinSettings.REFRACTION_RANGE, note = "How far the page bends at the lens edge.",
    ) { onChange(settings.copy(refraction = it)) }
    AppearanceSlider("Dispersion", "%.0f%%".format(settings.dispersion * 100f), settings.dispersion,
        SkinSettings.DISPERSION_RANGE, note = "The faint colour fringe at the edge.",
    ) { onChange(settings.copy(dispersion = it)) }
}

@Composable
private fun ColorsGroup(settings: SkinSettings, onChange: (SkinSettings) -> Unit) {
    var editing by remember { mutableStateOf<ColorSlot?>(null) }
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

@Composable
private fun AppearanceSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    note: String? = null,
    disabledReason: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().disabledReason(disabledReason)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
        if (note != null) SettingsNote(note)
        if (disabledReason != null) {
            Text(disabledReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else {
            Spacer(Modifier.height(4.dp))
            SkinSlider(
                value = current.coerceIn(range),
                onValueChange = onChange,
                valueRange = range,
                modifier = Modifier.fillMaxWidth(),
                // Whole steps where the range is wide enough to be counted in them.
                step = if (range.endInclusive - range.start >= 20f) 1f else 0f,
            )
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
            AppearanceSettings(
                skin, settings, onSkin, onChange,
                modifier = Modifier.padding(padding).background(MaterialTheme.colorScheme.surface),
            )
        }
    }
}
