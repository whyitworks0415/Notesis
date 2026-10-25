package com.notesis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * One page of the tutorial: what it is about, a picture of it made from the
 * app's own icons and shapes, and a few lines on how it is done.
 */
private class Lesson(
    val title: String,
    val lines: List<String>,
    val picture: @Composable () -> Unit,
)

private val LESSONS: List<Lesson> = listOf(
    Lesson(
        "Pick a tool",
        listOf(
            "The bar holds the tools: pen, pencil, highlighter, eraser, lasso, shapes, text and more.",
            "Tap one to pick it. Each pen keeps its own colour and width, so switching back finds it as you left it.",
            "The laser points without writing. Tap it again for a fading trail, a line that goes a second after the pen lifts, or a dot, in any colour.",
            "With a keyboard, Ctrl+1 to Ctrl+9 pick them in the order the bar shows.",
        ),
    ) { ToolBarPicture(selected = 0) },
    Lesson(
        "Tap it again for its settings",
        listOf(
            "Tap the tool already in hand and its panel opens: colour, width, pen type, pressure and line style.",
            "\"More for this pen\" holds smoothing, prediction and the width range.",
            "Save keeps what you set; Cancel leaves the pen as it was. Tapping past the panel keeps it too.",
        ),
    ) { PenPanelPicture() },
    Lesson(
        "Colour and width on the bar",
        listOf(
            "On the full bar, the pen row has swatches and a width slider for the tool in hand.",
            "Tap a swatch to write in it; tap the one in use for every setting.",
            "+ keeps the colour you are using; hold a swatch to move it or take it off.",
        ),
    ) { SwatchPicture() },
    Lesson(
        "Hold to make a shape",
        listOf(
            "Draw a line, a triangle, a box or a circle and keep the pen still for half a second.",
            "A ring under the pen says it took, and the stroke becomes the clean shape.",
            "Keep moving before you lift to stretch it, turn it or move its last corner. Bent lines keep their bends.",
            "The Shape tool draws exact shapes by dragging; tap it again to choose one.",
        ),
    ) { HoldShapePicture() },
    Lesson(
        "Erase and select",
        listOf(
            "The eraser takes whole strokes, or only what it crosses with Partial on; pressing harder can make it bigger.",
            "The lasso picks up ink, pictures and text. Drag to move, use the corners to resize and turn.",
            "The bar over a selection copies, cuts, recolours, links and locks it.",
        ),
    ) { EraseSelectPicture() },
    Lesson(
        "Fingers",
        listOf(
            "One finger scrolls; two fingers pinch to zoom.",
            "Two fingers tapped twice undo; three fingers tapped twice redo.",
            "Turning three fingers turns the note, and it snaps when it is nearly straight.",
            "All of it can be changed in Settings › Touch & gestures.",
        ),
    ) { FingersPicture() },
    Lesson(
        "Pages",
        listOf(
            "Pull up past the last page to add a new one, or use Add page in Functions.",
            "The page panel lists the pages, the outline and masks. Its Search tab finds words in PDFs, text boxes and handwriting.",
            "Ctrl+F searches; Ctrl+Alt+G goes to a page.",
        ),
    ) { PagesPicture() },
    Lesson(
        "Functions",
        listOf(
            "The round button on the bar opens Functions.",
            "Pictures, camera, layers, recording, capture, text, the clock, calendar, to-do and calculator, other notes, add page and Settings.",
            "The bar's size is chosen at its top: 1/4 is the whole bar, 4/4 a single button you can drag aside.",
        ),
    ) { FunctionsPicture() },
    Lesson(
        "A second note beside this one",
        listOf(
            "Three fingers up open another note in a popup over this one; you can write in it.",
            "Drag its top bar to move it, and any corner to resize it.",
            "Three fingers down close it.",
        ),
    ) { PopupNotePicture() },
    Lesson(
        "Split notes",
        listOf(
            "Functions › Split notes divides the screen: side by side, top and bottom, three across, or with one side split again, up to four notes.",
            "This note stays in the first part; each other part opens a note of its own, written on with the same pen.",
            "Drag the handles between the parts to resize them. Each part's bar, along its bottom, changes its note, splits it again or closes it.",
            "The toolbar floats over every part at a narrower size, so it never takes room from one of them.",
        ),
    ) { SplitPicture() },
    Lesson(
        "AI and the web",
        listOf(
            "The AI tool, or Functions, opens the internet panel beside the note.",
            "The chips at its top switch between Gemini, Claude, ChatGPT and the rest; ⋮ holds the desktop site and more.",
            "Pop it out to float it over the page instead.",
        ),
    ) { WebPicture() },
    Lesson(
        "Settings",
        listOf(
            "Settings opens along the edge, over the note.",
            "Pen settings: pressure, smoothing, prediction and drawing options for every pen.",
            "Touch & gestures, Appearance (skin, colours, dark mode), Customize toolbar and Note & view are beside it.",
        ),
    ) { SettingsPicture() },
    Lesson(
        "Your notes",
        listOf(
            "Folders, tags and favourites keep the library in order; search reaches across all of them.",
            "Back up one note, or all of them, from the home screen; restore puts them back.",
            "Deleted notes wait in the trash until you empty it.",
        ),
    ) { LibraryPicture() },
)

/**
 * The tutorial: the app a page at a time, swiped or stepped through. On a
 * note, [onShowOnScreen] ends it by pointing at the bar's own parts; on the
 * home screen [onGuide] opens the guide note to try things in.
 */
@Composable
internal fun TutorialOverlay(
    onClose: () -> Unit,
    onShowOnScreen: (() -> Unit)? = null,
    onGuide: (() -> Unit)? = null,
) {
    BackHandler(onBack = onClose)
    val skin = LocalSkin.current
    val pager = rememberPagerState(pageCount = { LESSONS.size })
    val scope = rememberCoroutineScope()
    fun go(page: Int) {
        scope.launch {
            // Material steps; the glass skins slide.
            if (skin.animatesPopups) pager.animateScrollToPage(page) else pager.scrollToPage(page)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            Modifier
                .windowInsetsPadding(ChromeInsets)
                .padding(Ui.Space.l),
            contentAlignment = Alignment.Center,
        ) {
            val tall = maxHeight
            SkinSurface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = minOf(tall, 720.dp)),
                corner = Ui.Corner.sheet,
            ) {
                NestedCorners(Ui.Corner.sheet, Ui.Space.xl) {
                    Column(Modifier.padding(Ui.Space.xl), verticalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tutorial", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text(
                                "${pager.currentPage + 1} / ${LESSONS.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AppIconButton(onClick = onClose, icon = AppIcons.close, contentDescription = "Close tutorial")
                        }
                        HorizontalPager(pager, Modifier.weight(1f, fill = false)) { page ->
                            LessonPage(LESSONS[page])
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs, Alignment.CenterHorizontally),
                        ) {
                            repeat(LESSONS.size) { i ->
                                Box(
                                    Modifier
                                        .size(if (i == pager.currentPage) 8.dp else 6.dp)
                                        .background(
                                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                        val last = pager.currentPage == LESSONS.lastIndex
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (pager.currentPage > 0) TextButton(onClick = { go(pager.currentPage - 1) }) { Text("Back") }
                            Spacer(Modifier.weight(1f))
                            if (last) {
                                if (onGuide != null) TextButton(onClick = onGuide) { Text("Open the guide note") }
                                if (onShowOnScreen != null) TextButton(onClick = onShowOnScreen) { Text("Show me on this note") }
                                Button(onClick = onClose) { Text("Done") }
                            } else {
                                Button(onClick = { go(pager.currentPage + 1) }) { Text("Next") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LessonPage(lesson: Lesson) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.m),
    ) {
        // The picture, on a scrap of paper, the same in every skin.
        Box(
            Modifier
                .fillMaxWidth()
                .height(168.dp)
                .clip(RoundedCornerShape(nestedCorner(Ui.Corner.card)))
                .background(PAPER)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) { lesson.picture() }
        Text(lesson.title, style = MaterialTheme.typography.headlineSmall)
        for (line in lesson.lines) {
            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                Box(
                    Modifier
                        .padding(top = 7.dp)
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---- pictures ---------------------------------------------------------------

private val PAPER = Color(0xFFF7F4EC)
private val INK = Color(0xFF23252B)
private val BLUE = Color(0xFF2F6FD6)
private val RED = Color(0xFFD94B3D)
private val GREEN = Color(0xFF3C9A5F)
private val SOFT = Color(0xFF8A8D96)

/** A little bar of real tool icons, [selected] lit as the bar lights it. */
@Composable
private fun ToolBarPicture(selected: Int, ring: Boolean = false) {
    val tools = listOf(
        SpotiToolbarTool.PEN, SpotiToolbarTool.PENCIL, SpotiToolbarTool.HIGHLIGHTER,
        SpotiToolbarTool.ERASE, SpotiToolbarTool.LASSO, SpotiToolbarTool.SHAPE, SpotiToolbarTool.TEXT,
    )
    val colors = listOf(BLUE, INK, Color(0xFFE0B000), SOFT, SOFT, SOFT, SOFT)
    Row(
        Modifier
            .background(Color.White, CircleShape)
            .border(1.dp, Color(0x22000000), CircleShape)
            .padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tools.forEachIndexed { i, tool ->
            val chosen = i == selected
            Box(
                Modifier
                    .size(40.dp)
                    .background(if (chosen) BLUE.copy(alpha = 0.16f) else Color.Transparent, CircleShape)
                    .then(if (chosen && ring) Modifier.border(2.dp, BLUE, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tool.icon(), contentDescription = null, tint = if (chosen) colors[i] else INK.copy(alpha = 0.75f),
                    modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun PenPanelPicture() {
    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.l), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
            Box(
                Modifier.size(52.dp).background(BLUE.copy(alpha = 0.16f), CircleShape).border(2.dp, BLUE, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(SpotiToolbarTool.PEN.icon(), contentDescription = null, tint = BLUE) }
            Text("Tap again", style = MaterialTheme.typography.labelMedium, color = INK)
        }
        // The panel it opens: its three parts, as the real one heads them.
        Column(
            Modifier
                .width(170.dp)
                .background(Color.White, RoundedCornerShape(18.dp))
                .border(1.dp, Color(0x22000000), RoundedCornerShape(18.dp))
                .padding(Ui.Space.m),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Pen", style = MaterialTheme.typography.titleSmall, color = INK)
            PictureLabel("Color")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in listOf(INK, BLUE, RED, GREEN, Color(0xFFE0B000))) Box(Modifier.size(16.dp).background(c, CircleShape))
            }
            PictureLabel("Pen width")
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                drawLine(SOFT.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4.dp.toPx(), StrokeCap.Round)
                drawLine(BLUE, Offset(0f, size.height / 2), Offset(size.width * 0.45f, size.height / 2), 4.dp.toPx(), StrokeCap.Round)
                drawCircle(Color.White, 6.dp.toPx(), Offset(size.width * 0.45f, size.height / 2))
                drawCircle(BLUE, 6.dp.toPx(), Offset(size.width * 0.45f, size.height / 2), style = Stroke(2.dp.toPx()))
            }
            PictureLabel("Pen type")
        }
    }
}

@Composable
private fun PictureLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = SOFT)
}

@Composable
private fun SwatchPicture() {
    Row(
        Modifier
            .background(Color.White, CircleShape)
            .border(1.dp, Color(0x22000000), CircleShape)
            .padding(horizontal = Ui.Space.m, vertical = Ui.Space.s),
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(INK, BLUE, RED, GREEN).forEachIndexed { i, c ->
            Box(
                Modifier.size(26.dp).then(if (i == 1) Modifier.border(2.dp, c, CircleShape).padding(4.dp) else Modifier)
                    .background(c, CircleShape),
            )
        }
        Box(Modifier.size(26.dp).border(1.5.dp, SOFT, CircleShape), contentAlignment = Alignment.Center) {
            Text("+", color = SOFT, style = MaterialTheme.typography.titleMedium)
        }
        Canvas(Modifier.width(110.dp).height(20.dp)) {
            val y = size.height / 2
            drawLine(SOFT.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
            drawLine(BLUE, Offset(0f, y), Offset(size.width * 0.3f, y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(Color.White, 8.dp.toPx(), Offset(size.width * 0.3f, y))
            drawCircle(BLUE, 8.dp.toPx(), Offset(size.width * 0.3f, y), style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun HoldShapePicture() {
    Canvas(Modifier.fillMaxSize().padding(Ui.Space.l)) {
        val w = size.width
        val h = size.height
        val box = Size(minOf(w * 0.28f, 130.dp.toPx()), minOf(h * 0.6f, 84.dp.toPx()))
        val left = Offset(w * 0.25f, h / 2f)
        val right = Offset(w * 0.75f, h / 2f)
        // The rough one, as drawn, with the pen held at its end and the ring closing.
        val rough = Path()
        val steps = 64
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val p = boxPerimeter(left, box, (t * 1.04f) % 1f)
            val wobble = 3.dp.toPx() * sin(t * 6.28f * 3f)
            val q = p + Offset(cos(t * 6.28f) * wobble, sin(t * 10f) * wobble)
            if (i == 0) rough.moveTo(q.x, q.y) else rough.lineTo(q.x, q.y)
        }
        drawPath(rough, BLUE, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        val end = boxPerimeter(left, box, 0.04f)
        drawArc(BLUE, -90f, 290f, false, end - Offset(14.dp.toPx(), 14.dp.toPx()), Size(28.dp.toPx(), 28.dp.toPx()),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(INK, 3.5.dp.toPx(), end)
        // The arrow, then what it becomes.
        arrow(Offset(w * 0.45f, h / 2f), Offset(w * 0.55f, h / 2f), SOFT)
        drawRect(BLUE, right - Offset(box.width / 2f, box.height / 2f), box, style = Stroke(3.dp.toPx()))
    }
}

private fun boxPerimeter(centre: Offset, box: Size, t: Float): Offset {
    val left = centre.x - box.width / 2f
    val top = centre.y - box.height / 2f
    var d = t * 2f * (box.width + box.height)
    if (d < box.width) return Offset(left + d, top)
    d -= box.width
    if (d < box.height) return Offset(left + box.width, top + d)
    d -= box.height
    if (d < box.width) return Offset(left + box.width - d, top + box.height)
    d -= box.width
    return Offset(left, top + box.height - d)
}

private fun DrawScope.arrow(from: Offset, to: Offset, color: Color) {
    val width = 2.5.dp.toPx()
    drawLine(color, from, to, width, StrokeCap.Round)
    val back = 8.dp.toPx()
    val dir = (to - from).let { it / it.getDistance().coerceAtLeast(1f) }
    val side = Offset(-dir.y, dir.x)
    drawLine(color, to, to - dir * back + side * back * 0.6f, width, StrokeCap.Round)
    drawLine(color, to, to - dir * back - side * back * 0.6f, width, StrokeCap.Round)
}

@Composable
private fun EraseSelectPicture() {
    Canvas(Modifier.fillMaxSize().padding(Ui.Space.l)) {
        val w = size.width
        val h = size.height
        // A stroke with a gap where the eraser passed, and the eraser.
        val y = h * 0.5f
        drawLine(INK, Offset(w * 0.05f, y), Offset(w * 0.2f, y - 10.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
        drawLine(INK, Offset(w * 0.28f, y - 14.dp.toPx()), Offset(w * 0.42f, y - 4.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(SOFT, 12.dp.toPx(), Offset(w * 0.24f, y - 12.dp.toPx()), style = Stroke(2.dp.toPx()))
        // Strokes inside a dashed lasso, with its corner handles.
        val cx = w * 0.74f
        drawLine(BLUE, Offset(cx - 30.dp.toPx(), y + 6.dp.toPx()), Offset(cx + 26.dp.toPx(), y - 14.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
        drawLine(RED, Offset(cx - 22.dp.toPx(), y - 18.dp.toPx()), Offset(cx + 20.dp.toPx(), y + 14.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
        val half = Size(52.dp.toPx(), 38.dp.toPx())
        val topLeft = Offset(cx - half.width, y - half.height)
        val frame = Size(half.width * 2, half.height * 2)
        drawRoundRect(BLUE, topLeft, frame, CornerRadius(10.dp.toPx()),
            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        for (corner in listOf(topLeft, topLeft + Offset(frame.width, 0f), topLeft + Offset(0f, frame.height), topLeft + Offset(frame.width, frame.height))) {
            drawCircle(Color.White, 6.dp.toPx(), corner)
            drawCircle(BLUE, 6.dp.toPx(), corner, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun FingersPicture() {
    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xl), verticalAlignment = Alignment.CenterVertically) {
        GesturePicture("Pinch: zoom", fingers = 2, spread = true)
        GesturePicture("Two, twice: undo", fingers = 2, spread = false)
        GesturePicture("Three, twice: redo", fingers = 3, spread = false)
    }
}

@Composable
private fun GesturePicture(label: String, fingers: Int, spread: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        Canvas(Modifier.size(76.dp)) {
            val c = center
            val r = 9.dp.toPx()
            val gap = 22.dp.toPx()
            val start = c.x - gap * (fingers - 1) / 2f
            for (i in 0 until fingers) {
                val at = Offset(start + gap * i, c.y)
                drawCircle(BLUE.copy(alpha = 0.22f), r * 1.6f, at)
                drawCircle(BLUE, r, at)
            }
            if (spread) {
                arrow(Offset(c.x - gap / 2f - r * 1.8f, c.y), Offset(c.x - gap / 2f - r * 3.4f, c.y), SOFT)
                arrow(Offset(c.x + gap / 2f + r * 1.8f, c.y), Offset(c.x + gap / 2f + r * 3.4f, c.y), SOFT)
            } else {
                // Two taps: two rings round the hand.
                for (k in 1..2) drawCircle(SOFT, gap * (fingers / 2f) + r * (1.4f + k * 0.8f), c, style = Stroke(1.5.dp.toPx()))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = INK, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PagesPicture() {
    Canvas(Modifier.fillMaxSize().padding(Ui.Space.l)) {
        val pw = 70.dp.toPx()
        val ph = 92.dp.toPx()
        val x = size.width * 0.32f - pw / 2f
        for (i in 2 downTo 0) {
            val o = Offset(x + i * 10.dp.toPx(), size.height / 2f - ph / 2f - i * 8.dp.toPx())
            drawRoundRect(Color.White, o, Size(pw, ph), CornerRadius(6.dp.toPx()))
            drawRoundRect(SOFT.copy(alpha = 0.6f), o, Size(pw, ph), CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
            if (i == 0) for (l in 1..4) {
                val ly = o.y + l * ph / 6f
                drawLine(SOFT.copy(alpha = 0.5f), Offset(o.x + 8.dp.toPx(), ly), Offset(o.x + pw - 8.dp.toPx(), ly), 1.5.dp.toPx())
            }
        }
        // Pull past the end: a new page.
        val nx = size.width * 0.7f
        arrow(Offset(nx, size.height * 0.2f), Offset(nx, size.height * 0.62f), SOFT)
        drawCircle(BLUE, 16.dp.toPx(), Offset(nx, size.height * 0.8f))
        drawLine(Color.White, Offset(nx - 7.dp.toPx(), size.height * 0.8f), Offset(nx + 7.dp.toPx(), size.height * 0.8f), 2.5.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White, Offset(nx, size.height * 0.8f - 7.dp.toPx()), Offset(nx, size.height * 0.8f + 7.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun IconGrid(icons: List<ImageVector>, columns: Int, cell: Dp = 40.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        icons.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                row.forEach { icon ->
                    Box(
                        Modifier.size(cell).background(Color.White, RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0x14000000), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, contentDescription = null, tint = INK, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FunctionsPicture() {
    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.l), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).background(BLUE, CircleShape), contentAlignment = Alignment.Center) {
            Icon(AppIcons.functions, contentDescription = null, tint = Color.White)
        }
        IconGrid(
            listOf(
                AppIcons.picture, AppIcons.camera, AppIcons.layers, AppIcons.mic, AppIcons.capture, AppIcons.text,
                AppIcons.clock, AppIcons.calendar, AppIcons.todo, AppIcons.tools, AppIcons.otherNotes, AppIcons.settings,
            ),
            columns = 6,
            cell = 34.dp,
        )
    }
}

@Composable
private fun PopupNotePicture() {
    Canvas(Modifier.fillMaxSize().padding(Ui.Space.m)) {
        val page = Size(size.width * 0.8f, size.height)
        val pageAt = Offset((size.width - page.width) / 2f, 0f)
        drawRoundRect(Color.White, pageAt, page, CornerRadius(8.dp.toPx()))
        for (l in 1..6) {
            val ly = pageAt.y + l * page.height / 7f
            drawLine(SOFT.copy(alpha = 0.35f), Offset(pageAt.x + 12.dp.toPx(), ly), Offset(pageAt.x + page.width * 0.5f, ly), 1.5.dp.toPx())
        }
        // The popup, with its top bar and a handle at each corner.
        val panel = Size(page.width * 0.42f, page.height * 0.78f)
        val at = Offset(pageAt.x + page.width - panel.width - 10.dp.toPx(), pageAt.y + 10.dp.toPx())
        val corner = 12.dp.toPx()
        drawRoundRect(Color(0xFFEFF3FB), at, panel, CornerRadius(corner))
        drawRoundRect(BLUE.copy(alpha = 0.5f), at, panel, CornerRadius(corner), style = Stroke(1.5.dp.toPx()))
        drawLine(SOFT, Offset(at.x + panel.width / 2f - 12.dp.toPx(), at.y + 7.dp.toPx()),
            Offset(at.x + panel.width / 2f + 12.dp.toPx(), at.y + 7.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
        drawLine(SOFT.copy(alpha = 0.4f), Offset(at.x, at.y + 22.dp.toPx()), Offset(at.x + panel.width, at.y + 22.dp.toPx()), 1.dp.toPx())
        val r = corner - 4.dp.toPx()
        for ((cx, cy, start) in listOf(
            Triple(at.x + corner, at.y + corner, 180f), Triple(at.x + panel.width - corner, at.y + corner, 270f),
            Triple(at.x + panel.width - corner, at.y + panel.height - corner, 0f), Triple(at.x + corner, at.y + panel.height - corner, 90f),
        )) {
            drawArc(BLUE, start, 90f, false, Offset(cx - r, cy - r), Size(r * 2, r * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        // Three fingers going up.
        val hand = Offset(pageAt.x + page.width * 0.25f, page.height * 0.72f)
        for (i in -1..1) drawCircle(BLUE, 7.dp.toPx(), hand + Offset(i * 18.dp.toPx(), 0f))
        arrow(hand - Offset(0f, 14.dp.toPx()), hand - Offset(0f, 46.dp.toPx()), SOFT)
    }
}

@Composable
private fun SplitPicture() {
    Canvas(Modifier.fillMaxSize().padding(Ui.Space.m)) {
        val gap = 6.dp.toPx()
        val radius = CornerRadius(8.dp.toPx())
        SplitPreset.RIGHT_SPLIT.tree().layout(SplitRect(0f, 0f, size.width, size.height), gap).forEach { (id, r) ->
            drawRoundRect(if (id == 0) Color.White else Color(0xFFEFF3FB), Offset(r.left, r.top), Size(r.width, r.height), radius)
            for (l in 1..3) {
                val y = r.top + l * r.height / 4f
                drawLine(SOFT.copy(alpha = 0.35f), Offset(r.left + 10.dp.toPx(), y), Offset(r.left + r.width * 0.6f, y), 1.5.dp.toPx())
            }
        }
        // The bar over all of them, and a handle on the seam.
        val bar = Size(size.width * 0.42f, 18.dp.toPx())
        drawRoundRect(BLUE.copy(alpha = 0.85f), Offset((size.width - bar.width) / 2f, 4.dp.toPx()), bar, CornerRadius(bar.height / 2f))
        val seamX = (size.width - gap) / 2f + gap / 2f
        drawLine(SOFT, Offset(seamX, size.height / 2f - 20.dp.toPx()), Offset(seamX, size.height / 2f + 20.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun WebPicture() {
    Row(
        Modifier.fillMaxSize().padding(Ui.Space.m),
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
    ) {
        Box(Modifier.weight(1f).fillMaxSize().background(Color.White, RoundedCornerShape(8.dp)))
        Column(
            Modifier.weight(1f).fillMaxSize().background(Color.White, RoundedCornerShape(8.dp)).padding(Ui.Space.s),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((name, on) in listOf("Gemini" to false, "Claude" to true, "ChatGPT" to false)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) Color.White else INK,
                        modifier = Modifier
                            .background(if (on) BLUE else Color(0xFFECEEF2), CircleShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1,
                    )
                }
            }
            HorizontalDivider(color = Color(0x22000000))
            for (k in 0 until 3) {
                Box(
                    Modifier
                        .fillMaxWidth(if (k % 2 == 0) 0.8f else 0.6f)
                        .height(12.dp)
                        .background(Color(0xFFECEEF2), CircleShape)
                        .then(if (k % 2 == 1) Modifier.align(Alignment.End) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun SettingsPicture() {
    Column(
        Modifier
            .width(240.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(Ui.Space.s),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((icon, label) in listOf(
            AppIcons.penSettings to "Pen settings",
            AppIcons.touch to "Touch & gestures",
            AppIcons.appearance to "Appearance",
            AppIcons.editTools to "Customize toolbar",
            AppIcons.noteMenu to "Note & view",
        )) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Ui.Space.s, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ui.Space.s),
            ) {
                Icon(icon, contentDescription = null, tint = INK, modifier = Modifier.size(18.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = INK)
            }
        }
    }
}

@Composable
private fun LibraryPicture() {
    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.l), verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                for (c in listOf(Color(0xFFFFF4C2), Color(0xFFDDEBFF))) {
                    Box(Modifier.size(54.dp, 70.dp).background(c, RoundedCornerShape(10.dp)).border(1.dp, Color(0x14000000), RoundedCornerShape(10.dp)))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (tag in listOf("#maths", "#exam")) Text(
                    tag, style = MaterialTheme.typography.labelSmall, color = INK,
                    modifier = Modifier.background(Color(0xFFECEEF2), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        IconGrid(
            listOf(
                AppIconStyle.pick(Lucide.Folder, Reicons.Folder), AppIconStyle.pick(Lucide.Tag, Reicons.Category),
                AppIcons.search, AppIconStyle.pick(Lucide.Download, Reicons.Archive),
                AppIconStyle.pick(Lucide.Upload, Reicons.Restore), AppIconStyle.pick(Lucide.Trash, Reicons.Delete),
            ),
            columns = 3,
        )
    }
}
