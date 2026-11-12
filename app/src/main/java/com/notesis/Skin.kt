package com.notesis

import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * How the app's chrome is dressed: the bars, panels and dialogs floating over
 * the note. The note itself never changes - paper is paper.
 *
 * Material is the one look: layered tonal surfaces, solid. The two glass
 * looks (Glassmorphism and Liquid glass) are kept outside the build in
 * archive/glass-skins, to be put back when the owner asks. A copy that had
 * one chosen still has its name and its settings saved, untouched.
 */

val LocalSkinSettings = compositionLocalOf { SkinSettings() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvideSkin(settings: SkinSettings, content: @Composable () -> Unit) {
    // Whether the chrome may move is watched once here and shared by every control.
    val motionAllowed = LocalMotionAllowed.current ?: rememberSystemMotionAllowed()
    CompositionLocalProvider(
        LocalSkinSettings provides settings,
        // MaterialTheme alone does not provide this local. Text/icons in a
        // plain Box otherwise inherit the default black even in dark mode.
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        LocalMotionAllowed provides motionAllowed,
        // No grey wash under a hovering pointer and no grey flash on a press,
        // in Material's own components (the ripple) and in every plain
        // clickable (the indication): the controls answer a touch with their
        // own motion.
        LocalRippleConfiguration provides null,
        LocalIndication provides FocusOnlyIndication,
        content = content,
    )
}

/**
 * An indication that draws nothing: no ripple on a press, no wash on hover,
 * and no veil on focus either - with a mouse a click moves the focus, and
 * the veil stayed on whatever was clicked as a grey highlight.
 */
private object FocusOnlyIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * The outer corner of the chrome: the toolbar, the strip and pill concentric
 * inside it, the function menu, the pen panel and the floating panels. One
 * value, the corner setting, so every bar and popup is drawn on one curve.
 */
@Composable
internal fun chromeCorner(): Dp = LocalSkinSettings.current.corner.coerceIn(SkinSettings.CORNER_RANGE).dp

/** The shadow a floating bar or panel casts, where shadows are on. */
private val CHROME_SHADOW = 4.dp

/** Which kind of chrome a surface is: the toolbar sits a tone lower than what opens from it. */
enum class ChromeRole { TOOLBAR, POPUP, SIDEBAR }

/**
 * Every floating piece of chrome - the toolbar, the page list, the action bars -
 * goes through here, so the look is chosen once and lands everywhere.
 */
@Composable
fun SkinSurface(
    modifier: Modifier = Modifier,
    /** Null takes the chrome's own radius; pass 0.dp for chrome flush to an edge. */
    corner: Dp? = null,
    /** Set for chrome that spans the window rather than floating in it: its corners go square. */
    flush: Boolean = false,
    /** Which kind of chrome this is, for its tone; null is a panel's. */
    role: ChromeRole? = null,
    content: @Composable () -> Unit,
) {
    val radius = if (flush) 0.dp else (corner ?: chromeCorner())
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(radius),
        color = if (role == ChromeRole.TOOLBAR) scheme.surfaceContainerLow else scheme.surfaceContainer,
        contentColor = scheme.onSurface,
        border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        tonalElevation = 0.dp,
        shadowElevation = if (LocalSkinSettings.current.shadows) CHROME_SHADOW else 0.dp,
        content = content,
    )
}

/**
 * The chrome's material on an outline the caller chooses, for chrome built
 * from the toolbar's shapes rather than from [SkinSurface]'s rounded panel.
 * A [panel] carries text and sits a tone higher than a bar that only carries
 * icons.
 */
@Composable
internal fun Modifier.skinMaterial(
    shape: Shape,
    panel: Boolean = false,
    /**
     * False leaves the shadow to [skinShadow] further out, for a surface
     * drawn inside a layer of its own, which would cut the shadow off at its
     * bounds.
     */
    shadow: Boolean = true,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    return this
        .then(if (shadow) Modifier.skinShadow(shape, panel) else Modifier)
        .clip(shape)
        .background(if (panel) scheme.surfaceContainerHigh else scheme.surfaceContainerLow)
        .border(0.75.dp, scheme.outlineVariant.copy(alpha = 0.6f), shape)
}

/** The shadow [skinMaterial] casts for this [shape], on its own. */
@Composable
internal fun Modifier.skinShadow(shape: Shape, panel: Boolean = false): Modifier {
    if (!LocalSkinSettings.current.shadows) return this
    return shadow(if (panel) 6.dp else 3.dp, shape, clip = false)
}

/**
 * The app's slider: a thin track that fills as it goes, and a wide, flat
 * handle standing on it - taller than the track and wide enough to have a
 * face, so it is easy to take hold of with a fingertip or the pen.
 */
@Composable
fun SkinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    /**
     * The interval values come in. The handle only ever stands on one of
     * them and the value reported is always one, rather than gliding across
     * the track in between. 0 picks a hundredth of the range.
     */
    step: Float = 0f,
) {
    // A light tick as the handle lands on another step, where the slider has
    // steps; a continuous one slides without.
    val view = androidx.compose.ui.platform.LocalView.current
    val ticked = remember { floatArrayOf(value) }
    val change: (Float) -> Unit = { next ->
        if (step > 0f && next != ticked[0]) Haptics.tick(view)
        ticked[0] = next
        onValueChange(next)
    }
    val steps = sliderSteps(valueRange, step)
    val scheme = MaterialTheme.colorScheme
    val trackAlpha = if (LocalSkinSettings.current.highContrast) 0.34f else 0.14f
    val density = LocalDensity.current
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var width by remember { mutableFloatStateOf(0f) }

    // The travel is the track less one thumb, so the thumb comes to rest with
    // its edge on the end of the track rather than half of it hanging off.
    val half = with(density) { SLIDER_THUMB_W.toPx() } / 2f
    // Held, the handle follows the finger itself, not the stepped value under
    // it; let go, it eases onto the value it chose. See [sliderShownFraction].
    var dragFraction by remember { mutableFloatStateOf(NOT_DRAGGING) }
    val shown = sliderShownFraction(fraction, dragFraction, animate = rememberMotionAllowed())
    val centre = half + shown * ((width - 2f * half).coerceAtLeast(1f))

    // A gesture block outlives the composition that started it, so both the
    // width it measures against and the callback it reports to are read at the
    // moment of the drag rather than captured when the finger went down. Caught
    // the hard way: a captured callback writes back the settings as they were,
    // taking whatever was changed in between with it.
    val latest by rememberUpdatedState(change)
    fun report(x: Float) {
        val travel = (width - 2f * half).coerceAtLeast(1f)
        val raw = ((x - half) / travel).coerceIn(0f, 1f)
        dragFraction = raw
        latest(sliderValue(raw, valueRange, steps))
    }

    Box(
        modifier
            .height(SLIDER_ROW_H)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(valueRange) {
                detectDragGestures(
                    onDragEnd = { dragFraction = NOT_DRAGGING },
                    onDragCancel = { dragFraction = NOT_DRAGGING },
                ) { change, _ ->
                    change.consume()
                    report(change.position.x)
                }
            }
            .pointerInput(valueRange) {
                detectTapGestures(
                    onPress = {
                        report(it.x)
                        tryAwaitRelease()
                        dragFraction = NOT_DRAGGING
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SLIDER_TRACK_H)
                .clip(CircleShape)
                .background(scheme.onSurface.copy(alpha = trackAlpha)),
        ) {
            // Filled to the middle of the thumb, not to its near edge: the
            // reading is where the handle is centred, so that is where the
            // colour has to stop.
            Box(
                Modifier
                    .fillMaxWidth(if (width > 2f * half) (centre / width).coerceIn(0f, 1f) else 0f)
                    .fillMaxHeight()
                    // The outer clip only rounds the track's own two ends; the
                    // fill's far edge is a corner of its own and stayed square
                    // wherever the thumb was short of the end. Its own capsule
                    // rounds that edge too.
                    .clip(CircleShape)
                    .background(scheme.primary),
            )
        }
        Thumb(
            Modifier
                .offset { IntOffset((centre - SLIDER_THUMB_W.toPx() / 2f).roundToInt(), 0) }
                .size(SLIDER_THUMB_W, SLIDER_THUMB_H),
        )
    }
}

/** A slider's value at [fraction] of its travel, on one of its [steps] stops where it has any. */
internal fun sliderValue(fraction: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val bounded = fraction.coerceIn(0f, 1f)
    val snapped = if (steps > 0) (bounded * (steps + 1)).roundToInt().toFloat() / (steps + 1) else bounded
    return range.start + (range.endInclusive - range.start) * snapped
}

/**
 * The app's switch: one handle sliding along a coloured capsule, a little
 * taller than the track it rides on so it stands proud of it. It slides from
 * side to side where the chrome may move, and changes sides at once where it
 * may not.
 */
@Composable
fun SkinSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val scheme = MaterialTheme.colorScheme
    val trackAlpha = if (LocalSkinSettings.current.highContrast) 0.34f else 0.14f
    val moves = rememberMotionAllowed()
    val interaction = remember { MutableInteractionSource() }
    val centre by animateDpAsState(
        targetValue = if (checked) {
            SWITCH_W - SWITCH_THUMB_INSET - SWITCH_THUMB_W / 2f
        } else {
            SWITCH_THUMB_INSET + SWITCH_THUMB_W / 2f
        },
        animationSpec = if (moves) MaterialMotion.selection() else snap(),
        label = "switch handle centre",
    )
    val shift = centre - SWITCH_THUMB_W / 2f
    val track by animateColorAsState(
        if (checked) scheme.primary else scheme.onSurface.copy(alpha = trackAlpha),
        animationSpec = if (moves) MaterialMotion.effects() else snap(),
        label = "switch track",
    )
    Box(
        Modifier
            .size(SWITCH_W, SWITCH_ROW)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                // No ripple: it would be a rectangle round a capsule, and the
                // handle moving is already the answer to the press.
                indication = null,
                role = Role.Switch,
                onValueChange = { on -> Haptics.tick(view); onCheckedChange(on) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SWITCH_TRACK)
                .clip(CircleShape)
                .background(track),
        )
        Thumb(
            Modifier
                .offset(x = shift)
                .size(SWITCH_THUMB_W, SWITCH_THUMB_H),
        )
    }
}

/** The handle both controls are held by: a white capsule with a hairline to find it on white. */
@Composable
private fun Thumb(modifier: Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .then(if (LocalSkinSettings.current.shadows) Modifier.shadow(THUMB_SHADOW, shape, clip = false) else Modifier)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), shape),
    )
}

// Slider: the thumb is 1.45 as wide as it is tall, and about four times the
// height of the track it rides on. One row height for everything in the
// toolbar: an icon button is 40dp, and a slider that asks for more makes the
// whole bar taller for one control.
private val SLIDER_ROW_H = 40.dp
private val SLIDER_TRACK_H = 6.dp
private val SLIDER_THUMB_W = 36.dp
private val SLIDER_THUMB_H = 25.dp

// Switch: long, with a lozenge-shaped handle.
private val SWITCH_ROW = 44.dp
private val SWITCH_W = 74.dp
private val SWITCH_TRACK = 32.dp
private val SWITCH_THUMB_W = 47.dp
private val SWITCH_THUMB_H = 28.dp
private val SWITCH_THUMB_INSET = 2.dp

private val THUMB_SHADOW = 6.dp

/**
 * The colours the app is drawn in, before [skinColors] inks them: the
 * wallpaper's where it may (Android 12 and up, contrast not turned up), else
 * made from the accent; black under AMOLED in the dark.
 */
internal fun lookScheme(context: Context?, look: SkinSettings): ColorScheme {
    val dark = look.themeMode == AppThemeMode.DARK
    val wallpaper = look.dynamicColor && !look.highContrast &&
        context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var scheme = if (wallpaper) {
        if (dark) dynamicDarkColorScheme(context!!) else dynamicLightColorScheme(context!!)
    } else {
        schemeFrom(look.accent, look.highContrast, dark)
    }
    scheme = materialStudioScheme(scheme, dark)
    if (look.highContrast) scheme = scheme.copy(
        onSurface = if (dark) Color.White else Color.Black,
        onBackground = if (dark) Color.White else Color.Black,
        onSurfaceVariant = if (dark) Color.White else Color.Black,
    )
    if (look.amoled && dark) scheme = amoledScheme(scheme)
    return scheme
}

/**
 * The dark surfaces on a black page: the container ladder keeps Material's
 * steps, from #111111 up to #2A2A2A.
 */
internal fun amoledScheme(scheme: ColorScheme): ColorScheme = scheme.copy(
    background = Color.Black,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF161616),
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = Color(0xFF2A2A2A),
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
)

/**
 * Text and icons take the colour the settings name, and they take it in the
 * "on" roles together, so a pale ink cannot leave half the app dark.
 *
 * The settings are passed rather than read off the composition: the theme is
 * built above [ProvideSkin], where the local still holds its defaults, and a
 * read there quietly gives back the colours nobody chose.
 */
fun skinColors(base: ColorScheme, look: SkinSettings): ColorScheme {
    // A light-mode ink used as it is in the dark would be black text on a dark
    // panel; there the generated dark palette's own readable colour is used.
    val ink = readableChrome(
        if (look.themeMode == AppThemeMode.DARK) base.onSurface else Color(look.content),
        base.surfaceContainerHighest,
    )
    return base.copy(
        onSurface = ink,
        onBackground = ink,
        // The quieter ink: labels, hints, and every icon that is not the tool
        // in hand. Turned up it stops being quieter.
        onSurfaceVariant = if (look.highContrast) ink else readableChrome(base.onSurfaceVariant, base.surfaceContainerHighest),
    )
}

/**
 * Stops between the ends of a slider for values every [step] across
 * [range] - the count the sliders take - or every hundredth when no step is
 * given. A step that does not divide the range lands on the nearest count.
 */
internal fun sliderSteps(range: ClosedFloatingPointRange<Float>, step: Float): Int {
    val span = range.endInclusive - range.start
    if (!(span > 0f)) return 0
    val size = if (step > 0f) step else span / 100f
    return ((span / size).roundToInt() - 1).coerceIn(0, 10_000)
}

/** A slider handle's drag position when no finger is on it. */
internal const val NOT_DRAGGING = -1f

/**
 * Where a slider's handle is drawn. While held ([dragFraction] at or above 0)
 * it stays under the finger, moving as smoothly as the finger does, whatever
 * stepped value that lands on. Released, it settles onto [valueFraction] - the
 * stop it chose - with a short spring rather than a jump, unless [animate]
 * is off.
 */
@Composable
internal fun sliderShownFraction(
    valueFraction: Float,
    dragFraction: Float,
    /** False lands it on the stop at once, where the chrome does not move. */
    animate: Boolean = true,
): Float {
    val dragging = dragFraction >= 0f
    val shown by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dragging) dragFraction else valueFraction,
        animationSpec = if (dragging || !animate) snap() else Ui.Motion.spring(androidx.compose.animation.core.Spring.DampingRatioNoBouncy),
        label = "slider handle",
    )
    return shown.coerceIn(0f, 1f)
}
