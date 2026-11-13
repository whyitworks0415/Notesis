package com.notesis

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/*
 * The screens of the agent connection (Todo.md, C2 and C3): the panel where
 * it is switched on and set up, and the card that asks before a change an
 * agent wants to make. Both are on the panel every tool opens on.
 */

internal object AgentPanelState {
    var open by mutableStateOf(false)
}

/** The setup panel, while it is open. */
@Composable
internal fun AgentPanelHost() {
    if (AgentPanelState.open) AgentPanel()
}

private fun copySecret(context: Context, label: String, text: String) {
    runCatching {
        val clip = ClipData.newPlainText(label, text)
        // Keyboards and the clipboard preview are asked not to show it.
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        KToast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun AgentPanel() {
    val context = LocalContext.current
    val state = rememberToolPanelState()
    val close = { state.close { AgentPanelState.open = false } }
    var enabled by remember { mutableStateOf(AgentSettings.enabled(context)) }
    var lan by remember { mutableStateOf(AgentSettings.lan(context)) }
    var policy by remember { mutableStateOf(AgentSettings.policy(context)) }
    var token by remember { mutableStateOf(AgentSettings.token(context)) }
    var showToken by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    // Android 13 asks before a notification is shown; the connection works without it, only out of sight.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val running = AgentRuntime.running
    val port = if (running) AgentRuntime.port else AgentSettings.port(context)
    val url = "http://127.0.0.1:$port/mcp"
    // Read so the list below is drawn again when a change is made or taken back.
    ChangeLog.version

    ToolPanel(
        title = "Agent connection",
        state = state,
        onOutside = close,
        maxWidth = 560.dp,
        actions = { IconButton(onClick = close) { Icon(Reicons.Close, contentDescription = "Close") } },
    ) {
        Text(
            "Lets a program on this tablet, such as Claude Code or Codex in Termux, read and change your notes through the note commands. " +
                "Off until you switch it on.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PanelSwitchRow("Connection", enabled, hint = when {
            !enabled -> null
            running -> "Listening on this tablet, port {0}.".tr.replace("{0}", port.toString())
            AgentRuntime.error != null -> AgentRuntime.error
            else -> "Starting…".tr
        }) { on ->
            enabled = on
            AgentSettings.setEnabled(context, on)
            if (on && Build.VERSION.SDK_INT >= 33) runCatching { notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            AgentService.apply(context)
        }

        if (enabled) {
            PanelSectionHeader("Token")
            Text("An agent must send this with every request; nothing is answered without it.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(AnnotatedString(if (showToken) token else maskedToken(token)), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") }
                TextButton(onClick = { copySecret(context, "Notesis token", token) }) { Text("Copy token") }
                if (!asking) TextButton(onClick = { asking = true }) { Text("New token") }
                else {
                    TextButton(onClick = { asking = false }) { Text("Cancel") }
                    Button(onClick = {
                        token = AgentSettings.regenerateToken(context)
                        asking = false
                        showToken = true
                    }) { Text("Stop what is connected") }
                }
            }

            PanelSectionHeader("Changes")
            SegmentedControl(AgentPolicy.entries.map { it.label }, policy.ordinal, {
                policy = AgentPolicy.entries[it]
                AgentSettings.setPolicy(context, policy)
            }, Modifier.fillMaxWidth())
            Text(
                if (policy == AgentPolicy.ASK) "A card asks before each change. Reading is never asked about."
                else "Changes are made at once, and a pill lets you undo the latest. Reading is never asked about.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PanelSectionHeader("Same Wi-Fi")
            PanelSwitchRow(
                "Allow other devices on this network", lan,
                hint = "A computer on the same Wi-Fi can connect with the token. The traffic is not encrypted: use a network you trust.",
            ) { on ->
                lan = on
                AgentSettings.setLan(context, on)
                AgentService.apply(context, restart = true)
            }
            if (lan) {
                val addresses = remember { deviceAddresses() }
                Text(
                    if (addresses.isEmpty()) "No Wi-Fi address found." else addresses.joinToString("\n") { "http://$it:$port/mcp" },
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                )
            }

            PanelSectionHeader("Connect")
            Text("Run this where the agent is (on this tablet, in Termux):", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                TextButton(onClick = { copySecret(context, "Claude Code command", claudeCodeCommand(url, token)) }) { Text("Copy Claude Code command") }
                TextButton(onClick = { copySecret(context, "MCP settings", mcpClientJson(url, token)) }) { Text("Copy settings as JSON") }
                TextButton(onClick = { copySecret(context, "Address", url) }) { Text("Copy address") }
                TextButton(onClick = { copySecret(context, "Notesis guide", AGENT_GUIDE) }) { Text("Copy guide") }
            }
            Text("The guide tells an agent the rules and the shortest way to common tasks. The Termux setup puts it in each agent's instructions.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            AgentsOnThisTablet(url, token)

            PanelSectionHeader("Recent changes")
            val recent = remember(ChangeLog.version) { ChangeLog.all().take(8) }
            if (recent.isEmpty()) {
                Text("Nothing changed by an agent yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val latest = recent.firstOrNull { !it.undone }?.id
            for (record in recent) key(record.id) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("{0} · {1}".tr.replace("{0}", record.client)
                            .replace("{1}", DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(record.at))), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(AnnotatedString(record.labels.joinToString("\n")), style = MaterialTheme.typography.bodySmall)
                    }
                    when {
                        record.undone -> Text("Undone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        record.id == latest -> TextButton(onClick = { AgentRuntime.undo(context, record) }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) { Text("Undo") }
                    }
                }
            }

            PanelSectionHeader("Use")
            // What agents read is what they pay for: a count, so whole notes read again and again show up.
            if (AgentUsage.calls == 0) {
                Text("No calls since the app started.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("{0} calls · {1} characters asked · {2} characters answered".tr
                    .replace("{0}", AgentUsage.calls.toString()).replace("{1}", "%,d".format(AgentUsage.sent))
                    .replace("{2}", "%,d".format(AgentUsage.answered)), style = MaterialTheme.typography.bodySmall)
                Text("Largest answer: {0} characters · {1} pictures".tr.replace("{0}", "%,d".format(AgentUsage.largest))
                    .replace("{1}", AgentUsage.images.toString()), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { AgentUsage.reset() }) { Text("Reset count") }
            }
        }
    }
}

/** The card that asks before a change an agent wants to make, while one waits. */
@Composable
internal fun ChangeApprovalHost() {
    // Read so a card appears when a change starts waiting and goes when it is answered.
    AgentApprovals.version
    val all = AgentApprovals.waiting()
    val pending = all.firstOrNull() ?: return
    key(pending.id) { ApprovalCard(pending, more = all.size - 1) }
}

@Composable
private fun ApprovalCard(pending: PendingChange, more: Int) {
    val state = rememberToolPanelState()
    // The answer is given once the card has folded away, so the agent is not told before the owner has seen it go.
    fun answer(allow: Boolean) = state.close { AgentApprovals.decide(pending, allow) }
    ToolPanel(
        title = "Allow this change?",
        state = state,
        onOutside = { answer(false) },
        maxWidth = 520.dp,
        actions = {},
    ) {
        Text(
            "“{0}” asks to change your notes. The name is only what the program calls itself.".tr.replace("{0}", pending.client),
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
            for (line in pending.lines) {
                Text(AnnotatedString("• " + line.text { it.tr }), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text("Everything can be undone with the terminal's undo, and nothing is deleted for good.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (more > 0) {
            Text("{0} more waiting".tr.replace("{0}", more.toString()), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { answer(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Don't allow") }
            Spacer(Modifier.width(Ui.Space.s))
            Button(onClick = { answer(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow") }
        }
    }
}

/**
 * Setting an agent up on this tablet (Todo.md, T2): in Termux, a separate app, since Android
 * does not let Notesis run programs it downloads. The script is copied whole, to be read
 * and pasted there; it fetches Debian, Node.js and the agents and connects them here.
 */
@Composable
private fun AgentsOnThisTablet(url: String, token: String) {
    val context = LocalContext.current
    val termux = remember { context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) }
    var agents by remember { mutableStateOf(setOf(AgentCli.CLAUDE)) }
    PanelSectionHeader("On this tablet")
    Text(
        "Claude Code, Codex, OpenCode and Gemini CLI run in Termux, a separate app, in Debian: Android does not let an app run programs it downloads. " +
            "The setup script installs them there and connects them to your notes.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        for (agent in AgentCli.entries) {
            SettingsChoiceChip(agent in agents, { agents = if (agent in agents) agents - agent else agents + agent }, agent.label)
        }
    }
    Text(
        "1. Copy the setup script, then open Termux.\n2. Paste it, press Enter and wait until it says done.\n" +
            "3. In a new Termux session, type notesis-agents, then the agent's name.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        if (termux == null) {
            TextButton(onClick = {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(TERMUX_DOWNLOAD))) }
            }) { Text("Get Termux") }
        }
        TextButton(enabled = agents.isNotEmpty(), onClick = {
            runCatching {
                val bridge = context.assets.open("agent/notesis-mcp.js").bufferedReader().use { it.readText() }
                copySecret(context, "Notesis setup", termuxSetupScript(agents, url, token, bridge))
            }
        }) { Text("Copy setup script") }
        if (termux != null) TextButton(onClick = { runCatching { context.startActivity(termux) } }) { Text("Open Termux") }
        TextButton(onClick = { copySecret(context, "Notesis token update", termuxTokenUpdate(token)) }) { Text("Copy token update") }
    }
    Text("After making a new token, run the token update in Termux; the agents keep their setup.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
