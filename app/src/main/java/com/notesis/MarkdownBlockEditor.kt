package com.notesis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The Live mode of a Markdown note, made the way Obsidian's live preview and
 * Notion are used (Todo.md, E1): the note is a column of blocks, each drawn
 * as it reads - headings, lists with real boxes, callouts, tables, code in
 * colour, formulas, pictures - and the one touched opens for editing with
 * its marks showing. Enter makes the next block, a backspace at the start of
 * one joins it to the one before, "/" offers blocks to make (in the screen),
 * the handle beside the open block turns, moves, copies or removes it, and a
 * heading folds what is under it. The text itself stays plain Markdown.
 */

/** Before every open block's text, so a backspace at its very start can be seen: it takes this away. */
private const val START_MARK = '​'

/** What the handle beside a block does. */
internal sealed interface BlockAction {
    data class Turn(val style: BlockStyle) : BlockAction
    data object Up : BlockAction
    data object Down : BlockAction
    data object Duplicate : BlockAction
    data object Delete : BlockAction
    data object Copy : BlockAction
}

/**
 * The editor. [value] is the whole note and where its cursor is; typing in
 * it comes out of [onValueChange], and changes made whole (a block moved, a
 * table cell, a box ticked) out of [onChange], so undo can tell them apart.
 * [onKey] gets keys first for the shortcuts the screen keeps (undo, bold,
 * Tab). [jumpTo] scrolls to a block holding that offset once, then [onJumped].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarkdownBlockEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onChange: (MdEdit) -> Unit,
    source: MarkdownSource,
    onFollow: (String) -> Unit,
    onUrl: (String) -> Unit,
    onKey: (KeyEvent) -> Boolean,
    scroll: ScrollState,
    jumpTo: Int?,
    onJumped: () -> Unit,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = mdColors()
    val text = value.text
    val blocks = remember(text) { liveBlocks(text) }
    val definitions = remember(text) { linkDefinitions(text) }
    val current = if (editing) blockAt(blocks, value.selection.start) else -1
    var folded by remember { mutableStateOf(setOf<String>()) }
    val hidden = remember(blocks, folded, current) { foldedBlocks(text, blocks, folded, current) }
    val tops = remember { mutableStateMapOf<Int, Int>() }
    var tableAsText by remember { mutableStateOf(false) }
    if (current < 0) tableAsText = false

    // The open block's own copy of its text, which keeps what the keyboard is composing
    // (a Hangul syllable half made) across the note being written back on every key.
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var fieldStart by remember { mutableIntStateOf(-1) }
    var fieldLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val block = blocks.getOrNull(current)
    if (block != null) {
        val expected = START_MARK + block.text(text)
        val local = TextRange(
            (value.selection.start - block.start + 1).coerceIn(1, expected.length),
            (value.selection.end - block.start + 1).coerceIn(1, expected.length),
        )
        if (field.text != expected || fieldStart != block.start) {
            field = TextFieldValue(expected, local)
            fieldStart = block.start
        } else if (field.selection != local && field.composition == null) {
            field = field.copy(selection = local)
        }
    }

    fun open(offset: Int) {
        onEditing(true)
        onValueChange(TextFieldValue(text, TextRange(offset.coerceIn(0, text.length))))
    }

    fun typed(next: TextFieldValue) {
        val b = blocks.getOrNull(current) ?: return
        val marked = next.text.indexOf(START_MARK)
        if (marked != 0) {
            if (marked < 0 && next.text.length < field.text.length && field.text.endsWith(next.text)) {
                // The mark is gone and nothing else: a backspace at the very start of the block.
                joinWithPrevious(text, blocks, current)?.let(onChange)
                return
            }
            // The keyboard put words before the mark, or took it with others: keep the words, put the mark back.
            val words = next.text.replace(START_MARK.toString(), "")
            val joined = text.substring(0, b.start) + words + text.substring(b.end)
            val cursor = (b.start + next.selection.end - (if (marked in 0 until next.selection.end) 1 else 0)).coerceIn(0, joined.length)
            onValueChange(TextFieldValue(joined, TextRange(cursor)))
            return
        }
        field = next
        val words = next.text.substring(1)
        val joined = text.substring(0, b.start) + words + text.substring(b.end)
        val s = (b.start + next.selection.start - 1).coerceIn(b.start, b.start + words.length)
        val e = (b.start + next.selection.end - 1).coerceIn(b.start, b.start + words.length)
        onValueChange(TextFieldValue(joined, TextRange(s, e)))
    }

    fun act(index: Int, action: BlockAction) {
        val target = blocks.getOrNull(index) ?: return
        when (action) {
            is BlockAction.Turn -> onChange(turnInto(text, target.start, target.end, action.style))
            BlockAction.Up -> moveBlock(text, blocks, index, -1)?.let(onChange)
            BlockAction.Down -> moveBlock(text, blocks, index, 1)?.let(onChange)
            BlockAction.Duplicate -> duplicateBlock(text, blocks, index)?.let(onChange)
            BlockAction.Delete -> { deleteBlock(text, blocks, index)?.let(onChange); onEditing(false) }
            BlockAction.Copy -> Unit
        }
    }

    LaunchedEffect(jumpTo) {
        val wanted = jumpTo ?: return@LaunchedEffect
        val index = blockAt(blocks, wanted)
        tops[index]?.let { scroll.animateScrollTo(it) }
        onJumped()
    }

    val clipboard = LocalClipboardManager.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 4.dp, end = 24.dp, top = 12.dp),
    ) {
        for ((index, b) in blocks.withIndex()) {
            if (index in hidden) continue
            androidx.compose.runtime.key(index) {
                val isOpen = index == current
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { tops[index] = it.positionInParent().y.toInt() }
                        .padding(vertical = blockGap(b.kind)),
                    verticalAlignment = Alignment.Top,
                ) {
                    // The gutter: a heading's fold, or the open block's handle.
                    Box(Modifier.width(32.dp).heightIn(min = 28.dp), contentAlignment = Alignment.TopCenter) {
                        if (isOpen) {
                            Text("⋮⋮", color = c.marker, fontSize = 16.sp,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { menu = true }
                                    .padding(horizontal = 6.dp, vertical = 2.dp).semantics { contentDescription = "Block menu" })
                        } else if (b.kind == LiveKind.HEADING) {
                            val key = foldKey(text, b)
                            val shut = key in folded
                            Text(if (shut) "▸" else "▾", color = c.marker, fontSize = 15.sp,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .clickable { folded = if (shut) folded - key else folded + key }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .semantics { contentDescription = if (shut) "Unfold" else "Fold" })
                        }
                        BlockMenu(menu, onDismiss = { menu = false }) { action ->
                            menu = false
                            if (action == BlockAction.Copy) clipboard.setText(AnnotatedString(b.text(text)))
                            else act(index, action)
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when {
                            isOpen && b.kind == LiveKind.TABLE && !tableAsText -> parseTable(b.text(text))?.let { table ->
                                TableEditor(table, onChange = { changed ->
                                    val made = formatTable(changed)
                                    onChange(MdEdit(text.substring(0, b.start) + made + text.substring(b.end), b.start))
                                }, onAsText = { tableAsText = true })
                            } ?: OpenField(field, b, c, ::typed, onKey = { event ->
                                arrowAcross(event, fieldLayout, field, blocks, index, hidden, text, ::open) || onKey(event)
                            }, onLayout = { fieldLayout = it })
                            isOpen -> OpenField(field, b, c, ::typed, onKey = { event ->
                                arrowAcross(event, fieldLayout, field, blocks, index, hidden, text, ::open) || onKey(event)
                            }, onLayout = { fieldLayout = it })
                            else -> Box(Modifier.combinedClickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClick = { open(b.end) }, onLongClick = { menu = true },
                            )) {
                                RenderedBlock(text, b, definitions, c, source, open = ::open, onFollow = onFollow, onUrl = onUrl,
                                    onTask = { at -> onChange(MdEdit(toggleTask(text, at), value.selection.start, value.selection.end)) })
                            }
                        }
                    }
                }
            }
        }
        // Below the last block: a tap writes on at the end, on a new line if the last one has words.
        Box(
            Modifier.fillMaxWidth().height(240.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                val last = blocks.lastOrNull()
                if (last != null && last.kind != LiveKind.BLANK && text.isNotEmpty()) {
                    onEditing(true)
                    onChange(MdEdit(text + "\n", text.length + 1))
                } else open(text.length)
            },
        )
    }
}

/** A heading's fold is remembered by its level and words, so it holds while the note is written above it. */
private fun foldKey(text: String, block: LiveBlock): String = "${block.level}:${headingContent(block.text(text)).second}"

/** The blocks under a folded heading, down to the next heading as big or bigger; never the one open. */
private fun foldedBlocks(text: String, blocks: List<LiveBlock>, folded: Set<String>, open: Int): Set<Int> {
    if (folded.isEmpty()) return emptySet()
    val out = HashSet<Int>()
    var shutLevel = 0
    for ((i, b) in blocks.withIndex()) {
        if (b.kind == LiveKind.HEADING && shutLevel > 0 && b.level <= shutLevel) shutLevel = 0
        if (shutLevel > 0 && i != open) out += i
        if (b.kind == LiveKind.HEADING && shutLevel == 0 && foldKey(text, b) in folded) shutLevel = b.level
    }
    return out
}

/** The break before block [index] taken out, so it joins the one above; null for the first. */
private fun joinWithPrevious(text: String, blocks: List<LiveBlock>, index: Int): MdEdit? {
    val b = blocks.getOrNull(index) ?: return null
    if (b.start == 0) return null
    return MdEdit(text.removeRange(b.start - 1, b.start), b.start - 1)
}

/** Up on the first line of the open block goes to the end of the one above; down on its last, to the start of the next. */
private fun arrowAcross(
    event: KeyEvent, layout: TextLayoutResult?, field: TextFieldValue, blocks: List<LiveBlock>, index: Int,
    hidden: Set<Int>, text: String, open: (Int) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || layout == null) return false
    val line = layout.getLineForOffset(field.selection.end.coerceIn(0, field.text.length))
    return when (event.key) {
        Key.DirectionUp -> if (line == 0) {
            val above = (index - 1 downTo 0).firstOrNull { it !in hidden } ?: return false
            open(blocks[above].end); true
        } else false
        Key.DirectionDown -> if (line == layout.lineCount - 1) {
            val below = (index + 1 until blocks.size).firstOrNull { it !in hidden } ?: return false
            open(blocks[below].start); true
        } else false
        else -> false
    }
}

private fun blockGap(kind: LiveKind) = when (kind) {
    LiveKind.HEADING -> 6.dp
    LiveKind.CODE, LiveKind.TABLE, LiveKind.QUOTE, LiveKind.MATH, LiveKind.FRONT_MATTER -> 6.dp
    LiveKind.LIST_ITEM -> 1.dp
    else -> 2.dp
}

/** The text style of a block, open or drawn: the body, a heading's size, code's fixed width. */
@Composable
private fun blockStyle(block: LiveBlock, c: MdColors): TextStyle {
    val base = TextStyle(color = c.text, fontSize = BODY_SIZE, lineHeight = 1.6.em)
    return when (block.kind) {
        LiveKind.HEADING -> base.copy(fontSize = BODY_SIZE * headingSize(block.level).value, fontWeight = FontWeight.Bold, lineHeight = 1.3.em)
        LiveKind.CODE, LiveKind.MATH, LiveKind.TABLE, LiveKind.FRONT_MATTER, LiveKind.COMMENT ->
            base.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 1.45.em)
        else -> base
    }.forLanguage()
}

/** The open block: its source with every mark showing, dimmed, and its words styled as they read. */
@Composable
private fun OpenField(
    field: TextFieldValue,
    block: LiveBlock,
    c: MdColors,
    onChange: (TextFieldValue) -> Unit,
    onKey: (KeyEvent) -> Boolean,
    onLayout: (TextLayoutResult) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val spans = remember(field.text) {
        markdownSpans(field.text.substring(1)).map { it.copy(start = it.start + 1, end = it.end + 1) }
    }
    val look = remember(spans, field.text.length, c) { LivePreview(spans, 0..field.text.length, c) }
    val boxed = block.kind == LiveKind.CODE || block.kind == LiveKind.MATH || block.kind == LiveKind.TABLE ||
        block.kind == LiveKind.FRONT_MATTER
    BasicTextField(
        value = field,
        onValueChange = onChange,
        textStyle = blockStyle(block, c),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = look,
        onTextLayout = onLayout,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (boxed) Modifier.clip(Ui.Shape.preview).background(c.codeBack).padding(10.dp) else Modifier)
            .focusRequester(focus)
            .onPreviewKeyEvent(onKey)
            .semantics { contentDescription = "Note text" },
    )
    LaunchedEffect(block.start) { runCatching { focus.requestFocus() } }
}

// ---- drawn blocks -------------------------------------------------------------------------------

@Composable
private fun RenderedBlock(
    text: String,
    block: LiveBlock,
    definitions: Map<String, String>,
    c: MdColors,
    source: MarkdownSource,
    open: (Int) -> Unit,
    onFollow: (String) -> Unit,
    onUrl: (String) -> Unit,
    onTask: (Int) -> Unit,
) {
    val raw = block.text(text)
    val style = blockStyle(block, c)
    @Composable
    fun inline(piece: String, at: Int, pieceStyle: TextStyle = style, color: Color = Color.Unspecified, done: Boolean = false) {
        val parsed = remember(piece, at, definitions) { inlineText(piece, at, definitions) }
        if (onlyMedia(parsed)) {
            MediaRow(parsed, source, c, onFollow, open = { open(at) })
        } else {
            InlineView(parsed, pieceStyle.copy(color = if (color != Color.Unspecified) color else pieceStyle.color,
                textDecoration = if (done) TextDecoration.LineThrough else null), c, open, onFollow, onUrl)
        }
    }
    when (block.kind) {
        LiveKind.BLANK -> Spacer(Modifier.fillMaxWidth().height(22.dp))
        LiveKind.PARAGRAPH -> inline(raw, block.start)
        LiveKind.HEADING -> {
            val (offset, words) = headingContent(raw)
            Box(Modifier.semantics { heading() }) { inline(words, block.start + offset) }
        }
        LiveKind.LIST_ITEM -> {
            val parts = listParts(raw) ?: return inline(raw, block.start)
            Row(Modifier.padding(start = (block.level * 26).dp), verticalAlignment = Alignment.Top) {
                when {
                    parts.task != null -> {
                        val boxAt = block.start + raw.indexOf('[', parts.indent.length + parts.mark.length)
                        Checkbox(checked = parts.task, onCheckedChange = { onTask(boxAt) },
                            modifier = Modifier.size(28.dp).padding(end = 4.dp))
                    }
                    parts.mark[0].isDigit() -> Text(AnnotatedString(parts.mark), style = style.copy(color = c.marker),
                        modifier = Modifier.widthIn(min = 26.dp).padding(end = 4.dp))
                    else -> Text(AnnotatedString(listOf("•", "◦", "▪")[block.level % 3]), style = style.copy(color = c.marker,
                        fontWeight = FontWeight.Bold), modifier = Modifier.width(22.dp), textAlign = TextAlign.Center)
                }
                Box(Modifier.weight(1f).padding(top = if (parts.task != null) 2.dp else 0.dp)) {
                    inline(raw.substring(parts.contentStart.coerceAtMost(raw.length)), block.start + parts.contentStart,
                        color = if (parts.task == true) c.done else Color.Unspecified, done = parts.task == true)
                }
            }
        }
        LiveKind.QUOTE -> QuoteBlock(raw, block.start, style, c) { piece, at, pieceStyle -> inline(piece, at, pieceStyle) }
        LiveKind.CODE -> CodeBlock(raw, block, c)
        LiveKind.MATH -> Box(Modifier.fillMaxWidth()) {
            MathBlockView(mathBody(raw))
            // The formula is drawn in a web view; the tap that opens it is caught above it.
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                open(block.end)
            })
        }
        LiveKind.TABLE -> {
            // Drawn, a table reads in the body's letters; only its source is in fixed width.
            val body = TextStyle(color = c.text, fontSize = 15.sp, lineHeight = 1.45.em).forLanguage()
            parseTable(raw)?.let { table ->
                TableView(table, c) { cell, header ->
                    inline(cell, block.start, if (header) body.copy(fontWeight = FontWeight.SemiBold) else body)
                }
            } ?: inline(raw, block.start)
        }
        LiveKind.RULE -> HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = 1.5.dp, color = c.marker.copy(alpha = 0.5f))
        LiveKind.FRONT_MATTER -> PropertiesView(frontMatterProperties(raw), c)
        LiveKind.FOOTNOTE -> {
            val close = raw.indexOf("]:")
            val id = raw.substring(raw.indexOf("[^") + 2, close)
            Row {
                Text(AnnotatedString(id), style = style.copy(fontSize = 13.sp, color = c.link), modifier = Modifier.width(28.dp).padding(top = 2.dp))
                Box(Modifier.weight(1f)) { inline(raw.substring(close + 2).trimStart(), block.start + raw.length - raw.substring(close + 2).trimStart().length,
                    style.copy(fontSize = 14.sp, color = c.quote)) }
            }
        }
        LiveKind.LINK_DEF, LiveKind.COMMENT -> Text(AnnotatedString(raw), style = style.copy(fontSize = 13.sp, color = c.marker,
            fontStyle = FontStyle.Italic, fontFamily = FontFamily.Monospace))
    }
}

/** A line of only pictures and embeds, drawn as them rather than as their names. */
private fun onlyMedia(text: InlineText): Boolean {
    val media = text.spans.filter { it.kind == InlineKind.EMBED || it.kind == InlineKind.IMAGE }
    if (media.isEmpty()) return false
    val rest = StringBuilder(text.shown)
    for (s in media) for (i in s.start until s.end) rest.setCharAt(i, ' ')
    return rest.isBlank()
}

@Composable
private fun MediaRow(text: InlineText, source: MarkdownSource, c: MdColors, onFollow: (String) -> Unit, open: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in text.spans) {
            val target = s.target ?: continue
            when (s.kind) {
                InlineKind.EMBED -> {
                    val (name, width) = embedParts(target)
                    if (isImageName(name)) NoteImage(name, width, text.shown.substring(s.start, s.end), source, c, open)
                    else Surface(color = Color.Transparent, shape = Ui.Shape.preview,
                        modifier = Modifier.fillMaxWidth().border(1.dp, c.marker.copy(alpha = 0.4f), Ui.Shape.preview)
                            .clickable { onFollow(name.substringBefore('#')) }) {
                        Text(AnnotatedString("↳ $name"), color = c.link, modifier = Modifier.padding(12.dp))
                    }
                }
                InlineKind.IMAGE -> NoteImage(target, null, text.shown.substring(s.start, s.end), source, c, open)
                else -> Unit
            }
        }
    }
}

/** Pictures already read, by where they came from; a note scrolled up and down does not read them again. */
private object NoteImages {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(key: String): Bitmap? = cache.get(key)

    /** Read and made no wider than [maxPx]; off the main thread. Pictures on the web are read up to 12 MB. */
    fun load(key: String, maxPx: Int, open: () -> java.io.InputStream?): Bitmap? {
        cache.get(key)?.let { return it }
        val bytes = runCatching { open()?.use { readCapped(it, 12 * 1024 * 1024) } }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > cap) return null
        }
        return out.toByteArray()
    }
}

@Composable
private fun NoteImage(target: String, width: Int?, alt: String, source: MarkdownSource, c: MdColors, open: () -> Unit) {
    val web = target.startsWith("https://", ignoreCase = true) || target.startsWith("http://", ignoreCase = true)
    val maxPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp).roundToPx() }
    val key = (if (web) "" else source.key + "|") + target
    val image by produceState<ImageBitmap?>(NoteImages.cached(key)?.asImageBitmap(), key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            NoteImages.load(key, maxPx) {
                if (web) java.net.URL(target).openConnection().apply { connectTimeout = 8_000; readTimeout = 12_000 }.getInputStream()
                else source.openFile(java.net.URLDecoder.decode(target, "UTF-8"))
            }?.asImageBitmap()
        }
    }
    val shown = image
    if (shown == null) {
        Text(AnnotatedString("{0} (not found)".tr.replace("{0}", alt.ifBlank { target })), color = c.marker,
            fontStyle = FontStyle.Italic, modifier = Modifier.clickable(onClick = open).padding(vertical = 4.dp))
        return
    }
    Image(
        bitmap = shown,
        contentDescription = alt.ifBlank { target },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .then(if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth())
            .heightIn(max = 640.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = open),
        alignment = Alignment.CenterStart,
    )
}

/** Inline Markdown drawn with its marks gone; a tap on a link follows it, elsewhere opens the block there. */
@Composable
private fun InlineView(
    text: InlineText,
    style: TextStyle,
    c: MdColors,
    open: (Int) -> Unit,
    onFollow: (String) -> Unit,
    onUrl: (String) -> Unit,
) {
    val annotated = remember(text, c, style.fontSize) { inlineAnnotatedString(text, c) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        annotated,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().pointerInput(text) {
            detectTapGestures { position ->
                val at = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                val hit = text.spans.firstOrNull { s ->
                    s.kind in TAPPABLE && (at in s.start until s.end || at - 1 in s.start until s.end)
                }
                when (hit?.kind) {
                    InlineKind.LINK -> hit.target?.let { if (it.contains(':')) onUrl(it) else onFollow(it.removeSuffix(".md")) }
                    InlineKind.WIKILINK -> hit.target?.let(onFollow)
                    InlineKind.EMBED -> hit.target?.let { onFollow(embedParts(it).first) }
                    else -> open(text.sourceAt(at))
                }
            }
        },
    )
}

private val TAPPABLE = setOf(InlineKind.LINK, InlineKind.WIKILINK, InlineKind.EMBED)

/** [text]'s spans as Compose styles. */
internal fun inlineAnnotatedString(text: InlineText, c: MdColors): AnnotatedString {
    val b = AnnotatedString.Builder(text.shown)
    for (s in text.spans) {
        if (s.end <= s.start) continue
        val style = when (s.kind) {
            InlineKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            InlineKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            InlineKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            InlineKind.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
            InlineKind.HIGHLIGHT -> SpanStyle(background = c.highlight)
            InlineKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = c.codeBack, fontSize = 0.9.em)
            InlineKind.KBD -> SpanStyle(fontFamily = FontFamily.Monospace, background = c.codeBack, fontSize = 0.85.em)
            InlineKind.LINK -> SpanStyle(color = c.link, textDecoration = TextDecoration.Underline)
            InlineKind.WIKILINK -> SpanStyle(color = c.link)
            InlineKind.EMBED, InlineKind.IMAGE -> SpanStyle(color = c.link, fontStyle = FontStyle.Italic)
            InlineKind.TAG -> SpanStyle(color = c.link, background = c.tagBack)
            InlineKind.MATH -> SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, background = c.codeBack.copy(alpha = 0.6f))
            InlineKind.FOOTNOTE_REF -> SpanStyle(color = c.link, baselineShift = BaselineShift.Superscript, fontSize = 0.7.em)
            InlineKind.SUB -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)
            InlineKind.SUP -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)
            InlineKind.COMMENT -> null
        } ?: continue
        b.addStyle(style, s.start, s.end)
    }
    return b.toAnnotatedString()
}

@Composable
private fun QuoteBlock(raw: String, start: Int, style: TextStyle, c: MdColors, inline: @Composable (String, Int, TextStyle) -> Unit) {
    val lines = remember(raw) { quoteLines(raw) }
    val callout = remember(raw) { calloutOf(lines.joinToString("\n") { it.text }) }
    if (callout != null) {
        val tint = calloutTint(callout.type)
        var open by remember(raw) { mutableStateOf(callout.startsOpen ?: true) }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(Ui.Shape.preview).background(tint.copy(alpha = 0.10f))) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(tint))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = if (callout.startsOpen != null) Modifier.clickable { open = !open } else Modifier) {
                    Text(AnnotatedString(callout.title), style = style.copy(color = tint, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f, fill = false))
                    if (callout.startsOpen != null) Text(AnnotatedString(if (open) "  ▾" else "  ▸"), color = tint)
                }
                if (open) for (line in lines.drop(1)) inline(line.text, start + line.contentStart, style)
            }
        }
        return
    }
    Column {
        for (line in lines) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                repeat(line.depth.coerceAtLeast(1)) {
                    Box(Modifier.padding(end = 10.dp).width(3.dp).fillMaxHeight().background(c.marker.copy(alpha = 0.45f)))
                }
                Box(Modifier.weight(1f)) {
                    if (line.text.isBlank()) Spacer(Modifier.height(8.dp))
                    else inline(line.text, start + line.contentStart, style.copy(color = c.quote))
                }
            }
        }
    }
}

/** The colour Obsidian gives each kind of callout, near enough; anything unknown reads as a note. */
internal fun calloutTint(type: String): Color = when (type) {
    "tip", "hint", "important", "success", "check", "done" -> Color(0xFF2E9E5B)
    "question", "help", "faq" -> Color(0xFFD08A1B)
    "warning", "caution", "attention" -> Color(0xFFE07B1F)
    "failure", "fail", "missing", "danger", "error", "bug" -> Color(0xFFD9443A)
    "example" -> Color(0xFF7B5CD6)
    "abstract", "summary", "tldr" -> Color(0xFF14A3A3)
    "quote", "cite" -> Color(0xFF8A8A8A)
    else -> Color(0xFF3B7DDD)
}

@Composable
private fun CodeBlock(raw: String, block: LiveBlock, c: MdColors) {
    val (_, body) = remember(raw) { codeBody(raw, block.info) }
    val language = block.info.takeIf { it != INDENTED }.orEmpty()
    val clipboard = LocalClipboardManager.current
    val scheme = MaterialTheme.colorScheme
    val coloured = remember(body, language, scheme) {
        val b = AnnotatedString.Builder(body)
        for ((s, e, kind) in codeTokens(body, language)) b.addStyle(SpanStyle(color = when (kind) {
            CodeToken.KEYWORD -> scheme.primary
            CodeToken.STRING -> scheme.tertiary
            CodeToken.COMMENT -> scheme.outline
            CodeToken.NUMBER -> scheme.secondary
        }, fontStyle = if (kind == CodeToken.COMMENT) FontStyle.Italic else null), s, e)
        b.toAnnotatedString()
    }
    Surface(color = c.codeBack, shape = Ui.Shape.preview, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(AnnotatedString(language), style = MaterialTheme.typography.labelSmall, color = c.marker, modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(body)) }) { Text("Copy", style = MaterialTheme.typography.labelSmall) }
            }
            Text(coloured, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 1.45.em, color = c.text,
                softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp))
        }
    }
}

@Composable
private fun TableView(table: MdTable, c: MdColors, cell: @Composable (String, Boolean) -> Unit) {
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, c.marker.copy(alpha = 0.35f), RoundedCornerShape(4.dp))) {
        (listOf(table.header) + table.rows).forEachIndexed { r, row ->
            Row(Modifier.height(IntrinsicSize.Min).background(if (r == 0) c.codeBack else Color.Transparent)) {
                for (column in 0 until table.columns) {
                    Box(Modifier.widthIn(min = 96.dp, max = 280.dp).fillMaxHeight().border(0.5.dp, c.marker.copy(alpha = 0.3f)).padding(8.dp),
                        contentAlignment = when (table.align[column]) {
                            TableAlign.CENTER -> Alignment.TopCenter
                            TableAlign.RIGHT -> Alignment.TopEnd
                            else -> Alignment.TopStart
                        }) {
                        cell(row.getOrElse(column) { "" }, r == 0)
                    }
                }
            }
        }
    }
}

/**
 * The open table, Notion's way: each cell its own box to type in, rows and
 * columns added and taken away around the one being written, a column's
 * alignment set, or the table written as text instead.
 */
@Composable
private fun TableEditor(table: MdTable, onChange: (MdTable) -> Unit, onAsText: () -> Unit) {
    val c = mdColors()
    var row by remember { mutableIntStateOf(-1) }
    var column by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(4.dp))) {
            (listOf(table.header) + table.rows).forEachIndexed { i, cells ->
                val r = i - 1
                Row(Modifier.height(IntrinsicSize.Min).background(if (r < 0) c.codeBack else Color.Transparent)) {
                    for (col in 0 until table.columns) {
                        val chosen = r == row && col == column
                        var cellValue by remember(r, col) { mutableStateOf(TextFieldValue(cells[col])) }
                        if (cellValue.text != cells[col] && cellValue.composition == null) cellValue = TextFieldValue(cells[col], TextRange(cells[col].length))
                        BasicTextField(
                            value = cellValue,
                            onValueChange = { v ->
                                cellValue = v
                                row = r; column = col
                                if (v.text != cells[col]) onChange(table.withCell(r, col, v.text.replace("\n", " ")))
                            },
                            textStyle = TextStyle(color = c.text, fontSize = 15.sp, fontWeight = if (r < 0) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = when (table.align[col]) {
                                    TableAlign.CENTER -> TextAlign.Center
                                    TableAlign.RIGHT -> TextAlign.End
                                    else -> TextAlign.Start
                                }).forLanguage(),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine = true,
                            modifier = Modifier.width(140.dp).fillMaxHeight()
                                .border(if (chosen) 1.5.dp else 0.5.dp, if (chosen) MaterialTheme.colorScheme.primary else c.marker.copy(alpha = 0.3f))
                                .onFocusChanged { if (it.isFocused) { row = r; column = col } }
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
        // What can be done around the chosen cell.
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TableButton("+ Row above", enabled = row >= 0) { onChange(table.withRow(row)) }
            TableButton("+ Row below") { onChange(table.withRow(row + 1)) }
            TableButton("+ Column left") { onChange(table.withColumn(column)); column += 1 }
            TableButton("+ Column right") { onChange(table.withColumn(column + 1)) }
            TableButton("Delete row", enabled = row >= 0) { onChange(table.withoutRow(row)); row = (row - 1).coerceAtLeast(-1) }
            TableButton("Delete column", enabled = table.columns > 1) { onChange(table.withoutColumn(column)); column = column.coerceAtMost(table.columns - 2) }
            TableButton("Align left") { onChange(table.withAlign(column, TableAlign.LEFT)) }
            TableButton("Align center") { onChange(table.withAlign(column, TableAlign.CENTER)) }
            TableButton("Align right") { onChange(table.withAlign(column, TableAlign.RIGHT)) }
            TableButton("Edit as text", onClick = onAsText)
        }
    }
}

@Composable
private fun TableButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PropertiesView(properties: List<Pair<String, String>>, c: MdColors) {
    Surface(color = Color.Transparent, shape = Ui.Shape.preview,
        modifier = Modifier.fillMaxWidth().border(1.dp, c.marker.copy(alpha = 0.35f), Ui.Shape.preview)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("Properties", style = MaterialTheme.typography.labelMedium, color = c.marker)
            for ((key, value) in properties) Row(Modifier.padding(vertical = 3.dp)) {
                Text(AnnotatedString(key), color = c.marker, fontSize = 14.sp, modifier = Modifier.width(120.dp))
                Text(AnnotatedString(value), color = c.text, fontSize = 14.sp)
            }
        }
    }
}

/** The handle's menu: turn the block into another kind, move it, copy it, take it out. */
@Composable
private fun BlockMenu(expanded: Boolean, onDismiss: () -> Unit, onAction: (BlockAction) -> Unit) {
    GlassMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text("Turn into", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        BlockStyle.entries.forEach { style -> GlassMenuItem(text = { Text(style.label) }, onClick = { onAction(BlockAction.Turn(style)) }) }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        GlassMenuItem(text = { Text("Move up") }, onClick = { onAction(BlockAction.Up) })
        GlassMenuItem(text = { Text("Move down") }, onClick = { onAction(BlockAction.Down) })
        GlassMenuItem(text = { Text("Duplicate") }, onClick = { onAction(BlockAction.Duplicate) })
        GlassMenuItem(text = { Text("Copy") }, onClick = { onAction(BlockAction.Copy) })
        GlassMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { onAction(BlockAction.Delete) })
    }
}
