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
    val chat = AgentChat
    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val termux = remember { context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) != null }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val waiting = chat.waiting

    fun deliver(text: String) {
        if (chat.send(context, text)) draft = ""
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val text = pending
        pending = null
        if (granted && text != null) deliver(text)
        else if (!granted) chat.messages += ChatMessage(false,
            "Notesis may not ask Termux to run commands. Allow it under Settings → Apps → Notesis → Permissions (Run commands in Termux).",
            failed = true)
    }
    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || waiting != null) return
        if (context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            pending = text
            permission.launch(TERMUX_RUN_COMMAND_PERMISSION)
        } else deliver(text)
    }

    LaunchedEffect(waiting) {
        while (chat.waiting != null) { now = System.currentTimeMillis(); delay(1_000) }
    }
    LaunchedEffect(chat.messages.size, AgentActivity.lines.size) {
        val count = chat.messages.size + (if (chat.waiting != null) 1 else 0)
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    // As wide as the owner last dragged it, kept between openings; the same panel as settings and the pages.
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
            IconButton(onClick = { chat.clear(context) }, enabled = waiting == null) {
                Icon(AppIcons.reset, contentDescription = "New conversation")
            }
        },
    ) {
    Column(
        Modifier.fillMaxSize().padding(Ui.Space.l),
        verticalArrangement = Arrangement.spacedBy(Ui.Space.s),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (cli in AgentCli.entries) SettingsChoiceChip(cli == chat.agent, { if (waiting == null) chat.choose(context, cli) }, cli.label)
        }
        // The agent's own earlier conversations, to carry one on from here.
        val picking = chat.sessions != null || chat.loadingSessions || chat.sessionsError != null
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (picking) chat.closeSessions()
                else if (context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(TERMUX_RUN_COMMAND_PERMISSION)
                } else chat.listSessions(context)
            }, enabled = termux && waiting == null) { Text(if (picking) "Back to the conversation" else "Earlier conversations") }
            chat.resume?.let { session ->
                Text(AnnotatedString(session.title), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            }
        }
        chat.note?.let { (_, title) ->
            Text(AnnotatedString("📝 " + title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1)
        }
        if (picking) SessionPicker(Modifier.weight(1f).fillMaxWidth()) { chat.pick(context, it) }
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chat.messages.isEmpty()) item {
                Text(
                    if (termux) "Ask in your own words: “put an outline in this note from its PDF”, “sum up today's notes”. " +
                        "The agent runs in Termux and changes notes through Notesis, so each change shows at once and can be undone."
                    else "The agents run in Termux. Set them up first: Agent connection → On this tablet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(chat.messages) { _, message -> ChatBubble(message) }
            if (waiting != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // No running animation while it waits: one kept the whole screen redrawing every frame,
                    // for as many minutes as an agent takes. The clock below moves once a second.
                    val seconds = ((now - chat.sentAt) / 1000).coerceAtLeast(0)
                    Text("{0} is working in Termux · {1}".tr.replace("{0}", chat.agent.label)
                        .replace("{1}", "%d:%02d".format(seconds / 60, seconds % 60)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // What the agent has asked of the notes so far: it comes through the connection as it happens.
                    for (line in AgentActivity.lines.drop(chat.activityFrom).takeLast(8)) {
                        Text(AnnotatedString(line), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { chat.stopWaiting(context) }) { Text("Stop waiting") }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                placeholder = { Text("Ask about your notes…") },
                maxLines = 5,
                enabled = termux,
            )
            Button(onClick = ::send, enabled = termux && waiting == null && draft.isNotBlank()) { Text("Send") }
        }
        if (chat.messages.isNotEmpty() && waiting == null) {
            Text(if ((chat.carryOn && carriesOn(chat.agent)) || chat.resume != null) "The next request carries on this conversation." else "The next request starts afresh.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart) {
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
                    .widthIn(max = 340.dp)
                    .background(
                        when { message.failed -> scheme.errorContainer; message.mine -> scheme.primaryContainer; else -> scheme.surfaceContainerHighest },
                        RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
