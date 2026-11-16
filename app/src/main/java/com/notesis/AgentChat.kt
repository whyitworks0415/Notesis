package com.notesis

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/*
 * Asking an agent from inside Notesis (the agent sidebar). The agents run in
 * Termux, a separate app (AgentSetup.kt says why), and an app cannot show
 * another's screen inside its own; but Termux runs a command another app asks
 * for, when the owner has allowed that app (its RUN_COMMAND permission) and
 * Termux itself (allow-external-apps, which the setup script turns on). So a
 * request goes to Termux as one command in Debian - the agent's own one-shot
 * mode - and the answer comes back when it is done. Meanwhile the commands the
 * agent runs reach Notesis through the agent connection, and are shown as
 * they come (AgentActivity), so there is something to watch.
 *
 * The request's words travel on the command's standard input to a small
 * helper the setup script writes (~/.notesis/ask), never as words of the
 * command line: nothing the owner types can become part of a command.
 */

internal const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
private const val TERMUX_ACTION = "com.termux.RUN_COMMAND"
private const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
private const val TERMUX_HOME_DIR = "/data/data/com.termux/files/home"

/** The helper in Debian that reads the request from its input and hands it to the agent; written by the setup script. */
internal const val ASK_HELPER = "/root/.notesis/ask"

/** The helper that lists an agent's earlier conversations; written by the setup script beside [ASK_HELPER]. */
internal const val SESSIONS_HELPER = "/root/.notesis/sessions.js"

/** An id as the agents write them: letters, digits, - _ . and nothing that could be taken for more than one word. */
private val SESSION_ID = Regex("^[A-Za-z0-9._-]{1,100}$")

/**
 * What Termux runs for one request to [agent]: Debian, the helper, the agent's
 * name, and which conversation it goes on - the last one ([carryOn]), the
 * earlier one [resumeId] chosen from the list, or a new one. Fixed words only:
 * the request itself goes on standard input.
 */
internal fun agentAskArguments(agent: AgentCli, carryOn: Boolean, resumeId: String? = null): List<String> {
    val base = listOf("login", "debian", "--", "bash", ASK_HELPER, agent.command)
    return when {
        resumeId != null -> {
            require(SESSION_ID.matches(resumeId)) { "Not a conversation id" }
            base + listOf("resume", resumeId)
        }
        carryOn -> base + "continue"
        else -> base
    }
}

/** What Termux runs to list [agent]'s earlier conversations, one a line: id, when, title. */
internal fun agentSessionsArguments(agent: AgentCli): List<String> =
    listOf("login", "debian", "--", "bash", ASK_HELPER, "sessions", agent.command)

/** An earlier conversation of an agent's, as the list gives it. */
internal data class AgentSession(val id: String, val at: String, val title: String)

/** The list's lines (id TAB when TAB title), newest first; a line that is not one is left out. */
internal fun parseSessions(stdout: String): List<AgentSession> = stdout.lines().mapNotNull { line ->
    val parts = line.split('\t')
    val id = parts.getOrNull(0)?.trim().orEmpty()
    if (!SESSION_ID.matches(id)) null else AgentSession(id, parts.getOrNull(1)?.trim().orEmpty(), parts.getOrNull(2)?.trim().orEmpty())
}

/**
 * The helper: the request from standard input, given to each agent in its
 * one-shot mode, in a new conversation, the last one, or one picked from the
 * list. Claude Code is allowed the Notesis tools and nothing else without
 * asking; the owner's own rules (ask first, or make at once) still decide each
 * change in Notesis. `ask sessions AGENT` lists the conversations instead.
 */
internal val ASK_HELPER_SCRIPT = """
#!/bin/bash
# Notesis: asks an agent once, or lists its earlier conversations.
#   ask AGENT [continue | resume ID]   the request comes on standard input
#   ask sessions AGENT                 one conversation a line: id, when, title
cd "${'$'}HOME"
if [ "${'$'}1" = sessions ]; then
  case "${'$'}2" in
    gemini) gemini --list-sessions 2>&1 | node "${'$'}HOME/.notesis/sessions.js" gemini ;;
    opencode) opencode session list --format json 2>/dev/null | node "${'$'}HOME/.notesis/sessions.js" opencode ;;
    *) node "${'$'}HOME/.notesis/sessions.js" "${'$'}2" </dev/null ;;
  esac
  exit
fi
prompt="${'$'}(cat)"
how="${'$'}2"
id="${'$'}3"
case "${'$'}1" in
  claude)
    case "${'$'}how" in continue) set -- --continue ;; resume) set -- --resume "${'$'}id" ;; *) set -- ;; esac
    exec claude -p "${'$'}prompt" --allowedTools mcp__notesis__notesis_run,mcp__notesis__notesis_help "${'$'}@" ;;
  codex)
    case "${'$'}how" in
      continue) exec codex exec --skip-git-repo-check resume --last "${'$'}prompt" ;;
      resume) exec codex exec --skip-git-repo-check resume "${'$'}id" "${'$'}prompt" ;;
      *) exec codex exec --skip-git-repo-check "${'$'}prompt" ;;
    esac ;;
  gemini)
    case "${'$'}how" in
      continue) exec gemini --resume latest -p "${'$'}prompt" ;;
      resume) exec gemini --resume "${'$'}id" -p "${'$'}prompt" ;;
      *) exec gemini -p "${'$'}prompt" ;;
    esac ;;
  opencode)
    case "${'$'}how" in
      continue) exec opencode run --continue "${'$'}prompt" ;;
      resume) exec opencode run --session "${'$'}id" "${'$'}prompt" ;;
      *) exec opencode run "${'$'}prompt" ;;
    esac ;;
  *) echo "Notesis: no agent called ${'$'}1" >&2; exit 2 ;;
esac
""".trimIndent()

/**
 * Lists an agent's earlier conversations from where each keeps them (Claude
 * Code and Codex: their own files; Gemini CLI and OpenCode: their own list,
 * read from standard input), newest first, as "id TAB when TAB title".
 */
internal val SESSIONS_SCRIPT = """
// Notesis: an agent's earlier conversations, newest first, one a line: id, when, title.
const fs = require("fs"), path = require("path"), os = require("os");
const agent = process.argv[2], home = os.homedir(), out = [];
const clean = (s) => String(s || "").replace(/\s+/g, " ").trim().slice(0, 80);
const textOf = (c) => typeof c === "string" ? c : Array.isArray(c) ? c.map((p) => (p && (p.text || p.input_text)) || "").join(" ") : "";
const lines = (file, n) => { try { return fs.readFileSync(file, "utf8").split("\n").slice(0, n); } catch (e) { return []; } };
const walk = (dir, files) => {
  let list = [];
  try { list = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return files; }
  for (const d of list) { const p = path.join(dir, d.name); if (d.isDirectory()) walk(p, files); else files.push(p); }
  return files;
};
const input = () => { try { return fs.readFileSync(0, "utf8"); } catch (e) { return ""; } };
const when = (f) => { try { return fs.statSync(f).mtimeMs; } catch (e) { return 0; } };
if (agent === "claude") {
  const dir = path.join(home, ".claude", "projects", home.replace(/[^A-Za-z0-9]/g, "-"));
  for (const f of walk(dir, []).filter((f) => f.endsWith(".jsonl"))) {
    let title = "";
    for (const line of lines(f, 80)) {
      try { const j = JSON.parse(line); if (j.type === "user" && j.message) { title = textOf(j.message.content); if (title) break; } } catch (e) {}
    }
    out.push([path.basename(f, ".jsonl"), when(f), title]);
  }
} else if (agent === "codex") {
  for (const f of walk(path.join(home, ".codex", "sessions"), []).filter((f) => f.endsWith(".jsonl"))) {
    let id = "", title = "";
    for (const line of lines(f, 120)) {
      try {
        const j = JSON.parse(line), p = j.payload || {};
        if (!id && j.type === "session_meta" && p.id) id = p.id;
        if (!title && p.type === "message" && p.role === "user") { const t = textOf(p.content); if (t && !t.trim().startsWith("<")) title = t; }
      } catch (e) {}
    }
    if (id) out.push([id, when(f), title]);
  }
} else if (agent === "opencode") {
  let list = [];
  try { list = JSON.parse(input()); } catch (e) {}
  for (const s of Array.isArray(list) ? list : []) out.push([s.id, s.updated || (s.time && s.time.updated) || 0, s.title]);
} else if (agent === "gemini") {
  for (const line of input().split("\n")) {
    const m = line.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
    if (m) out.push([m[0], 0, line.replace(m[0], "").replace(/[\[\]()]/g, " ")]);
  }
}
out.sort((a, b) => b[1] - a[1]);
for (const [id, at, title] of out.slice(0, 40)) {
  const stamp = at ? new Date(at).toISOString().slice(0, 16).replace("T", " ") : "";
  console.log([id, stamp, clean(title) || "(untitled)"].join("\t"));
}
""".trimIndent()

/** Every agent here carries on a conversation, and picks up an earlier one, in its own way (see [ASK_HELPER_SCRIPT]). */
internal fun carriesOn(agent: AgentCli): Boolean = true

/**
 * The request as sent: the owner's words, with the note on screen named first
 * when there is one, so "this note" means something and the agent need not
 * list every note to find it.
 */
internal fun askText(words: String, noteTitle: String?, noteId: String?): String =
    if (noteTitle == null || noteId == null) words.trim()
    else "[Notesis: the note on screen is \"$noteTitle\" (id ${noteId.take(8)}).]\n" + words.trim()

/** What Termux reports for a command it ran (Activity.RESULT_OK). */
internal const val TERMUX_OK = -1

/** What came back from Termux, as the sidebar says it. */
internal fun askReply(stdout: String, stderr: String, exitCode: Int, error: Int, errorMessage: String): Pair<String, Boolean> {
    val out = stdout.trim()
    // Termux says a command ran with Android's RESULT_OK, -1, not with 0; either is no error of Termux's own.
    val termuxFailed = error != TERMUX_OK && error != 0
    return when {
        termuxFailed && errorMessage.contains("allow-external-apps", ignoreCase = true) ->
            "Termux does not take commands from other apps yet. Run the setup script again in Termux (Agent connection → On this tablet); it turns that on." to true
        termuxFailed && out.isEmpty() -> ("Termux could not run it: " + errorMessage.ifBlank { "error $error" }) to true
        exitCode == 127 || stderr.contains("No such file") && stderr.contains(".notesis/ask") ->
            "The agent helper is not in Debian yet. Run the setup script again in Termux (Agent connection → On this tablet)." to true
        exitCode != 0 && out.isEmpty() -> (stderr.trim().takeLast(800).ifBlank { "The agent stopped with code $exitCode." }) to true
        else -> out.ifEmpty { "(No answer.)" } to (exitCode != 0)
    }
}

/** One message in the sidebar. */
internal data class ChatMessage(val mine: Boolean, val text: String, val agent: String = "", val failed: Boolean = false)

/** The commands agents ran through the connection lately, newest last, for the sidebar to show while it waits. */
internal object AgentActivity {
    private const val KEEP = 40
    val lines = mutableStateListOf<String>()

    fun add(commands: List<String>, ok: Boolean) {
        val shown = commands.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.take(120) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            for (line in shown) lines += (if (ok) "› " else "✗ ") + line
            while (lines.size > KEEP) lines.removeAt(0)
        }
    }
}

/** The sidebar's state: kept while the app runs, the conversation kept in the app's settings. */
internal object AgentChat {
    var open by mutableStateOf(false)
    var agent by mutableStateOf(AgentCli.CLAUDE)
    val messages = mutableStateListOf<ChatMessage>()
    /** The request waiting for its answer, and when it went. */
    var waiting by mutableStateOf<Long?>(null)
        private set
    var sentAt by mutableStateOf(0L)
        private set
    /** Where the activity list stood when the request went, so only what it caused is shown. */
    var activityFrom by mutableStateOf(0)
        private set
    /** Whether the next request carries on the conversation. */
    var carryOn by mutableStateOf(false)
    /** An earlier conversation picked from the list, which the next request goes on. */
    var resume by mutableStateOf<AgentSession?>(null)
        private set
    /** The agent's earlier conversations, while the list is open; null when it is not. */
    var sessions by mutableStateOf<List<AgentSession>?>(null)
        private set
    var loadingSessions by mutableStateOf(false)
        private set
    var sessionsError by mutableStateOf<String?>(null)
        private set
    /** The note on screen, named in each request; set by the note screens. */
    var note by mutableStateOf<Pair<String, String>?>(null)
    private var read = false
    private var nextId = System.currentTimeMillis()

    fun show(context: Context) {
        load(context)
        open = true
    }

    private fun load(context: Context) {
        if (read) return
        read = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        agent = AgentCli.entries.firstOrNull { it.name == prefs.getString("agent", null) } ?: AgentCli.CLAUDE
        runCatching {
            val saved = JSONArray(prefs.getString("messages", "[]"))
            for (i in 0 until saved.length()) saved.getJSONObject(i).let {
                messages += ChatMessage(it.optBoolean("mine"), it.optString("text"), it.optString("agent"), it.optBoolean("failed"))
            }
        }
        carryOn = messages.isNotEmpty()
    }

    private fun save(context: Context) {
        val list = JSONArray(messages.takeLast(KEEP_MESSAGES).map {
            JSONObject().put("mine", it.mine).put("text", it.text.take(8_000)).put("agent", it.agent).put("failed", it.failed)
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("messages", list.toString()).putString("agent", agent.name).apply()
    }

    fun clear(context: Context) {
        messages.clear()
        carryOn = false
        resume = null
        save(context)
    }

    fun choose(context: Context, cli: AgentCli) {
        if (cli != agent) { carryOn = false; resume = null; sessions = null }
        agent = cli
        save(context)
    }

    /** Goes on [session] from the next request; what this sidebar showed of another conversation is put away. */
    fun pick(context: Context, session: AgentSession) {
        resume = session
        sessions = null
        carryOn = false
        messages.clear()
        messages += ChatMessage(false, "Carrying on an earlier conversation: {0}".tr.replace("{0}",
            listOf(session.title, session.at).filter { it.isNotBlank() }.joinToString(" · ")), agent.label)
        save(context)
    }

    fun closeSessions() { sessions = null; sessionsError = null }

    /** Asks Termux for the agent's earlier conversations; they arrive in [sessions]. */
    fun listSessions(context: Context) {
        if (loadingSessions || waiting != null) return
        val app = context.applicationContext
        val id = ++nextId
        sessionsRequest = id
        sessionsError = null
        sessions = null
        if (!runInTermux(app, id, agentSessionsArguments(agent), null, "Notesis: ${agent.label} conversations")) {
            sessionsError = "Termux could not be asked. Is it installed and up to date?"
            return
        }
        loadingSessions = true
    }

    private var sessionsRequest = 0L

    /** Hands [arguments] (and [stdin]) to Termux to run in the background, the answer to come back as request [id]. */
    private fun runInTermux(app: Context, id: Long, arguments: List<String>, stdin: String?, label: String): Boolean {
        val reply = Intent(app, AgentChatResultReceiver::class.java).putExtra(EXTRA_REQUEST, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(app, (id % Int.MAX_VALUE).toInt(), reply, flags)
        val intent = Intent(TERMUX_ACTION).setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/proot-distro")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments.toTypedArray())
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME_DIR)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        if (stdin != null) intent.putExtra("com.termux.RUN_COMMAND_STDIN", stdin)
        val started = runCatching { app.startService(intent) }.recoverCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else throw it
        }
        lastStartError = started.exceptionOrNull()?.message
        return started.isSuccess && started.getOrNull() != null
    }

    private var lastStartError: String? = null

    /** Sends [words] to Termux; false when Termux could not be asked (the reason is added as a message). */
    fun send(context: Context, words: String): Boolean {
        if (waiting != null || words.isBlank()) return false
        val app = context.applicationContext
        val id = ++nextId
        val cli = agent
        messages += ChatMessage(true, words.trim(), cli.label)
        val arguments = agentAskArguments(cli, carryOn && carriesOn(cli), resume?.id)
        if (!runInTermux(app, id, arguments, askText(words, note?.second, note?.first), "Notesis: ${cli.label}")) {
            messages += ChatMessage(false, "Termux could not be asked: " + (lastStartError ?: "is Termux installed and up to date?"),
                cli.label, failed = true)
            save(app)
            return false
        }
        waiting = id
        sentAt = System.currentTimeMillis()
        activityFrom = AgentActivity.lines.size
        save(app)
        return true
    }

    /** Termux's answer to request [id]. */
    fun finish(context: Context, id: Long, stdout: String, stderr: String, exitCode: Int, error: Int, errorMessage: String) {
        if (id == sessionsRequest && loadingSessions) {
            loadingSessions = false
            val (text, failed) = askReply(stdout, stderr, exitCode, error, errorMessage)
            if (failed) { sessionsError = text; return }
            sessions = parseSessions(stdout)
            return
        }
        if (waiting != id) return
        val (text, failed) = askReply(stdout, stderr, exitCode, error, errorMessage)
        messages += ChatMessage(false, text, agent.label, failed)
        // Once a picked conversation has answered, it is the latest: the next request carries on from it.
        if (!failed) { carryOn = true; resume = null }
        waiting = null
        save(context.applicationContext)
    }

    /** Stops waiting for an answer that is not coming (Termux was closed, say); the agent may still finish in Termux. */
    fun stopWaiting(context: Context) {
        waiting = null
        messages += ChatMessage(false, "Stopped waiting. If the agent is still working in Termux, its changes still come through.", agent.label, true)
        save(context.applicationContext)
    }

    internal const val EXTRA_REQUEST = "notesis.request"
    private const val PREFS = "agent-chat"
    private const val KEEP_MESSAGES = 60
}

/** Where Termux sends a request's result: its output, errors and exit code. */
class AgentChatResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AgentChat.EXTRA_REQUEST, -1L)
        val result = intent.getBundleExtra("result") ?: return
        val stdout = result.getString("stdout").orEmpty()
        val stderr = result.getString("stderr").orEmpty()
        val exit = result.getInt("exitCode", -1)
        val err = result.getInt("err", TERMUX_OK)
        val errmsg = result.getString("errmsg").orEmpty()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            AgentChat.finish(context, id, stdout, stderr, exit, err, errmsg)
            LibraryChanges.changed()
        }
    }
}
