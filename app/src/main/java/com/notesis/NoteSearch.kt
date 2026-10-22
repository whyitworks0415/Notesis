package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Where in a note a search match was found. */
internal enum class SearchSource(val label: String) {
    PDF("PDF text"),
    TEXT_BOX("Text box"),
    INK("Handwriting"),
}

/** One page's searchable text from one source. Plain data, so matching can be tested off the device. */
internal data class PageText(
    val pageId: String,
    val pageIndex: Int,
    val source: SearchSource,
    val text: String,
    /** Where the text sits on the page, in page units, when that is known (a text box). */
    val box: InkBox? = null,
)

/** One match, as the page panel lists it. */
internal data class NoteSearchHit(
    val pageId: String,
    val pageIndex: Int,
    val source: SearchSource,
    val snippet: String,
    /** The match within [snippet], end exclusive. */
    val matchStart: Int,
    val matchEnd: Int,
    /** Which match this is within its page's text from [source]: the n-th, for finding it on a PDF page. */
    val occurrence: Int,
    val box: InkBox? = null,
)

/** A short excerpt of a text with the match inside it located. */
internal data class SearchSnippet(val text: String, val matchStart: Int, val matchEnd: Int)

/**
 * About [radius] characters either side of the match at [at] (of [length]),
 * on one line, with an ellipsis where it was cut. The match keeps its place,
 * so it can be emphasised.
 */
internal fun snippetAround(body: String, at: Int, length: Int, radius: Int = SNIPPET_RADIUS): SearchSnippet {
    val start = at.coerceIn(0, body.length)
    val end = (at + length).coerceIn(start, body.length)
    var from = (start - radius).coerceAtLeast(0)
    val to = (end + radius).coerceAtMost(body.length)
    // Leading blanks would only push the match right.
    while (from < start && body[from].isWhitespace()) from++
    val lead = if (from > 0) "…" else ""
    val tail = if (to < body.length) "…" else ""
    val middle = body.substring(from, to).replace('\n', ' ').replace('\r', ' ').trimEnd()
    val matchStart = lead.length + (start - from)
    val matchEnd = (lead.length + (end - from)).coerceAtMost(lead.length + middle.length)
    return SearchSnippet(lead + middle + tail, matchStart, matchEnd)
}

/**
 * Every match of [query] in [texts], in page order and, within a page, PDF
 * text first, then text boxes, then handwriting. Case is ignored. Stops at
 * [limit] matches: a one-letter query on a long PDF would otherwise list
 * thousands, which nobody steps through.
 */
internal fun searchPageTexts(texts: List<PageText>, query: String, limit: Int = MAX_SEARCH_HITS): List<NoteSearchHit> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val hits = ArrayList<NoteSearchHit>()
    val ordered = texts.filter { it.pageIndex >= 0 }.sortedWith(compareBy({ it.pageIndex }, { it.source.ordinal }))
    for (page in ordered) {
        var from = 0
        var occurrence = 0
        while (hits.size < limit) {
            val at = page.text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            val snippet = snippetAround(page.text, at, needle.length)
            hits += NoteSearchHit(page.pageId, page.pageIndex, page.source, snippet.text,
                snippet.matchStart, snippet.matchEnd, occurrence, page.box)
            occurrence++
            from = at + needle.length
        }
        if (hits.size >= limit) break
    }
    return hits
}

/**
 * One note's text as it already sits on disk beside its pages: the PDF text
 * extracted on import (`.txt`) and the handwriting the indexer has already
 * read (`.ink`). Nothing here recognises or extracts anything; a file is read
 * once and again only when its modification time changes, so searching as
 * you type costs a directory listing.
 */
internal class NoteTextIndex(private val dir: File) {
    private val cache = HashMap<String, Pair<Long, String>>()

    /** Blocking; off the main thread. File name to text, for every text file of the note. */
    @Synchronized
    fun refresh(stillWanted: () -> Boolean = { true }): Map<String, String> {
        val files = dir.listFiles { file ->
            file.name.endsWith(PDF_TEXT) || file.name.endsWith(NoteStore.INK_INDEX)
        }.orEmpty()
        val present = HashSet<String>(files.size)
        for (file in files) {
            if (!stillWanted()) break
            present += file.name
            val modified = file.lastModified()
            if (cache[file.name]?.first == modified) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            cache[file.name] = modified to text
        }
        cache.keys.retainAll(present)
        return cache.mapValues { it.value.second }
    }

    companion object {
        const val PDF_TEXT = ".txt"
    }
}

/**
 * The page panel's search, kept by the note screen rather than the panel, so
 * the query and where you were in its results survive the panel closing -
 * which writing makes it do.
 */
@Stable
internal class NoteSearchState(pagesDir: File) {
    val index = NoteTextIndex(pagesDir)
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<NoteSearchHit>>(emptyList())
    /** Which of [hits] was last opened, or -1. */
    var selected by mutableIntStateOf(-1)
    var searching by mutableStateOf(false)
    /** PDF pages without an extracted-text file, read from the open PDF once and kept here. */
    val extracted = HashMap<Int, String>()
    /** The page panel's tab when it was last open; not state, read once as the panel opens. */
    var panelTab = 0
}

/**
 * Searches the body of the note: PDF text, text boxes and recognised
 * handwriting. Typing is debounced and a newer query cancels the search in
 * flight. [view] supplies the live document (text boxes, page order) and, for
 * a PDF page with no extracted text on disk, the PDF that is already open.
 */
@Composable
internal fun NoteSearchPanel(
    state: NoteSearchState,
    view: InkCanvasView?,
    onOpen: (NoteSearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestView by rememberUpdatedState(view)
    LaunchedEffect(state) {
        snapshotFlow { state.query }.collectLatest { typed ->
            val needle = typed.trim()
            if (needle.isEmpty()) {
                state.hits = emptyList()
                state.selected = -1
                state.searching = false
                return@collectLatest
            }
            delay(SEARCH_DEBOUNCE_MS)
            val canvas = latestView ?: return@collectLatest
            // The live pages, read here on the main thread: ids, order, and
            // what is typed in each box with where the box is. No strokes.
            val pages = canvas.document.pages.toList()
            val pageIndex = HashMap<String, Int>(pages.size)
            val pdfPages = ArrayList<Triple<String, Int, Int>>()
            val typed = ArrayList<PageText>()
            pages.forEachIndexed { index, page ->
                pageIndex[page.id] = index
                if (page.background == PageBackground.PDF && page.pdfPageIndex >= 0) {
                    pdfPages += Triple(page.id, index, page.pdfPageIndex)
                }
                for (image in page.images) {
                    val text = image.textContent?.text?.takeIf { it.isNotBlank() } ?: continue
                    typed += PageText(page.id, index, SearchSource.TEXT_BOX, text,
                        InkBox(image.x, image.y, image.x + image.width, image.y + image.height))
                }
            }
            state.searching = true
            try {
                val hits = withContext(Dispatchers.IO) {
                    val files = state.index.refresh { isActive }
                    ensureActive()
                    val texts = ArrayList<PageText>(files.size + typed.size)
                    texts += typed
                    for ((name, text) in files) {
                        val source = if (name.endsWith(NoteStore.INK_INDEX)) SearchSource.INK else SearchSource.PDF
                        val pageId = SearchIndex.pageIdOf(name)
                        val index = pageIndex[pageId] ?: continue
                        texts += PageText(pageId, index, source, text)
                    }
                    // A PDF imported before text was indexed has no files yet.
                    // Its text comes from the PDF already open - extraction,
                    // not recognition - once, and is kept for the next query.
                    for ((pageId, index, pdfIndex) in pdfPages) {
                        if (pageId + NoteTextIndex.PDF_TEXT in files) continue
                        ensureActive()
                        val text = synchronized(state.extracted) { state.extracted[pdfIndex] }
                            ?: canvas.pdfPageText(pdfIndex).also { synchronized(state.extracted) { state.extracted[pdfIndex] = it } }
                        if (text.isNotEmpty()) texts += PageText(pageId, index, SearchSource.PDF, text)
                    }
                    ensureActive()
                    searchPageTexts(texts, needle)
                }
                if (hits != state.hits) {
                    state.hits = hits
                    state.selected = -1
                }
            } finally {
                state.searching = false
            }
        }
    }

    fun open(index: Int) {
        val hit = state.hits.getOrNull(index) ?: return
        state.selected = index
        onOpen(hit)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.selected) {
        if (state.selected >= 0) listState.animateScrollToItem(state.selected)
    }

    Column(modifier) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { state.query = it },
            placeholder = { Text("Search this note") },
            singleLine = true,
            leadingIcon = { Icon(Reicons.Search, contentDescription = null) },
            trailingIcon = if (state.query.isNotEmpty()) {
                {
                    IconButton(onClick = { state.query = "" }) {
                        Icon(Reicons.Close, contentDescription = "Clear search")
                    }
                }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Ui.Space.m),
        )
        val count = state.hits.size
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Ui.Space.l, end = Ui.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    state.query.isBlank() -> "PDF text, text boxes and recognised handwriting"
                    state.searching && count == 0 -> "Searching…"
                    count == 0 -> "No matches"
                    state.selected >= 0 -> "${state.selected + 1} of $count" + if (count >= MAX_SEARCH_HITS) "+" else ""
                    else -> "$count match" + (if (count == 1) "" else "es") + if (count >= MAX_SEARCH_HITS) "+" else ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = count > 0,
                onClick = { open(if (state.selected <= 0) count - 1 else state.selected - 1) },
            ) { Icon(Reicons.ChevronLeft, contentDescription = "Previous match") }
            IconButton(
                enabled = count > 0,
                onClick = { open(if (state.selected !in 0 until count - 1) 0 else state.selected + 1) },
            ) { Icon(Reicons.ChevronRight, contentDescription = "Next match") }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).glassEdges(listState),
            contentPadding = PaddingValues(horizontal = Ui.Space.m, vertical = Ui.Space.s),
            verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
        ) {
            itemsIndexed(state.hits) { index, hit ->
                val selected = index == state.selected
                Column(
                    Modifier
                        .fillMaxWidth()
                        // Clipped too, so the press ripple keeps the row's corners.
                        .clip(Ui.Shape.chip)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        )
                        .clickable { open(index) }
                        .padding(horizontal = Ui.Space.m, vertical = Ui.Space.s),
                ) {
                    Text(
                        "Page ${hit.pageIndex + 1} · ${hit.source.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        emphasised(hit),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.hits.isNotEmpty()) item { Spacer(Modifier.padding(Ui.Space.xs)) }
        }
    }
}

/** The snippet with its match in bold on a soft marker. */
private fun emphasised(hit: NoteSearchHit): AnnotatedString = buildAnnotatedString {
    val text = hit.snippet
    val start = hit.matchStart.coerceIn(0, text.length)
    val end = hit.matchEnd.coerceIn(start, text.length)
    append(text.substring(0, start))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x55FFC107))) {
        append(text.substring(start, end))
    }
    append(text.substring(end))
}

/**
 * Frosted ends for a scrolling list in a glass panel: the content thins out
 * into the panel at an edge it can still scroll past, rather than being cut
 * off hard against it, so the panel reads as glass over the page and not as
 * a slab. Faded, not hidden - the line under the edge stays legible.
 * Offscreen only while there is an edge to fade.
 */
internal fun Modifier.glassEdges(state: LazyListState, edge: Dp = GLASS_EDGE): Modifier = this
    .graphicsLayer {
        compositingStrategy = if (state.canScrollBackward || state.canScrollForward) {
            CompositingStrategy.Offscreen
        } else {
            CompositingStrategy.Auto
        }
    }
    .drawWithContent {
        drawContent()
        val top = state.canScrollBackward
        val bottom = state.canScrollForward
        if ((!top && !bottom) || size.height <= 0f) return@drawWithContent
        val fade = (edge.toPx() / size.height).coerceIn(0f, 0.33f)
        val thin = Color.Black.copy(alpha = GLASS_EDGE_ALPHA)
        drawRect(
            brush = Brush.verticalGradient(
                0f to (if (top) thin else Color.Black),
                fade to Color.Black,
                1f - fade to Color.Black,
                1f to (if (bottom) thin else Color.Black),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

private val GLASS_EDGE = 28.dp
/** What is left of the content at the very edge. */
private const val GLASS_EDGE_ALPHA = 0.18f

private const val SNIPPET_RADIUS = 28
internal const val MAX_SEARCH_HITS = 500
private const val SEARCH_DEBOUNCE_MS = 250L
