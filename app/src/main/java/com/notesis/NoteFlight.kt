package com.notesis

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * A note opening out of its card and going back into it (Todo.md, A1).
 *
 * Tapped, the card's picture grows from where the card is to the whole
 * screen; only then is the note put under it, and the picture fades once the
 * note has shown its page. Closed, the picture starts over the whole screen
 * and shrinks into the card it came from, where the library has put it back.
 *
 * The note is put in place after the growth rather than during it: building
 * the note screen is the longest frame the app draws, and a growth running
 * over it stopped half way.
 */

/** One flight of a note's picture between its card and the screen. */
internal class NoteFlight(
    val noteId: String,
    /** The card, in the root's coordinates; null where it is not on screen. */
    val card: Rect?,
    /** The card's picture; null for one without (a Markdown note's words, say), which flies as paper. */
    val picture: Bitmap?,
    val opening: Boolean,
) {
    /** Runs once the picture has filled the screen: the note is put under it. */
    var onArrived: () -> Unit = {}
    /** Where a closing flight lands, once the library has placed the card again. */
    var landing by mutableStateOf<Rect?>(null)
    /** Set when the library has looked for the card and it is not on screen. */
    var noCard by mutableStateOf(false)
}

internal object NoteFlights {
    /** The flight on screen, if any. */
    var current by mutableStateOf<NoteFlight?>(null)
        private set

    /** What the library recorded as a card was tapped, until the note is opened. */
    private var pending: NoteFlight? = null

    /** The library: this card was tapped, and is where its note will come out of. */
    fun tapped(noteId: String, card: Rect?, picture: Bitmap?) {
        pending = NoteFlight(noteId, card, picture, opening = true)
    }

    /**
     * Opens [noteId] out of its card if the card was just tapped and the
     * chrome may move: [open] runs when the picture has grown. Returns false,
     * having done nothing, otherwise - the caller then opens the note at once.
     */
    fun open(noteId: String, moves: Boolean, open: () -> Unit): Boolean {
        val flight = pending?.takeIf { it.noteId == noteId && it.card != null }
        pending = null
        if (flight == null || !moves) return false
        flight.onArrived = open
        current = flight
        return true
    }

    /** A note is closing: its picture is laid over the library and shrinks into the card. */
    fun close(noteId: String, picture: Bitmap?, moves: Boolean) {
        pending = null
        current = if (moves) NoteFlight(noteId, card = null, picture = picture, opening = false) else null
    }

    /** The library has placed its cards: where the closing note's card is, or null if it is not on screen. */
    fun landed(noteId: String, card: Rect?) {
        val flight = current?.takeIf { !it.opening && it.noteId == noteId } ?: return
        if (card == null) flight.noCard = true else flight.landing = card
    }

    internal fun finished(flight: NoteFlight) { if (current === flight) current = null }
}

/**
 * Where a flight's picture is at [progress]: 0 at the card, 1 the whole
 * screen [full], each edge on a straight line between. With no card the
 * picture comes from or goes to a small rectangle in the middle.
 */
internal fun flightRect(card: Rect?, full: Rect, progress: Float): Rect {
    val from = card ?: Rect(full.center.x - full.width * 0.2f, full.center.y - full.height * 0.2f,
        full.center.x + full.width * 0.2f, full.center.y + full.height * 0.2f)
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    fun lerp(a: Float, b: Float) = a + (b - a) * p
    return Rect(lerp(from.left, full.left), lerp(from.top, full.top), lerp(from.right, full.right), lerp(from.bottom, full.bottom))
}

/** Out to the screen: Material's emphasized decelerate, quick away and soft landing. */
private val FLIGHT_OUT: AnimationSpec<Float> get() = tween<Float>(FLIGHT_MS, easing = EmphasizedDecelerateEasing).paced()

/** Back into the card, a little quicker. */
private val FLIGHT_BACK: AnimationSpec<Float> get() = tween<Float>(FLIGHT_BACK_MS, easing = EmphasizedDecelerateEasing).paced()

private const val FLIGHT_MS = 300
private const val FLIGHT_BACK_MS = 260
private const val FADE_MS = 160

/** How long the picture waits for the note's page before uncovering it anyway. */
private const val PAGE_WAIT_MS = 700L

/** How long a closing picture waits for the library to place the card. */
private const val CARD_WAIT_MS = 300L

/** A card's corner, which the picture's corners come from and go back to. */
private val CARD_CORNER: Dp = Ui.Corner.card

/**
 * The flight, over everything else in the window. Takes every touch while it
 * is up: a tap on the library under a growing note opened another one.
 */
@Composable
internal fun NoteFlightLayer(paper: Color) {
    val flight = NoteFlights.current ?: return
    val progress = remember(flight) { Animatable(if (flight.opening) 0f else 1f) }
    val fade = remember(flight) { Animatable(1f) }
    LaunchedEffect(flight) {
        ChromeMotion.during {
            if (flight.opening) {
                progress.animateTo(1f, FLIGHT_OUT)
                val shownBefore = NoteOpenClock.pagesShown
                flight.onArrived()
                // Uncovered once the note has shown its page, or after a while
                // whatever it is doing, so a slow note is not hidden behind a picture.
                val start = System.nanoTime()
                while (NoteOpenClock.pagesShown == shownBefore && (System.nanoTime() - start) / 1_000_000 < PAGE_WAIT_MS) {
                    withFrameNanos { }
                }
                fade.animateTo(0f, tween<Float>(FADE_MS).paced())
            } else {
                val start = System.nanoTime()
                while (flight.landing == null && !flight.noCard && (System.nanoTime() - start) / 1_000_000 < CARD_WAIT_MS) {
                    withFrameNanos { }
                }
                if (flight.landing == null) {
                    // The card is off screen: the picture goes towards the middle and fades on the way.
                    coroutineScope {
                        launch { progress.animateTo(0f, FLIGHT_BACK) }
                        launch { fade.animateTo(0f, tween<Float>(FLIGHT_BACK_MS).paced()) }
                    }
                } else {
                    progress.animateTo(0f, FLIGHT_BACK)
                    fade.animateTo(0f, tween<Float>(FADE_MS / 2).paced())
                }
            }
        }
        NoteFlights.finished(flight)
    }
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(flight) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }) {
        val full = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val cornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { CARD_CORNER.toPx() }
        Box(
            Modifier
                // Placed in the layout, read from the animation there: a frame
                // of the flight is a move, not a composition.
                .layout { measurable, _ ->
                    val r = flightRect(if (flight.opening) flight.card else flight.landing, full, progress.value)
                    val w = r.width.roundToInt().coerceAtLeast(1)
                    val h = r.height.roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
                }
                .graphicsLayer {
                    alpha = fade.value
                    val p = progress.value.coerceIn(0f, 1f)
                    shape = RoundedCornerShape(cornerPx * (1f - p))
                    clip = true
                }
                .background(paper),
        ) {
            flight.picture?.let { bitmap ->
                Image(
                    remember(bitmap) { bitmap.asImageBitmap() }, contentDescription = null,
                    // The page from its top, as the card shows it.
                    contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
