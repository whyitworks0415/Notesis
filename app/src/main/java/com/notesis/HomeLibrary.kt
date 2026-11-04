package com.notesis

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
    val bounds = HashMap<String, Rect>()
    /** The grid's own top-left in the root, for its pointer positions. */
    var origin: Offset = Offset.Zero
    /** Set while a drag selects, to pick again where the finger is once the grid has scrolled under it. */
    var reselect: (() -> Unit)? = null

    fun keyAt(local: Offset): String? {
        val at = local + origin
        return bounds.entries.firstOrNull { it.value.contains(at) }?.key
    }
}

/** Reports this tile's bounds to [tiles] for as long as it is on screen. */
@Composable
internal fun Modifier.libraryTile(tiles: LibraryTiles, key: String): Modifier {
    DisposableEffect(tiles, key) { onDispose { tiles.bounds.remove(key) } }
    return onGloballyPositioned { tiles.bounds[key] = it.boundsInRoot() }
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
): Modifier {
    // The gesture outlives a recomposition; it reads the latest of each.
    val order by rememberUpdatedState(order)
    val selection by rememberUpdatedState(selection)
    val onSelection by rememberUpdatedState(onSelection)
    val onHold by rememberUpdatedState(onHold)
    val edgeTop by rememberUpdatedState(edgeTop)
    val edgeBottom by rememberUpdatedState(edgeBottom)
    val onEdge by rememberUpdatedState(onEdge)
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
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = Ui.Space.xs).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp,
                lineHeight = 30.sp, letterSpacing = (-0.2).sp),
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
internal fun LibrarySectionToggle(text: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (expanded) 90f else 0f, Ui.Motion.spring(), label = "sectionChevron")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            // No press highlight: the chevron turning is the answer to the tap.
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onToggle)
            .padding(top = Ui.Space.s).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Reicons.ChevronRight, contentDescription = if (expanded) "Fold $text" else "Open $text",
            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = turn })
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
        SpotiGlassItem("All", Reicons.Category),
        SpotiGlassItem("Folders", Reicons.Folder),
        SpotiGlassItem("Notes", Reicons.Edit),
        SpotiGlassItem("Markdown", Reicons.Description),
    )
    SpotiGlassBar(
        items, selectedIndex = selected.coerceIn(0, items.lastIndex), onSelected = onSelect,
        modifier = modifier.widthIn(max = 420.dp).fillMaxWidth(0.9f),
        showLabels = true, surface = SpotiBarSurface.LIQUID,
    )
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
) {
    var menu by remember(name) { mutableStateOf(false) }
    val primary = if (tint != 0) Color(tint) else MaterialTheme.colorScheme.primary
    val picture = rememberFileBitmap(thumbnail, 480)
    val press by animateFloatAsState(if (selected) 0.95f else 1f, Ui.Motion.spring(), label = "folderSelected")
    // A plain card, as the notes beside it are: no shadow under it.
    Box(
        modifier
            .graphicsLayer { scaleX = press; scaleY = press }
            .clip(Ui.Shape.card)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .then(if (selected) Modifier.border(2.5.dp, primary, Ui.Shape.card) else Modifier),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = LIBRARY_CARD_INSET, end = LIBRARY_CARD_INSET, top = LIBRARY_CARD_INSET),
        ) {
            val inner = RoundedCornerShape(Ui.concentric(Ui.Corner.card, LIBRARY_CARD_INSET))
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
                    Box(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp))
                        .background(primary).padding(horizontal = 8.dp, vertical = 4.dp)) {
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
                if (selectionMode) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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

/** The search field under the big title: a filled capsule, as search sits in Notes and Files. */
@Composable
internal fun LibrarySearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.TextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        shape = RoundedCornerShape(50),
        placeholder = { Text("Search titles, PDFs and handwriting", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Reicons.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Reicons.Close, contentDescription = "Clear search")
                }
            }
        } else null,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.widthIn(max = 720.dp).fillMaxWidth(),
    )
}
