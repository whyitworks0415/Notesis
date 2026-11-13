package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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

    private var commands: NoteCommands? = null

    /**
     * The commands, one set for the app: the terminal and an agent's connection share them,
     * so one undo history and one line at a time (NoteCommands is synchronized).
     */
    @Synchronized
    fun commands(context: android.content.Context): NoteCommands =
        commands ?: NoteCommands(NoteStoreWorkspace(context.applicationContext, SharedNotes.store(context)) { OpenNotes.isOpen(it) })
            .also { commands = it }

    /** For tests: a fresh set of commands, for the store the next asking finds. */
    @Synchronized
    internal fun resetForTests() { commands = null; lines.clear() }

    fun show() { open = true }

    fun print(text: String, tone: TerminalTone) {
        lines += TerminalLine(text, tone)
        // A session left open all day keeps its last few thousand lines.
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    private const val MAX_LINES = 2_000
}

/**
 * The terminal, while it is open: on the panel every tool opens on
 * ([ToolPanel]), grown out of the point that opened it and folded back into
 * it.
 */
@Composable
internal fun TerminalHost(store: NoteStore) {
    if (TerminalSession.open) TerminalPanel(store)
}

/** How tall the terminal's screen is at most, and what the panel takes round it (header, keys, insets). */
private val TERMINAL_HEIGHT = 520.dp
private val TERMINAL_CHROME = 150.dp

@Composable
private fun TerminalPanel(store: NoteStore) {
    val state = rememberToolPanelState()
    val onClose = { state.close { TerminalSession.open = false } }
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
                runCatching { session.commands(context).runAll(lines) }
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

    ToolPanel(
        title = "Terminal",
        state = state,
        onOutside = onClose,
        maxWidth = 760.dp,
        actions = {
            if (session.running) Text("Running…", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            TextButton(onClick = { AgentPanelState.open = true }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) { Text("Agents") }
            TextButton(onClick = { session.lines.clear() }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) { Text("Clear") }
            IconButton(onClick = onClose) { Icon(Reicons.Close, contentDescription = "Close") }
        },
    ) {
        // One height whatever is on it, the keyboard's room taken off, so the panel stands still as lines come in.
        val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val room = LocalConfiguration.current.screenHeightDp.dp * 0.9f - keyboard - TERMINAL_CHROME
        val inner = RoundedCornerShape(Ui.concentric(chromeCorner(), TOOL_PANEL_INSET))
        Column(
            Modifier.fillMaxWidth().height(room.coerceIn(160.dp, TERMINAL_HEIGHT))
                .clip(inner).background(scheme.surfaceContainerLowest),
        ) {
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Ui.Space.m, vertical = Ui.Space.s)) {
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
            HorizontalDivider(color = scheme.outlineVariant)
            // The line being written.
            Row(Modifier.fillMaxWidth().padding(start = Ui.Space.m, end = Ui.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(AnnotatedString("notesis ›"), style = mono, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(Ui.Space.s))
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
                // Always in reach at the line's end, whatever the keys beside it scroll to.
                TextButton(onClick = { run() }, enabled = !session.running,
                    modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) { Text("Run") }
            }
        }
        // The keys a tablet's keyboard lacks, as the panels' own chips.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
            SettingsChoiceChip(false, { complete() }, "Tab")
            SettingsChoiceChip(ctrl, { ctrl = !ctrl }, "Ctrl")
            SettingsChoiceChip(false, { set(""); ctrl = false }, "Esc")
            SettingsChoiceChip(false, { move(-1) }, "←")
            SettingsChoiceChip(false, { move(1) }, "→")
            SettingsChoiceChip(false, { history(up = true) }, "↑")
            SettingsChoiceChip(false, { history(up = false) }, "↓")
            SettingsChoiceChip(false, { set(value.text, 0) }, "Home")
            SettingsChoiceChip(false, { set(value.text) }, "End")
            for (sign in listOf("--", "\"", "-", "/", "|", ",")) SettingsChoiceChip(false, {
                val at = value.selection.start
                set(value.text.substring(0, at) + sign + value.text.substring(value.selection.end), at + sign.length)
            }, sign)
        }
    }
}
