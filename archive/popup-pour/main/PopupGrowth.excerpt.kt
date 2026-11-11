@Stable
/**
 * Lets the frame that first composes and draws something new go by before
 * anything is timed from it. It waited for the frame after as well, which on
 * a 60 Hz screen held every popup and panel still for a frame more than
 * needed: the beat between a tap and the first movement.
 */
internal suspend fun awaitFirstFrames() {
    androidx.compose.runtime.withFrameNanos { }
}

/**
 * Two frames: the one a popup's window is added in and the one it is first
 * laid out and drawn in. Something moving that starts after them does not
 * stall halfway for the popup.
 */
internal suspend fun awaitPopupFrames() {
    androidx.compose.runtime.withFrameNanos { }
    androidx.compose.runtime.withFrameNanos { }
}

internal class PopupGrowth(shown: Boolean) {
    val width = Animatable(if (shown) 1f else 0f)
    val height = Animatable(if (shown) 1f else 0f)
    // Animatable clears velocity when its coroutine is cancelled. Keep the
    // last frame's sample so a reversal starts with the motion it interrupted.
    private val velocities = floatArrayOf(0f, 0f)

    /**
     * Read in a draw: the glass inside the popup redraws, and looks at the
     * page again from where it now is, on every frame either axis moves.
     */
    val motion: () -> Float = { width.value + height.value }

    /** A recipe's own way in and out (see [openProfile]); null is this app's. Set as the popup is composed. */
    var profile: OpenProfile? = null

    /** Whether the last move was a close, so the scale it is drawn at is the closing one. */
    var closing = false
        private set

    /** Whether the popup is anywhere but fully open: on its way, or shut. */
    fun moving(w: Float, h: Float): Boolean = w < 1f || h < 1f || width.isRunning || height.isRunning

    suspend fun open(animate: Boolean) {
        closing = false
        // Opening from nothing: the popup's first frame - composing, measuring
        // and drawing all of it, its glass included - is the slow one, and a
        // curve started on it lost its first steps to that frame and jumped.
        // It goes out unseen; the curve starts on the frame after.
        if (animate && width.value == 0f && height.value == 0f) awaitFirstFrames()
        val spec = profile?.open ?: POPUP_OPEN
        // Opened again while closing: on from where it is, at the speed it has.
        val resumed = midFlight()
        move(1f, animate, spec, spec, if (resumed) Float.NaN else profile?.openVelocity ?: 0f,
            widthDelayMs = if (resumed) 0 else profile?.widthDelayMs ?: 0)
    }

    /** At rest and shut. */
    fun isShut(): Boolean = width.value == 0f && height.value == 0f && !width.isRunning && !height.isRunning

    /** Neither shut nor fully open: a move was caught on its way. */
    private fun midFlight(): Boolean =
        width.isRunning || height.isRunning || width.value in 0.001f..0.999f || height.value in 0.001f..0.999f

    suspend fun close(animate: Boolean) {
        closing = true
        // Already shut: nothing to move. Every note card's menu is composed
        // shut, and each one ran a whole close from 0 to 0 as its card
        // scrolled in - 240 ms of frames counted as chrome motion, which the
        // library's recording reads in its draw, so the list was drawn again
        // over and over while it scrolled.
        if (isShut()) return
        val spec = profile?.close ?: POPUP_CLOSE
        // Caught while still opening, it turns round at the speed it has
        // rather than being thrown back at the profile's own.
        val throwBack = if (midFlight()) Float.NaN else profile?.closeVelocity ?: 0f
        move(0f, animate, profile?.closeWidth ?: spec, spec, throwBack)
    }

    private suspend fun move(
        target: Float, animate: Boolean,
        widthSpec: androidx.compose.animation.core.AnimationSpec<Float>,
        heightSpec: androidx.compose.animation.core.AnimationSpec<Float>,
        velocity: Float,
        widthDelayMs: Int = 0,
    ) {
        if (!animate) {
            velocities.fill(0f)
            width.snapTo(target)
            height.snapTo(target)
            return
        }
        // The same curve on both unless the opening pours, so the shape keeps
        // its proportion on the way; a poured one runs down, then spreads.
        // NaN velocity: each axis keeps the speed it is already moving at.
        val widthStart = if (velocity.isNaN()) velocities[0] else velocity
        val heightStart = if (velocity.isNaN()) velocities[1] else velocity
        ChromeMotion.during {
            coroutineScope {
                launch {
                    if (widthDelayMs > 0) kotlinx.coroutines.delay((widthDelayMs * MotionPrefs.responseScale).toLong())
                    width.animateTo(target, widthSpec.paced(), initialVelocity = widthStart) { velocities[0] = this.velocity }
                    velocities[0] = 0f
                }
                launch {
                    height.animateTo(target, heightSpec.paced(), initialVelocity = heightStart) { velocities[1] = this.velocity }
                    velocities[1] = 0f
                }
            }
        }
    }
}

/**
 * A popup's growth: Material's out of the button on its spring, the glass
 * looks' poured as Mornye pours its menus, or a recipe's own opening where
 * that is switched on.
 */
@Composable
internal fun rememberPopupGrowth(shown: Boolean): PopupGrowth {
    val growth = remember { PopupGrowth(shown) }
    val look = LocalSkinSettings.current
    growth.profile = popupOpening(LocalSkin.current, look)
    return growth
}

/** How [skin]'s popups open under [look]; see [rememberPopupGrowth]. */
internal fun popupOpening(skin: Skin, look: SkinSettings): OpenProfile = when (skin) {
    Skin.MATERIAL -> MaterialOpen
    Skin.GLASSMORPHISM -> PourOpen
    Skin.LIQUID_GLASS -> look.openProfile() ?: PourOpen
}

// Material's emphasized accelerate: leaves slowly and is gone quickly.
internal val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
// Material's emphasized decelerate: arrives quickly and lands softly.
internal val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
// Out of the point: fast at first, then a soft landing at full size.
private val POPUP_OPEN = tween<Float>(Ui.Motion.MS, easing = EmphasizedDecelerateEasing)
// Back into it: slow to leave, then drawn in quickly.
private val POPUP_CLOSE = tween<Float>(Ui.Motion.MS, easing = EmphasizedAccelerateEasing)

/**
 * Grows a popup out of [anchor], a point on screen - the button or the tool
 * that opened it - as [growth] runs from 0 to 1, and folds it back into that
 * point as it returns, each axis on its own curve. The scale is a layer over
 * the popup, so the glass inside it has to be told to keep looking at the
 * page from where it now is: see [ProvideMovingBackdrops], whose alignment
 * undoes the two scales separately. Put it outside any room kept for the
 * shadow: while the popup fades this layer is drawn offscreen, and cuts off
 * whatever lies past its own bounds.
 */
@Composable
internal fun Modifier.growOutOf(anchor: () -> Offset?, growth: PopupGrowth): Modifier {
    var origin by remember { mutableStateOf(TransformOrigin(0.5f, 0f)) }
    return this
        // Before the layer, so the bounds are the popup's own, unscaled.
        .onGloballyPositioned { coordinates ->
            val at = anchor() ?: return@onGloballyPositioned
            val corner = coordinates.positionOnScreen()
            val (x, y) = popupGrowOrigin(at.x, at.y, corner.x, corner.y,
                coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
            val next = TransformOrigin(x, y)
            if (next != origin) origin = next
        }
        .graphicsLayer {
            val w = growth.width.value
            val h = growth.height.value
            val profile = growth.profile
            if (profile?.reveal == true) {
                // Nothing scaled: the pane's glass is poured round content that
                // stays its size (see PopupReveal.kt).
                return@graphicsLayer
            }
            if (profile == null) {
                alpha = popupGrowAlpha(w, h)
                scaleX = popupGrowScale(w, POPUP_START_SCALE_X)
                scaleY = popupGrowScale(h, POPUP_START_SCALE_Y)
            } else {
                val start = if (growth.closing) profile.closeScale else profile.startScale
                alpha = profile.alpha(maxOf(w, h))
                scaleX = profile.scaleOf(w, start)
                scaleY = profile.scaleOf(h, start)
                translationY = -profile.dropDp * density * (1f - minOf(w, h).coerceIn(0f, 1f))
            }
            transformOrigin = origin
            // On a 60 Hz screen the fast start of a popup jumps far between
            // frames; a trace of blur along it reads as motion rather than
            // as steps. Gone as it slows, and never at 120 Hz.
            renderEffect = popupMotionBlur(growth, size.width, size.height, 4.dp.toPx())
        }
}

/** The blur along a growing popup's motion, or none; see [motionBlurPx]. */
private fun popupMotionBlur(growth: PopupGrowth, width: Float, height: Float, maxPx: Float): androidx.compose.ui.graphics.RenderEffect? =
    // An edge of a pane scaled about a point inside it moves at about the
    // scale's speed times half the pane's size.
    motionBlurEffect(BlurPart.POPUPS, growth.width.velocity * width / 2f, growth.height.velocity * height / 2f, maxPx)

/**
 * How small a popup is at the point it comes out of: nearly nothing, so it is
 * seen to come from where the finger was, the same on both axes.
 */
private const val POPUP_START_SCALE_X = 0.06f
private const val POPUP_START_SCALE_Y = 0.06f
