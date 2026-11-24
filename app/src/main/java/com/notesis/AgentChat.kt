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
 * name, the request's number (so what it does on the way reaches the right
 * message), and which conversation it goes on - the last one ([carryOn]), the
 * one [resumeId] names, or a new one. Fixed words only: the request itself
 * goes on standard input.
 */
internal fun agentAskArguments(agent: AgentCli, carryOn: Boolean, resumeId: String? = null, request: Long = 0L): List<String> {
    val base = listOf("login", "debian", "--", "bash", ASK_HELPER, agent.command, request.toString())
    return when {
        resumeId != null -> {
            require(SESSION_ID.matches(resumeId)) { "Not a conversation id" }
            base + listOf("resume", resumeId)
        }
        carryOn -> base + "continue"
        else -> base
    }
}

/** What Termux runs for a shell line typed after `!`: the line on standard input, its output shown as it comes. */
internal fun shellArguments(request: Long): List<String> =
    listOf("login", "debian", "--", "bash", ASK_HELPER, "shell", request.toString())

/** What Termux runs to put the helpers in Debian: the app's own fixed script ([helperFilesScript]) on standard input. */
internal val HELPER_INSTALL_ARGUMENTS = listOf("login", "debian", "--", "bash", "-s")

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
 * one-shot mode, in a new conversation, the last one, or one by its id; what
 * the agent does on the way is shown in Notesis as it comes ([STREAM_SCRIPT]),
 * and its answer comes back last, with the conversation's id. Claude Code is
 * allowed the Notesis tools and nothing else without asking, and - where it
 * can leave them out - is not sent its own tools, which every request would
 * otherwise carry; the owner's own rules (ask first, or make at once) still
 * decide each change in Notesis. `ask shell` runs a line the owner typed
 * after `!`; `ask sessions AGENT` lists the conversations.
 */
internal val ASK_HELPER_SCRIPT = """
#!/bin/bash
# Notesis: asks an agent once, runs a shell line, or lists an agent's earlier conversations.
#   ask AGENT REQ [continue | resume ID]   the request on standard input, its progress shown in Notesis
#   ask shell REQ                          a shell line on standard input, its output shown as it comes
#   ask sessions AGENT                     one conversation a line: id, when, title
cd "${'$'}HOME"
N="${'$'}HOME/.notesis"
list() {
  case "${'$'}1" in
    gemini) gemini --list-sessions 2>&1 | node "${'$'}N/sessions.js" gemini ;;
    opencode) opencode session list --format json 2>/dev/null | node "${'$'}N/sessions.js" opencode ;;
    *) node "${'$'}N/sessions.js" "${'$'}1" </dev/null ;;
  esac
}
if [ "${'$'}1" = sessions ]; then list "${'$'}2"; exit; fi
set -o pipefail
agent="${'$'}1"; req="${'$'}2"; how="${'$'}3"; id="${'$'}4"
if [ "${'$'}agent" = shell ]; then bash -s 2>&1 | node "${'$'}N/stream.js" raw "${'$'}req"; exit; fi
prompt="${'$'}(cat)"
# What an agent writes on its error output as it works is shown too, and still reaches the answer.
show() { node "${'$'}N/stream.js" err "${'$'}req"; }
case "${'$'}agent" in
  claude)
    case "${'$'}how" in continue) set -- --continue ;; resume) set -- --resume "${'$'}id" ;; *) set -- ;; esac
    [ -f "${'$'}N/claude-tools" ] && set -- "${'$'}@" --tools ""
    claude -p "${'$'}prompt" --output-format stream-json --verbose --allowedTools mcp__notesis__notesis_run,mcp__notesis__notesis_help "${'$'}@" | node "${'$'}N/stream.js" claude "${'$'}req"
    exit ;;
  codex)
    case "${'$'}how" in
      continue) codex exec --skip-git-repo-check resume --last "${'$'}prompt" ;;
      resume) codex exec --skip-git-repo-check resume "${'$'}id" "${'$'}prompt" ;;
      *) codex exec --skip-git-repo-check "${'$'}prompt" ;;
    esac 2> >(show) | node "${'$'}N/stream.js" raw "${'$'}req" ;;
  gemini)
    case "${'$'}how" in
      continue) gemini --resume latest -p "${'$'}prompt" ;;
      resume) gemini --resume "${'$'}id" -p "${'$'}prompt" ;;
      *) gemini -p "${'$'}prompt" ;;
    esac 2> >(show) | node "${'$'}N/stream.js" raw "${'$'}req" ;;
  opencode)
    case "${'$'}how" in
      continue) opencode run --continue "${'$'}prompt" ;;
      resume) opencode run --session "${'$'}id" "${'$'}prompt" ;;
      *) opencode run "${'$'}prompt" ;;
    esac 2> >(show) | node "${'$'}N/stream.js" raw "${'$'}req" ;;
  *) echo "Notesis: no agent called ${'$'}agent" >&2; exit 2 ;;
esac
status=${'$'}?
# Which conversation it was, so the sidebar's thread goes on with this one and no other.
s="${'$'}(list "${'$'}agent" 2>/dev/null | head -n 1 | cut -f1)"
[ -n "${'$'}s" ] && echo "NOTESIS-SESSION ${'$'}s"
exit ${'$'}status
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

/**
 * Passes on what an agent does as it does it - its thinking, its words, the
 * tools it calls and what they answer - to the Notesis connection (`/events`,
 * with the connection's token), one event a call, and writes the answer on
 * standard output at the end for Termux to hand back. Claude Code's
 * stream-json is read event by event, other agents' plain lines as they come.
 * With the connection off it only passes the answer on.
 */
internal val STREAM_SCRIPT = """
// Notesis: an agent's progress to the sidebar as it comes; its answer on standard output at the end.
//   node stream.js claude REQ   Claude Code's stream-json
//   node stream.js raw REQ      plain lines: shown as they come, and all of them the answer
//   node stream.js err REQ      plain lines from an error output: shown, and passed on there
const fs = require("fs"), http = require("http"), path = require("path"), os = require("os"), readline = require("readline");
const [mode, req] = process.argv.slice(2);
const dir = path.join(os.homedir(), ".notesis");
let target = null, token = "";
try {
  target = new URL("/events", fs.readFileSync(path.join(dir, "url"), "utf8").trim());
  token = fs.readFileSync(path.join(dir, "token"), "utf8").trim();
} catch (e) { target = null; }
const queue = []; let busy = false;
function send(kind, text) {
  if (!target || text == null) return;
  text = String(text);
  if (!text.trim()) return;
  queue.push(JSON.stringify({ request: req, kind, text: text.length > 4000 ? text.slice(0, 4000) + "…" : text }));
  pump();
}
function pump() {
  if (busy || !queue.length || !target) return;
  busy = true;
  const body = queue.shift();
  const r = http.request(target, { method: "POST", headers: { "Content-Type": "application/json",
    "Authorization": "Bearer " + token, "Content-Length": Buffer.byteLength(body) } },
    (res) => { res.resume(); res.on("end", () => { busy = false; pump(); }); });
  // Notesis not listening: the answer still comes back through Termux.
  r.on("error", () => { busy = false; target = null; queue.length = 0; });
  r.setTimeout(3000, () => r.destroy());
  r.end(body);
}
const textOf = (c) => typeof c === "string" ? c : Array.isArray(c) ? c.map((p) => (p && p.text) || "").join("\n") : "";
let answer = "", last = "", session = "";
const lines = readline.createInterface({ input: process.stdin });
lines.on("line", (line) => {
  if (mode === "raw") { answer += line + "\n"; send("text", line); return; }
  if (mode === "err") { process.stderr.write(line + "\n"); send("output", line); return; }
  let e;
  try { e = JSON.parse(line); } catch (x) { send("output", line); return; }
  if (e.session_id) session = e.session_id;
  if (e.type === "assistant" && e.message) {
    for (const p of e.message.content || []) {
      if (p.type === "thinking") send("thinking", p.thinking);
      else if (p.type === "text") { last = p.text; send("text", p.text); }
      else if (p.type === "tool_use") send("tool", String(p.name || "").replace(/^mcp__notesis__/, "") + " " + JSON.stringify(p.input || {}).slice(0, 400));
    }
  } else if (e.type === "user" && e.message && Array.isArray(e.message.content)) {
    for (const p of e.message.content) if (p.type === "tool_result") send("output", textOf(p.content).slice(0, 1500));
  } else if (e.type === "result") {
    if (typeof e.result === "string") answer = e.result;
    const u = e.usage || {};
    send("usage", [u.input_tokens, u.cache_read_input_tokens, u.cache_creation_input_tokens, u.output_tokens].map((n) => n || 0).join(" "));
  }
});
lines.on("close", () => {
  if (mode === "err") return;
  if (session) process.stdout.write("NOTESIS-SESSION " + session + "\n");
  process.stdout.write(answer || last);
  // What is still on its way to the sidebar goes first, but not for long.
  const end = Date.now() + 2000;
  (function wait() { if ((queue.length || busy) && Date.now() < end) setTimeout(wait, 50); })();
});
""".trimIndent()

/** Which helpers this app writes; Debian's are brought up to it before the next request (see [AgentChat.send]). */
internal const val HELPER_VERSION = 2

/**
 * Writes the helpers into Debian's ~/.notesis. The setup script runs it, and
 * the app itself does (this fixed script, on standard input) when the ones
 * there are older than [HELPER_VERSION]. Whether this Claude Code can leave
 * its own tools out is asked once here, not on every request.
 */
internal fun helperFilesScript(): String = buildString {
    appendLine("mkdir -p \"\$HOME/.notesis\" && chmod 700 \"\$HOME/.notesis\"")
    appendLine("cat > \"\$HOME/.notesis/ask\" <<'NOTESIS_ASK'")
    append(ASK_HELPER_SCRIPT.trimEnd('\n')).append('\n')
    appendLine("NOTESIS_ASK")
    appendLine("chmod 700 \"\$HOME/.notesis/ask\"")
    appendLine("cat > \"\$HOME/.notesis/sessions.js\" <<'NOTESIS_SESSIONS'")
    append(SESSIONS_SCRIPT.trimEnd('\n')).append('\n')
    appendLine("NOTESIS_SESSIONS")
    appendLine("cat > \"\$HOME/.notesis/stream.js\" <<'NOTESIS_STREAM'")
    append(STREAM_SCRIPT.trimEnd('\n')).append('\n')
    appendLine("NOTESIS_STREAM")
    appendLine("if command -v claude >/dev/null && claude --help 2>/dev/null | grep -q -- '--tools '; then " +
        "touch \"\$HOME/.notesis/claude-tools\"; else rm -f \"\$HOME/.notesis/claude-tools\"; fi")
}

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

/** One step of what an agent did on the way to an answer, as [STREAM_SCRIPT] reports it. */
internal data class LiveStep(val kind: String, val text: String) {
    companion object {
        const val THINKING = "thinking"
        const val TEXT = "text"
        const val TOOL = "tool"
        const val OUTPUT = "output"
        const val USAGE = "usage"
    }
}

/** One message in the sidebar; an answer keeps the [steps] it was reached by. */
internal data class ChatMessage(
    val mine: Boolean,
    val text: String,
    val agent: String = "",
    val failed: Boolean = false,
    val steps: List<LiveStep> = emptyList(),
)

/**
 * One conversation in the sidebar, a tab of its own: its agent, and the id of
 * the agent's own conversation it goes on, so two tabs never carry on each
 * other's - "the last one" is whichever ran last, in any tab.
 */
internal class ChatThread(val id: Long, agent: AgentCli, title: String = "", agentSession: String? = null) {
    var agent by mutableStateOf(agent)
    var title by mutableStateOf(title)
    var agentSession by mutableStateOf(agentSession)
    val messages = mutableStateListOf<ChatMessage>()
    /** Kept from before tabs: an answered conversation with no id goes on as "the last one". */
    var carryOn by mutableStateOf(false)
}

/** What the owner typed, when it is not words for the agent. */
internal sealed class ChatInput {
    data class Ask(val words: String) : ChatInput()
    /** `/word rest`: one of the sidebar's own commands, or else the agent's own, passed on as typed. */
    data class Slash(val name: String, val rest: String) : ChatInput()
    /** `!line`: run in Debian, its output shown. */
    data class Shell(val line: String) : ChatInput()
    /** `notesis …` (or `n …`): a note command run here, no agent and no tokens. */
    data class Notes(val line: String) : ChatInput()
}

/** The sidebar's own commands; any other `/word` is the agent's. */
internal val SIDEBAR_COMMANDS = linkedMapOf(
    "new" to "a new conversation in its own tab",
    "close" to "close this tab",
    "clear" to "empty this tab and start its conversation afresh",
    "agent" to "/agent claude|codex|gemini|opencode: who answers in this tab",
    "sessions" to "the agent's earlier conversations, to carry one on here",
    "stop" to "stop waiting for the answer",
    "help" to "these commands",
)

internal fun parseChatInput(text: String): ChatInput {
    val t = text.trim()
    return when {
        t.startsWith("!") -> ChatInput.Shell(t.drop(1).trim())
        t.startsWith("/") && t.length > 1 && !t[1].isWhitespace() -> {
            val name = t.drop(1).substringBefore(' ').lowercase()
            ChatInput.Slash(name, t.substringAfter(' ', "").trim())
        }
        Regex("^(notesis|n)\\s+\\S", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ChatInput.Notes(t.substringAfter(' ').trim())
        else -> ChatInput.Ask(t)
    }
}

/** The conversation id an answer ends with ([ASK_HELPER_SCRIPT]), and the answer without that line. */
internal fun splitSession(stdout: String): Pair<String?, String> {
    var id: String? = null
    val kept = stdout.lines().filter { line ->
        val found = line.trim().removePrefix("NOTESIS-SESSION ").takeIf { line.trim().startsWith("NOTESIS-SESSION ") }
        if (found != null && SESSION_ID.matches(found)) { id = found; false } else true
    }
    return id to kept.joinToString("\n").trim()
}

/** Tokens as `input cacheRead cacheWrite output`, said short: "12.3k in (10k cached) · 410 out". */
internal fun usageLine(raw: String): String? {
    val n = raw.trim().split(' ').map { it.toLongOrNull() ?: return null }
    if (n.size < 4) return null
    fun k(v: Long) = if (v >= 1000) "%.1fk".format(v / 1000.0) else v.toString()
    val input = n[0] + n[1] + n[2]
    return "${k(input)} in" + (if (n[1] > 0) " (${k(n[1])} cached)" else "") + " · ${k(n[3])} out"
}

/** The commands agents ran through the connection lately, newest last, for the sidebar to show while it waits. */
internal object AgentActivity {
    private const val KEEP = 40
    val lines = mutableStateListOf<String>()

    fun add(commands: List<String>, ok: Boolean) {
        val shown = commands.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.take(400) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            for (line in shown) lines += (if (ok) "› " else "✗ ") + line
            while (lines.size > KEEP) lines.removeAt(0)
        }
    }
}

/** What the agent asked from the sidebar is doing, as [STREAM_SCRIPT] sends it through the connection. */
internal object AgentStream {
    /** One event, from the connection's thread: kept for the request it belongs to, if that one is waiting. */
    fun receive(body: String) {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return
        val request = json.optString("request").toLongOrNull() ?: return
        val kind = json.optString("kind").takeIf { it in KINDS } ?: return
        val text = json.optString("text").take(MAX_TEXT)
        android.os.Handler(android.os.Looper.getMainLooper()).post { AgentChat.step(request, LiveStep(kind, text)) }
    }

    private val KINDS = setOf(LiveStep.THINKING, LiveStep.TEXT, LiveStep.TOOL, LiveStep.OUTPUT, LiveStep.USAGE)
    private const val MAX_TEXT = 4_000
}

/** The sidebar's state: kept while the app runs, the conversations kept in the app's settings. */
internal object AgentChat {
    var open by mutableStateOf(false)
    /** Never empty: there is always a tab to type in, before the saved ones are read too. */
    val threads = mutableStateListOf(ChatThread(0L, AgentCli.CLAUDE))
    var current by mutableStateOf(0)
        private set
    val thread: ChatThread get() = threads[current.coerceIn(0, threads.lastIndex)]
    val messages get() = thread.messages
    val agent: AgentCli get() = thread.agent
    /** The request waiting for its answer, which tab it is for, and when it went. */
    var waiting by mutableStateOf<Long?>(null)
        private set
    var waitingThread by mutableStateOf<Long?>(null)
        private set
    var sentAt by mutableStateOf(0L)
        private set
    /** What the waiting request has done so far, as it comes. */
    val live = mutableStateListOf<LiveStep>()
    /** Where the activity list stood when the request went, so only what it caused is shown. */
    var activityFrom by mutableStateOf(0)
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
        val loaded = ArrayList<ChatThread>()
        runCatching {
            val saved = JSONArray(prefs.getString("threads", "[]"))
            for (i in 0 until saved.length()) {
                val t = saved.getJSONObject(i)
                val cli = AgentCli.entries.firstOrNull { it.name == t.optString("agent") } ?: AgentCli.CLAUDE
                val thread = ChatThread(t.optLong("id", ++nextId), cli, t.optString("title"),
                    t.optString("session").takeIf { SESSION_ID.matches(it) })
                thread.carryOn = t.optBoolean("carryOn")
                thread.messages += messagesFrom(t.optJSONArray("messages") ?: JSONArray())
                loaded += thread
            }
        }
        if (loaded.isNotEmpty()) { threads.clear(); threads += loaded }
        else if (threads.size == 1 && threads[0].messages.isEmpty()) {
            // Before tabs there was one conversation: it becomes the first tab, carried on as it was.
            val cli = AgentCli.entries.firstOrNull { it.name == prefs.getString("agent", null) } ?: AgentCli.CLAUDE
            val first = ChatThread(++nextId, cli)
            runCatching { first.messages += messagesFrom(JSONArray(prefs.getString("messages", "[]"))) }
            first.carryOn = first.messages.isNotEmpty()
            first.title = first.messages.firstOrNull { it.mine }?.text?.let(::titleOf).orEmpty()
            threads[0] = first
        }
        current = prefs.getInt("current", 0).coerceIn(0, threads.lastIndex)
    }

    private fun messagesFrom(array: JSONArray): List<ChatMessage> = (0 until array.length()).map { i ->
        val it = array.getJSONObject(i)
        val steps = it.optJSONArray("steps")?.let { s ->
            (0 until s.length()).mapNotNull { j -> s.optJSONObject(j)?.let { o -> LiveStep(o.optString("k"), o.optString("t")) } }
        }.orEmpty()
        ChatMessage(it.optBoolean("mine"), it.optString("text"), it.optString("agent"), it.optBoolean("failed"), steps)
    }

    private fun save(context: Context) {
        val list = JSONArray(threads.takeLast(MAX_THREADS).map { t ->
            JSONObject().put("id", t.id).put("agent", t.agent.name).put("title", t.title).put("session", t.agentSession ?: "")
                .put("carryOn", t.carryOn)
                .put("messages", JSONArray(t.messages.takeLast(KEEP_MESSAGES).map {
                    JSONObject().put("mine", it.mine).put("text", it.text.take(MAX_SAVED_TEXT)).put("agent", it.agent).put("failed", it.failed)
                        .put("steps", JSONArray(it.steps.takeLast(KEEP_STEPS).map { s -> JSONObject().put("k", s.kind).put("t", s.text.take(600)) }))
                }))
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("threads", list.toString()).putInt("current", current).remove("messages").apply()
    }

    /** The first words of a request, as a tab's name. */
    private fun titleOf(words: String): String = words.lines().first().trim().take(28)

    fun select(context: Context, index: Int) {
        if (index !in threads.indices) return
        current = index
        sessions = null
        save(context)
    }

    /** A new tab for [cli], the current one's agent unless named. */
    fun newThread(context: Context, cli: AgentCli = agent) {
        threads += ChatThread(++nextId, cli)
        current = threads.lastIndex
        sessions = null
        save(context)
    }

    fun closeThread(context: Context, index: Int = current) {
        if (index !in threads.indices || threads[index].id == waitingThread) return
        threads.removeAt(index)
        if (threads.isEmpty()) threads += ChatThread(++nextId, AgentCli.CLAUDE)
        if (current > index || current > threads.lastIndex) current = (current - 1).coerceAtLeast(0)
        save(context)
    }

    fun clear(context: Context) {
        if (thread.id == waitingThread) return
        thread.messages.clear()
        thread.agentSession = null
        thread.carryOn = false
        thread.title = ""
        save(context)
    }

    /** Who answers in this tab. A tab that has talked to another agent keeps it: the new one gets a tab of its own. */
    fun choose(context: Context, cli: AgentCli) {
        if (cli == agent) return
        if (thread.messages.isEmpty()) { thread.agent = cli; thread.agentSession = null; sessions = null; save(context) }
        else newThread(context, cli)
    }

    /** Goes on [session] in a tab of its own. */
    fun pick(context: Context, session: AgentSession) {
        sessions = null
        val tab = threads.indexOfFirst { it.agent == agent && it.agentSession == session.id }
        if (tab >= 0) { select(context, tab); return }
        val t = if (thread.messages.isEmpty()) thread else ChatThread(++nextId, agent).also { threads += it; current = threads.lastIndex }
        t.agentSession = session.id
        t.title = session.title.take(28)
        t.messages += ChatMessage(false, "Carrying on an earlier conversation: {0}".tr.replace("{0}",
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
    private var installRequest = 0L
    /** A request held while the helpers are brought up to date: what was typed, and for which tab. */
    private var held: Pair<String, Long>? = null

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

    private fun failed(app: Context, text: String) {
        thread.messages += ChatMessage(false, text, agent.label, failed = true)
        save(app)
    }

    /**
     * Sends [words] to the agent, or to Debian's shell after `!`; false when
     * Termux could not be asked (the reason is added as a message). The
     * helpers in Debian are brought up to date first, once, when they are older
     * than this app's.
     */
    fun send(context: Context, words: String, shell: Boolean = false): Boolean {
        if (waiting != null || words.isBlank()) return false
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val t = thread
        t.messages += ChatMessage(true, if (shell) "!" + words.trim() else words.trim(), t.agent.label)
        if (t.title.isEmpty()) t.title = titleOf(words)
        val id = ++nextId
        if (prefs.getInt("helpers", 0) < HELPER_VERSION) {
            held = (if (shell) "!$words" else words) to t.id
            installRequest = id
            if (!runInTermux(app, id, HELPER_INSTALL_ARGUMENTS, helperFilesScript(), "Notesis: helpers")) {
                held = null
                failed(app, "Termux could not be asked: " + (lastStartError ?: "is Termux installed and up to date?"))
                return false
            }
            begin(app, id, t)
            return true
        }
        return dispatch(app, id, t, words, shell)
    }

    private fun dispatch(app: Context, id: Long, t: ChatThread, words: String, shell: Boolean): Boolean {
        val cli = t.agent
        val arguments = if (shell) shellArguments(id) else agentAskArguments(cli, t.carryOn && t.agentSession == null, t.agentSession, id)
        // An agent's own command (`/compact`) goes as typed: a note named before it would make it words.
        val stdin = if (shell || words.trimStart().startsWith("/")) words.trim() else askText(words, note?.second, note?.first)
        if (!runInTermux(app, id, arguments, stdin, if (shell) "Notesis: shell" else "Notesis: ${cli.label}")) {
            failed(app, "Termux could not be asked: " + (lastStartError ?: "is Termux installed and up to date?"))
            return false
        }
        begin(app, id, t)
        return true
    }

    private fun begin(app: Context, id: Long, t: ChatThread) {
        waiting = id
        waitingThread = t.id
        sentAt = System.currentTimeMillis()
        activityFrom = AgentActivity.lines.size
        live.clear()
        save(app)
    }

    /** One step of the waiting request, from [AgentStream]. */
    fun step(request: Long, step: LiveStep) {
        if (request != waiting) return
        live += step
        while (live.size > KEEP_LIVE) live.removeAt(0)
    }

    /** Termux's answer to request [id]. */
    fun finish(context: Context, id: Long, stdout: String, stderr: String, exitCode: Int, error: Int, errorMessage: String) {
        val app = context.applicationContext
        if (id == sessionsRequest && loadingSessions) {
            loadingSessions = false
            val (text, failed) = askReply(stdout, stderr, exitCode, error, errorMessage)
            if (failed) { sessionsError = text; return }
            sessions = parseSessions(stdout)
            return
        }
        if (waiting != id) return
        val t = threads.firstOrNull { it.id == waitingThread }
        waiting = null
        waitingThread = null
        if (id == installRequest) {
            val (text, failed) = askReply(stdout, stderr, exitCode, error, errorMessage)
            val (words, tab) = held ?: return
            held = null
            if (failed || t == null) {
                (t ?: thread).messages += ChatMessage(false, "The helpers in Debian could not be brought up to date: {0} Run the setup script again in Termux (Agent connection → On this tablet).".tr
                    .replace("{0}", text.take(300)), agent.label, failed = true)
                save(app)
                return
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("helpers", HELPER_VERSION).apply()
            if (t.id == tab) dispatch(app, ++nextId, t, words.removePrefix("!"), words.startsWith("!"))
            return
        }
        val (session, answer) = splitSession(stdout)
        val (text, failed) = askReply(answer, stderr, exitCode, error, errorMessage)
        val steps = live.filter { it.kind != LiveStep.TEXT || it.text.trim() != text.trim() }.toList()
        live.clear()
        val target = t ?: thread
        target.messages += ChatMessage(false, text, target.agent.label, failed, steps)
        if (session != null) target.agentSession = session
        // Once it has answered, the next request carries on from here.
        if (!failed) target.carryOn = true
        save(app)
    }

    /** Stops waiting for an answer that is not coming (Termux was closed, say); the agent may still finish in Termux. */
    fun stopWaiting(context: Context) {
        val t = threads.firstOrNull { it.id == waitingThread } ?: thread
        waiting = null
        waitingThread = null
        held = null
        t.messages += ChatMessage(false, "Stopped waiting. If the agent is still working in Termux, its changes still come through.", t.agent.label,
            true, live.toList())
        live.clear()
        save(context.applicationContext)
    }

    /** Words the sidebar says itself, in this tab, not from an agent: a command's answer, a note command's output. */
    fun say(context: Context, text: String, failed: Boolean = false) {
        thread.messages += ChatMessage(false, text, "Notesis", failed)
        save(context.applicationContext)
    }

    /** What the owner typed as `/word` with no agent behind it. */
    fun mine(context: Context, text: String) {
        thread.messages += ChatMessage(true, text, agent.label)
        save(context.applicationContext)
    }

    internal const val EXTRA_REQUEST = "notesis.request"
    private const val PREFS = "agent-chat"
    private const val KEEP_MESSAGES = 60
    private const val MAX_THREADS = 12
    private const val KEEP_STEPS = 40
    private const val KEEP_LIVE = 300
    /** An answer is kept whole up to this: a long one cut on saving lost its end when the app came back. */
    private const val MAX_SAVED_TEXT = 60_000
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
