package com.notesis

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * A small player for AnimateIcons (https://animateicons.in, MIT, Avijit Dey),
 * whose shapes are Lucide's (ISC). The web originals are motion/react
 * variants; each one is written out here as parts with keyframe tracks, and
 * played by redrawing the 24-unit Lucide grid on a Canvas.
 */

/** Motion's named curves. Keyframes ease segment by segment, as motion does. */
internal enum class IconEase { LINEAR, IN, OUT, IN_OUT;
    fun at(t: Float): Float = when (this) {
        LINEAR -> t
        IN -> cubicBezier(0.42f, 0f, 1f, 1f, t)
        OUT -> cubicBezier(0f, 0f, 0.58f, 1f, t)
        IN_OUT -> cubicBezier(0.42f, 0f, 0.58f, 1f, t)
    }
}

/** y on the CSS cubic-bezier(x1, y1, x2, y2) at x = [t], by bisection on x. */
private fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float, t: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    fun bez(a: Float, b: Float, s: Float): Float {
        val u = 1 - s
        return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s
    }
    var lo = 0f
    var hi = 1f
    var s = t
    repeat(24) {
        s = (lo + hi) / 2
        if (bez(x1, x2, s) < t) lo = s else hi = s
    }
    return bez(y1, y2, s)
}

/**
 * One animated property: [values] reached at [times] (fractions of
 * [duration], evenly spaced when null), starting [delay] seconds in.
 */
internal class IconTrack(
    val values: FloatArray,
    val duration: Float,
    val delay: Float = 0f,
    val times: FloatArray? = null,
    val ease: IconEase = IconEase.IN_OUT,
) {
    val end: Float get() = delay + duration

    fun at(seconds: Float): Float {
        if (values.size == 1) return values[0]
        val t = ((seconds - delay) / duration).coerceIn(0f, 1f)
        val stops = times ?: FloatArray(values.size) { it / (values.size - 1f) }
        for (i in 1 until values.size) {
            if (t <= stops[i] || i == values.lastIndex) {
                val span = (stops[i] - stops[i - 1]).takeIf { it > 0f } ?: return values[i]
                val local = ((t - stops[i - 1]) / span).coerceIn(0f, 1f)
                return values[i - 1] + (values[i] - values[i - 1]) * ease.at(local)
            }
        }
        return values.last()
    }
}

/** Shorthand for a track: `track(0f, 30f, -6f, 0f, duration = 0.9f, times = floatArrayOf(...))`. */
internal fun track(
    vararg values: Float,
    duration: Float,
    delay: Float = 0f,
    times: FloatArray? = null,
    ease: IconEase = IconEase.IN_OUT,
) = IconTrack(values, duration, delay, times, ease)

/**
 * A group of shapes moving together. [paths] are SVG path data on the 24-unit
 * grid (see [circle], [rect], [line] for the other SVG elements). Transforms
 * turn about [pivot], or the centre of the part's bounds when it is null, as
 * motion does by default. [trim] is the visible share of each path from its
 * start, which is how stroke-dashoffset and pathLength draw-ins are played.
 */
internal class IconPart(
    val paths: List<String> = emptyList(),
    val children: List<IconPart> = emptyList(),
    val pivot: Offset? = null,
    val rotate: IconTrack? = null,
    val scale: IconTrack? = null,
    val scaleX: IconTrack? = null,
    val scaleY: IconTrack? = null,
    val x: IconTrack? = null,
    val y: IconTrack? = null,
    val opacity: IconTrack? = null,
    val trim: IconTrack? = null,
    val fill: Boolean = false,
) {
    internal val parsed: List<Path> by lazy { paths.map { PathParser().parsePathString(it).toPath() } }

    internal val centre: Offset by lazy {
        val all = allPaths()
        if (all.isEmpty()) return@lazy Offset(12f, 12f)
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        all.forEach { p -> val box = p.getBounds(); l = minOf(l, box.left); t = minOf(t, box.top); r = maxOf(r, box.right); b = maxOf(b, box.bottom) }
        Offset((l + r) / 2, (t + b) / 2)
    }

    private fun allPaths(): List<Path> = parsed + children.flatMap { it.allPaths() }

    internal val tracks: List<IconTrack>
        get() = listOfNotNull(rotate, scale, scaleX, scaleY, x, y, opacity, trim) + children.flatMap { it.tracks }
}

internal fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

internal fun rect(x: Float, y: Float, width: Float, height: Float, rx: Float = 0f): String {
    if (rx <= 0f) return "M$x ${y}h${width}v${height}h${-width}z"
    val r = minOf(rx, width / 2, height / 2)
    return "M${x + r} ${y}h${width - 2 * r}a$r $r 0 0 1 $r ${r}v${height - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
        "h${-(width - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(height - 2 * r)}a$r $r 0 0 1 $r ${-r}z"
}

internal fun line(x1: Float, y1: Float, x2: Float, y2: Float) = "M$x1 ${y1}L$x2 $y2"

/** An icon: its parts, optionally mirrored top to bottom. [name] is the AnimateIcons/Lucide name. */
internal class AnimIcon(val name: String, val parts: List<IconPart>, val flipY: Boolean = false) {
    /** How long one play lasts, in seconds. */
    val length: Float by lazy { parts.flatMap { it.tracks }.maxOfOrNull { it.end } ?: 0f }

    fun flippedVertically() = AnimIcon(name, parts, !flipY)

    /** The icon at rest, for every place that takes an ImageVector. */
    val vector: ImageVector by lazy {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        if (flipY) builder.addGroup(scaleY = -1f, pivotX = 12f, pivotY = 12f)
        fun add(part: IconPart) {
            part.paths.forEach { d ->
                builder.addPath(
                    pathData = addPathNodes(d),
                    fill = if (part.fill) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            part.children.forEach(::add)
        }
        parts.forEach(::add)
        if (flipY) builder.clearGroup()
        builder.build().also { AnimIconRegistry.register(it, this) }
    }
}

/** From a static vector back to the icon that can play, so ImageVector call sites can animate. */
internal object AnimIconRegistry {
    private val byVector = java.util.IdentityHashMap<ImageVector, AnimIcon>()
    @Synchronized fun register(vector: ImageVector, icon: AnimIcon) { byVector[vector] = icon }
    @Synchronized fun of(vector: ImageVector): AnimIcon? = byVector[vector]
}

internal fun DrawScope.drawAnimIcon(icon: AnimIcon, seconds: Float, color: Color) {
    val unit = size.minDimension / 24f
    val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    withTransform({
        translate((size.width - 24f * unit) / 2, (size.height - 24f * unit) / 2)
        scale(unit, unit, Offset.Zero)
        if (icon.flipY) scale(1f, -1f, Offset(12f, 12f))
    }) {
        icon.parts.forEach { drawPart(it, seconds, color, 1f, stroke) }
    }
}

private fun DrawScope.drawPart(part: IconPart, s: Float, color: Color, alpha: Float, stroke: Stroke) {
    val pivot = part.pivot ?: part.centre
    val a = alpha * (part.opacity?.at(s) ?: 1f).coerceIn(0f, 1f)
    val uniform = part.scale?.at(s) ?: 1f
    val sx = uniform * (part.scaleX?.at(s) ?: 1f)
    val sy = uniform * (part.scaleY?.at(s) ?: 1f)
    withTransform({
        translate(part.x?.at(s) ?: 0f, part.y?.at(s) ?: 0f)
        rotate(part.rotate?.at(s) ?: 0f, pivot)
        scale(sx, sy, pivot)
    }) {
        val trim = part.trim?.at(s)?.coerceIn(0f, 1f)
        part.parsed.forEach { path ->
            val shown = if (trim == null || trim >= 1f) path else trimmed(path, trim)
            if (part.fill) drawPath(shown, color, a, Fill)
            drawPath(shown, color, a, stroke)
        }
        part.children.forEach { drawPart(it, s, color, a, stroke) }
    }
}

/**
 * The first [fraction] of [path]. The measure and the output are reused; icons
 * are drawn on the main thread only, one path at a time, so one pair suffices.
 */
private val trimMeasure by lazy { PathMeasure() }
private val trimOut by lazy { Path() }

private fun trimmed(path: Path, fraction: Float): Path {
    trimOut.reset()
    if (fraction <= 0f) return trimOut
    trimMeasure.setPath(path, false)
    trimMeasure.getSegment(0f, trimMeasure.length * fraction, trimOut, true)
    return trimOut
}

/**
 * Draws [icon] and plays it once each time [play] changes (not on first
 * composition). Tint and size follow [Icon]'s conventions.
 */
@Composable
internal fun AnimIconImage(
    icon: AnimIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    play: Any? = null,
) {
    val time = remember(icon) { Animatable(0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(icon, play) {
        if (first) { first = false; return@LaunchedEffect }
        if (!AppIconStyle.animate) return@LaunchedEffect
        time.snapTo(0f)
        time.animateTo(icon.length, tween((icon.length * 1000).toInt().coerceAtLeast(1), easing = LinearEasing))
        time.snapTo(0f)
    }
    Canvas(
        modifier.size(24.dp).then(
            if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier,
        ),
    ) { drawAnimIcon(icon, time.value, tint) }
}

/**
 * [Icon] for any vector: one that came from an [AnimIcon] is drawn by the
 * player, so it can move when [play] changes; anything else is drawn as is.
 */
@Composable
internal fun AppIcon(
    vector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    play: Any? = null,
) {
    val anim = AnimIconRegistry.of(vector)
    if (anim != null) AnimIconImage(anim, contentDescription, modifier, tint, play)
    else Icon(vector, contentDescription, modifier, tint)
}

/** Which icon set the toolbar uses: Reicon's outlines, or AnimateIcons, which also move when used. */
internal object AppIconStyle {
    var animated by mutableStateOf(true)
        private set

    /** Whether icons play their animation; off keeps the AnimateIcons shapes still. */
    var animate by mutableStateOf(true)
        private set

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("pens", android.content.Context.MODE_PRIVATE)

    fun load(context: android.content.Context) {
        animated = prefs(context).getBoolean("iconAnimateIcons", true)
        animate = prefs(context).getBoolean("iconAnimation", true)
    }

    fun setAnimated(context: android.content.Context, on: Boolean) {
        animated = on
        prefs(context).edit().putBoolean("iconAnimateIcons", on).apply()
    }

    fun setAnimate(context: android.content.Context, on: Boolean) {
        animate = on
        prefs(context).edit().putBoolean("iconAnimation", on).apply()
    }

    /** [lucide] when AnimateIcons is chosen, else [reicon]. Reads snapshot state, so callers recompose on a switch. */
    fun pick(lucide: AnimIcon, reicon: ImageVector): ImageVector = if (animated) lucide.vector else reicon
}

/** [androidx.compose.material3.IconButton] whose icon plays once per tap. */
@Composable
internal fun AppIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    var taps by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.material3.IconButton(onClick = { taps++; onClick() }, modifier = modifier, enabled = enabled) {
        AppIcon(icon, contentDescription, tint = tint ?: LocalContentColor.current, play = taps)
    }
}
