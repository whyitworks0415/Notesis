package com.notesis

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The terminal (Todo.md, T1): a panel that comes up from the foot of any
 * screen and runs the note commands (NoteCommands.kt) as lines typed in -
 * no Linux under it, only `notesis` and a few words of its own. Fixed-width
 * letters, a row of the keys a tablet's keyboard lacks (Tab, Ctrl, Esc, the
 * arrows), Tab to complete, up and down through what was run.
 */

/** Tells the library its notes were changed from elsewhere (the terminal), so it reads them again. */
internal object LibraryChanges {
    var version by mutableIntStateOf(0)
        private set

    fun changed() { version++ }
}

/** The terminal's session: kept while the app runs, so closing the panel loses neither what is on it nor undo. */
internal object TerminalSession {
    var open by mutableStateOf(false)
    val lines = mutableStateListOf<TerminalLine>()
    val history = TerminalHistory()
    var running by mutableStateOf(false)

    /** The note on screen now, which the commands may read but not change. */
    @Volatile var openNoteId: String? = null

    private var commands: NoteCommands? = null

    fun commands(context: android.content.Context, store: NoteStore): NoteCommands =
        commands ?: NoteCommands(NoteStoreWorkspace(context.applicationContext, store) { openNoteId }).also { commands = it }

    fun show() { open = true }

    fun print(text: String, tone: TerminalTone) {
        lines += TerminalLine(text, tone)
        // A session left open all day keeps its last few thousand lines.
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    private const val MAX_LINES = 2_000
}

/** How tall the panel is, as a share of the screen above the keyboard; dragging its handle changes it. */
private const val MIN_SHARE = 0.25f
private const val MAX_SHARE = 0.92f

/**
 * The terminal over everything, at the foot of the window, while it is open.
 * [openNoteId] is the note on screen now.
 */
@Composable
internal fun TerminalHost(store: NoteStore, openNoteId: String?) {
    SideEffect { TerminalSession.openNoteId = openNoteId }
    val open = TerminalSession.open
    val moves = rememberMotionAllowed()
    val shown = remember { Animatable(if (open) 1f else 0f) }
    var present by remember { mutableStateOf(open) }
    LaunchedEffect(open, moves) {
        if (open) {
            present = true
            if (moves) shown.animateTo(1f, MaterialMotion.rawContainer<Float>().paced()) else shown.snapTo(1f)
        } else {
            if (moves) shown.animateTo(0f, MaterialMotion.rawExit<Float>().paced()) else shown.snapTo(0f)
            present = false
        }
    }
    if (!present) return
    var share by remember { mutableFloatStateOf(0.45f) }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
        val total = maxHeight
        val density = LocalDensity.current
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 1100.dp)
                .height(total * share)
                // Up out of the foot of the screen, the edge it belongs to, and back down into it.
                .graphicsLayer { translationY = (1f - shown.value) * size.height }
                .skinMaterial(RoundedCornerShape(topStart = Ui.Corner.card, topEnd = Ui.Corner.card), panel = true)
                .clip(RoundedCornerShape(topStart = Ui.Corner.card, topEnd = Ui.Corner.card)),
        ) {
            // The handle: dragged, the panel grows or shrinks.
            Box(
                Modifier.fillMaxWidth().height(20.dp)
                    .pointerInput(total) {
                        detectVerticalDragGestures { _, drag ->
                            val totalPx = with(density) { total.toPx() }
                            share = (share - drag / totalPx).coerceIn(MIN_SHARE, MAX_SHARE)
                        }
                    }
                    .semantics { contentDescription = "Resize the terminal" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant))
            }
            TerminalPanel(store, onClose = { TerminalSession.open = false })
        }
    }
}

@Composable
private fun TerminalPanel(store: NoteStore, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val session = TerminalSession
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var ctrl by remember { mutableStateOf(false) }
    var names by remember { mutableStateOf(emptyList<String>()) }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)

    // The notes' names, for Tab; read again after every command.
    suspend fun readNames() {
        names = withContext(Dispatchers.IO) { runCatching { (store.list() + store.trashed()).map { it.title }.distinct() }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(Unit) {
        if (session.lines.isEmpty()) {
            session.print("Notesis terminal. `help` lists the commands, Tab completes, ↑ ↓ go back through what was run. A note open on screen is only read.".tr, TerminalTone.HINT)
        }
        readNames()
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(session.lines.size) {
        if (session.lines.isNotEmpty()) list.scrollToItem(session.lines.lastIndex)
    }

    fun set(text: String, cursor: Int = text.length) { value = TextFieldValue(text, TextRange(cursor.coerceIn(0, text.length))) }

    fun run() {
        if (session.running) return
        val typed = value.text
        val lines = terminalLines(typed)
        set("")
        if (lines.isEmpty()) { session.print("$ ", TerminalTone.INPUT); return }
        for (line in lines) { session.print("$ $line", TerminalTone.INPUT); session.history.add(line) }
        if (lines.size == 1) when (terminalBuiltin(lines[0])) {
            TerminalBuiltin.CLEAR -> { session.lines.clear(); return }
            TerminalBuiltin.HISTORY -> {
                session.print(session.history.all.withIndex().joinToString("\n") { (i, l) -> "${(i + 1).toString().padStart(4)}  $l" }, TerminalTone.OUTPUT)
                return
            }
            TerminalBuiltin.EXIT -> { onClose(); return }
            null -> Unit
        }
        session.running = true
        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                runCatching { session.commands(context, store).runAll(lines) }
                    .getOrElse { "{\"ok\":false,\"error\":${org.json.JSONObject.quote(it.message ?: it.javaClass.simpleName)}}" }
            }
            for (out in terminalOutput(answer)) session.print(clipLines(out.text, 400), out.tone)
            if (terminalChangedNotes(answer)) LibraryChanges.changed()
            session.running = false
            readNames()
        }
    }

    fun complete() {
        val done = completeLine(value.text, value.selection.start, names)
        if (done.options.size > 1) {
            session.print("$ ${value.text}", TerminalTone.INPUT)
            session.print(done.options.joinToString("   "), TerminalTone.HINT)
        }
        set(done.line, done.cursor)
    }

    fun history(up: Boolean) {
        val line = if (up) session.history.up(value.text) else session.history.down()
        if (line != null) set(line)
    }

    fun move(by: Int) { set(value.text, value.selection.start + by) }

    /** Ctrl and a letter, as a shell has them. */
    fun control(letter: Char): Boolean {
        when (letter.lowercaseChar()) {
            'c' -> { session.print("$ ${value.text}^C", TerminalTone.INPUT); set("") }
            'l' -> session.lines.clear()
            'u' -> set(value.text.substring(value.selection.start), 0)
            'k' -> set(value.text.substring(0, value.selection.start))
            'a' -> set(value.text, 0)
            'e' -> set(value.text)
            'd' -> if (value.text.isEmpty()) onClose()
            else -> return false
        }
        return true
    }

    fun key(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.isCtrlPressed) {
            val letter = (event.key.nativeKeyCode - android.view.KeyEvent.KEYCODE_A).takeIf { it in 0..25 }?.let { 'a' + it }
            return letter != null && control(letter)
        }
        return when (event.key) {
            Key.Enter, Key.NumPadEnter -> if (event.isShiftPressed) false else { run(); true }
            Key.Tab -> { complete(); true }
            Key.DirectionUp -> { history(up = true); true }
            Key.DirectionDown -> { history(up = false); true }
            Key.Escape -> { set(""); true }
            else -> false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Terminal", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (session.running) Text("Running…", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp))
            TextButton(onClick = { session.lines.clear() }) { Text("Clear") }
            TextButton(onClick = onClose) { Text("Close") }
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                itemsIndexed(session.lines) { _, line ->
                    Text(AnnotatedString(line.text), style = mono, color = when (line.tone) {
                        TerminalTone.INPUT -> scheme.primary
                        TerminalTone.OUTPUT -> scheme.onSurface
                        TerminalTone.ERROR -> scheme.error
                        TerminalTone.HINT -> scheme.onSurfaceVariant
                    }, fontWeight = if (line.tone == TerminalTone.INPUT) FontWeight.SemiBold else null,
                        modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
        // The line being written.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Text(AnnotatedString("notesis ›"), style = mono, color = scheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = { next ->
                    val grew = next.text.length > value.text.length && next.selection.collapsed
                    val char = if (grew) next.text.getOrNull(next.selection.start - 1) else null
                    when {
                        // Ctrl from the key row, then a letter from the keyboard.
                        ctrl && char != null && char.isLetter() && next.text.length == value.text.length + 1 -> { ctrl = false; control(char) }
                        // A line break just before the cursor is Enter, or a pasted block that ends in one, and runs
                        // what is there, as a shell does; a pasted block without one waits for Enter.
                        char == '\n' -> { value = next.copy(text = next.text.removeRange(next.selection.start - 1, next.selection.start)); run() }
                        else -> value = next
                    }
                },
                textStyle = mono.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    imeAction = ImeAction.None),
                modifier = Modifier.weight(1f).heightIn(min = 22.dp).focusRequester(focus).onPreviewKeyEvent(::key)
                    .semantics { contentDescription = "Command line" },
            )
        }
        // The keys a tablet's keyboard lacks.
        Row(
            Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh).horizontalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TerminalKey("Tab") { complete() }
            TerminalKey("Ctrl", selected = ctrl) { ctrl = !ctrl }
            TerminalKey("Esc") { set(""); ctrl = false }
            TerminalKey("←") { move(-1) }
            TerminalKey("→") { move(1) }
            TerminalKey("↑") { history(up = true) }
            TerminalKey("↓") { history(up = false) }
            TerminalKey("Home") { set(value.text, 0) }
            TerminalKey("End") { set(value.text) }
            for (sign in listOf("--", "\"", "-", "/", "|", ",")) TerminalKey(sign) {
                val at = value.selection.start
                set(value.text.substring(0, at) + sign + value.text.substring(value.selection.end), at + sign.length)
            }
            TerminalKey("↵ Run") { run() }
        }
    }
}

@Composable
private fun TerminalKey(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(AnnotatedString(label), fontFamily = FontFamily.Monospace, fontSize = 14.sp,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface)
    }
}
