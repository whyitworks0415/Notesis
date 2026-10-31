package com.notesis

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads and writes of Markdown notes, one at a time and outliving the screen:
 * a note left mid-save is still written, and opened again it is read after
 * that write, never before.
 */
internal object MarkdownIo {
    @OptIn(ExperimentalCoroutinesApi::class)
    val serial = Dispatchers.IO.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob() + serial)
}

/** How a Markdown note is shown: written with its marks tucked away, written as plain source, or read. */
internal enum class MarkdownMode(val label: String) { LIVE("Live"), SOURCE("Source"), READING("Read") }

/** The colours Markdown is drawn in, from the theme. */
private data class MdColors(
    val text: Color, val heading: Color, val marker: Color, val link: Color, val tagBack: Color,
    val codeBack: Color, val highlight: Color, val quote: Color, val done: Color,
)

@Composable
private fun mdColors(): MdColors {
    val scheme = MaterialTheme.colorScheme
    return MdColors(
        text = scheme.onSurface,
        heading = scheme.onSurface,
        marker = scheme.outline,
        link = scheme.primary,
        tagBack = scheme.primary.copy(alpha = 0.12f),
        codeBack = scheme.surfaceVariant,
        highlight = Color(0x66FFD54F),
        quote = scheme.onSurfaceVariant,
        done = scheme.outline,
    )
}

private val BODY_SIZE = 17.sp

private fun headingSize(level: Int): TextUnit = when (level) {
    1 -> 1.7.em
    2 -> 1.45.em
    3 -> 1.25.em
    4 -> 1.1.em
    else -> 1.em
}

/**
 * A Markdown note, written and read in the manner of Obsidian: in Live mode
 * the marks (#, **, [[ ]]) are tucked away except on the line being written,
 * lists carry on by themselves, [[ offers the notes there are to link to;
 * Source shows every mark; Read lays the note out, its tasks ticked with a tap
 * and its links followed. Saved as it is written, to the note's own .md file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownNoteScreen(
    source: MarkdownSource,
    onBack: () -> Unit,
    /** Lays the note's text out as PDF pages; null hides the action. */
    onPdf: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var value by remember(source.key) { mutableStateOf(TextFieldValue("")) }
    var loaded by remember(source.key) { mutableStateOf(false) }
    var failed by remember(source.key) { mutableStateOf<String?>(null) }
    var saved by remember(source.key) { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(MarkdownPrefs.mode(context)) }
    var names by remember(source.key) { mutableStateOf(emptyList<String>()) }
    var showOutline by remember { mutableStateOf(false) }
    var backlinks by remember(source.key) { mutableStateOf<List<String>?>(null) }
    var showLinks by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    LaunchedEffect(source.key) {
        runCatching { withContext(MarkdownIo.serial) { source.load() } }
            .onSuccess { value = TextFieldValue(it); saved = it; loaded = true }
            .onFailure { failed = "Couldn't read the note" }
        names = runCatching { source.names() }.getOrDefault(emptyList())
    }
    fun saveNow() {
        if (!loaded) return
        val text = value.text
        if (text == saved) return
        saved = text
        val appContext = context.applicationContext
        MarkdownIo.scope.launch {
            runCatching { source.save(text) }.onFailure {
                withContext(Dispatchers.Main) {
                    KToast.makeText(appContext, "Couldn't save ${source.title}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    // Saved a moment after the typing stops, and on the way out.
    LaunchedEffect(value.text, loaded) {
        if (!loaded) return@LaunchedEffect
        delay(800)
        saveNow()
    }
    fun leave() { saveNow(); onBack() }
    BackHandler { leave() }
    fun follow(name: String) {
        saveNow()
        scope.launch { runCatching { source.follow(name) } }
    }
    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webAddress(url)))) }
    }
    fun edit(next: TextFieldValue) {
        val old = value
        var updated = next
        // A line break typed at the end of a list item carries the list on.
        val at = next.selection.start
        if (next.selection.collapsed && next.text.length == old.text.length + 1 && at > 0 && next.text[at - 1] == '\n' &&
            next.text.removeRange(at - 1, at) == old.text) {
            continueAfterBreak(next.text, at)?.let { updated = TextFieldValue(it.text, TextRange(it.start, it.end)) }
        }
        value = updated
    }
    fun apply(change: MdEdit) { value = TextFieldValue(change.text, TextRange(change.start, change.end)) }
    fun jumpTo(offset: Int) {
        if (mode == MarkdownMode.READING) mode = MarkdownMode.LIVE
        value = value.copy(selection = TextRange(offset))
        val at = layout ?: return
        val top = at.getLineTop(at.getLineForOffset(offset.coerceIn(0, at.layoutInput.text.length))).toInt()
        scope.launch { scroll.animateScrollTo(top) }
    }

    Scaffold(
        topBar = {
            SkinSurface(flush = true) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = ::leave) { Icon(Reicons.ArrowBack, "Back") } },
                    title = {
                        Column {
                            Text(source.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(source.where, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        TextButton(onClick = { showOutline = true }, enabled = loaded) { Text("Outline") }
                        TextButton(onClick = {
                            showLinks = true
                            scope.launch { backlinks = runCatching { source.backlinks() }.getOrDefault(emptyList()) }
                        }, enabled = loaded) { Text("Links") }
                        if (onPdf != null) TextButton(onClick = { saveNow(); onPdf(value.text) }, enabled = loaded) { Text("PDF") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // The way it is shown, and what has happened to it.
            Row(Modifier.fillMaxWidth().padding(horizontal = Ui.Space.m, vertical = Ui.Space.xs),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                MarkdownMode.entries.forEach { option ->
                    SettingsChoiceChip(selected = mode == option, onClick = {
                        mode = option
                        MarkdownPrefs.setMode(context, option)
                    }, label = option.label)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (!loaded) "" else "${countNoun(wordCount(value.text), "word")} · " +
                        if (value.text == saved) "Saved" else "Editing",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when {
                failed != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(failed!!, color = MaterialTheme.colorScheme.error)
                }
                !loaded -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                mode == MarkdownMode.READING -> MarkdownReader(
                    text = value.text,
                    onToggleTask = { at -> value = value.copy(text = toggleTask(value.text, at)) },
                    onWiki = ::follow,
                    onUrl = ::openUrl,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        MarkdownEditor(
                            value = value,
                            onValueChange = ::edit,
                            live = mode == MarkdownMode.LIVE,
                            scroll = scroll,
                            onLayout = { layout = it },
                        )
                    }
                    val query = remember(value.text, value.selection) {
                        if (value.selection.collapsed) wikiQueryAt(value.text, value.selection.start) else null
                    }
                    val linkHere = remember(value.text, value.selection) {
                        if (query != null) null else markdownSpans(value.text).firstOrNull {
                            it.kind == MdKind.WIKILINK && value.selection.start in it.start..it.end
                        }?.target
                    }
                    when {
                        query != null -> WikiSuggestions(names.filter { it != source.title }, query.second) { name ->
                            apply(completeWiki(value.text, query.first, value.selection.start, name))
                            if (name !in names) names = names + name
                        }
                        linkHere != null -> Row(Modifier.fillMaxWidth().padding(horizontal = Ui.Space.s)) {
                            TextButton(onClick = { follow(linkHere) }) { Text("Open “$linkHere”") }
                        }
                    }
                    FormatBar { action ->
                        val s = value.selection.min
                        val e = value.selection.max
                        apply(when (action) {
                            FormatAction.BOLD -> toggleWrap(value.text, s, e, "**")
                            FormatAction.ITALIC -> toggleWrap(value.text, s, e, "*")
                            FormatAction.STRIKE -> toggleWrap(value.text, s, e, "~~")
                            FormatAction.HIGHLIGHT -> toggleWrap(value.text, s, e, "==")
                            FormatAction.CODE -> toggleWrap(value.text, s, e, "`")
                            FormatAction.HEADING -> toggleLineStyle(value.text, s, e, LineStyle.HEADING)
                            FormatAction.BULLET -> toggleLineStyle(value.text, s, e, LineStyle.BULLET)
                            FormatAction.NUMBERED -> toggleLineStyle(value.text, s, e, LineStyle.NUMBERED)
                            FormatAction.TASK -> toggleLineStyle(value.text, s, e, LineStyle.TASK)
                            FormatAction.QUOTE -> toggleLineStyle(value.text, s, e, LineStyle.QUOTE)
                            FormatAction.WIKILINK -> {
                                val picked = value.text.substring(s, e)
                                val inserted = value.text.substring(0, s) + "[[" + picked + "]]" + value.text.substring(e)
                                // With words picked they become the link; without, the cursor waits inside for a name.
                                if (picked.isEmpty()) MdEdit(inserted, s + 2) else MdEdit(inserted, s + picked.length + 4)
                            }
                            FormatAction.LINK -> {
                                val picked = value.text.substring(s, e).ifEmpty { "text" }
                                val inserted = value.text.substring(0, s) + "[$picked](https://)" + value.text.substring(e)
                                val urlAt = s + picked.length + 3
                                MdEdit(inserted, urlAt, urlAt + 8)
                            }
                        })
                    }
                }
            }
        }
    }

    if (showOutline) {
        val headings = remember(value.text) { outlineOf(value.text) }
        AlertDialog(
            onDismissRequest = { showOutline = false },
            title = { Text("Outline") },
            text = {
                if (headings.isEmpty()) Text("No headings yet. Start a line with # to make one.")
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(headings) { heading ->
                        Text(heading.title.ifBlank { "(untitled)" },
                            style = if (heading.level <= 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable {
                                showOutline = false
                                jumpTo(heading.start)
                            }.padding(start = ((heading.level - 1) * 16).dp, top = 8.dp, bottom = 8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showOutline = false }) { Text("Close") } },
        )
    }
    if (showLinks) {
        val outgoing = remember(value.text) {
            markdownSpans(value.text).filter { it.kind == MdKind.WIKILINK }.mapNotNull { it.target }.distinct()
        }
        val tags = remember(value.text) { tagsIn(value.text).sorted() }
        AlertDialog(
            onDismissRequest = { showLinks = false },
            title = { Text("Links") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    item { LinkHeading("Linked from") }
                    val incoming = backlinks
                    if (incoming == null) item { Text("Looking…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else if (incoming.isEmpty()) item { Text("No other note links here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else items(incoming) { name -> LinkRow(name) { showLinks = false; follow(name) } }
                    item { LinkHeading("Links to") }
                    if (outgoing.isEmpty()) item { Text("Type [[ to link a note.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else items(outgoing) { name -> LinkRow(name) { showLinks = false; follow(name) } }
                    if (tags.isNotEmpty()) {
                        item { LinkHeading("Tags") }
                        item { Text(tags.joinToString("  ") { "#$it" }, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLinks = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun LinkHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Ui.Space.m, bottom = Ui.Space.xs))
}

@Composable
private fun LinkRow(name: String, onClick: () -> Unit) {
    Text(name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp))
}

/** The way the editor was last left: Live, Source or Read. */
private object MarkdownPrefs {
    private const val KEY = "markdownMode"
    fun mode(context: android.content.Context): MarkdownMode =
        runCatching { MarkdownMode.valueOf(context.getSharedPreferences("pens", 0).getString(KEY, null)!!) }
            .getOrDefault(MarkdownMode.LIVE)
    fun setMode(context: android.content.Context, mode: MarkdownMode) {
        context.getSharedPreferences("pens", 0).edit().putString(KEY, mode.name).apply()
    }
}

// ---- editing ----------------------------------------------------------------------------------

@Composable
private fun MarkdownEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    live: Boolean,
    scroll: ScrollState,
    onLayout: (TextLayoutResult) -> Unit,
) {
    val colors = mdColors()
    val spans = remember(value.text) { markdownSpans(value.text) }
    val active = remember(value.text, value.selection) { activeLines(value.text, value.selection) }
    val transformation = remember(spans, active, live, colors) {
        if (live) LivePreview(spans, active, colors) else SourceView(spans, colors)
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                color = colors.text,
                fontSize = BODY_SIZE,
                lineHeight = 1.55.em,
                fontFamily = if (live) FontFamily.Default else FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
            onTextLayout = onLayout,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 400.dp)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .semantics { contentDescription = "Note text" },
        )
    }
}

/** From the start of the first line the selection touches to the end of the last. */
private fun activeLines(text: String, selection: TextRange): IntRange {
    val s = selection.min.coerceIn(0, text.length)
    val e = selection.max.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', s - 1) + 1
    val end = text.indexOf('\n', e).let { if (it < 0) text.length else it }
    return start..end
}

/** Marks shown dimmed, every one of them: the source as it is, coloured. */
private class SourceView(private val spans: List<MdSpan>, private val c: MdColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val b = AnnotatedString.Builder(text.text)
        for (s in spans) {
            if (s.end <= s.start || s.end > text.length) continue
            when (s.kind) {
                MdKind.MARKER, MdKind.FENCE, MdKind.FRONT_MATTER, MdKind.BULLET -> b.addStyle(SpanStyle(color = c.marker), s.start, s.end)
                MdKind.HEADING -> b.addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.heading), s.start, s.end)
                MdKind.LINK, MdKind.WIKILINK, MdKind.TAG -> b.addStyle(SpanStyle(color = c.link), s.start, s.end)
                MdKind.BOLD -> b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), s.start, s.end)
                MdKind.ITALIC -> b.addStyle(SpanStyle(fontStyle = FontStyle.Italic), s.start, s.end)
                else -> Unit
            }
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/**
 * Live preview: the note drawn as it reads, its marks out of sight on every
 * line but the one being written, where they come back to be edited. The
 * text keeps its length - a hidden mark is drawn see-through at a hair's
 * width, a bullet is drawn as • - so the cursor and the source stay one to one.
 */
private class LivePreview(
    private val spans: List<MdSpan>,
    private val active: IntRange,
    private val c: MdColors,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val chars = raw.toCharArray()
        val styles = ArrayList<AnnotatedString.Range<SpanStyle>>()
        val hidden = SpanStyle(color = Color.Transparent, fontSize = 0.05.em, letterSpacing = 0.sp)
        val taskStarts = spans.filter { it.kind == MdKind.TASK_OPEN || it.kind == MdKind.TASK_DONE }.map { it.start }.toHashSet()
        fun on(s: MdSpan) = s.start <= active.last && s.end >= active.first
        fun style(style: SpanStyle, start: Int, end: Int) {
            if (end > start && end <= raw.length) styles += AnnotatedString.Range(style, start, end)
        }
        for (s in spans) {
            if (s.end <= s.start || s.end > raw.length) continue
            val shown = on(s)
            when (s.kind) {
                MdKind.MARKER -> style(if (shown) SpanStyle(color = c.marker) else hidden, s.start, s.end)
                MdKind.HEADING -> style(SpanStyle(fontSize = headingSize(s.level), fontWeight = FontWeight.Bold, color = c.heading), s.start, s.end)
                MdKind.BOLD -> style(SpanStyle(fontWeight = FontWeight.Bold), s.start, s.end)
                MdKind.ITALIC -> style(SpanStyle(fontStyle = FontStyle.Italic), s.start, s.end)
                MdKind.STRIKE -> style(SpanStyle(textDecoration = TextDecoration.LineThrough), s.start, s.end)
                MdKind.HIGHLIGHT -> style(SpanStyle(background = c.highlight), s.start, s.end)
                MdKind.CODE -> style(SpanStyle(fontFamily = FontFamily.Monospace, background = c.codeBack, fontSize = 0.92.em), s.start, s.end)
                MdKind.LINK -> style(SpanStyle(color = c.link, textDecoration = TextDecoration.Underline), s.start, s.end)
                MdKind.WIKILINK -> style(SpanStyle(color = c.link), s.start, s.end)
                MdKind.TAG -> style(SpanStyle(color = c.link, background = c.tagBack), s.start, s.end)
                MdKind.QUOTE -> style(SpanStyle(color = c.quote, fontStyle = FontStyle.Italic), s.start, s.end)
                MdKind.BULLET -> {
                    val unordered = s.target?.firstOrNull()?.isDigit() == false
                    when {
                        shown -> style(SpanStyle(color = c.marker), s.start, s.end)
                        // A task's box stands for its bullet.
                        s.end in taskStarts -> style(hidden, s.start, s.end)
                        unordered -> {
                            chars[s.start] = '•'
                            style(SpanStyle(color = c.marker, fontWeight = FontWeight.Bold), s.start, s.end)
                        }
                        else -> style(SpanStyle(color = c.marker), s.start, s.end)
                    }
                }
                MdKind.TASK_OPEN, MdKind.TASK_DONE -> if (shown) {
                    style(SpanStyle(color = c.link, fontFamily = FontFamily.Monospace), s.start, s.end)
                } else {
                    chars[s.start] = if (s.kind == MdKind.TASK_DONE) '☑' else '☐'
                    style(SpanStyle(color = c.link, fontSize = 1.15.em), s.start, s.start + 1)
                    style(hidden, s.start + 1, (s.start + 3).coerceAtMost(s.end))
                }
                MdKind.DONE_TEXT -> style(SpanStyle(color = c.done, textDecoration = TextDecoration.LineThrough), s.start, s.end)
                MdKind.CODE_BLOCK -> style(SpanStyle(fontFamily = FontFamily.Monospace, background = c.codeBack, fontSize = 0.92.em), s.start, s.end)
                MdKind.FENCE -> style(SpanStyle(fontFamily = FontFamily.Monospace, color = c.marker, fontSize = 0.85.em), s.start, s.end)
                MdKind.RULE -> if (shown) style(SpanStyle(color = c.marker), s.start, s.end) else {
                    for (i in s.start until s.end) chars[i] = '─'
                    style(SpanStyle(color = c.marker, letterSpacing = 0.sp), s.start, s.end)
                }
                MdKind.FRONT_MATTER -> style(SpanStyle(fontFamily = FontFamily.Monospace, color = c.marker, fontSize = 0.85.em), s.start, s.end)
            }
        }
        val out = AnnotatedString.Builder(String(chars))
        for (range in styles) out.addStyle(range.item, range.start, range.end)
        return TransformedText(out.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/** The buttons along the bottom of the editor. */
private enum class FormatAction(val label: String, val description: String) {
    BOLD("B", "Bold"), ITALIC("I", "Italic"), STRIKE("S", "Strikethrough"), HIGHLIGHT("==", "Highlight"),
    CODE("</>", "Code"), HEADING("H", "Heading"), BULLET("•", "Bullet list"), NUMBERED("1.", "Numbered list"),
    TASK("☐", "Task"), QUOTE("❝", "Quote"), WIKILINK("[[ ]]", "Link a note"), LINK("Link", "Web link"),
}

@Composable
private fun FormatBar(onAction: (FormatAction) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Ui.Space.s),
            verticalAlignment = Alignment.CenterVertically) {
            FormatAction.entries.forEach { action ->
                TextButton(onClick = { onAction(action) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = action.description }) {
                    Text(action.label, fontWeight = if (action == FormatAction.BOLD) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (action == FormatAction.ITALIC) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if (action == FormatAction.STRIKE) TextDecoration.LineThrough else null)
                }
            }
        }
    }
}

/** Notes to link to while a [[ is being typed; the name typed, if no note has it, makes a new one when followed. */
@Composable
private fun WikiSuggestions(names: List<String>, query: String, onPick: (String) -> Unit) {
    val matches = remember(names, query) { suggestNames(names, query) }
    val typed = query.trim()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Ui.Space.s, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
        matches.forEach { name -> SettingsChoiceChip(selected = false, onClick = { onPick(name) }, label = name) }
        if (typed.isNotEmpty() && matches.none { linkKey(it) == linkKey(typed) }) {
            SettingsChoiceChip(selected = false, onClick = { onPick(typed) }, label = "New “$typed”")
        }
        if (matches.isEmpty() && typed.isEmpty()) {
            Text("Type a note's name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- reading ----------------------------------------------------------------------------------

@Composable
private fun MarkdownReader(
    text: String,
    onToggleTask: (Int) -> Unit,
    onWiki: (String) -> Unit,
    onUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = mdColors()
    val blocks = remember(text) { markdownBlocks(text) }
    if (blocks.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Nothing written yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    fun inline(source: String) = inlineAnnotated(source, colors, onWiki, onUrl)
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(blocks, key = { it.start }) { block ->
            when (block) {
                is MdBlock.Heading -> Text(inline(block.text),
                    fontSize = (BODY_SIZE.value * headingSize(block.level).value).sp, fontWeight = FontWeight.Bold,
                    lineHeight = 1.3.em,
                    modifier = Modifier.padding(top = if (block.level <= 2) 10.dp else 4.dp).semantics { heading() })
                is MdBlock.Paragraph -> Text(inline(block.text), fontSize = BODY_SIZE, lineHeight = 1.55.em)
                is MdBlock.Item -> Row(Modifier.padding(start = (block.depth * 22).dp), verticalAlignment = Alignment.Top) {
                    if (block.task != null) {
                        Checkbox(checked = block.task, onCheckedChange = { onToggleTask(block.taskAt) },
                            modifier = Modifier.padding(end = 2.dp))
                    } else {
                        Text(block.marker, fontSize = BODY_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp))
                    }
                    Text(inline(block.text), fontSize = BODY_SIZE, lineHeight = 1.5.em,
                        color = if (block.task == true) colors.done else colors.text,
                        textDecoration = if (block.task == true) TextDecoration.LineThrough else null,
                        modifier = Modifier.padding(top = if (block.task != null) 12.dp else 0.dp))
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
                    Text(inline(block.text), fontSize = BODY_SIZE, fontStyle = FontStyle.Italic, color = colors.quote,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
                }
                is MdBlock.Code -> Surface(color = colors.codeBack, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        if (block.language.isNotBlank()) Text(block.language, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(block.text, fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()))
                    }
                }
                is MdBlock.Table -> MarkdownTable(block, ::inline)
                is MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                is MdBlock.FrontMatter -> Surface(color = colors.codeBack, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Properties", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(block.text, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, inline: (String) -> AnnotatedString) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, line)) {
        (listOf(table.header) + table.rows).forEachIndexed { index, row ->
            Row(Modifier.height(IntrinsicSize.Min).background(if (index == 0) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)) {
                for (column in 0 until columns) {
                    Box(Modifier.widthIn(min = 96.dp, max = 260.dp).fillMaxHeight().border(0.5.dp, line).padding(8.dp)) {
                        Text(inline(row.getOrElse(column) { "" }), fontSize = 15.sp,
                            fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

/** A line of Markdown as styled text, its marks gone and its links followable. */
private fun inlineAnnotated(text: String, c: MdColors, onWiki: (String) -> Unit, onUrl: (String) -> Unit): AnnotatedString {
    val spans = markdownSpans(text)
    val hidden = BooleanArray(text.length)
    for (s in spans) if (s.kind == MdKind.MARKER) for (i in s.start until s.end.coerceAtMost(text.length)) hidden[i] = true
    // Where each source character lands in the text shown.
    val at = IntArray(text.length + 1)
    val shown = StringBuilder()
    for (i in text.indices) {
        at[i] = shown.length
        if (!hidden[i]) shown.append(text[i])
    }
    at[text.length] = shown.length
    val out = AnnotatedString.Builder(shown.toString())
    for (s in spans) {
        val start = at[s.start.coerceIn(0, text.length)]
        val end = at[s.end.coerceIn(0, text.length)]
        if (end <= start) continue
        when (s.kind) {
            MdKind.BOLD -> out.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            MdKind.ITALIC -> out.addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
            MdKind.STRIKE -> out.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
            MdKind.HIGHLIGHT -> out.addStyle(SpanStyle(background = c.highlight), start, end)
            MdKind.CODE -> out.addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.codeBack), start, end)
            MdKind.TAG -> out.addStyle(SpanStyle(color = c.link, background = c.tagBack), start, end)
            MdKind.WIKILINK -> s.target?.let { target ->
                out.addLink(LinkAnnotation.Clickable(target, TextLinkStyles(SpanStyle(color = c.link))) { onWiki(target) }, start, end)
            }
            MdKind.LINK -> s.target?.let { url ->
                out.addLink(LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = c.link, textDecoration = TextDecoration.Underline))) {
                    onUrl(url)
                }, start, end)
            }
            else -> Unit
        }
    }
    return out.toAnnotatedString()
}
