package com.notesis

/*
 * Agents on this tablet (Todo.md, T2). Notesis cannot run Claude Code, Codex
 * or OpenCode itself: since Android 10 an app that targets it may not run a
 * program it has written to its own storage, only the code inside its APK,
 * and these are programs fetched and updated from the internet. Termux may
 * (it keeps to the older target for exactly this), so they run there, in
 * Debian under proot-distro, and reach the notes through the agent
 * connection (AgentServer.kt) by way of a small bridge (assets/agent/
 * notesis-mcp.js) that each of them starts as an ordinary MCP server.
 *
 * Here: the script the owner pastes into Termux, written out in full so it
 * can be read before it is run. Nothing here needs Android.
 */

internal const val TERMUX_PACKAGE = "com.termux"

/** Where Termux is got from: its own releases; the copy in the Play store is old and no longer kept up. */
internal const val TERMUX_DOWNLOAD = "https://f-droid.org/packages/com.termux/"

/** An agent the setup can install, its npm package, and how it is told about the Notesis bridge. */
internal enum class AgentCli(val label: String, val npm: String, val command: String) {
    CLAUDE("Claude Code", "@anthropic-ai/claude-code", "claude"),
    CODEX("Codex", "@openai/codex", "codex"),
    OPENCODE("OpenCode", "opencode-ai", "opencode"),
}

private val SAFE_TOKEN = Regex("^[A-Za-z0-9_-]{16,128}$")
private val SAFE_URL = Regex("^http://[A-Za-z0-9.:\\[\\]-]+/mcp$")

/**
 * The script that sets the chosen [agents] up in Debian on Termux and points
 * each at the bridge: idempotent, so running it again updates rather than
 * doubles. [bridge] is the bridge's source, written into Debian as it is.
 */
internal fun termuxSetupScript(agents: Set<AgentCli>, url: String, token: String, bridge: String): String {
    require(agents.isNotEmpty()) { "No agent chosen" }
    require(SAFE_TOKEN.matches(token)) { "A token is letters, digits, - and _" }
    require(SAFE_URL.matches(url)) { "Not an address of this server" }
    require(bridge.lines().none { it.trim() == "NOTESIS_BRIDGE" || it.trim() == "NOTESIS_DEBIAN" }) { "The bridge would end the script early" }
    val chosen = AgentCli.entries.filter { it in agents }
    val names = chosen.joinToString(", ") { it.label }
    return buildString {
        appendLine("# Notesis: sets up $names in Debian on Termux, connected to your notes.")
        appendLine("# It downloads Debian, Node.js and the agents, which takes a while and needs room.")
        appendLine("set -e")
        appendLine("pkg update -y")
        appendLine("pkg install -y proot-distro")
        appendLine("if [ ! -d \"\$PREFIX/var/lib/proot-distro/installed-rootfs/debian\" ]; then proot-distro install debian; fi")
        appendLine("STATUS=0")
        appendLine("proot-distro login debian -- bash -s <<'NOTESIS_DEBIAN' || STATUS=\$?")
        appendLine("set -e")
        appendLine("export DEBIAN_FRONTEND=noninteractive")
        // The agents are large and some unpack a program into the temporary folder: keep that in the home folder, where the room is.
        appendLine("export TMPDIR=\"\$HOME/.notesis/tmp\"")
        appendLine("mkdir -p \"\$HOME/.notesis\" && chmod 700 \"\$HOME/.notesis\" && mkdir -p \"\$TMPDIR\"")
        appendLine("FREE_KB=\$(df -Pk \"\$HOME\" | awk 'NR==2{print \$4}')")
        appendLine("if [ \"\${FREE_KB:-0}\" -lt 1000000 ]; then echo \"Notesis: only \$((\${FREE_KB:-0}/1024)) MB free. The agents are large; if an install fails, free some space and run this again.\"; fi")
        appendLine("apt-get update")
        appendLine("apt-get install -y nodejs npm ca-certificates git")
        appendLine("printf '%s' '$url' > \"\$HOME/.notesis/url\"")
        appendLine("( umask 077; printf '%s' '$token' > \"\$HOME/.notesis/token\" )")
        appendLine("cat > \"\$HOME/.notesis/notesis-mcp.js\" <<'NOTESIS_BRIDGE'")
        append(bridge.trimEnd('\n')).append('\n')
        appendLine("NOTESIS_BRIDGE")
        appendLine("BRIDGE=\"\$HOME/.notesis/notesis-mcp.js\"")
        // One at a time: one that fails to install must not take the others with it.
        appendLine("FAILED=''")
        for (agent in chosen) {
            appendLine("echo \"Notesis: installing ${agent.label}…\"")
            appendLine("if npm install -g ${agent.npm} && command -v ${agent.command} >/dev/null 2>&1; then")
            when (agent) {
                AgentCli.CLAUDE -> {
                    appendLine("  claude mcp remove --scope user notesis >/dev/null 2>&1 || true")
                    appendLine("  claude mcp add --scope user notesis -- node \"\$BRIDGE\" || FAILED=\"\$FAILED ${agent.label}(connect)\"")
                }
                AgentCli.CODEX -> {
                    appendLine("  mkdir -p \"\$HOME/.codex\"")
                    appendLine("  if ! grep -q '^\\[mcp_servers.notesis\\]' \"\$HOME/.codex/config.toml\" 2>/dev/null; then")
                    appendLine("    printf '\\n[mcp_servers.notesis]\\ncommand = \"node\"\\nargs = [\"%s\"]\\n' \"\$BRIDGE\" >> \"\$HOME/.codex/config.toml\"")
                    appendLine("  fi")
                }
                AgentCli.OPENCODE -> {
                    appendLine("  node -e 'const fs=require(\"fs\"),path=require(\"path\"),p=process.argv[1];let c={};" +
                        "try{c=JSON.parse(fs.readFileSync(p,\"utf8\"))}catch(e){}c.mcp=c.mcp||{};" +
                        "c.mcp.notesis={type:\"local\",command:[\"node\",process.argv[2]],enabled:true};" +
                        "fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(c,null,2)+\"\\n\")' " +
                        "\"\$HOME/.config/opencode/opencode.json\" \"\$BRIDGE\" || FAILED=\"\$FAILED ${agent.label}(connect)\"")
                }
            }
            appendLine("else")
            appendLine("  FAILED=\"\$FAILED ${agent.label}\"")
            appendLine("fi")
        }
        appendLine("rm -rf \"\$TMPDIR\"")
        appendLine("if [ -n \"\$FAILED\" ]; then echo \"Notesis: not set up:\$FAILED\"; fi")
        appendLine("[ -z \"\$FAILED\" ]")
        appendLine("NOTESIS_DEBIAN")
        // A short way back in, from Termux.
        appendLine("grep -q 'alias notesis-agents=' \"\$HOME/.bashrc\" 2>/dev/null || echo \"alias notesis-agents='proot-distro login debian'\" >> \"\$HOME/.bashrc\"")
        appendLine("echo")
        appendLine("if [ \"\$STATUS\" = 0 ]; then")
        appendLine("  echo 'Notesis: done. Open a new Termux session, type notesis-agents, then ${chosen.joinToString(" or ") { it.command }}.'")
        appendLine("else")
        appendLine("  echo 'Notesis: finished, but not everything was set up (see above). What did install is ready: open a new Termux session and type notesis-agents. Run the setup again to retry the rest.'")
        appendLine("fi")
    }
}

/** What to run in Termux after a new token was made in Notesis: the agents keep working without setting up again. */
internal fun termuxTokenUpdate(token: String): String {
    require(SAFE_TOKEN.matches(token)) { "A token is letters, digits, - and _" }
    return "proot-distro login debian -- bash -c \"umask 077; printf '%s' '$token' > \\\$HOME/.notesis/token\""
}
