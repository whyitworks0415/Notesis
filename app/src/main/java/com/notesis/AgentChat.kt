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

/**
 * What Termux runs for one request to [agent]: Debian, the helper, the agent's
 * name, and whether this carries on the last conversation. Fixed words only:
 * the request itself goes on standard input.
 */
internal fun agentAskArguments(agent: AgentCli, carryOn: Boolean): List<String> =
    listOf("login", "debian", "--", "bash", ASK_HELPER, agent.command) + (if (carryOn) listOf("continue") else emptyList())

/**
 * The helper: the request from standard input, given to each agent in its
 * one-shot mode. Claude Code is allowed the Notesis tools and nothing else
 * without asking; the owner's own rules (ask first, or make at once) still
 * decide each change in Notesis.
 */
internal val ASK_HELPER_SCRIPT = """
#!/bin/bash
# Notesis: asks an agent once. The request comes on standard input; usage: ask AGENT [continue]
prompt="${'$'}(cat)"
cd "${'$'}HOME"
case "${'$'}1" in
  claude) exec claude -p "${'$'}prompt" --allowedTools mcp__notesis__notesis_run,mcp__notesis__notesis_help ${'$'}{2:+--continue} ;;
  codex) exec codex exec --skip-git-repo-check "${'$'}prompt" ;;
  gemini) exec gemini -p "${'$'}prompt" ;;
  opencode) exec opencode run ${'$'}{2:+--continue} "${'$'}prompt" ;;
  *) echo "Notesis: no agent called ${'$'}1" >&2; exit 2 ;;
esac
""".trimIndent()

/** Which agents carry on a conversation from one request to the next here. */
internal fun carriesOn(agent: AgentCli): Boolean = agent == AgentCli.CLAUDE || agent == AgentCli.OPENCODE

/**
 * The request as sent: the owner's words, with the note on screen named first
 * when there is one, so "this note" means something and the agent need not
 * list every note to find it.
 */
internal fun askText(words: String, noteTitle: String?, noteId: String?): String =
    if (noteTitle == null || noteId == null) words.trim()
    else "[Notesis: the note on screen is \"$noteTitle\" (id ${noteId.take(8)}).]\n" + words.trim()

/** What came back from Termux, as the sidebar says it. */
internal fun askReply(stdout: String, stderr: String, exitCode: Int, error: Int, errorMessage: String): Pair<String, Boolean> {
    val out = stdout.trim()
    return when {
        error != 0 && errorMessage.contains("allow-external-apps", ignoreCase = true) ->
            "Termux does not take commands from other apps yet. Run the setup script again in Termux (Agent connection → On this tablet); it turns that on." to true
        error != 0 -> ("Termux could not run it: " + errorMessage.ifBlank { "error $error" }) to true
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
        save(context)
    }

    fun choose(context: Context, cli: AgentCli) {
        if (cli != agent) carryOn = false
        agent = cli
        save(context)
    }

    /** Sends [words] to Termux; false when Termux could not be asked (the reason is added as a message). */
    fun send(context: Context, words: String): Boolean {
        if (waiting != null || words.isBlank()) return false
        val app = context.applicationContext
        val id = ++nextId
        val cli = agent
        messages += ChatMessage(true, words.trim(), cli.label)
        val reply = Intent(app, AgentChatResultReceiver::class.java).putExtra(EXTRA_REQUEST, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(app, (id % Int.MAX_VALUE).toInt(), reply, flags)
        val intent = Intent(TERMUX_ACTION).setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/proot-distro")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", agentAskArguments(cli, carryOn && carriesOn(cli)).toTypedArray())
            .putExtra("com.termux.RUN_COMMAND_STDIN", askText(words, note?.second, note?.first))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME_DIR)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Notesis: ${cli.label}")
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        val started = runCatching { app.startService(intent) }.recoverCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else throw it
        }
        if (started.isFailure || started.getOrNull() == null) {
            messages += ChatMessage(false, "Termux could not be asked: " +
                (started.exceptionOrNull()?.message ?: "is Termux installed and up to date?"), cli.label, failed = true)
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
        if (waiting != id) return
        val (text, failed) = askReply(stdout, stderr, exitCode, error, errorMessage)
        messages += ChatMessage(false, text, agent.label, failed)
        if (!failed) carryOn = true
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
        val err = result.getInt("err", 0)
        val errmsg = result.getString("errmsg").orEmpty()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            AgentChat.finish(context, id, stdout, stderr, exit, err, errmsg)
            LibraryChanges.changed()
        }
    }
}
