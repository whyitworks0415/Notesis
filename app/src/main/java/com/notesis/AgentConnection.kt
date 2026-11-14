package com.notesis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64

/*
 * The connection an agent makes to the notes on this device (Todo.md, C3):
 * the owner's settings, the server that listens for it, and the service that
 * keeps it alive while another app is in front - an agent in Termux is the
 * point, and Android freezes a background app that holds no service.
 *
 * Off until the owner turns it on. Listening on this device only, unless the
 * same network is switched on too; either way nothing is answered without the
 * token, which is made here, shown only on the owner's screen, and kept out of
 * backups.
 */
internal object AgentSettings {
    private const val PREFS = "agent"
    private const val DEFAULT_PORT = 47821

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean("enabled", on).apply() }

    /** Whether private networks may connect as well as this device. */
    fun lan(context: Context): Boolean = prefs(context).getBoolean("lan", false)
    fun setLan(context: Context, on: Boolean) { prefs(context).edit().putBoolean("lan", on).apply() }

    fun policy(context: Context): AgentPolicy = AgentPolicy.of(prefs(context).getString("policy", null))
    fun setPolicy(context: Context, policy: AgentPolicy) { prefs(context).edit().putString("policy", policy.saved).apply() }

    fun port(context: Context): Int = prefs(context).getInt("port", DEFAULT_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_PORT
    fun setPort(context: Context, port: Int) { prefs(context).edit().putInt("port", port).apply() }

    /** The token, made the first time it is asked for. */
    @Synchronized
    fun token(context: Context): String {
        val saved = prefs(context).getString("token", null)
        if (!saved.isNullOrEmpty()) return saved
        return newToken().also { prefs(context).edit().putString("token", it).apply() }
    }

    /** A new token: what an agent had stops working at once. */
    @Synchronized
    fun regenerateToken(context: Context): String = newToken().also { prefs(context).edit().putString("token", it).apply() }
}

/** 256 random bits, written so that it can go in a header: "nts_" and 43 letters and digits. */
internal fun newToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(32).also(random::nextBytes)
    return "nts_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** The token with all but its first characters hidden, for showing without giving it away. */
internal fun maskedToken(token: String): String = if (token.length <= 8) "•".repeat(token.length) else token.take(8) + "•".repeat(12)

/** The private IPv4 addresses of this device on its networks, such as the Wi-Fi's. */
internal fun deviceAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress || (it.address[0].toInt() and 0xFF) == 100 }
        .map { it.hostAddress.orEmpty() }
        .filter { it.isNotEmpty() }
        .toList()
}.getOrDefault(emptyList())

/** The command help an agent is given: how each line is written, and the rules the app keeps. */
/**
 * The help an agent reads: with no [topic], the commands and the rules, kept
 * short since every new session reads it; "recipes" for the step-by-step
 * ways to common tasks; any other word, the commands that start with it.
 */
internal fun agentHelp(topic: String = ""): String = buildString {
    val wanted = topic.trim().lowercase()
    if (wanted == "recipes") {
        append(AGENT_GUIDE.substringAfter("## Recipes").let { "Recipes" + it }).append('\n')
        return@buildString
    }
    if (wanted.isNotEmpty()) {
        val shown = COMMAND_USAGE.filterKeys { it.startsWith(wanted) }.values
        if (shown.isEmpty()) append("No command starts with \"$topic\". notesis_help with no topic lists them all.\n")
        for (usage in shown) append(usage).append('\n')
        return@buildString
    }
    append("Notesis note commands. Pass them, one per string, in the commands list of notesis_run.\n\n")
    for (usage in COMMAND_USAGE.values) append("  ").append(usage).append('\n')
    append("\nNOTE is a note's id (or its first 8 letters) or title. PAGE is a page number from 1, or last.\n")
    append("Several lines run as one batch: if one fails, the ones before it are taken back.\n")
    append("A note open on screen takes changes and shows them at once (not one in a split part or the reference panel). ")
    append("Deleting only moves a note to the trash.\n")
    append("`undo` takes back the latest change. The owner may be asked before a change; a refusal is final for that request.\n")
    append("A failure carries a code: NOT_FOUND, AMBIGUOUS (with candidates), OPEN_ON_SCREEN, LOCKED, BAD_PAGE, NO_PDF, NO_OUTLINE, ")
    append("NOT_SUPPORTED, NOT_READY, UNKNOWN_COMMAND or INVALID.\n")
    append("Read a note again only if it changed: note read NOTE --since MODIFIED. Recipes for common tasks: notesis_help with topic \"recipes\".\n")
}

/** Runs the server for as long as the connection is on, and says how it is doing. */
internal object AgentRuntime {
    /** Whether the server is listening now, and where. */
    var running by mutableStateOf(false)
        private set
    var port by mutableIntStateOf(0)
        private set
    /** Why it could not start, in a few words, for the screen. */
    var error by mutableStateOf<String?>(null)
        private set
    /** Whether the running server was started to take other devices on the network. */
    var runningLan by mutableStateOf(false)
        private set

    private var server: AgentServer? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Starts the server as the settings say, or again when [restart]. True when it listens.
     * It looks for a free port from the saved one upwards and remembers the one it got.
     */
    @Synchronized
    fun start(context: Context, restart: Boolean = false): Boolean {
        if (running && !restart) return true
        stop()
        val app = context.applicationContext
        val lan = AgentSettings.lan(app)
        val commands = { TerminalSession.commands(app) }
        val gateway = AgentGateway(
            commands = commands,
            policy = { AgentSettings.policy(app) },
            applied = { record -> announce(app, record) },
        )
        val tools = object : McpTools {
            override fun run(lines: List<String>, client: String): String {
                val answer = gateway.run(lines, client)
                // Shown in the sidebar while it waits: what the agent is doing with the notes.
                AgentActivity.add(lines, runCatching { org.json.JSONObject(answer).optBoolean("ok") }.getOrDefault(false))
                // The library reads its notes again, whoever changed them.
                if (terminalChangedNotes(answer)) main.post { LibraryChanges.changed() }
                return answer
            }
            override fun help(topic: String): String = agentHelp(topic)
        }
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"
        val protocol = McpProtocol(tools, version) { _, sent, answered, images -> AgentUsage.add(sent, answered, images) }
        val candidate = AgentServer({ AgentSettings.token(app) }, protocol, lan)
        val first = AgentSettings.port(app)
        var bound = 0
        for (attempt in 0 until PORT_TRIES) {
            val wanted = if (first + attempt in 1024..65535) first + attempt else first
            bound = runCatching { candidate.start(wanted) }.getOrDefault(0)
            if (bound != 0) break
        }
        if (bound == 0) {
            main.post { error = "No free port was found."; running = false; port = 0 }
            return false
        }
        if (bound != first) AgentSettings.setPort(app, bound)
        server = candidate
        main.post { error = null; port = bound; runningLan = lan; running = true }
        return true
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        main.post { running = false; port = 0 }
    }

    /** A change an agent made: a pill with an Undo, and the library to read again. */
    private fun announce(context: Context, record: ChangeRecord) {
        main.post {
            LibraryChanges.changed()
            Notices.show("{0} changed your notes".tr.replace("{0}", record.client), "Undo".tr, onAction = { undo(context, record) })
        }
    }

    /** Takes back [record]'s change, if it is still the latest. */
    fun undo(context: Context, record: ChangeRecord) {
        val app = context.applicationContext
        Thread {
            val answer = TerminalSession.commands(app).undoIfLast(record.step)
            val ok = runCatching { org.json.JSONObject(answer).optBoolean("ok") }.getOrDefault(false)
            if (ok) ChangeLog.markUndone(record.id)
            main.post {
                if (ok) LibraryChanges.changed()
                Notices.show(if (ok) "Undone".tr else "Newer changes were made after this one, so it cannot be taken back on its own.".tr)
            }
        }.start()
    }

    private const val PORT_TRIES = 10
}

/**
 * Holds the server while the connection is on. A foreground service, so that
 * Android keeps the app running when another app (the agent's) is in front.
 */
class AgentService : Service() {
    private val waitingListener: () -> Unit = {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AgentApprovals.listen(waitingListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OFF) {
            AgentSettings.setEnabled(this, false)
            AgentRuntime.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Started again after the system ended it: only if the owner has not turned it off since.
        if (!AgentSettings.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        val shown = runCatching {
            val n = notification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, n)
        }
        if (shown.isFailure) { stopSelf(); return START_NOT_STICKY }
        val started = AgentRuntime.start(this, restart = intent?.getBooleanExtra(EXTRA_RESTART, false) == true)
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
        if (!started) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        AgentApprovals.unlisten(waitingListener)
        AgentRuntime.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Agent connection".tr, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while an agent can connect to your notes".tr
                    setShowBadge(false)
                },
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val off = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction(ACTION_OFF), flags)
        val waiting = AgentApprovals.waiting().size
        val text = when {
            waiting > 0 -> "Waiting for your OK on a change. Open Notesis.".tr
            AgentRuntime.running -> "Listening on port {0}".tr.replace("{0}", AgentRuntime.port.toString())
            else -> "Starting…".tr
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Agent connection".tr)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Turn off".tr, off).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL = "agent"
        private const val NOTIFICATION_ID = 7421
        private const val ACTION_OFF = "com.notesis.agent.OFF"
        private const val EXTRA_RESTART = "restart"

        /** Makes the service as the settings say: running when on, stopped when off; [restart] to listen anew (the network changed). */
        fun apply(context: Context, restart: Boolean = false) {
            val app = context.applicationContext
            val intent = Intent(app, AgentService::class.java).putExtra(EXTRA_RESTART, restart)
            if (AgentSettings.enabled(app)) runCatching { app.startForegroundService(intent) }
            else { runCatching { app.stopService(intent) }; AgentRuntime.stop() }
        }
    }
}

/** The command that adds this server to Claude Code, with the token in it: for the owner to paste where the agent runs. */
internal fun claudeCodeCommand(url: String, token: String): String =
    "claude mcp add --transport http notesis $url --header \"Authorization: Bearer $token\""

/** The same connection as the JSON many MCP clients read ("mcpServers"). */
internal fun mcpClientJson(url: String, token: String): String = org.json.JSONObject()
    .put("mcpServers", org.json.JSONObject().put("notesis", org.json.JSONObject()
        .put("type", "http").put("url", url)
        .put("headers", org.json.JSONObject().put("Authorization", "Bearer $token"))))
    .toString(2).replace("\\/", "/")
