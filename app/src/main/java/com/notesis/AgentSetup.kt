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
internal enum class AgentCli(val label: String, val npm: String, val command: String, /** Its instructions file, from the home folder. */ val guide: String) {
    CLAUDE("Claude Code", "@anthropic-ai/claude-code", "claude", ".claude/CLAUDE.md"),
    CODEX("Codex", "@openai/codex", "codex", ".codex/AGENTS.md"),
    OPENCODE("OpenCode", "opencode-ai", "opencode", ".config/opencode/AGENTS.md"),
    GEMINI("Gemini CLI", "@google/gemini-cli", "gemini", ".gemini/GEMINI.md"),
}

/**
 * Puts the guide file (argument 2) into an instructions file (argument 1)
 * between the markers, as [withGuide] does: node, because Debian has it by
 * then and sed would trip over the guide's own characters.
 */
private val GUIDE_MERGE_JS = """const fs=require("fs"),path=require("path"),[p,g]=process.argv.slice(1);""" +
    """let t="";try{t=fs.readFileSync(p,"utf8")}catch(e){}""" +
    """const A="$GUIDE_START",B="$GUIDE_END",b=A+"\n"+fs.readFileSync(g,"utf8").trim()+"\n"+B;""" +
    """const s=t.indexOf(A),e=t.indexOf(B);""" +
    """t=(s>=0&&e>s)?t.slice(0,s)+b+t.slice(e+B.length):(t.trim()?t.replace(/\n*${'$'}/,"\n\n")+b+"\n":b+"\n");""" +
    """fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,t)"""

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
    require(AGENT_GUIDE.lines().none { it.trim() in setOf("NOTESIS_GUIDE", "NOTESIS_DEBIAN") } && '\'' !in GUIDE_MERGE_JS) { "The guide would end the script early" }
    require((ASK_HELPER_SCRIPT.lines() + SESSIONS_SCRIPT.lines()).none { it.trim() in setOf("NOTESIS_ASK", "NOTESIS_SESSIONS", "NOTESIS_DEBIAN") }) {
        "A helper would end the script early"
    }
    val chosen = AgentCli.entries.filter { it in agents }
    val names = chosen.joinToString(", ") { it.label }
    return buildString {
        appendLine("# Notesis: sets up $names in Debian on Termux, connected to your notes.")
        appendLine("# It downloads Debian, Node.js and the agents, which takes a while and needs room.")
        appendLine("set -e")
        appendLine("pkg update -y")
        appendLine("pkg install -y proot-distro")
        // So the agent sidebar in Notesis can ask Termux to run an agent: only apps the owner gave the
        // "run commands in Termux" permission may, and Notesis is the one this is for.
        appendLine("mkdir -p \"\$HOME/.termux\"")
        appendLine("grep -q '^allow-external-apps *= *true' \"\$HOME/.termux/termux.properties\" 2>/dev/null || " +
            "echo 'allow-external-apps = true' >> \"\$HOME/.termux/termux.properties\"")
        appendLine("termux-reload-settings >/dev/null 2>&1 || true")
        // Whether Debian is there is asked by going in, not by looking for its folder: where proot-distro keeps it changed between versions,
        // and installing over one that exists is an error.
        appendLine("if ! proot-distro login debian -- true >/dev/null 2>&1; then proot-distro install debian; fi")
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
        // The helper the sidebar's requests go through (AgentChat.kt): the request on its input, never on a command line.
        appendLine("cat > \"\$HOME/.notesis/ask\" <<'NOTESIS_ASK'")
        append(ASK_HELPER_SCRIPT.trimEnd('\n')).append('\n')
        appendLine("NOTESIS_ASK")
        appendLine("chmod 700 \"\$HOME/.notesis/ask\"")
        appendLine("cat > \"\$HOME/.notesis/sessions.js\" <<'NOTESIS_SESSIONS'")
        append(SESSIONS_SCRIPT.trimEnd('\n')).append('\n')
        appendLine("NOTESIS_SESSIONS")
        // What each agent is told about the notes, in its own instructions file; setting up again replaces only this part.
        appendLine("cat > \"\$HOME/.notesis/guide.md\" <<'NOTESIS_GUIDE'")
        append(AGENT_GUIDE.trimEnd('\n')).append('\n')
        appendLine("NOTESIS_GUIDE")
        appendLine("notesis_guide() { node -e '$GUIDE_MERGE_JS' \"\$1\" \"\$HOME/.notesis/guide.md\"; }")
        // One at a time: one that fails to install must not take the others with it.
        appendLine("FAILED=''")
        for (agent in chosen) {
            appendLine("echo \"Notesis: installing ${agent.label}…\"")
            // Gemini CLI wants Node 20 or newer, and Debian's own is older: fetch a newer one for it if need be.
            val prepare = if (agent == AgentCli.GEMINI)
                "[ \"\$(node -p 'process.versions.node.split(\".\")[0]')\" -ge 20 ] || { echo 'Notesis: Gemini CLI needs Node 20 or newer; getting it…'; npm install -g n && n 20 && hash -r; } && "
                else ""
            appendLine("if ${prepare}npm install -g ${agent.npm} && command -v ${agent.command} >/dev/null 2>&1; then")
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
                AgentCli.GEMINI -> {
                    appendLine("  node -e 'const fs=require(\"fs\"),path=require(\"path\"),p=process.argv[1];let c={};" +
                        "try{c=JSON.parse(fs.readFileSync(p,\"utf8\"))}catch(e){}c.mcpServers=c.mcpServers||{};" +
                        "c.mcpServers.notesis={command:\"node\",args:[process.argv[2]],trust:true};" +
                        "fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(c,null,2)+\"\\n\")' " +
                        "\"\$HOME/.gemini/settings.json\" \"\$BRIDGE\" || FAILED=\"\$FAILED ${agent.label}(connect)\"")
                }
                AgentCli.OPENCODE -> {
                    appendLine("  node -e 'const fs=require(\"fs\"),path=require(\"path\"),p=process.argv[1];let c={};" +
                        "try{c=JSON.parse(fs.readFileSync(p,\"utf8\"))}catch(e){}c.mcp=c.mcp||{};" +
                        "c.mcp.notesis={type:\"local\",command:[\"node\",process.argv[2]],enabled:true};" +
                        "fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(c,null,2)+\"\\n\")' " +
                        "\"\$HOME/.config/opencode/opencode.json\" \"\$BRIDGE\" || FAILED=\"\$FAILED ${agent.label}(connect)\"")
                }
            }
            appendLine("  notesis_guide \"\$HOME/${agent.guide}\" || FAILED=\"\$FAILED ${agent.label}(guide)\"")
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
