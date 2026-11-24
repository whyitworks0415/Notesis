package com.notesis

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How Termux is opened from Notesis: beside it (split screen), in a window over it, or on its own. */
internal enum class TermuxWindow { BESIDE, WINDOW, FULL }

/**
 * Opens Termux [window]-wise; false when it is not installed. Beside asks for
 * the other half of a split screen, which large screens give from Android 12L
 * on; a window asks for bounds, which the system honours where it has
 * windows (Samsung's pop-up view, desktop modes) and otherwise opens it whole.
 */
internal fun openTermux(context: Context, window: TermuxWindow): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    var options: android.os.Bundle? = null
    when (window) {
        TermuxWindow.BESIDE -> intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        TermuxWindow.WINDOW -> {
            val metrics = context.resources.displayMetrics
            val width = (metrics.widthPixels * 0.46f).toInt()
            val height = (metrics.heightPixels * 0.62f).toInt()
            val left = metrics.widthPixels - width - (metrics.widthPixels * 0.03f).toInt()
            val top = (metrics.heightPixels * 0.12f).toInt()
            options = ActivityOptions.makeBasic().setLaunchBounds(android.graphics.Rect(left, top, left + width, top + height)).toBundle()
        }
        TermuxWindow.FULL -> Unit
    }
    return runCatching { context.startActivity(intent, options) }.isSuccess
}

/** The agent sidebar, over the right edge of whatever is open, the page still usable beside it. */
@Composable
internal fun AgentSidebarHost() {
    Box(Modifier.fillMaxSize()) {
        SlideInSheet(visible = AgentChat.open) { appear -> AgentSidebar(appear) }
    }
}

@Composable
private fun AgentSidebar(appear: () -> Float) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chat = AgentChat
    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var notesRunning by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val termux = remember { context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) != null }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val waiting = chat.waiting
    val waitingHere = waiting != null && chat.waitingThread == chat.thread.id
    val scheme = MaterialTheme.colorScheme

    fun allowed(text: String): Boolean {
        if (context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED) return true
        pending = text
        return false
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val text = pending
        pending = null
        // Allowed, what was typed is still there to send.
        if (!granted && text != null) chat.say(context,
            "Notesis may not ask Termux to run commands. Allow it under Settings → Apps → Notesis → Permissions (Run commands in Termux).",
            failed = true)
    }

    /** A note command, here and at once: no agent, no tokens. */
    fun runNotes(line: String) {
        chat.mine(context, "notesis $line")
        notesRunning = true
        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                runCatching { TerminalSession.commands(context).runAll(listOf(line)) }
                    .getOrElse { "{\"ok\":false,\"error\":${org.json.JSONObject.quote(it.message ?: it.javaClass.simpleName)}}" }
            }
            val out = terminalOutput(answer)
            chat.say(context, "```\n" + out.joinToString("\n") { clipLines(it.text, 200) } + "\n```",
                failed = out.any { it.tone == TerminalTone.ERROR })
            if (terminalChangedNotes(answer)) LibraryChanges.changed()
            notesRunning = false
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || waiting != null || notesRunning) return
        when (val input = parseChatInput(text)) {
            is ChatInput.Notes -> { draft = ""; runNotes(input.line) }
            is ChatInput.Slash -> when (input.name) {
                "new" -> { draft = ""; chat.newThread(context) }
                "close" -> { draft = ""; chat.closeThread(context) }
                "clear" -> { draft = ""; chat.clear(context) }
                "stop" -> { draft = ""; if (waiting != null) chat.stopWaiting(context) }
                "sessions" -> { draft = ""; if (allowed(text)) chat.listSessions(context) else permission.launch(TERMUX_RUN_COMMAND_PERMISSION) }
                "agent" -> {
                    draft = ""
                    val cli = AgentCli.entries.firstOrNull { it.command == input.rest.lowercase() || it.label.equals(input.rest, true) }
                    if (cli != null) chat.choose(context, cli)
                    else chat.say(context, "Which agent? " + AgentCli.entries.joinToString(", ") { it.command })
                }
                "help" -> {
                    draft = ""
                    chat.say(context, SIDEBAR_COMMANDS.entries.joinToString("\n") { "- **/${it.key}**: ${it.value.tr}" } +
                        "\n- **!line**: " + "runs the line in Debian, its output shown as it comes".tr +
                        "\n- **notesis …**: " + "a note command, run here at once without the agent".tr +
                        "\n- **/other**: " + "passed to the agent as its own command".tr)
                }
                // Anything else is the agent's own command, as typed.
                else -> if (allowed(text)) { if (chat.send(context, text)) draft = "" } else permission.launch(TERMUX_RUN_COMMAND_PERMISSION)
            }
            is ChatInput.Shell -> if (input.line.isNotEmpty()) {
                if (allowed(text)) { if (chat.send(context, input.line, shell = true)) draft = "" } else permission.launch(TERMUX_RUN_COMMAND_PERMISSION)
            }
            is ChatInput.Ask -> if (allowed(text)) { if (chat.send(context, text)) draft = "" } else permission.launch(TERMUX_RUN_COMMAND_PERMISSION)
        }
    }

    LaunchedEffect(waiting) {
        while (chat.waiting != null) { now = System.currentTimeMillis(); delay(1_000) }
    }
    // Follows what comes in - each step as it arrives - unless the owner has scrolled up to read.
    LaunchedEffect(chat.thread.id, chat.messages.size, chat.live.size, AgentActivity.lines.size) {
        val count = chat.messages.size + (if (waitingHere) 1 else 0)
        if (count > 0 && (!list.canScrollForward || (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= count - 2)) {
            list.animateScrollToItem(count - 1, Int.MAX_VALUE)
        }
    }

    val prefs = remember { context.getSharedPreferences("agent-chat", android.content.Context.MODE_PRIVATE) }
    var width by remember { mutableFloatStateOf(prefs.getFloat("width", 420f)) }
    SettingsSidebarFrame(
        title = "Ask an agent", canGoBack = false, onBack = {}, onClose = { chat.open = false },
        widthDp = width, onWidthChange = { width = it; prefs.edit().putFloat("width", it).apply() }, appear = appear,
        closeLabel = "Close the agent panel", resizeLabel = "Resize the agent panel",
        actions = {
            if (termux) IconButton(onClick = { openTermux(context, TermuxWindow.BESIDE) }) {
                Icon(AppIcons.terminal, contentDescription = "Open Termux beside")
            }
            IconButton(onClick = { chat.newThread(context) }) { Icon(AppIcons.addPage, contentDescription = "New conversation") }
        },
    ) {
    // Above the keyboard: typing, the conversation and the field stay in sight, the newest at the bottom.
    val keyboard = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(keyboard > 0) {
        val count = chat.messages.size + (if (waitingHere) 1 else 0)
        if (keyboard > 0 && count > 0) list.scrollToItem(count - 1, Int.MAX_VALUE)
    }
    Column(
        Modifier.fillMaxSize().imePadding().padding(horizontal = Ui.Space.l, vertical = Ui.Space.s),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
    ) {
        // The conversations, a tab each: held, a tab closes.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
            chat.threads.forEachIndexed { index, t ->
                val busy = waiting != null && chat.waitingThread == t.id
                SettingsChoiceChip(
                    selected = index == chat.current,
                    onClick = { chat.select(context, index) },
                    label = (if (busy) "● " else "") + t.title.ifEmpty { "New conversation".tr } + " · " + t.agent.label.substringBefore(' '),
                )
            }
            if (chat.threads.size > 1 && !waitingHere) IconButton(onClick = { chat.closeThread(context) }) {
                Icon(AppIcons.close, contentDescription = "Close this conversation")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
            for (cli in AgentCli.entries) SettingsChoiceChip(cli == chat.agent, { if (!waitingHere) chat.choose(context, cli) }, cli.label)
        }
        // The agent's own earlier conversations, to carry one on in a tab.
        val picking = chat.sessions != null || chat.loadingSessions || chat.sessionsError != null
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (picking) chat.closeSessions()
                else if (context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(TERMUX_RUN_COMMAND_PERMISSION)
                } else chat.listSessions(context)
            }, enabled = termux && waiting == null) { Text(if (picking) "Back to the conversation" else "Earlier conversations") }
            chat.note?.let { (_, title) ->
                Text(AnnotatedString("📝 " + title), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (picking) SessionPicker(Modifier.weight(1f).fillMaxWidth()) { chat.pick(context, it) }
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            if (chat.messages.isEmpty()) item {
                Text(
                    if (termux) "Ask in your own words: “put an outline in this note from its PDF”, “sum up today's notes”. " +
                        "The agent runs in Termux and changes notes through Notesis, so each change shows at once and can be undone."
                    else "The agents run in Termux. Set them up first: Agent connection → On this tablet.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
            if (chat.messages.isEmpty() && termux) item {
                Text("Type / for commands, ! to run a line in Debian, notesis … for a note command without the agent.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            itemsIndexed(chat.messages) { _, message -> ChatBubble(message) }
            if (waitingHere) item {
                Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                    // No running animation while it waits: one kept the whole screen redrawing every frame,
                    // for as many minutes as an agent takes. The clock below moves once a second.
                    val seconds = ((now - chat.sentAt) / 1000).coerceAtLeast(0)
                    Text("{0} is working in Termux · {1}".tr.replace("{0}", chat.agent.label)
                        .replace("{1}", "%d:%02d".format(seconds / 60, seconds % 60)),
                        style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    // What it does, as it does it: its thinking, its words, the tools it calls and what they answer.
                    for (step in chat.live.takeLast(LIVE_SHOWN)) LiveStepRow(step)
                    // What it has asked of the notes, through the connection, when no steps come (an older helper).
                    if (chat.live.isEmpty()) for (line in AgentActivity.lines.drop(chat.activityFrom).takeLast(8)) {
                        Text(AnnotatedString(line), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { chat.stopWaiting(context) }) { Text("Stop waiting") }
                }
            }
        }
        // `/`: the commands there are, to tap rather than remember.
        if (draft.startsWith("/") && !draft.contains(' ')) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                for (name in SIDEBAR_COMMANDS.keys.filter { it.startsWith(draft.drop(1).lowercase()) }) {
                    SettingsChoiceChip(false, { draft = if (name == "agent") "/agent " else "/$name" }, "/$name")
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).heightIn(min = Ui.Size.touch),
                placeholder = { Text("Ask, / for commands, ! for a shell line…") },
                maxLines = 6,
                enabled = termux || draft.startsWith("notesis") || draft.startsWith("/"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Button(onClick = ::send, enabled = waiting == null && !notesRunning && draft.isNotBlank()) { Text("Send") }
        }
        if (chat.messages.isNotEmpty() && !waitingHere) {
            Text(if (chat.thread.agentSession != null || chat.thread.carryOn) "The next request carries on this conversation." else "The next request starts afresh.",
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
    }
}

/** One step an agent took, as it is shown while it works and under its answer. */
@Composable
private fun LiveStepRow(step: LiveStep) {
    val scheme = MaterialTheme.colorScheme
    when (step.kind) {
        LiveStep.THINKING -> Text(AnnotatedString(step.text.trim()), style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
        LiveStep.TEXT -> Text(AnnotatedString(step.text.trim()), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        LiveStep.TOOL -> Text(AnnotatedString("› " + step.text.trim()), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = scheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        LiveStep.OUTPUT -> Text(AnnotatedString(step.text.trimEnd()), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = scheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, Ui.Shape.badge).padding(Ui.Space.xs))
        LiveStep.USAGE -> usageLine(step.text)?.let {
            Text(AnnotatedString(it), style = MaterialTheme.typography.labelSmall, color = scheme.outline)
        }
    }
}

/** The agent's earlier conversations, newest first; one tapped is carried on from the next request. */
@Composable
private fun SessionPicker(modifier: Modifier, onPick: (AgentSession) -> Unit) {
    val chat = AgentChat
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            chat.loadingSessions -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Asking Termux for {0}'s earlier conversations…".tr.replace("{0}", chat.agent.label),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            chat.sessionsError != null -> Text(AnnotatedString(chat.sessionsError!!.tr), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            chat.sessions.isNullOrEmpty() -> Text(
                "No earlier conversations were found. If you used this agent before, run the setup script again in Termux: it adds what lists them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(chat.sessions!!) { _, session ->
                    TextButton(onClick = { onPick(session) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(AnnotatedString(session.title), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            if (session.at.isNotBlank()) Text(AnnotatedString(session.at), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val scheme = MaterialTheme.colorScheme
    var stepsShown by remember(message) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        // How it got there, folded under the answer: what it thought, the tools it called, what it cost.
        if (message.steps.isNotEmpty()) {
            val usage = message.steps.lastOrNull { it.kind == LiveStep.USAGE }?.let { usageLine(it.text) }
            val count = message.steps.count { it.kind != LiveStep.USAGE }
            TextButton(onClick = { stepsShown = !stepsShown }) {
                Text(AnnotatedString((if (stepsShown) "▾ " else "▸ ") + "{0} steps".tr.replace("{0}", count.toString()) +
                    (usage?.let { " · $it" } ?: "")), style = MaterialTheme.typography.labelSmall)
            }
            if (stepsShown) Column(Modifier.fillMaxWidth().padding(start = Ui.Space.s), verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                for (step in message.steps) if (step.kind != LiveStep.USAGE) LiveStepRow(step)
            }
        }
        SelectionContainer {
            // The app's own words (a failure, a hint) are translated; the agent's and the owner's are shown as written.
            Text(
                when {
                    message.failed -> AnnotatedString(message.text.tr)
                    message.mine -> AnnotatedString(message.text)
                    // Agents answer in Markdown: headings, lists, bold and code read as they are meant to.
                    else -> remember(message.text, scheme.primary) {
                        markdownPreviewText(markdownPreview(message.text, maxChars = Int.MAX_VALUE), 14.sp, scheme.primary, scheme.onSurfaceVariant)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when { message.failed -> scheme.onErrorContainer; message.mine -> scheme.onPrimaryContainer; else -> scheme.onSurface },
                modifier = Modifier
                    // As wide as the panel lets it, less a margin on the other side: a long answer is not a narrow column.
                    .fillMaxWidth(if (message.mine) 0.85f else 1f)
                    .wrapContentWidth(if (message.mine) Alignment.End else Alignment.Start)
                    .background(
                        when { message.failed -> scheme.errorContainer; message.mine -> scheme.primaryContainer; else -> scheme.surfaceContainerHighest },
                        Ui.Shape.chip,
                    )
                    .padding(horizontal = Ui.Space.m, vertical = Ui.Space.s),
            )
        }
    }
}

/** Names [note] to the sidebar for as long as it is on screen, so a request about "this note" finds it. */
@Composable
internal fun AgentChatNote(note: NoteMeta) {
    androidx.compose.runtime.DisposableEffect(note.id, note.title) {
        val mine = note.id to note.title
        AgentChat.note = mine
        onDispose { if (AgentChat.note == mine) AgentChat.note = null }
    }
}

/** How many of the steps a working agent has taken are shown at once; the rest are folded under its answer. */
private const val LIVE_SHOWN = 40
