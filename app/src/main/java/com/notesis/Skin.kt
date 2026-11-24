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
import android.graphics.BlurMaskFilter
import androidx.compose.material3.Shapes
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.ripple
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/*
 * How the app's chrome is dressed: the bars, panels and dialogs floating over
 * the note. The note itself never changes - paper is paper.
 *
 * Material is the one look: layered tonal surfaces, solid. The two glass
 * looks (Glassmorphism and Liquid glass) are kept outside the build in
 * archive/glass-skins, to be put back when the owner asks. A copy that had
 * one chosen still has its name and its settings saved, untouched.
 */

/**
 * What a skin actually changes. Plain numbers rather than a pile of composables,
 * so a new skin is a row of values and not a new widget set.
 */
class SkinTokens(
    val corner: Dp,
    /** How much of the surface colour survives; the rest is what is behind. */
    val fillAlpha: Float,
    /** The body colour, when the skin names one rather than tinting surface. */
    val fill: Color? = null,
    /** The lit edge. Diagonal, because that is where light enters and leaves. */
    val rim: Brush?,
    val rimWidth: Dp,
    /** A darker line just inside the rim, which is what gives glass thickness. */
    val bevel: Brush?,
    /** Width and colour of each inner glow, widest first. */
    val glows: List<Pair<Dp, Color>>,
    val shadow: Dp,
    val tonalElevation: Dp,
)

val LocalSkin = compositionLocalOf { Skin.MATERIAL }
val LocalSkinSettings = compositionLocalOf { SkinSettings() }

/** Material, for previews and tests that do not choose a look. */
@Composable
fun ProvideSkin(settings: SkinSettings, content: @Composable () -> Unit) = ProvideSkin(Skin.MATERIAL, settings, content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvideSkin(
    skin: Skin,
    settings: SkinSettings,
    onLiquidStyle: ((LiquidStyle) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Whether the chrome may move is watched once here and shared by every control.
    val motionAllowed = LocalMotionAllowed.current ?: rememberSystemMotionAllowed()
    // The same for whether the glass may bend and blur: shared by every Liquid glass control.
    val effectsAllowed = LocalLiquidGlassEffectsAllowed.current ?: rememberSystemLiquidGlassEffectsAllowed()
    CompositionLocalProvider(
        LocalSkin provides skin,
        LocalSkinSettings provides settings,
        // Without this the pickers' choice of Liquid glass recipe (iOS, Mornye) went nowhere.
        LocalLiquidStyleChange provides (onLiquidStyle ?: LocalLiquidStyleChange.current),
        // MaterialTheme alone does not provide this local. Text/icons in a
        // plain Box otherwise inherit the default black even in dark mode.
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        LocalMotionAllowed provides motionAllowed,
        LocalLiquidGlassEffectsAllowed provides effectsAllowed,
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
internal fun chromeCorner(): Dp =
    LocalSkinSettings.current.let { if (LocalSkin.current == Skin.LIQUID_GLASS) it.glassCorner else it.corner }
        .coerceIn(SkinSettings.CORNER_RANGE).dp

@Composable
fun Skin.tokens(): SkinTokens = when (this) {
    // Every skin rounds its chrome by the one corner the person chose, so the
    // toolbar, its popups and the panels have the same outline whichever skin
    // is on; only what they are made of changes. See [chromeCorner].
    Skin.MATERIAL -> SkinTokens(
        corner = chromeCorner(),
        fillAlpha = 1f,
        rim = null,
        rimWidth = 0.dp,
        bevel = null,
        glows = emptyList(),
        shadow = 4.dp,
        tonalElevation = 3.dp,
    )

    // Frosted glass, and the numbers the settings screen owns. No lens: one
    // even rim, one soft shadow, and a body that is mostly what is behind it.
    Skin.GLASSMORPHISM -> LocalSkinSettings.current.let { look ->
        val body = darkAwareTint(Color(look.tint), look.themeMode == AppThemeMode.DARK)
        SkinTokens(
            corner = chromeCorner(),
            fillAlpha = 1f,
            // Turned up, the glass stops being see-through: a panel you can
            // read is worth more than one you can look through, and this is
            // the setting where somebody has said so.
            fill = if (look.highContrast) {
                body.copy(alpha = maxOf(body.alpha, 0.94f))
            } else {
                body
            },
            // Even, not diagonal. A gradient that lights two corners is a lens
            // catching the light; frost scatters it the same way all round.
            // Turned up it becomes the ink colour, because a white rim on a
            // white panel is an edge nobody can find.
            rim = SolidColor(
                if (look.highContrast) {
                    if (look.themeMode == AppThemeMode.DARK) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color(look.content)
                    }
                } else {
                    Color(look.border)
                },
            ),
            rimWidth = if (look.highContrast) 2.dp else 1.dp,
            bevel = null,
            glows = emptyList(),
            shadow = 12.dp,
            tonalElevation = 0.dp,
        )
    }

    Skin.LIQUID_GLASS -> LocalSkinSettings.current.let { look ->
        SkinTokens(
            corner = chromeCorner(),
            fillAlpha = 1f,
            fill = Color(look.tint).copy(alpha = Color(look.tint).alpha * 0.6f),
            // Light caught at two corners, as on iOS, not an even white line.
            rim = glassRimBrush(0.6f, Color(look.border), dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f),
            rimWidth = if (look.highContrast) 2.dp else 0.75.dp,
            bevel = null,
            glows = emptyList(),
            shadow = 8.dp,
            tonalElevation = 0.dp,
        )
    }
}


/** A panel's outline: round all round, or round only on its start side when it stands on the end edge. */
internal fun edgePanelShape(radius: Dp, flushEnd: Boolean): RoundedCornerShape =
    if (flushEnd) RoundedCornerShape(topStart = radius, bottomStart = radius, topEnd = 0.dp, bottomEnd = 0.dp)
    else RoundedCornerShape(radius)

/** The shadow a floating bar or panel casts, where shadows are on. */
private val CHROME_SHADOW = 4.dp

/** Which kind of chrome a surface is: the toolbar sits a tone lower than what opens from it. */
enum class ChromeRole { TOOLBAR, POPUP, SIDEBAR }

/**
 * What the glass has to look through.
 *
 * One layer, recorded by whatever sits under the chrome, and every pane draws
 * it back translated to its own position. Recording is the whole page redrawn
 * once more per frame, which is why this is offered rather than assumed: it is
 * switched on by [SkinSettings.blur] or [SkinSettings.vibrancy] being above
 * zero, and a person who wants the frames back turns them down to nothing.
 */
class Backdrop(val layer: GraphicsLayer?) {
    /** Where the recording starts on screen, so panes can subtract their own. */
    var origin: Offset = Offset.Zero

    /**
     * Held while the pen is down.
     *
     * Recording is the page drawn a second time, into a layer, on the same
     * render pass as the ink - and every pane that frosts it then re-records
     * itself through a blur because the layer changed. That is a chain of work
     * hanging off every stroke, to keep a toolbar's frost current while
     * somebody is looking at their pen. So it stops for the length of a stroke:
     * the panes go on drawing the last recording, which is a frost of the page
     * as it was a moment ago, and nobody has ever noticed the difference.
     */
    var paused by mutableStateOf(false)
}

val LocalBackdrop = compositionLocalOf { Backdrop(null) }

/**
 * Nothing to look through, for the inside of a recording.
 *
 * A pane that refracts draws the layer, so a pane *inside* the layer draws the
 * layer into itself and the render tree becomes a cycle - the RenderThread
 * walks it until the stack runs out. Every screen that records a backdrop hands
 * this to its own content, so the chrome above can frost it and the panels
 * within it simply stay as they are.
 */
val NoBackdrop = Backdrop(null)

/**
 * Whether anything can be drawn through a recorded backdrop here: blur and
 * the glass's lens both need Android 12's render effects. Below it a
 * recording is only the screen drawn a second time every frame, for nothing,
 * so it is not made, whatever the look or the light mode say.
 */
internal val BACKDROP_DRAWABLE: Boolean = android.os.Build.VERSION.SDK_INT >= 31

@Composable
fun rememberBackdrop(active: Boolean): Backdrop {
    // Always the one layer, handed out only while active. Remembered only
    // when active, it was released each time the frost went off - battery
    // saver coming on, the blur turned to nothing - while a pane in a popup
    // or a dialog, a window of its own, could still draw it in that frame,
    // and drawing a released layer takes the app down.
    val layer = rememberGraphicsLayer()
    return remember(layer, active) { Backdrop(if (active) layer else null) }
}

/**
 * Records everything drawn inside it, for the panes above to look through -
 * or, given a [source], only what that draws, while the content itself is
 * drawn as usual. A source is how a screen keeps a view that holds surfaces
 * out of the recording.
 */
fun Modifier.recordBackdrop(
    backdrop: Backdrop,
    source: (DrawScope.() -> Unit)? = null,
    /** True when the last recording no longer matches the page and must be redone even while paused. */
    stale: (() -> Boolean)? = null,
): Modifier {
    val layer = backdrop.layer ?: return this
    return this
        // On screen rather than in the window: a popup or a dialog is a window
        // of its own, and a pane in it measured against this window's corner
        // drew the page from the wrong place.
        .onGloballyPositioned { backdrop.origin = it.positionOnScreen() }
        .drawWithContent {
            // Read here rather than in composition, so putting the pen down
            // invalidates one draw instead of recomposing the screen. The
            // governor covers every canvas, the reference panel's too, and
            // the settle after a stroke, so strokes in quick succession do not
            // re-record the page in each gap between them.
            if (layer.isReleased) {
                drawContent()
                return@drawWithContent
            }
            if (stale?.invoke() != true && (backdrop.paused || InteractionGovernor.observed != Interaction.IDLE)) {
                drawContent()
                return@drawWithContent
            }
            if (source != null) {
                drawContent()
                layer.record { source() }
                return@drawWithContent
            }
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

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
    /**
     * Set for a panel that stands on the window's end edge - the sidebars,
     * the AI panel beside the note: its corners on that edge go square, so it
     * runs flush into the edge instead of leaving a notch at its top and foot.
     */
    flushEnd: Boolean = false,
    /** Which kind of chrome this is, for its tone; null is a panel's. */
    role: ChromeRole? = null,
    content: @Composable () -> Unit,
) {
    val skin = LocalSkin.current
    val radius = if (flush) 0.dp else (corner ?: chromeCorner())
    val shape = edgePanelShape(radius, flushEnd)
    if (skin == Skin.MATERIAL) {
        val scheme = MaterialTheme.colorScheme
        Surface(
            modifier = modifier,
            shape = shape,
            color = if (role == ChromeRole.TOOLBAR) scheme.surfaceContainerLow else scheme.surfaceContainer,
            contentColor = scheme.onSurface,
            border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
            tonalElevation = 0.dp,
            shadowElevation = if (LocalSkinSettings.current.shadows) CHROME_SHADOW else 0.dp,
            content = content,
        )
        return
    }
    val tokens = skin.tokens()
    val glassHost = if (skin == Skin.LIQUID_GLASS) rememberLiquidGlassBackdrop() else null
    val surfaceModifier = if (skin == Skin.LIQUID_GLASS) {
        // A round rectangle, as in the other looks: Backdrop's lens can take no other outline.
        modifier.liquidGlassSurface(
            shape, LocalSkinSettings.current,
            elevation = if (flush) 0.dp else tokens.shadow, rim = !flush,
            exportedBackdrop = glassHost,
        ).then(if (flush) Modifier.flushEdge(tokens) else Modifier).chromeVeil(role, shape)
    } else {
        modifier
            .frostedGlass(tokens, shape)
            .then(if (flush) Modifier.flushEdge(tokens) else Modifier.glassEdge(tokens, shape))
            .chromeVeil(role, shape)
    }
    Box(surfaceModifier) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            if (glassHost != null) {
                CompositionLocalProvider(LocalLiquidGlassHostBackdrop provides glassHost) { content() }
            } else content()
        }
    }
}

/**
 * The chrome's material on an outline the caller chooses, for chrome built
 * from the toolbar's shapes rather than from [SkinSurface]'s rounded panel:
 * solid under Material, frosted under Glassmorphism, and under Liquid glass
 * the refractive lens the bar itself is made of. A [panel] carries text and
 * sits a tone higher (or more solid) than a bar that only carries icons.
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
    /** Which kind of chrome this is, for its own transparency on glass; null takes none. */
    role: ChromeRole? = null,
): Modifier {
    val skin = LocalSkin.current
    return when (skin) {
        Skin.MATERIAL -> {
            val scheme = MaterialTheme.colorScheme
            this
                .then(if (shadow) Modifier.skinShadow(shape, panel) else Modifier)
                .clip(shape)
                .background(if (panel) scheme.surfaceContainerHigh else scheme.surfaceContainerLow)
                .border(0.75.dp, scheme.outlineVariant.copy(alpha = 0.6f), shape)
        }
        Skin.GLASSMORPHISM -> this
            .frostedGlass(skin.tokens(), shape, shadow = shadow,
                fill = if (panel) MaterialTheme.colorScheme.surfaceContainerHigh else null)
            .glassEdge(skin.tokens(), shape)
            .chromeVeil(role, shape)
        Skin.LIQUID_GLASS -> liquidGlassPane(shape, panel = panel, shadow = shadow).chromeVeil(role, shape)
    }
}

/** The shadow [skinMaterial] casts for this [shape], on its own. */
@Composable
internal fun Modifier.skinShadow(shape: Shape, panel: Boolean = false): Modifier {
    if (!LocalSkinSettings.current.shadows) return this
    return when (val skin = LocalSkin.current) {
        Skin.MATERIAL -> shadow(if (panel) 6.dp else 3.dp, shape, clip = false)
        Skin.GLASSMORPHISM -> shadow(skin.tokens().shadow, shape, clip = false)
        // Round the glass, never under it; see [glassShadow].
        Skin.LIQUID_GLASS -> glassShadow(LocalSkinSettings.current.let { look ->
            if (look.smoothCorners) mornyeSquircle(lensSafeShape(shape))
            else lensSafeShape(shape)
        }, liquidGlassPaneShadow(panel),
            dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f)
    }
}

/**
 * [role]'s own transparency, colour and darkening over the glass under it:
 * its background colour (the panes' surface unless one is chosen) laid over
 * the glass as solid as the role is set below clear, then black at its
 * darkening, all under the content and the rim. Material's panes are solid
 * already and take nothing.
 */
@Composable
internal fun Modifier.chromeVeil(role: ChromeRole?, shape: Shape): Modifier {
    if (role == null || LocalSkin.current == Skin.MATERIAL) return this
    val settings = LocalSkinSettings.current
    val clarity = settings.clarityOf(role)
    val darken = settings.darkenOf(role)
    var result = this
    if (clarity < 0.999f) {
        val chosen = settings.colorOf(role)
        val base = if (chosen != 0) Color(chosen).copy(alpha = 1f) else MaterialTheme.colorScheme.surface
        result = result.background(base.copy(alpha = (1f - clarity).coerceIn(0f, 1f)), shape)
    }
    if (darken > 0.001f) result = result.background(Color.Black.copy(alpha = darken), shape)
    return result
}

/**
 * Frost: a shadow, the page behind blurred, and the body colour over it -
 * [fill], or the skin's own when null.
 */
@Composable
private fun Modifier.frostedGlass(tokens: SkinTokens, shape: Shape, shadow: Boolean = true, fill: Color? = null): Modifier {
    val body = fill ?: tokens.fill ?: MaterialTheme.colorScheme.surface.copy(alpha = tokens.fillAlpha)
    return this
        .then(if (shadow && LocalSkinSettings.current.shadows) Modifier.shadow(tokens.shadow, shape, clip = false) else Modifier)
        .clip(shape)
        .frost()
        // Battery saver stops the frost, and before Android 12 there is no
        // blur to frost with; a pane that stays see-through without it puts
        // its labels straight over the ink.
        .background(if (InteractionGovernor.observedPowerSave || android.os.Build.VERSION.SDK_INT < 31) {
            body.copy(alpha = maxOf(body.alpha, 0.92f))
        } else body)
}

/** The one edge a window-wide bar has: the line where it lets the page go. */
private fun Modifier.flushEdge(tokens: SkinTokens): Modifier = drawWithContent {
    drawContent()
    val brush = tokens.rim ?: return@drawWithContent
    val width = tokens.rimWidth.toPx()
    drawRect(
        brush,
        topLeft = Offset(0f, size.height - width),
        size = androidx.compose.ui.geometry.Size(size.width, width),
    )
}

/**
 * Draws the recorded backdrop back under this pane, frosted.
 *
 * The layer holds the whole page as it was drawn a moment ago; this takes the
 * part of it that lies under this pane and scatters it, so what shows through
 * is softened rather than merely dimmed. Without a backdrop - frost turned off,
 * or a pane with nothing recorded beneath it - nothing is drawn and the panel
 * stays as it was.
 */
@Composable
private fun Modifier.frost(): Modifier {
    // Before Android 12 there is no blur: the page would be drawn back under
    // the pane sharp, every line of it showing through the panel's own text.
    if (android.os.Build.VERSION.SDK_INT < 31) return this
    val backdrop = LocalBackdrop.current
    val layer = backdrop.layer ?: return this
    val settings = LocalSkinSettings.current
    // The pane's own layer, and the reason it exists: a RenderEffect belongs to
    // the layer it is set on, and the backdrop layer is also what draws the page
    // itself. Hanging the blur on it put it on the page - the PDF came out
    // softened and over-saturated, which is the effect meant for the half-inch
    // of glass sitting on top of it. This one holds the effect; the backdrop
    // stays clean.
    val pane = rememberGraphicsLayer()
    // Built when the numbers change and not once per pane per frame. This is a
    // native RenderEffect: it was being allocated inside the draw of every pane
    // on screen, every frame the page moved under them.
    val density = LocalDensity.current
    val effect = remember(settings.blur, settings.vibrancy, density) {
        frostEffect(blurPx = with(density) { settings.blur.dp.toPx() }, vibrancy = settings.vibrancy)
    }
    // Set while a popup this pane belongs to is growing; reading it redraws
    // the pane each frame of that, so the page under it keeps its place.
    val motion = LocalBackdropMotion.current
    // In a sliding panel it looks at the page where the panel comes to rest.
    val slide = LocalSlideShift.current
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var here by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned {
            placed[0] = it
            here = it.positionOnScreen()
        }
        .drawBehind {
            here
            motion?.invoke()
            // Gone with the screen it came from: nothing to frost, and drawing
            // it would throw.
            if (layer.isReleased || pane.isReleased) return@drawBehind
            pane.renderEffect = effect
            // The pane is already clipped to its shape, so drawing the whole
            // backdrop back by this pane's place on screen - and at the
            // inverse of any scale it is drawn at - lands the right part of
            // the page underneath it.
            val dx = slide?.x ?: 0f
            // Sliding, what it frosts is where the panel rests and does not
            // change: recorded once, then only drawn on each frame of the slide.
            val keep = slide != null && ChromeMotion.busy && pane.size.width > 0
            if (!keep) pane.record { translate(dx, 0f) { drawBackdropAligned(layer, backdrop.origin, placed[0]) } }
            drawLayer(pane)
        }
}

/**
 * The edge, in the order the reference file layers it: inner glows, then the
 * bevel, then the specular over the top. Everything is stroked and the surface
 * is clipped, so the outer half of each stroke falls away and what is left hugs
 * the inside - the difference between glass with thickness and a rectangle with
 * a border drawn round it.
 */
private fun Modifier.glassEdge(tokens: SkinTokens, shape: Shape): Modifier = drawWithContent {
    drawContent()
    // The edge follows whatever shape the fill uses - the panel's continuous
    // curve, or the slider's true capsule. Stroking a different curve over the
    // fill leaves a visible double line at every corner.
    val edge = pathOf(shape, size, layoutDirection, this)
    if (tokens.glows.isNotEmpty()) {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
            }
            // The glows are measured for a panel. On something as thin as a
            // slider a 16dp glow reaches past the middle from both sides and
            // floods the whole control white, so each one is capped against the
            // short side rather than applied blind.
            val ceiling = size.minDimension / 4f
            for ((widthDp, colour) in tokens.glows) {
                val glow = widthDp.toPx().coerceAtMost(ceiling)
                if (glow <= 0.5f) continue
                paint.strokeWidth = glow
                paint.color = colour.toArgb()
                paint.maskFilter = BlurMaskFilter(glow / 2f, BlurMaskFilter.Blur.NORMAL)
                canvas.nativeCanvas.drawPath(edge.asAndroidPath(), paint)
            }
        }
    }
    val width = tokens.rimWidth.toPx()
    fun strokeEdge(brush: Brush, insetBy: Float) {
        inset(insetBy) {
            drawPath(pathOf(shape, size, layoutDirection, this), brush, style = Stroke(width))
        }
    }
    // The bevel sits one line further in than the specular, so the two read as
    // the near and the far face of the same edge rather than as one thick line.
    tokens.bevel?.let { strokeEdge(it, width * 1.5f) }
    tokens.rim?.let { strokeEdge(it, width / 2f) }
}

/** A shape's outline as a path, whatever kind of outline it produces. */
private fun pathOf(
    shape: Shape,
    size: androidx.compose.ui.geometry.Size,
    layoutDirection: LayoutDirection,
    density: Density,
): Path = when (val outline = shape.createOutline(size, layoutDirection, density)) {
    is androidx.compose.ui.graphics.Outline.Generic -> outline.path
    is androidx.compose.ui.graphics.Outline.Rounded ->
        Path().apply { addRoundRect(outline.roundRect) }
    is androidx.compose.ui.graphics.Outline.Rectangle ->
        Path().apply { addRect(outline.rect) }
}

/**
 * The skin's rim, for a surface this file does not own.
 *
 * A dialog paints itself, and all a theme can hand it is a colour and a shape;
 * this is the third thing glass needs. Applied to a component's own modifier it
 * lands outside that component's clip and on the same outline, so the dialog
 * gets the edge every other pane has instead of ending in a soft nothing.
 */
@Composable
fun skinEdge(shape: Shape): Modifier {
    val skin = LocalSkin.current
    if (skin == Skin.MATERIAL) return Modifier
    return Modifier.glassEdge(skin.tokens(), shape)
}

/** The border a skin would draw, for the few places that want one directly. */
@Composable
fun skinBorder(): BorderStroke? {
    val tokens = LocalSkin.current.tokens()
    val rim = tokens.rim ?: return null
    return BorderStroke(tokens.rimWidth, rim)
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
/** How solid a popup's own surface is on glass. The platform blurs what is behind it. */
private const val POPUP_ALPHA = 0.86f

fun skinColors(base: ColorScheme, look: SkinSettings, skin: Skin = Skin.MATERIAL): ColorScheme {
    // 다크 모드에서 라이트 모드용 사용자 잉크색을 그대로 쓰면 검은 글씨가 됩니다.
    // 이 경우 생성한 다크 팔레트의 대비 보장 색을 사용합니다.
    val ink = readableChrome(
        if (look.themeMode == AppThemeMode.DARK) base.onSurface else Color(look.content),
        base.surfaceContainerHighest,
    )
    val inked = base.copy(
        onSurface = ink,
        onBackground = ink,
        // The quieter ink: labels, hints, and every icon that is not the tool
        // in hand. Turned up it stops being quieter.
        onSurfaceVariant = if (look.highContrast) ink else readableChrome(base.onSurfaceVariant, base.surfaceContainerHighest),
    )
    if (skin == Skin.MATERIAL) return inked
    // Popups live in their own window and are blurred by the platform rather
    // than by the backdrop layer, so they can be far more solid than the in-app
    // chrome without looking like a Material slab dropped in.
    val alpha = when {
        look.highContrast -> 0.98f
        skin.isRefractive && look.themeMode == AppThemeMode.DARK -> 0.96f
        skin.isRefractive -> REFRACTIVE_POPUP_ALPHA
        else -> POPUP_ALPHA
    }
    val tint = if (look.themeMode == AppThemeMode.DARK && skin.isRefractive && look.liquidStyle == LiquidStyle.IOS) {
        Color.Transparent
    } else darkAwareTint(Color(look.tint), look.themeMode == AppThemeMode.DARK)
    fun glassy(container: Color): Color =
        tint.compositeOver(container).copy(alpha = alpha)
    return inked.copy(
        surfaceContainer = glassy(base.surfaceContainer),
        surfaceContainerHigh = glassy(base.surfaceContainerHigh),
        surfaceContainerHighest = glassy(base.surfaceContainerHighest),
        surfaceContainerLow = glassy(base.surfaceContainerLow),
    )
}

/**
 * The glass tint as a theme should show it. A white frost is right over light
 * panels; over dark ones it turns them grey and pales the light text on them,
 * so a dark theme keeps only a trace of a light tint. A dark tint stays as chosen.
 */
internal fun darkAwareTint(tint: Color, dark: Boolean): Color =
    if (dark && tint.luminance() > 0.5f) tint.copy(alpha = (tint.alpha * 0.25f).coerceAtMost(0.08f)) else tint

/** Clearer than frost, still solid enough for a popup window with no backdrop capture. */
private const val REFRACTIVE_POPUP_ALPHA = 0.80f


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
