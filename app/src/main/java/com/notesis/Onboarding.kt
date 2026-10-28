package com.notesis

import android.content.Context
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a first run has already shown, kept beside the guide note's own mark.
 * Read once per screen, written as each part is finished.
 */
internal object Onboarding {
    private const val PREFS = "onboarding"
    private const val WELCOME = "welcomeShown"
    private const val SKIN = "skinChosen"
    private const val HOME_TIPS = "homeTipsShown"
    private const val NOTE_TIPS = "noteTipsSeen"
    private const val TIPS_OFF = "noteTipsOff"
    private const val NOTE_TOUR = "noteTourShown"
    private const val FIRST_RUN_MODE = "firstRunMode"
    private const val RESEED_GUIDE = "reseedGuide"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun step(context: Context): OnboardingStep {
        val p = prefs(context)
        return onboardingStep(
            welcomeDone = p.getBoolean(WELCOME, false),
            skinChosen = p.getBoolean(SKIN, false),
            homeTipsDone = p.getBoolean(HOME_TIPS, false),
            animations = animationsOn(context),
        )
    }

    fun finishWelcome(context: Context) = prefs(context).edit().putBoolean(WELCOME, true).apply()
    fun finishSkin(context: Context) = prefs(context).edit().putBoolean(SKIN, true).apply()
    fun finishHomeTips(context: Context) = prefs(context).edit().putBoolean(HOME_TIPS, true).apply()
    fun noteTourDone(context: Context): Boolean = prefs(context).getBoolean(NOTE_TOUR, false)
    fun finishNoteTour(context: Context) = prefs(context).edit().putBoolean(NOTE_TOUR, true).apply()

    /**
     * Whether the tutorial is open. Asked for from the home bar, the note's
     * Functions and About & help; whichever screen is showing puts it up.
     */
    var tutorialOpen by mutableStateOf(false)

    fun seenTips(context: Context): Set<NoteTip> {
        val names = prefs(context).getStringSet(NOTE_TIPS, emptySet()).orEmpty()
        return NoteTip.entries.filterTo(HashSet()) { it.name in names }
    }

    fun markSeen(context: Context, tip: NoteTip) {
        val names = prefs(context).getStringSet(NOTE_TIPS, emptySet()).orEmpty() + tip.name
        prefs(context).edit().putStringSet(NOTE_TIPS, names).apply()
    }

    fun tipsOff(context: Context): Boolean = prefs(context).getBoolean(TIPS_OFF, false)
    fun turnTipsOff(context: Context) = prefs(context).edit().putBoolean(TIPS_OFF, true).apply()

    /**
     * First-run mode: while it is on, every start of the app is a fresh
     * install's first start as far as the welcome and the tutorial go - and
     * no further. Notes, settings and the look are left exactly as they are.
     */
    fun firstRunMode(context: Context): Boolean = prefs(context).getBoolean(FIRST_RUN_MODE, false)
    fun setFirstRunMode(context: Context, on: Boolean) = prefs(context).edit().putBoolean(FIRST_RUN_MODE, on).apply()

    /**
     * Called as the app starts (not on a rotation): in first-run mode, forgets
     * that the welcome, the choice of look, the marks, the note's tour and its
     * tips were ever shown.
     */
    fun startAsFirstRunIfAsked(context: Context) {
        if (!firstRunMode(context)) return
        prefs(context).edit()
            .remove(WELCOME).remove(SKIN).remove(HOME_TIPS).remove(NOTE_TIPS).remove(TIPS_OFF).remove(NOTE_TOUR)
            .putBoolean(RESEED_GUIDE, true)
            .apply()
        strokes = 0
    }

    /** Once after a first-run start: whether the guide note should be put back if it is not on the shelf. */
    fun takeGuideReseed(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(RESEED_GUIDE, false)) return false
        p.edit().remove(RESEED_GUIDE).apply()
        return true
    }

    /** About & help's "Show the tips again": the home marks and every note tip, from the start. */
    fun resetTips(context: Context) {
        prefs(context).edit().remove(HOME_TIPS).remove(NOTE_TIPS).remove(TIPS_OFF).remove(NOTE_TOUR).apply()
        strokes = 0
    }

    /**
     * Strokes written in notes since the app started, for when the note tips
     * are due. Counted in memory: a tip that is due is not urgent, and a
     * restart only means a little more writing before the next one.
     */
    var strokes by mutableIntStateOf(0)

    /**
     * Whether the system plays animations at all. Only the animator scale:
     * the welcome is a few seconds of drawing, not chrome, so battery saver
     * and the skin's own stillness do not take it away.
     */
    fun animationsOn(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)
}

/** The parts of the home screen and the note's bar the coach marks point at. */
internal enum class CoachTarget { NEW_NOTE, GUIDE, SEARCH, TUTORIAL, LOOK, HELP, TOOLS, PEN_ROW, TOP_ROW, FUNCTIONS }

/** Where each [CoachTarget] is on screen, in root coordinates, as last laid out. */
internal class CoachTargets {
    val bounds = mutableStateMapOf<CoachTarget, Rect>()
}

/** Reports where this element is, for the coach mark that points at it. */
internal fun Modifier.coachTarget(targets: CoachTargets, target: CoachTarget): Modifier =
    onGloballyPositioned { coordinates ->
        val rect = coordinates.boundsInRoot()
        // Written only on a change, so a list scrolling under it is not a stream of state writes.
        if (targets.bounds[target] != rect) targets.bounds[target] = rect
    }

/**
 * [coachTarget] for an element that comes and goes - a part of the bar
 * that only some sizes have - so a mark never points at where it used to be.
 */
@Composable
internal fun Modifier.coachTargetWhileShown(targets: CoachTargets?, target: CoachTarget): Modifier {
    if (targets == null) return this
    DisposableEffect(targets, target) { onDispose { targets.bounds.remove(target) } }
    return coachTarget(targets, target)
}

/** Where the note's bar is, for the note's coach marks. One note screen at a time, so one set. */
internal val NoteCoachTargets = CoachTargets()

internal class CoachMark(val target: CoachTarget, val title: String, val text: String)

internal val HOME_MARKS = listOf(
    CoachMark(CoachTarget.NEW_NOTE, "New notes", "A blank page, a PDF, pictures, a web page or Markdown all start here."),
    CoachMark(CoachTarget.GUIDE, "The guide", "Welcome to Notesis is a short tour, one page per part of the app. Write on it."),
    CoachMark(CoachTarget.SEARCH, "Search", "Finds titles, words in PDFs and your own handwriting."),
    CoachMark(CoachTarget.TUTORIAL, "Tutorial", "The pen and its settings, gestures, pages, shapes, the AI panel and every setting, a page each. Open it any time."),
    CoachMark(CoachTarget.LOOK, "The look", "Skin, colours, corners and dark mode, any time."),
    CoachMark(CoachTarget.HELP, "About & help", "The guide note and these tips, whenever you want them again."),
)

/** The note's bar, part by part, the first time a note is opened. */
internal val NOTE_MARKS = listOf(
    CoachMark(CoachTarget.TOOLS, "Your tools",
        "Pen, pencil, highlighter, eraser, lasso, shapes and more. Tap one to pick it; tap the one in hand again for its settings."),
    CoachMark(CoachTarget.PEN_ROW, "Colour and width",
        "For the tool in hand: its width, the colour palette, and the page you are on. More is in Settings › Customize toolbar."),
    CoachMark(CoachTarget.TOP_ROW, "The note's buttons",
        "Back to the notes, undo and redo, recordings, and search. Choose which ones show in Settings › Customize toolbar."),
    CoachMark(CoachTarget.FUNCTIONS, "Functions",
        "Pictures, Tools (calculator, clock, calendar, to-do), layers, text, capture, Pages & notes, split notes, the tutorial and Settings. The bar's size, 1/4 to 4/4, is chosen here too, or by dragging this button left and right."),
)

/**
 * The welcome, on white paper: the name written out by a pen; below it the
 * icon's rounded square drawing itself as a rough line while the same pen
 * writes a rough ">" inside it, both coming clean together (the app's own
 * trick); the pen dropped to strike, rock and lie where "_" goes, black on
 * white; and then the page folding into the guide note's card on the shelf
 * below. A tap anywhere skips to that last part.
 */
@Composable
internal fun WelcomeOverlay(
    /** The guide note's card in root coordinates, or null if it is not on screen. */
    target: () -> Rect?,
    onDone: () -> Unit,
) {
    val clock = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestDone by rememberUpdatedState(onDone)
    var finished by remember { mutableStateOf(false) }
    fun finish() {
        if (finished) return
        finished = true
        latestDone()
    }
    LaunchedEffect(Unit) {
        // A skip takes the clock over, which cancels this; it finishes from there.
        clock.animateTo(WelcomeTimeline.TOTAL.toFloat(), tween(WelcomeTimeline.TOTAL.toInt(), easing = LinearEasing))
        finish()
    }
    fun skip() {
        scope.launch {
            val exit = WelcomeTimeline.EXIT.first.toFloat()
            val total = WelcomeTimeline.TOTAL.toFloat()
            if (clock.value < exit) clock.snapTo(exit)
            clock.animateTo(total, tween((total - clock.value).roundToInt().coerceAtLeast(1), easing = LinearEasing))
            finish()
        }
    }

    val density = LocalDensity.current
    // White paper in either theme: the welcome ends on the icon's own black
    // on white, and black ink would be lost on a dark page.
    val paper = Color.White
    val ink = Color(0xFF23252B)
    val muted = Color(0xFF6B6F78)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val logo = remember { WelcomeLogo() }
    val mark = remember { WelcomeMarkPainter() }
    val cardCorner = with(density) { Ui.Corner.card.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) { detectTapGestures { skip() } }
            .semantics { contentDescription = "Welcome to Notesis. Tap to skip." }
            .graphicsLayer {
                val t = clock.value
                val e = easeInOutQuad(WelcomeTimeline.progress(WelcomeTimeline.EXIT, t))
                if (e <= 0f) return@graphicsLayer
                val w = size.width
                val h = size.height
                // The guide's card, or a card-sized spot in the middle if it is not there.
                val card = target()?.translate(-origin.x, -origin.y)
                    ?: Rect(Offset(w / 2f, h / 2f), 0f).inflate(with(density) { 90.dp.toPx() })
                val sx = lerp(1f, card.width / w, e)
                val sy = lerp(1f, card.height / h, e)
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = sx
                scaleY = sy
                translationX = lerp(0f, card.left, e)
                translationY = lerp(0f, card.top, e)
                // The card's corner once the page is the card's size.
                shape = RoundedCornerShape(lerp(0f, cardCorner / max(min(sx, sy), 0.01f), e))
                clip = true
                alpha = 1f - ((e - 0.55f) / 0.45f).coerceIn(0f, 1f)
            }
            .background(paper),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            val contentAlpha = 1f - (WelcomeTimeline.progress(WelcomeTimeline.EXIT, t) * 2f).coerceIn(0f, 1f)
            if (contentAlpha <= 0f) return@Canvas
            val w = size.width
            val h = size.height
            val unit = min(w, h)
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                // The name, centred a little above the middle.
                val logoWidth = min(w * 0.62f, 520.dp.toPx())
                logo.layout(logoWidth, w / 2f, h * 0.40f)
                val write = WelcomeTimeline.progress(WelcomeTimeline.WRITE, t)
                val fill = WelcomeTimeline.progress(WelcomeTimeline.FILL, t)
                logo.draw(native, write, fill, ink.copy(alpha = contentAlpha).toArgb(), 2.2.dp.toPx(), 3.dp.toPx())
                val tagline = easeOut(WelcomeTimeline.progress(WelcomeTimeline.TAGLINE, t))
                if (tagline > 0f) {
                    logo.text.color = muted.copy(alpha = tagline * contentAlpha).toArgb()
                    logo.text.textSize = 18.sp.toPx()
                    native.drawText("A notebook for the pen", w / 2f, logo.bottom + 34.dp.toPx() + (1f - tagline) * 8.dp.toPx(), logo.text)
                }
                // The mark, below: the icon drawn by a pen, which then lands in it.
                val side = min(176.dp.toPx(), min(w * 0.42f, h * 0.27f))
                val scale = side / WelcomeMark.SIZE
                native.save()
                native.translate(w / 2f - side / 2f, h * 0.68f - side / 2f)
                native.scale(scale, scale)
                mark.draw(native, t, ink.toArgb(), contentAlpha, 2.5.dp.toPx() / scale, 3.5.dp.toPx() / scale)
                native.restore()
            }
        }
        TextButton(
            onClick = ::skip,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(ChromeInsets)
                .padding(Ui.Space.l)
                .graphicsLayer {
                    alpha = 1f - WelcomeTimeline.progress(WelcomeTimeline.EXIT, clock.value)
                },
        ) { Text("Skip") }
    }
}

/**
 * The welcome's name: "Notesis" in a handwriting face, as outlines a pen can
 * trace one after another, left to right as the font gives them.
 */
private class WelcomeLogo {
    val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val face: Typeface = Typeface.create("cursive", Typeface.BOLD)
    private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        typeface = face
    }
    private val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val outline = android.graphics.Path()
    private val contours = ArrayList<android.graphics.PathMeasure>()
    private val segment = android.graphics.Path()
    private val position = FloatArray(2)
    private var total = 0f
    private var laidOut = Triple(0f, 0f, 0f)
    var bottom = 0f
        private set

    /** Lays the name out [width] wide, centred on [cx], with its baseline at [baseline]. */
    fun layout(width: Float, cx: Float, baseline: Float) {
        val key = Triple(width, cx, baseline)
        if (key == laidOut) return
        laidOut = key
        fillPaint.textSize = 100f
        val measured = fillPaint.measureText(NAME).coerceAtLeast(1f)
        fillPaint.textSize = 100f * width / measured
        fillPaint.textAlign = android.graphics.Paint.Align.LEFT
        outline.reset()
        fillPaint.getTextPath(NAME, 0, NAME.length, cx - width / 2f, baseline, outline)
        val bounds = android.graphics.RectF()
        outline.computeBounds(bounds, true)
        bottom = bounds.bottom
        contours.clear()
        total = 0f
        val measure = android.graphics.PathMeasure(outline, false)
        do {
            val length = measure.length
            if (length > 0f) {
                // Each contour on its own measure, so drawing a part of one needs no walk from the start.
                val copy = android.graphics.Path()
                measure.getSegment(0f, length, copy, true)
                contours += android.graphics.PathMeasure(copy, false)
                total += length
            }
        } while (measure.nextContour())
    }

    /** The outline traced to [write] of its length, then filled in by [fill]. */
    fun draw(canvas: android.graphics.Canvas, write: Float, fill: Float, color: Int, stroke: Float, nib: Float) {
        if (fill > 0f) {
            fillPaint.color = color
            fillPaint.alpha = (android.graphics.Color.alpha(color) * fill).roundToInt()
            canvas.drawPath(outline, fillPaint)
        }
        if (write <= 0f || total <= 0f) return
        strokePaint.color = color
        strokePaint.strokeWidth = stroke
        var left = write * total
        var head: FloatArray? = null
        for (contour in contours) {
            if (left <= 0f) break
            val length = contour.length
            val upTo = min(left, length)
            segment.reset()
            contour.getSegment(0f, upTo, segment, true)
            canvas.drawPath(segment, strokePaint)
            if (upTo < length) {
                contour.getPosTan(upTo, position, null)
                head = position
            }
            left -= length
        }
        // The pen at the head of the line, while there is still writing to do.
        if (head != null && write < 1f) {
            dotPaint.color = color
            canvas.drawCircle(head[0], head[1], nib, dotPaint)
        }
    }

    private companion object {
        const val NAME = "Notesis"
    }
}

/**
 * Draws [WelcomeMark] at a moment of the welcome, on a canvas already moved
 * and scaled to the mark's 1024 grid.
 */
private class WelcomeMarkPainter {
    private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val solid = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL_AND_STROKE
        strokeWidth = 10f
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val path = android.graphics.Path()
    // The pen with its tip at the origin and its body along +x, as on the icon.
    private val cone = android.graphics.Path().apply {
        moveTo(0f, 0f); lineTo(104f, -42f); lineTo(104f, 42f); close()
    }
    private val body = android.graphics.Path().apply {
        moveTo(118f, -42f); lineTo(320f, -42f)
        arcTo(android.graphics.RectF(278f, -42f, 362f, 42f), -90f, 180f, false)
        lineTo(118f, 42f); close()
    }

    fun draw(canvas: android.graphics.Canvas, t: Float, ink: Int, alpha: Float, hairline: Float, nib: Float) {
        val clean = WelcomeMark.clean(t)
        // The icon's square, a line drawing itself round roughly, then coming
        // clean - and staying a line, flat on the page.
        val frame = WelcomeMark.frameDrawn(t)
        if (frame > 0f) {
            path.reset()
            val steps = (160 * frame).roundToInt().coerceAtLeast(1)
            var head = 0f to 0f
            for (i in 0..steps) {
                head = WelcomeMark.roughFramePoint(frame * i / steps, clean)
                if (i == 0) path.moveTo(head.first, head.second) else path.lineTo(head.first, head.second)
            }
            line.color = ink
            line.alpha = (255 * alpha).roundToInt()
            line.strokeWidth = hairline
            canvas.drawPath(path, line)
            // Its point while it is still going round.
            if (frame < 1f) {
                dot.color = ink
                dot.alpha = (255 * alpha).roundToInt()
                canvas.drawCircle(head.first, head.second, nib, dot)
            }
        }
        // ">", black, as far as it is written: rough, then clean with the square.
        val chevron = WelcomeMark.chevronDrawn(t)
        if (chevron > 0f) {
            path.reset()
            val steps = (48 * chevron).roundToInt().coerceAtLeast(1)
            for (i in 0..steps) {
                val (x, y) = WelcomeMark.roughChevronPoint(chevron * i / steps, clean)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            line.color = MARK_BLACK
            line.alpha = (255 * alpha).roundToInt()
            line.strokeWidth = WelcomeMark.CHEVRON_WIDTH
            canvas.drawPath(path, line)
        }
        val pen = WelcomeMark.pen(t) ?: return
        val color = MARK_BLACK
        if (pen.lift > 0f) {
            // Its shadow on the paper, further off and fainter the higher it is.
            drawPen(canvas, pen.x + 16f * pen.lift, pen.y + 40f * pen.lift, pen.angle, 1f,
                android.graphics.Color.argb((40 * pen.alpha * alpha * (1f - 0.4f * pen.lift)).roundToInt(), 0, 0, 0))
        }
        // A rim of paper round it, so over the black ">" it reads as a pen lying on the ink
        // rather than one black shape with it - as the icon parts them with a gap.
        solid.strokeWidth = 10f + 2f * PEN_RIM
        drawPen(canvas, pen.x, pen.y, pen.angle, pen.scale,
            android.graphics.Color.argb((255 * pen.alpha * alpha).roundToInt(), 255, 255, 255))
        solid.strokeWidth = 10f
        drawPen(canvas, pen.x, pen.y, pen.angle, pen.scale,
            android.graphics.Color.argb((255 * pen.alpha * alpha).roundToInt(),
                android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color)))
    }

    private fun drawPen(canvas: android.graphics.Canvas, x: Float, y: Float, angle: Float, scale: Float, color: Int) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angle)
        canvas.scale(scale, scale)
        solid.color = color
        canvas.drawPath(cone, solid)
        canvas.drawPath(body, solid)
        canvas.restore()
    }

    private companion object {
        /** The icon's own black. */
        const val MARK_BLACK = 0xFF0E1116.toInt()
        /** The paper's rim round the pen, on the mark's grid. */
        const val PEN_RIM = 12f
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * The first choice a new install is asked: which look. Picking one changes
 * the screen behind at once, so the choice is made by seeing it.
 */
@Composable
internal fun SkinChooser(current: Skin, onSkin: (Skin) -> Unit, onDone: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            // Nothing behind is reachable until a look is chosen.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        SkinSurface(
            modifier = Modifier
                .windowInsetsPadding(ChromeInsets)
                .padding(Ui.Space.l)
                .widthIn(max = 440.dp),
            corner = Ui.Corner.sheet,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(Ui.Space.xl),
                verticalArrangement = Arrangement.spacedBy(Ui.Space.m),
            ) {
                Text("Pick a look", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Every bar and panel wears it. Change it any time in the display settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SkinChoiceList(current, onSkin)
                Button(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Continue") }
            }
        }
    }
}

/**
 * The home screen's coach marks: the screen dimmed but for one thing, a line
 * on what it is, and Next. Marks whose target is not on screen are left out.
 */
@Composable
internal fun CoachMarks(targets: CoachTargets, all: List<CoachMark> = HOME_MARKS, onDone: () -> Unit) {
    val skin = LocalSkin.current
    val marks = remember(all) { all.filter { it.target in targets.bounds } }
    var index by remember { mutableIntStateOf(0) }
    if (marks.isEmpty()) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val mark = marks[index.coerceIn(0, marks.lastIndex)]
    fun next() { if (index >= marks.lastIndex) onDone() else index++ }
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val pad = with(density) { 8.dp.toPx() }
    val wanted = (targets.bounds[mark.target] ?: Rect.Zero).translate(-origin.x, -origin.y).inflate(pad)
    // Material jumps from mark to mark; the glass skins slide the light across.
    val hole by animateRectAsState(
        wanted,
        if (skin.animatesPopups) tween<Rect>(420, easing = androidx.compose.animation.core.FastOutSlowInEasing) else snap<Rect>(),
        label = "Coach mark",
    )
    val scrim = Color.Black.copy(alpha = 0.55f)
    val ring = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) { detectTapGestures { next() } },
    ) {
        val screen = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        Canvas(Modifier.fillMaxSize()) {
            val corner = CornerRadius(Ui.Corner.control.toPx())
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(hole, corner))
            }
            drawPath(path, scrim)
            drawRoundRect(ring, hole.topLeft, hole.size, corner, style = Stroke(2.dp.toPx()))
        }
        var bubble by remember { mutableStateOf(IntSize.Zero) }
        val margin = with(density) { Ui.Space.l.toPx() }
        val gap = with(density) { Ui.Space.m.toPx() }
        val bubbleWidth = min(with(density) { 340.dp.toPx() }, screen.width - 2f * margin)
        val left = coachBubbleLeft(hole.center.x, bubbleWidth, screen.width, margin)
        val top = coachBubbleTop(hole.top, hole.bottom, bubble.height.toFloat(), screen.height, gap)
        SkinSurface(
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .width(with(density) { bubbleWidth.toDp() })
                .onSizeChanged { bubble = it }
                // Unmeasured, it would flash in the wrong place for a frame.
                .graphicsLayer { alpha = if (bubble == IntSize.Zero) 0f else 1f },
            corner = Ui.Corner.card,
        ) {
            Column(Modifier.padding(Ui.Space.l), verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                Text(mark.title, style = MaterialTheme.typography.titleMedium)
                Text(mark.text, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepDots(index, marks.size)
                    Spacer(Modifier.weight(1f))
                    if (index < marks.lastIndex) TextButton(onClick = onDone) { Text("Skip") }
                    TextButton(onClick = ::next) { Text(if (index < marks.lastIndex) "Next" else "Done") }
                }
            }
        }
    }
}

@Composable
private fun StepDots(at: Int, count: Int) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        repeat(count) { i ->
            Box(Modifier.size(6.dp).background(if (i == at) on else off, CircleShape))
        }
    }
}

/**
 * The note's tips: one at a time, after the pen has come off the page and
 * the hands have been still a moment, gone again as soon as the pen comes
 * back. Each is seen once; "No more tips" ends them.
 */
@Composable
internal fun NoteTips(onLastPage: () -> Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val skin = LocalSkin.current
    val seen = remember { Onboarding.seenTips(context).toMutableSet() }
    var off by remember { mutableStateOf(Onboarding.tipsOff(context)) }
    var showing by remember { mutableStateOf<NoteTip?>(null) }
    var lastShownAt by remember { mutableStateOf(Long.MIN_VALUE / 2) }
    // Tips follow writing in this note, not what was written before it was opened.
    val strokesAtOpen = remember { Onboarding.strokes }
    val latestLastPage by rememberUpdatedState(onLastPage)
    if (!off && seen.size < NoteTip.entries.size) {
        LaunchedEffect(Unit) {
            snapshotFlow { Triple(Onboarding.strokes, latestLastPage(), InteractionGovernor.observed) }
                .collectLatest { (strokes, lastPage, state) ->
                    if (state == Interaction.WRITING) {
                        // The pen is back: whatever was up goes.
                        showing = null
                        return@collectLatest
                    }
                    if (showing != null || strokes <= strokesAtOpen) return@collectLatest
                    val now = android.os.SystemClock.uptimeMillis()
                    val tip = dueNoteTip(seen, strokes, lastPage, now - lastShownAt) ?: return@collectLatest
                    // Only once the hands have been off a moment; cancelled if they come back.
                    delay(NOTE_TIP_QUIET_MS)
                    if (InteractionGovernor.state != Interaction.IDLE) return@collectLatest
                    seen += tip
                    Onboarding.markSeen(context, tip)
                    lastShownAt = android.os.SystemClock.uptimeMillis()
                    showing = tip
                }
        }
    }
    val current = showing
    LaunchedEffect(current) {
        if (current == null) return@LaunchedEffect
        delay(NOTE_TIP_SHOWN_MS)
        if (showing == current) showing = null
    }
    // Kept for the fade out, after showing has gone back to null. Not state:
    // it only ever follows [showing], which is.
    val shown = remember { arrayOfNulls<NoteTip>(1) }
    if (current != null) shown[0] = current
    val content: @Composable () -> Unit = {
        shown[0]?.let { tip ->
            TipBubble(
                tip,
                onGotIt = { showing = null },
                onNoMore = {
                    showing = null
                    off = true
                    Onboarding.turnTipsOff(context)
                },
            )
        }
    }
    if (skin.animatesPopups) {
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { it / 3 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 4 },
            modifier = modifier,
        ) { content() }
    } else if (current != null) {
        Box(modifier) { content() }
    }
}

@Composable
private fun TipBubble(tip: NoteTip, onGotIt: () -> Unit, onNoMore: () -> Unit) {
    SkinSurface(modifier = Modifier.widthIn(max = 460.dp), corner = Ui.Corner.card) {
        Column(Modifier.padding(start = Ui.Space.l, end = Ui.Space.s, top = Ui.Space.m, bottom = Ui.Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.m)) {
                Icon(tipIcon(tip), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(tip.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onNoMore) { Text("No more tips") }
                TextButton(onClick = onGotIt) { Text("Got it") }
            }
        }
    }
}

private fun tipIcon(tip: NoteTip): ImageVector = when (tip) {
    NoteTip.PEN_SETTINGS -> Reicons.Brush
    NoteTip.FUNCTIONS -> Reicons.Category
    NoteTip.HOLD_SHAPE -> Reicons.CropSquare
    NoteTip.GESTURES -> Reicons.Gesture
    NoteTip.NEW_PAGE -> Reicons.Add
}
