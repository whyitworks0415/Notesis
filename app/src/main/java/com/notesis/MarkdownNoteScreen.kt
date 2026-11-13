package com.notesis

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.size
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
internal data class MdColors(
    val text: Color, val heading: Color, val marker: Color, val link: Color, val tagBack: Color,
    val codeBack: Color, val highlight: Color, val quote: Color, val done: Color,
)

@Composable
internal fun mdColors(): MdColors {
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

internal val BODY_SIZE = 17.sp

internal fun headingSize(level: Int): TextUnit = when (level) {
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
    val blockScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val history = remember(source.key) { EditHistory() }
    androidx.compose.runtime.DisposableEffect(history) { onDispose { history.close() } }
    // Whether a block is open for writing in Live; the rest are drawn as they read.
    var editing by remember(source.key) { mutableStateOf(false) }
    var jump by remember { mutableStateOf<Int?>(null) }
    // Where a picture being picked goes, once it is chosen.
    var imageAt by remember { mutableStateOf<Int?>(null) }

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
    // Back first closes the block being written, as tapping away does.
    BackHandler(enabled = editing && mode == MarkdownMode.LIVE) { editing = false }
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
        if (updated.text != old.text) history.record(old.state(), updated.state(), typed = true, now = SystemClock.uptimeMillis())
        value = updated
    }
    fun apply(change: MdEdit) {
        val next = TextFieldValue(change.text, TextRange(change.start, change.end))
        history.record(value.state(), next.state(), typed = false, now = SystemClock.uptimeMillis())
        value = next
    }
    fun undo() { history.undo(value.state())?.let { value = TextFieldValue(it.text, TextRange(it.start, it.end)) } }
    fun redo() { history.redo(value.state())?.let { value = TextFieldValue(it.text, TextRange(it.start, it.end)) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val at = imageAt
        imageAt = null
        if (uri == null || at == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                val extension = resolver.getType(uri)?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "png"
                runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        source.addFile(attachmentName(extension, System.currentTimeMillis(), emptySet()), input)
                    }
                }.getOrNull()
            }
            if (name == null) KToast.makeText(context, "Couldn't add the picture", android.widget.Toast.LENGTH_SHORT).show()
            else apply(insertEmbed(value.text, at.coerceIn(0, value.text.length), name))
        }
    }
    fun pickImage(at: Int) {
        imageAt = at
        runCatching { picker.launch("image/*") }.onFailure { imageAt = null }
    }
    fun format(action: FormatAction) {
        val s = value.selection.min
        val e = value.selection.max
        when (action) {
            FormatAction.UNDO -> return undo()
            FormatAction.REDO -> return redo()
            FormatAction.IMAGE -> return pickImage(e)
            else -> Unit
        }
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
            FormatAction.INDENT -> indentLines(value.text, s, e, outdent = false)
            FormatAction.OUTDENT -> indentLines(value.text, s, e, outdent = true)
            FormatAction.TABLE -> applySlash(value.text, e, e, SlashItem.TABLE)
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
            FormatAction.UNDO, FormatAction.REDO, FormatAction.IMAGE -> return
        })
    }
    /** The keys a keyboard brings: undo and redo, bold, italic, a link, and Tab to nest a list. */
    fun shortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val action = when {
            ctrl && event.key == Key.Z && event.isShiftPressed -> FormatAction.REDO
            ctrl && event.key == Key.Z -> FormatAction.UNDO
            ctrl && event.key == Key.Y -> FormatAction.REDO
            ctrl && event.key == Key.B -> FormatAction.BOLD
            ctrl && event.key == Key.I -> FormatAction.ITALIC
            ctrl && event.key == Key.K -> FormatAction.LINK
            ctrl && event.key == Key.E -> FormatAction.CODE
            !ctrl && event.key == Key.Tab -> if (event.isShiftPressed) FormatAction.OUTDENT else FormatAction.INDENT
            else -> return false
        }
        format(action)
        return true
    }
    fun chooseSlash(item: SlashItem, slash: Pair<Int, String>) {
        val cursor = value.selection.start
        when (item) {
            SlashItem.IMAGE -> {
                apply(MdEdit(value.text.removeRange(slash.first, cursor), slash.first))
                pickImage(slash.first)
            }
            SlashItem.DATE -> apply(applySlash(value.text, slash.first, cursor, item,
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())))
            else -> apply(applySlash(value.text, slash.first, cursor, item))
        }
    }
    fun jumpTo(offset: Int) {
        if (mode == MarkdownMode.READING) mode = MarkdownMode.LIVE
        value = value.copy(selection = TextRange(offset))
        if (mode == MarkdownMode.LIVE) { jump = offset; return }
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
                mode == MarkdownMode.READING -> MarkdownWebText(
                    text = value.text,
                    source = source,
                    onTask = { n -> toggleNthTask(value.text, n)?.let { apply(MdEdit(it, value.selection.start, value.selection.end)) } },
                    onFollow = ::follow,
                    onUrl = ::openUrl,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (mode == MarkdownMode.LIVE) MarkdownBlockEditor(
                            value = value,
                            onValueChange = ::edit,
                            onChange = ::apply,
                            source = source,
                            onFollow = ::follow,
                            onUrl = ::openUrl,
                            onKey = ::shortcut,
                            scroll = blockScroll,
                            jumpTo = jump,
                            onJumped = { jump = null },
                            editing = editing,
                            onEditing = { editing = it },
                        ) else MarkdownEditor(
                            value = value,
                            onValueChange = ::edit,
                            live = false,
                            scroll = scroll,
                            onLayout = { layout = it },
                            onKey = ::shortcut,
                        )
                    }
                    val writing = mode == MarkdownMode.SOURCE || editing
                    val query = remember(value.text, value.selection, writing) {
                        if (writing && value.selection.collapsed) wikiQueryAt(value.text, value.selection.start) else null
                    }
                    val slash = remember(value.text, value.selection, writing) {
                        if (writing && query == null && value.selection.collapsed) slashQueryAt(value.text, value.selection.start) else null
                    }
                    val linkHere = remember(value.text, value.selection, writing) {
                        if (!writing || query != null) null else markdownSpans(value.text).firstOrNull {
                            it.kind == MdKind.WIKILINK && value.selection.start in it.start..it.end
                        }?.target
                    }
                    when {
                        query != null -> WikiSuggestions(names.filter { it != source.title }, query.second) { name ->
                            apply(completeWiki(value.text, query.first, value.selection.start, name))
                            if (name !in names) names = names + name
                        }
                        slash != null -> SlashMenu(slashMatches(slash.second)) { item -> chooseSlash(item, slash) }
                        linkHere != null -> Row(Modifier.fillMaxWidth().padding(horizontal = Ui.Space.s)) {
                            TextButton(onClick = { follow(linkHere) }) { Text("Open “$linkHere”") }
                        }
                    }
                    if (writing) FormatBar(history.canUndo, history.canRedo, ::format)
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
    onKey: (KeyEvent) -> Boolean = { false },
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
                .onPreviewKeyEvent(onKey)
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
internal class LivePreview(
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
internal enum class FormatAction(val label: String, val description: String) {
    UNDO("↶", "Undo"), REDO("↷", "Redo"),
    BOLD("B", "Bold"), ITALIC("I", "Italic"), STRIKE("S", "Strikethrough"), HIGHLIGHT("==", "Highlight"),
    CODE("</>", "Code"), HEADING("H", "Heading"), BULLET("•", "Bullet list"), NUMBERED("1.", "Numbered list"),
    TASK("☐", "Task"), QUOTE("❝", "Quote"), OUTDENT("⇤", "Outdent"), INDENT("⇥", "Indent"),
    WIKILINK("[[ ]]", "Link a note"), LINK("Link", "Web link"), IMAGE("", "Picture"), TABLE("", "Table"),
}

@Composable
private fun FormatBar(canUndo: Boolean, canRedo: Boolean, onAction: (FormatAction) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Ui.Space.s),
            verticalAlignment = Alignment.CenterVertically) {
            FormatAction.entries.forEach { action ->
                val enabled = when (action) { FormatAction.UNDO -> canUndo; FormatAction.REDO -> canRedo; else -> true }
                val icon = when (action) {
                    FormatAction.UNDO -> Reicons.Undo
                    FormatAction.REDO -> Reicons.Redo
                    FormatAction.IMAGE -> Reicons.Image
                    FormatAction.TABLE -> Reicons.AutoAwesomeMosaic
                    else -> null
                }
                if (icon != null) {
                    IconButton(onClick = { onAction(action) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                        Icon(icon, action.description)
                    }
                } else TextButton(onClick = { onAction(action) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = action.description }) {
                    Text(AnnotatedString(action.label), fontWeight = if (action == FormatAction.BOLD) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (action == FormatAction.ITALIC) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if (action == FormatAction.STRIKE) TextDecoration.LineThrough else null)
                }
                if (action == FormatAction.REDO || action == FormatAction.CODE || action == FormatAction.INDENT || action == FormatAction.LINK) {
                    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
            }
        }
    }
}

/** What "/" offers as it is typed: blocks to make, Notion's way, filtered by the words after the slash. */
@Composable
private fun SlashMenu(items: List<SlashItem>, onPick: (SlashItem) -> Unit) {
    Surface(tonalElevation = 3.dp, shape = Ui.Shape.preview, modifier = Modifier.padding(horizontal = Ui.Space.s, vertical = 4.dp)) {
        if (items.isEmpty()) {
            Text("No block by that name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp))
        } else LazyColumn(Modifier.widthIn(max = 420.dp).heightIn(max = 260.dp)) {
            items(items) { item ->
                Text(item.label, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(item) }.padding(horizontal = 16.dp, vertical = 11.dp))
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

private fun TextFieldValue.state() = EditHistory.State(text, selection.start, selection.end)
