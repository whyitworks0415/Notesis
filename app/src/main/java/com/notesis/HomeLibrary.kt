package com.notesis

import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min

/** A folder's key among the library's tiles; a note's is its id. */
internal fun folderKey(name: String): String = "folder:$name"

internal fun isFolderKey(key: String): Boolean = key.startsWith("folder:")

internal fun folderOfKey(key: String): String = key.removePrefix("folder:")

/**
 * Where each tile that can be selected sits on screen, so a finger dragging
 * across the library can tell which one it is over. Plain maps rather than
 * state: they change on every frame of a scroll and nothing draws from them.
 */
internal class LibraryTiles {
    /**
     * Where each tile is, as its coordinates: its bounds are worked out only
     * when a finger asks. Worked out on every frame a tile moved, a screen
     * of them was a bounds sum and a new rectangle each per scroll frame.
     */
    val placed = HashMap<String, LayoutCoordinates>()
    /** The grid's own top-left in the root, for its pointer positions. */
    var origin: Offset = Offset.Zero
    /** Set while a drag selects, to pick again where the finger is once the grid has scrolled under it. */
    var reselect: (() -> Unit)? = null

    fun keyAt(local: Offset): String? =
        tileUnder(local + origin, placed.entries.asSequence().filter { it.value.isAttached }.map { it.key to it.value.boundsInRoot() })
}

/** Which of [tiles] (each a key and its bounds in the root) is under the root point [at]. */
internal fun tileUnder(at: Offset, tiles: Sequence<Pair<String, Rect>>): String? =
    tiles.firstOrNull { it.second.contains(at) }?.first

/** Reports this tile's bounds to [tiles] for as long as it is on screen. */
@Composable
internal fun Modifier.libraryTile(tiles: LibraryTiles, key: String): Modifier {
    DisposableEffect(tiles, key) { onDispose { tiles.placed.remove(key) } }
    return onPlaced { tiles.placed[key] = it }
}

/**
 * Hold a tile to select it, then keep the finger down and slide to select
 * every tile between it and the one under the finger - as Photos and Files
 * do. Starting on a tile that is already selected takes the range away
 * instead. Near the top or bottom edge the grid scrolls on its own
 * ([onEdge] gets pixels per frame, 0 to stop).
 *
 * The hold is watched before the tiles see it, and once it is a selection
 * every touch after it is taken, so the tile under the finger never also
 * reads it as a tap that opens it or toggles it back. A tap, or a finger
 * that moves before the hold, is left alone: it opens, or scrolls.
 */
@Composable
internal fun Modifier.dragToSelect(
    tiles: LibraryTiles,
    order: () -> List<String>,
    selection: () -> Set<String>,
    onSelection: (Set<String>) -> Unit,
    onHold: () -> Unit,
    edgeTop: () -> Float,
    edgeBottom: () -> Float,
    onEdge: (Float) -> Unit,
    /**
     * Held on a note that is already selected, the drag carries the selected
     * notes instead of selecting: let go over a folder's tile and they are
     * moved into it. [onCarry] follows the finger (null when it is over).
     */
    onDropOnFolder: ((Set<String>, String) -> Unit)? = null,
    onCarry: (Offset?) -> Unit = {},
): Modifier {
    // The gesture outlives a recomposition; it reads the latest of each.
    val order by rememberUpdatedState(order)
    val selection by rememberUpdatedState(selection)
    val onSelection by rememberUpdatedState(onSelection)
    val onHold by rememberUpdatedState(onHold)
    val edgeTop by rememberUpdatedState(edgeTop)
    val edgeBottom by rememberUpdatedState(edgeBottom)
    val onEdge by rememberUpdatedState(onEdge)
    val onDropOnFolder by rememberUpdatedState(onDropOnFolder)
    val onCarry by rememberUpdatedState(onCarry)
    return onGloballyPositioned { tiles.origin = it.positionInRoot() }.pointerInput(tiles) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val startKey = tiles.keyAt(down.position) ?: return@awaitEachGesture
        // Whatever ends the wait before the hold time - a lift, a second
        // finger, a move past slop, someone else taking it - is not a hold.
        val notHeld = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var ended = false
            while (!ended) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                ended = event.changes.size > 1 || change == null || !change.pressed || change.isConsumed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            }
            true
        }
        if (notHeld != null) return@awaitEachGesture
        onHold()
        val keys = order()
        val anchor = keys.indexOf(startKey)
        if (anchor < 0) return@awaitEachGesture
        val base = selection()
        val drop = onDropOnFolder
        if (drop != null && startKey in base && !isFolderKey(startKey)) {
            // Carrying what is selected: the grid scrolls at the edges as when selecting.
            var at = down.position
            onCarry(at)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    event.changes.forEach { it.consume() }
                    if (change == null) return@awaitEachGesture
                    if (!change.pressed) break
                    at = change.position
                    onCarry(at)
                    val top = edgeTop() + EDGE_ZONE.toPx()
                    val bottom = edgeBottom() - EDGE_ZONE.toPx()
                    val fastest = EDGE_SPEED.toPx()
                    onEdge(when {
                        at.y < top -> -fastest * ((top - at.y) / EDGE_ZONE.toPx()).coerceIn(0f, 1f)
                        at.y > bottom -> fastest * ((at.y - bottom) / EDGE_ZONE.toPx()).coerceIn(0f, 1f)
                        else -> 0f
                    })
                }
                val target = tiles.keyAt(at)
                if (target != null && isFolderKey(target)) {
                    drop(base.filterNot(::isFolderKey).toSet(), folderOfKey(target))
                }
            } finally {
                onEdge(0f)
                onCarry(null)
            }
            return@awaitEachGesture
        }
        val adding = startKey !in base
        var last = down.position
        fun pick(at: Offset) {
            val key = tiles.keyAt(at) ?: return
            val i = keys.indexOf(key)
            if (i < 0) return
            val range = keys.subList(min(anchor, i), max(anchor, i) + 1).toSet()
            onSelection(if (adding) base + range else base - range)
        }
        tiles.reselect = { pick(last) }
        pick(last)
        val edge = EDGE_ZONE.toPx()
        val fastest = EDGE_SPEED.toPx()
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                event.changes.forEach { it.consume() }
                if (change == null || !change.pressed) break
                last = change.position
                pick(last)
                val top = edgeTop() + edge
                val bottom = edgeBottom() - edge
                onEdge(when {
                    last.y < top -> -fastest * ((top - last.y) / edge).coerceIn(0f, 1f)
                    last.y > bottom -> fastest * ((last.y - bottom) / edge).coerceIn(0f, 1f)
                    else -> 0f
                })
            }
        } finally {
            onEdge(0f)
            tiles.reselect = null
        }
    }
    }
}

private val EDGE_ZONE = 64.dp
/** The fastest the grid scrolls under a finger held at an edge, per frame. */
private val EDGE_SPEED = 22.dp

/**
 * The library's title: the place in one line, with how much is there beside
 * it and room at the end for what goes with it (the folders' show/hide). It
 * scrolls up with the notes; the bar above takes the title once this has
 * gone under it.
 */
@Composable
internal fun LibraryLargeTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp,
                lineHeight = 28.sp, letterSpacing = (-0.2).sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Ui.Space.s),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** A section's name in the library: small, bold and set apart, as the list's own headings are. */
@Composable
internal fun LibrarySection(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = modifier.padding(top = Ui.Space.s).semantics { heading() },
    )
}

/**
 * A section's heading that folds it away: a chevron pointing right while
 * it is folded and down while it is open, the name, and how many it holds.
 */
@Composable
internal fun LibrarySectionToggle(
    text: String,
    count: Int,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val turn by animateFloatAsState(if (expanded) 90f else 0f, Ui.Motion.spring(), label = "sectionChevron")
    Row(
        modifier.fillMaxWidth().heightIn(min = Ui.Size.touchCompact).clip(Ui.Shape.chip)
            // No press highlight: the chevron turning is the answer to the tap.
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onToggle)
            .padding(top = Ui.Space.s).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Reicons.ChevronRight, contentDescription = if (expanded) "Fold $text" else "Open $text",
            modifier = Modifier.size(Ui.Icon.regular).graphicsLayer { rotationZ = turn })
        Spacer(Modifier.width(Ui.Space.xs))
        Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.width(Ui.Space.s))
        Text("$count", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The library's floating bar: everything, or folders, handwritten notes or Markdown alone. */
@Composable
internal fun LibraryFilterBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(
        "All" to Reicons.Category,
        "Folders" to Reicons.Folder,
        "Notes" to Reicons.Edit,
        "Markdown" to Reicons.Description,
    )
    val chosen = selected.coerceIn(0, items.lastIndex)
    val scheme = MaterialTheme.colorScheme
    val corner = chromeCorner()
    val barHeight = 64.dp
    val padding = 6.dp
    val cellHeight = barHeight - padding * 2
    val pill = RoundedCornerShape(toolPillRadius(corner, barHeight, padding))
    // A neutral wash, never the accent (see [selectionPillFill]).
    val highlight = selectionPillFill(scheme.onSurface, scheme.surface.luminance() < 0.5f)
    val moves = rememberMotionAllowed() && !LocalSkinSettings.current.highContrast
    val slide = remember { Animatable(chosen.toFloat()) }
    LaunchedEffect(chosen, moves) {
        // Eased across and landing without a bounce (see [selectionEase]).
        if (moves) slide.animateTo(chosen.toFloat(), selectionEase()) else slide.snapTo(chosen.toFloat())
    }
    BoxWithConstraints(modifier.widthIn(max = 420.dp).fillMaxWidth(0.9f).height(barHeight).selectableGroup()) {
        val cell = (maxWidth - padding * 2).coerceAtLeast(1.dp) / items.size
        Box(Modifier.matchParentSize().skinMaterial(RoundedCornerShape(corner))) {
            // Moved in the draw, to the fraction of a pixel, so its slow end does not creep in steps.
            Box(Modifier
                .graphicsLayer {
                    translationX = (padding + cell * slide.value.coerceIn(0f, items.lastIndex.toFloat())).toPx()
                    translationY = padding.toPx()
                }
                .size(cell, cellHeight)
                .background(highlight, pill))
        }
        EvenCells(vertical = false, Modifier.matchParentSize().padding(horizontal = padding)) {
            items.forEachIndexed { index, (label, icon) ->
                val on = index == chosen
                val ink = if (on) scheme.primary else scheme.onSurface
                Column(
                    Modifier
                        .semantics { this.selected = on }
                        .clickable(role = Role.Tab, interactionSource = remember { MutableInteractionSource() },
                            indication = null) { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Ui.Space.hair, Alignment.CenterVertically),
                ) {
                    AppIcon(icon, null, tint = ink, modifier = Modifier.size(25.dp))
                    Text(label, color = ink, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The inset between a library card's edge and the page or folder inside it. */
internal val LIBRARY_CARD_INSET = 8.dp

/**
 * A folder among the notes, the same size as a note: a tinted square where
 * a note has its page, with the folder's mark and how many notes it holds,
 * and its name under it where a note has its title. Its ⋮ renames or
 * deletes it.
 */
@Composable
internal fun FolderTile(
    name: String,
    count: Int,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** The folder's own colour, ARGB; 0 for the accent. */
    tint: Int = 0,
    /** A picture shown where the folder's mark is; null for the mark. */
    thumbnail: java.io.File? = null,
    onThumbnail: () -> Unit = {},
    onClearThumbnail: () -> Unit = {},
    onColour: () -> Unit = {},
    /** The line under the name, where a note has its date: what kind of folder it is. */
    subtitle: String = "Folder",
) {
    var menu by remember(name) { mutableStateOf(false) }
    val primary = if (tint != 0) Color(tint) else MaterialTheme.colorScheme.primary
    val picture = rememberFileBitmap(thumbnail, 480)
    val press by animateFloatAsState(if (selected) 0.95f else 1f, Ui.Motion.spring(), label = "folderSelected")
    // A plain card, as the notes beside it are: no shadow under it.
    Box(
        modifier
            // No offscreen layer of its own, as on note cards.
            .graphicsLayer { scaleX = press; scaleY = press }
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, Ui.Shape.card)
            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), Ui.Shape.card)
            .clip(Ui.Shape.card)
            .then(if (selected) Modifier.border(2.5.dp, primary, Ui.Shape.card) else Modifier),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = LIBRARY_CARD_INSET, end = LIBRARY_CARD_INSET, top = LIBRARY_CARD_INSET),
        ) {
            // The same corner as the card round it, as a note's page has.
            val inner = Ui.Shape.card
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(inner)
                    .background(primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                if (picture != null) {
                    androidx.compose.foundation.Image(
                        bitmap = picture.asImageBitmap(), contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    Box(Modifier.align(Alignment.BottomStart).padding(Ui.Space.s).clip(Ui.Shape.badge)
                        .background(primary).padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs)) {
                        Text(countNoun(count, "note"), style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (selectionMode && selected) Reicons.Check else Reicons.Folder,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(Ui.Space.xs))
                    Text(countNoun(count, "note"), style = MaterialTheme.typography.labelLarge, color = primary)
                }
                if (selectionMode) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(Ui.Space.s))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Two lines, as a note's name and date are, so a folder and a note
                // side by side in the grid end at the same height.
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!selectionMode) Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Reicons.MoreVert, contentDescription = "Folder options for $name",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GlassMenu(menu, onDismissRequest = { menu = false }) {
                        GlassMenuItem(
                            text = { Text("Rename folder") },
                            leadingIcon = { Icon(Reicons.Edit, contentDescription = null) },
                            onClick = { menu = false; onRename() },
                        )
                        GlassMenuItem(
                            text = { Text("Folder colour") },
                            leadingIcon = { Icon(Reicons.Palette, contentDescription = null) },
                            onClick = { menu = false; onColour() },
                        )
                        GlassMenuItem(
                            text = { Text("Set thumbnail") },
                            leadingIcon = { Icon(Reicons.Image, contentDescription = null) },
                            onClick = { menu = false; onThumbnail() },
                        )
                        if (thumbnail != null) GlassMenuItem(
                            text = { Text("Remove thumbnail") },
                            onClick = { menu = false; onClearThumbnail() },
                        )
                        GlassMenuItem(
                            text = { Text("Delete folder") },
                            leadingIcon = { Icon(Reicons.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                } else Spacer(Modifier.height(48.dp))
            }
        }
    }
}

/** The round mark on a tile while selecting: filled with a check when it is in, an empty ring when not. */
@Composable
internal fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (selected) primary else Color.Black.copy(alpha = 0.18f))
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Reicons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp))
    }
}

/** One action on the bar shown while selecting: an icon over a short word. */
@Composable
internal fun SelectionAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(
        Modifier
            .clip(Ui.Shape.control)
            .clickable(enabled = enabled, onClick = onClick)
            .widthIn(min = 60.dp)
            .padding(horizontal = Ui.Space.s, vertical = Ui.Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Ui.Icon.regular))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/** The actions for what is selected, in a row that scrolls sideways when the screen is too narrow for all of them. */
@Composable
internal fun SelectionActions(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Ui.Space.xs, vertical = Ui.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.hair),
    ) { content() }
}

/** The height of the library's search, open or folded, and of the round buttons beside it. */
internal val LIBRARY_SEARCH_HEIGHT = 42.dp

/**
 * The library's search, one shape all the way from a field across the top to
 * a round button under the bar's: [fold] runs from 0, the field, to 1, the
 * button. The magnifier sits in a square of the field's own height at its
 * start, so as the field narrows towards the right it is left exactly in the
 * middle of the circle; the words keep their width and are cut off and faded
 * rather than squeezed. The field's fill gives way to the bar's glass as it
 * folds, so the button is of the same stuff as the one above it.
 */
@Composable
internal fun LibrarySearchMorph(
    query: String,
    onQuery: (String) -> Unit,
    fold: () -> Float,
    onIcon: () -> Unit,
    onFocus: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val height = LIBRARY_SEARCH_HEIGHT
    val capsule = RoundedCornerShape(50)
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val full = if (maxWidth < 720.dp) maxWidth else 720.dp
        Box(
            Modifier.layout { measurable, constraints ->
                val side = height.roundToPx()
                val (x, w) = searchMorphSpan(constraints.maxWidth, full.roundToPx(), side, fold())
                val placeable = measurable.measure(Constraints.fixed(w, side))
                layout(constraints.maxWidth, side) { placeable.place(x, 0) }
            },
        ) {
            // Open or folded, the same surface as the buttons beside it, and
            // thinning a little where it runs past its width as it opens.
            Box(Modifier.matchParentSize().graphicsLayer { scaleY = glassSquash(fold()) }
                .skinMaterial(capsule))
            Box(Modifier.matchParentSize().clip(capsule)) {
                Row(
                    Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(full).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(height).clip(CircleShape).clickable(onClick = onIcon),
                        contentAlignment = Alignment.Center) {
                        Icon(Reicons.Search, contentDescription = "Search")
                    }
                    Box(
                        Modifier.weight(1f).padding(end = Ui.Space.m)
                            .graphicsLayer { alpha = (1f - fold() * 1.6f).coerceIn(0f, 1f) },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = onQuery,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                                .onFocusChanged { onFocus(it.isFocused) },
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (query.isEmpty()) Text(
                                        "Search titles, PDFs and handwriting",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    inner()
                                }
                            },
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }, modifier = Modifier.padding(end = Ui.Space.xs)) {
                            Icon(Reicons.Close, contentDescription = "Clear search")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where the search sits in a row [row] wide, as (start, width): [full] wide in
 * the middle at [fold] 0, a [side] square at the row's right end at 1, and
 * every step between on the way, so it slides as it narrows.
 */
internal fun searchMorphSpan(row: Int, full: Int, side: Int, fold: Float): Pair<Int, Int> {
    // Below 0 it is opening past its width, the spring's overshoot; it stays inside the row.
    val f = fold.coerceIn(-GLASS_STRETCH_MAX, 1f)
    val open = full.coerceAtMost(row)
    val w = (open + (side - open) * f).roundToInt().coerceAtMost(row)
    val centred = (row - w) / 2f
    val right = (row - w).toFloat()
    return (centred + (right - centred) * f).roundToInt() to w
}

/**
 * Narrows what it wraps from the start by [fold] of its width, its end staying
 * where it was, and cuts off what no longer fits: buttons drawn one by one
 * into the button at their end.
 */
internal fun Modifier.foldTowardsEnd(fold: () -> Float): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
    // Below 0 the row is opening past its width: the extra is room for the glass to stretch into.
    val w = constraints.constrainWidth((placeable.width * (1f - fold().coerceIn(-GLASS_STRETCH_MAX, 1f))).roundToInt())
    layout(w, placeable.height) { placeable.place(w - placeable.width, 0) }
}

/** The most the home bar's glass stretches past its width while it springs open. */
internal const val GLASS_STRETCH_MAX = 0.15f

/** How tall the glass is while [fold] runs below 0: stretched long, it thins, up to a tenth. */
internal fun glassSquash(fold: Float): Float = 1f - ((-fold).coerceIn(0f, GLASS_STRETCH_MAX) * 0.66f)

/**
 * "3 selected", its number rolling to the next one as the selection grows or
 * shrinks: up for more, down for fewer (Todo.md, A6).
 */
@Composable
internal fun RollingCount(count: Int, modifier: Modifier = Modifier) {
    val moves = rememberMotionAllowed() && !InteractionGovernor.lightMode
    androidx.compose.animation.AnimatedContent(
        targetState = count,
        modifier = modifier,
        transitionSpec = {
            if (!moves) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            else {
                val up = targetState > initialState
                (androidx.compose.animation.slideInVertically(MaterialMotion.selection()) { h -> if (up) h else -h } +
                    androidx.compose.animation.fadeIn(MaterialMotion.effects())) togetherWith
                    (androidx.compose.animation.slideOutVertically(MaterialMotion.selection()) { h -> if (up) -h else h } +
                        androidx.compose.animation.fadeOut(MaterialMotion.effects()))
            }
        },
        label = "selected count",
    ) { shown ->
        Text("$shown selected", style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * A line of loose handwriting drawn once from left to right, as a pen would
 * write it, then left as it is: what an empty library shows. Still where the
 * chrome holds still.
 */
@Composable
internal fun EmptyLibraryStroke(modifier: Modifier = Modifier) {
    val moves = rememberMotionAllowed()
    val drawn = remember { Animatable(if (moves) 0f else 1f) }
    LaunchedEffect(Unit) { if (moves) drawn.animateTo(1f, androidx.compose.animation.core.tween<Float>(EMPTY_STROKE_MS, easing = EmphasizedDecelerateEasing).paced()) }
    val ink = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier) {
        val path = androidx.compose.ui.graphics.Path().apply {
            val w = size.width
            val h = size.height
            val mid = h * 0.55f
            moveTo(w * 0.04f, mid)
            // Loops of cursive, then a long tail.
            var x = w * 0.04f
            val step = w * 0.09f
            repeat(7) { i ->
                val up = if (i % 2 == 0) h * 0.18f else h * 0.32f
                cubicTo(x + step * 0.25f, up, x + step * 0.75f, up, x + step, mid)
                x += step
            }
            quadraticTo(w * 0.86f, h * 0.95f, w * 0.96f, h * 0.4f)
        }
        val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
        val part = androidx.compose.ui.graphics.Path()
        measure.getSegment(0f, measure.length * drawn.value.coerceIn(0f, 1f), part, true)
        drawPath(part, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

private const val EMPTY_STROKE_MS = 1100
