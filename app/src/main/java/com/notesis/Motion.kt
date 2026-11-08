package com.notesis

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

/**
 * How the chrome moves, after Apple's: every motion a spring given by how
 * quickly it responds and how much it is damped, as SwiftUI gives them
 * (stiffness (2π / response)², damping ratio as is), never a curve of a
 * fixed length - so a motion caught half way turns from where it is, at the
 * speed it has, and a finger's own speed carries into the spring it lets go.
 */
internal object MotionPrefs {
    /**
     * How fast the chrome moves, 1 as it was tuned; above is quicker. Set
     * here rather than by guess: the springs below were tuned at 1, and the
     * screen's own rate is allowed for separately (see [responseScale]).
     */
    var speed by mutableFloatStateOf(DEFAULT_SPEED)
        private set

    /** A light tick under the finger where something snaps or switches; never a buzz. */
    var haptics by mutableStateOf(true)
        private set

    /** A trace of blur along fast motion on screens that show only 60 frames a second. */
    var motionBlur by mutableStateOf(true)
        private set

    /** The screen's rate, as the activity last read it. */
    var refreshHz by mutableFloatStateOf(60f)

    /**
     * What a spring's response is multiplied by: slower for a slower
     * [speed], and a little longer on a 60 Hz screen, where a motion of the
     * same length moves twice as far between frames (see [lowRateStretch]).
     */
    val responseScale: Float get() = lowRateStretch(refreshHz) / speed.coerceIn(SPEED_RANGE)

    private fun prefs(context: Context) = context.getSharedPreferences("pens", Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        speed = p.getFloat(KEY_SPEED, DEFAULT_SPEED).takeIf { it.isFinite() }?.coerceIn(SPEED_RANGE) ?: DEFAULT_SPEED
        haptics = p.getBoolean(KEY_HAPTICS, true)
        motionBlur = p.getBoolean(KEY_BLUR, true)
    }

    fun setSpeed(context: Context, value: Float) {
        speed = value.coerceIn(SPEED_RANGE)
        prefs(context).edit().putFloat(KEY_SPEED, speed).apply()
    }

    fun setHaptics(context: Context, on: Boolean) {
        haptics = on
        prefs(context).edit().putBoolean(KEY_HAPTICS, on).apply()
    }

    fun setMotionBlur(context: Context, on: Boolean) {
        motionBlur = on
        prefs(context).edit().putBoolean(KEY_BLUR, on).apply()
    }

    const val DEFAULT_SPEED = 1f
    val SPEED_RANGE = 0.7f..1.4f
    private const val KEY_SPEED = "motionSpeed"
    private const val KEY_HAPTICS = "motionHaptics"
    private const val KEY_BLUR = "motionBlur"
}

/**
 * How much longer a spring takes on a screen of [hz]: at 60 a motion of the
 * same length jumps twice as far from frame to frame as at 120, and reads as
 * a judder; an eighth longer keeps each step small without feeling slow.
 */
internal fun lowRateStretch(hz: Float): Float = if (hz in 1f..75f) 1.12f else 1f

/** Stiffness per unit mass for a spring that responds in [responseSeconds]: (2π / response)². */
internal fun springStiffness(responseSeconds: Float): Float {
    val omega = 2.0 * PI / responseSeconds.coerceAtLeast(0.01f)
    return (omega * omega).toFloat()
}

/** A spring of [response] seconds and [damping] ratio, at the motion speed and the screen's rate. */
internal fun <T> motionSpring(response: Float, damping: Float, visibilityThreshold: T? = null): SpringSpec<T> =
    spring(dampingRatio = damping, stiffness = springStiffness(response * MotionPrefs.responseScale), visibilityThreshold = visibilityThreshold)

/**
 * The chrome's four springs. Spatial ones move things - a button, a panel,
 * a sheet against the screen's edge, which never overshoots its place;
 * effects change colour and opacity, and never overshoot either.
 */
internal object MotionTokens {
    fun <T> spatialFast(): SpringSpec<T> = motionSpring(0.25f, 0.85f)
    fun <T> spatial(): SpringSpec<T> = motionSpring(0.36f, 0.86f)
    fun <T> spatialSlow(): SpringSpec<T> = motionSpring(0.46f, 1f)
    fun <T> effects(): SpringSpec<T> = motionSpring(0.22f, 1f)
}

/**
 * [this] at the motion speed and the screen's rate: a spring's stiffness
 * and a curve's length move together with them. Anything else as it is.
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> AnimationSpec<T>.paced(): AnimationSpec<T> {
    val scale = MotionPrefs.responseScale
    if (abs(scale - 1f) < 0.001f) return this
    return when (this) {
        is SpringSpec<*> -> spring(dampingRatio, stiffness / (scale * scale), (this as SpringSpec<T>).visibilityThreshold)
        is TweenSpec<*> -> tween((durationMillis * scale).toInt(), (delay * scale).toInt(), easing)
        else -> this
    }
}

/**
 * Where a throw would come to rest, as a scroll view projects it: from
 * [position] at [velocity] units a second, slowing by [decelerationRate] a
 * millisecond (0.998, iOS's normal). What a release is decided by, rather
 * than where the finger happened to stop.
 */
internal fun projectedEnd(position: Float, velocity: Float, decelerationRate: Float = 0.998f): Float =
    position + velocity / 1000f * decelerationRate / (1f - decelerationRate)

/**
 * How far something pulled [offset] past its limit is drawn, iOS's rubber
 * band: (1 − 1 / (x c / d + 1)) d, with [dimension] d and stiffness [c].
 * It follows the finger at first and stiffens, never reaching d.
 */
internal fun rubberBand(offset: Float, dimension: Float, c: Float = 0.55f): Float {
    if (dimension <= 0f || offset == 0f) return 0f
    val x = abs(offset)
    return sign(offset) * (1f - 1f / (x * c / dimension + 1f)) * dimension
}

/**
 * How far ahead of a dragged finger to draw what it drags, so the thing does
 * not trail it by a frame: [velocity] px a second over [leadSeconds], held to
 * [maxPx]. Only while the finger is down; a release takes the true place.
 */
internal fun dragLead(velocity: Float, leadSeconds: Float = 0.012f, maxPx: Float): Float =
    (velocity * leadSeconds).coerceIn(-maxPx, maxPx)

/**
 * The blur along a motion of [velocityPx] px a second on a screen of [hz]:
 * a fifth of the step between two frames, nothing under half a pixel, never
 * more than [maxPx]. At 120 Hz the steps are small and none is drawn.
 */
internal fun motionBlurPx(velocityPx: Float, hz: Float, maxPx: Float): Float {
    if (hz > 75f || hz <= 0f) return 0f
    val step = abs(velocityPx) / hz
    val blur = step * 0.2f
    return if (blur < 0.5f) 0f else blur.coerceAtMost(maxPx)
}

/**
 * Whether the chrome is in motion: a popup opening or closing, a sidebar
 * sliding. While it is, the page behind is not recorded again for the glass
 * (it is not changing), and the frames are counted apart (see [MotionStats]).
 */
internal object ChromeMotion {
    var moving by mutableIntStateOf(0)
        private set

    val busy: Boolean get() = moving > 0

    /** Counts a motion that is not one suspending call, such as an AnimatedVisibility; see [HoldChromeMotion]. */
    fun hold() { moving++ }
    fun release() { moving = (moving - 1).coerceAtLeast(0) }

    /** Runs [block] counted as motion. */
    suspend fun <T> during(block: suspend () -> T): T {
        moving++
        try {
            return block()
        } finally {
            moving = (moving - 1).coerceAtLeast(0)
        }
    }
}

/**
 * Light taps under the finger, kept small: a clock tick where something
 * lands on a step or a switch flips. The system's own haptics setting still
 * decides whether anything is felt at all.
 */
internal object Haptics {
    fun tick(view: View) {
        if (!MotionPrefs.haptics) return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Where a drag lands on a resting place or crosses a point of no return. */
    fun snap(view: View) {
        if (!MotionPrefs.haptics) return
        val kind = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        view.performHapticFeedback(kind)
    }
}

/**
 * Frames drawn while the chrome was moving, apart from the rest: how many,
 * how many came late (past one and a half frames), and the longest. Shown in
 * Diagnostics, so a change to the motion can be judged by what it does on
 * the tablet rather than by eye.
 */
internal object MotionStats {
    var frames by mutableIntStateOf(0)
        private set
    var late by mutableIntStateOf(0)
        private set
    var worstMs by mutableFloatStateOf(0f)
        private set

    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private val main = Handler(android.os.Looper.getMainLooper())

    fun start(activity: Activity) {
        if (listener != null) return
        val handlerThread = thread ?: HandlerThread("motion-frames").also { it.start(); thread = it }
        val interval = 1_000_000_000L / (activity.display?.refreshRate ?: 60f).coerceAtLeast(1f).toLong()
        val watch = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (!ChromeMotion.busy) return@OnFrameMetricsAvailableListener
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val isLate = total > interval * 3 / 2
            val ms = total / 1_000_000f
            main.post {
                frames++
                if (isLate) late++
                if (ms > worstMs) worstMs = ms
            }
        }
        runCatching { activity.window.addOnFrameMetricsAvailableListener(watch, Handler(handlerThread.looper)) }
            .onSuccess { listener = watch }
    }

    fun stop(activity: Activity) {
        val watch = listener ?: return
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(watch) }
        listener = null
    }

    fun reset() {
        frames = 0
        late = 0
        worstMs = 0f
    }
}

/** Whether the glass has been drawn once in this process (see [GlassWarmup]). */
private object GlassWarm {
    var done = false
}

/**
 * Draws one tiny, all but invisible pane of the chrome's glass a moment
 * after the screen settles, as Mornye prepares its glass before it is
 * needed: the lens's shader and the blur are built then, not on the frame a
 * popup first opens, which used to be the one that dropped. Once a process,
 * and only while nothing is being written or moved.
 */
@androidx.compose.runtime.Composable
internal fun GlassWarmup() {
    var show by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (GlassWarm.done) return@LaunchedEffect
        kotlinx.coroutines.delay(WARMUP_DELAY_MS)
        while (InteractionGovernor.observed != Interaction.IDLE || ChromeMotion.busy) kotlinx.coroutines.delay(200)
        show = true
        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
        show = false
        GlassWarm.done = true
    }
    if (show) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .size(24.dp)
                .graphicsLayer { alpha = 0.01f }
                .skinMaterial(androidx.compose.foundation.shape.RoundedCornerShape(12.dp), panel = true, role = ChromeRole.POPUP),
        )
    }
}

private const val WARMUP_DELAY_MS = 1200L

/** Counts the chrome as moving while [running]; for motions the app does not drive itself. */
@androidx.compose.runtime.Composable
internal fun HoldChromeMotion(running: Boolean) {
    androidx.compose.runtime.DisposableEffect(running) {
        if (running) ChromeMotion.hold()
        onDispose { if (running) ChromeMotion.release() }
    }
}

/**
 * Every panel that slides in from an edge - settings, pages, layers - on the
 * same springs: in critically damped, so it never opens a gap at the edge it
 * stands on, out a little quicker.
 */
internal fun <T> sidebarOpenSpring(visibilityThreshold: T? = null): androidx.compose.animation.core.SpringSpec<T> =
    motionSpring(SIDEBAR_IN_RESPONSE, 1f, visibilityThreshold)

internal fun <T> sidebarCloseSpring(visibilityThreshold: T? = null): androidx.compose.animation.core.SpringSpec<T> =
    motionSpring(SIDEBAR_OUT_RESPONSE, 1f, visibilityThreshold)

internal const val SIDEBAR_IN_RESPONSE = 0.42f
internal const val SIDEBAR_OUT_RESPONSE = 0.3f
