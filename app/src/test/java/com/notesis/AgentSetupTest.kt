package com.notesis

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AgentSetupTest {
    private val bridgeFile = File("src/main/assets/agent/notesis-mcp.js")
    private val bridge get() = bridgeFile.readText()
    private val token = "nts_" + "A1b2C3d4-_".repeat(4)
    private val url = "http://127.0.0.1:47821/mcp"

    private fun has(program: String) =
        runCatching { ProcessBuilder(program, "--version").redirectErrorStream(true).start().also { it.inputStream.readBytes(); it.waitFor() }.exitValue() == 0 }.getOrDefault(false)

    @Test
    fun `the script installs what was chosen and points each at the bridge`() {
        val script = termuxSetupScript(setOf(AgentCli.CODEX, AgentCli.CLAUDE), url, token, bridge)
        // One at a time, so one that will not install cannot take the others with it.
        assertTrue(script.contains("if npm install -g @anthropic-ai/claude-code && command -v claude"))
        assertTrue(script.contains("if npm install -g @openai/codex && command -v codex"))
        assertFalse(script.contains("npm install -g @anthropic-ai/claude-code @openai/codex"))
        assertTrue(script.contains("claude mcp add --scope user notesis -- node \"\$BRIDGE\""))
        assertTrue(script.contains("export TMPDIR=\"\$HOME/.notesis/tmp\""))
        assertTrue(script.contains("[mcp_servers.notesis]"))
        assertFalse(script.contains("npm install -g opencode-ai"))
        assertTrue(script.contains(bridge.trimEnd('\n')))
        assertTrue(script.contains("printf '%s' '$token'"))
        assertTrue(script.contains("then claude or codex."))
    }

    @Test
    fun `nothing unsafe goes into the script`() {
        fun refused(block: () -> Unit) = assertTrue(runCatching(block).isFailure)
        refused { termuxSetupScript(emptySet(), url, token, bridge) }
        refused { termuxSetupScript(setOf(AgentCli.CLAUDE), url, "nts_'; rm -rf ~; '", bridge) }
        refused { termuxSetupScript(setOf(AgentCli.CLAUDE), "http://127.0.0.1:1/mcp'; reboot", token, bridge) }
        refused { termuxSetupScript(setOf(AgentCli.CLAUDE), url, token, "x\nNOTESIS_DEBIAN\nrm -rf /\n") }
        refused { termuxTokenUpdate("bad token") }
        assertEquals("proot-distro login debian -- bash -c \"umask 077; printf '%s' '$token' > \\\$HOME/.notesis/token\"", termuxTokenUpdate(token))
    }

    @Test
    fun `the script is valid bash`() {
        assumeTrue(has("bash"))
        val file = File.createTempFile("setup", ".sh").apply { writeText(termuxSetupScript(AgentCli.entries.toSet(), url, token, bridge)); deleteOnExit() }
        val p = ProcessBuilder("bash", "-n", file.path).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(out, 0, p.waitFor())
    }

    /** Runs the script with stand-ins for Termux's and Debian's tools that write down how they were called. */
    @Test
    fun `run twice with stand-ins, it sets everything up once`() {
        assumeTrue(has("bash") && has("node"))
        val root = Files.createTempDirectory("notesis-setup").toFile()
        try {
            val bin = File(root, "bin").apply { mkdirs() }
            val home = File(root, "home").apply { mkdirs() }
            val prefix = File(root, "prefix").apply { mkdirs() }
            val log = File(root, "log")
            fun stub(name: String, body: String) = File(bin, name).apply { writeText("#!/bin/bash\necho \"$name \$*\" >> '${log.path}'\n$body\n"); setExecutable(true) }
            stub("pkg", "")
            stub("apt-get", "")
            stub("npm", "")
            stub("claude", "")
            stub("codex", "")
            stub("opencode", "")
            stub("gemini", "")
            // Node 20 or newer is there already in this run; the fetching of a newer one is for a tablet.

            // proot-distro: install makes the folder; login runs what follows "--" here, with the same input.
            stub("proot-distro", """
                D="${'$'}PREFIX/debian-here"
                if [ "$1" = install ]; then
                  # Like the newer proot-distro: installing what exists is an error.
                  if [ -d "${'$'}D" ]; then echo "Error: container 'debian' already exists." >&2; exit 1; fi
                  mkdir -p "${'$'}D"; exit 0
                fi
                [ -d "${'$'}D" ] || exit 1
                while [ "$1" != "--" ]; do shift; done; shift; exec "$@"
            """.trimIndent())
            val script = File(root, "setup.sh").apply { writeText(termuxSetupScript(AgentCli.entries.toSet(), url, token, bridge)) }
            // The owner's own instructions for Claude Code, which the guide must sit beside, not replace.
            File(home, ".claude").mkdirs()
            File(home, ".claude/CLAUDE.md").writeText("Answer in Korean.\n")
            fun run() {
                val p = ProcessBuilder("bash", script.path).redirectErrorStream(true)
                p.environment().apply { put("HOME", home.path); put("PREFIX", prefix.path); put("PATH", bin.path + ":" + get("PATH")) }
                val proc = p.start()
                val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                assertEquals(out, 0, proc.waitFor())
                assertTrue(out, out.contains("Notesis: done."))
            }
            run()
            run()
            val calls = log.readLines()
            assertEquals(1, calls.count { it == "proot-distro install debian" })
            assertTrue(calls.contains("pkg install -y proot-distro"))
            for (npm in listOf("@anthropic-ai/claude-code", "@openai/codex", "opencode-ai", "@google/gemini-cli")) assertEquals(npm, 2, calls.count { it == "npm install -g $npm" })
            val bridgePath = File(home, ".notesis/notesis-mcp.js").path
            assertTrue(calls.contains("claude mcp add --scope user notesis -- node $bridgePath"))
            assertEquals(bridge.trimEnd('\n') + "\n", File(home, ".notesis/notesis-mcp.js").readText())
            val tokenFile = File(home, ".notesis/token")
            assertEquals(token, tokenFile.readText())
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(tokenFile.toPath()))
            assertEquals(url, File(home, ".notesis/url").readText())
            val codex = File(home, ".codex/config.toml").readText()
            assertEquals(1, Regex("\\[mcp_servers.notesis]").findAll(codex).count())
            assertTrue(codex.contains("args = [\"$bridgePath\"]"))
            val gemini = JSONObject(File(home, ".gemini/settings.json").readText()).getJSONObject("mcpServers").getJSONObject("notesis")
            assertEquals("node", gemini.getString("command"))
            assertEquals(bridgePath, gemini.getJSONArray("args").getString(0))
            val opencode = JSONObject(File(home, ".config/opencode/opencode.json").readText()).getJSONObject("mcp").getJSONObject("notesis")
            assertEquals("local", opencode.getString("type"))
            assertEquals(bridgePath, opencode.getJSONArray("command").getString(1))
            assertEquals(1, File(home, ".bashrc").readLines().count { it.contains("alias notesis-agents=") })
            // Each agent's instructions hold the guide once, however often the setup ran, and the owner's lines stay.
            val claudeMd = File(home, ".claude/CLAUDE.md").readText()
            assertTrue(claudeMd.startsWith("Answer in Korean.\n"))
            assertEquals(1, claudeMd.split(GUIDE_START).size - 1)
            assertTrue(claudeMd.contains("toc import-pdf"))
            for (agent in AgentCli.entries - AgentCli.CLAUDE) assertEquals(agent.guide, withGuide(""), File(home, agent.guide).readText())
            assertEquals(withGuide("Answer in Korean.\n"), claudeMd)
            // Termux takes the sidebar's requests, set once however often the setup ran, and the helper they go through is there.
            assertEquals(1, File(home, ".termux/termux.properties").readLines().count { it == "allow-external-apps = true" })
            assertEquals(ASK_HELPER_SCRIPT + "\n", File(home, ".notesis/ask").readText())
            assertTrue(JSONObject(File(home, ".gemini/settings.json").readText()).getJSONObject("mcpServers").getJSONObject("notesis").getBoolean("trust"))
            // The helper hands the request to the agent as one word, whatever is in it.
            val asked = ProcessBuilder("bash", File(home, ".notesis/ask").path, "claude", "continue").redirectErrorStream(true)
            asked.environment().apply { put("HOME", home.path); put("PATH", bin.path + ":" + get("PATH")) }
            val ask = asked.start()
            ask.outputStream.use { it.write("Add \"an\" outline; \$(rm -rf ~)".toByteArray()) }
            assertEquals(0, ask.waitFor())
            assertTrue(log.readLines().last(), log.readLines().last() == "claude -p Add \"an\" outline; \$(rm -rf ~) " +
                "--allowedTools mcp__notesis__notesis_run,mcp__notesis__notesis_help --continue")
            // An earlier conversation is carried on by its id, and the conversations are listed from the agent's own files.
            fun helper(vararg args: String, input: String = ""): String {
                val pb = ProcessBuilder(listOf("bash", File(home, ".notesis/ask").path) + args).redirectErrorStream(true)
                pb.environment().apply { put("HOME", home.path); put("PATH", bin.path + ":" + get("PATH")) }
                val proc = pb.start()
                proc.outputStream.use { it.write(input.toByteArray()) }
                val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                proc.waitFor()
                return out
            }
            helper("codex", "resume", "019a-77f2", input = "Go on")
            assertEquals("codex exec --skip-git-repo-check resume 019a-77f2 Go on", log.readLines().last())
            helper("gemini", "continue", input = "More")
            assertEquals("gemini --resume latest -p More", log.readLines().last())
            val projects = File(home, ".claude/projects/" + home.path.replace(Regex("[^A-Za-z0-9]"), "-")).apply { mkdirs() }
            File(projects, "5f1c-aa.jsonl").writeText("{\"type\":\"summary\"}\n" +
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Outline the PDF note\"}}\n")
            val listed = parseSessions(helper("sessions", "claude"))
            assertEquals(listOf("5f1c-aa"), listed.map { it.id })
            assertEquals("Outline the PDF note", listed[0].title)
            // The temporary folder the installs used is gone.
            assertFalse(File(home, ".notesis/tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    /** One agent that will not install (as OpenCode did on a tablet) must not stop the others being set up. */
    @Test
    fun `an agent that fails to install does not stop the others`() {
        assumeTrue(has("bash") && has("node"))
        val root = Files.createTempDirectory("notesis-setup-fail").toFile()
        try {
            val bin = File(root, "bin").apply { mkdirs() }
            val home = File(root, "home").apply { mkdirs() }
            val prefix = File(root, "prefix").apply { mkdirs() }
            val log = File(root, "log")
            fun stub(name: String, body: String) = File(bin, name).apply { writeText("#!/bin/bash\necho \"$name \$*\" >> '${log.path}'\n$body\n"); setExecutable(true) }
            stub("pkg", ""); stub("apt-get", ""); stub("claude", ""); stub("codex", "")
            // npm fails for opencode-ai, as its postinstall did; and so no opencode program exists.
            stub("npm", "if [ \"${'$'}3\" = opencode-ai ]; then echo 'npm ERR! Binary not found' >&2; exit 1; fi")
            stub("proot-distro", """
                D="${'$'}PREFIX/debian-here"
                if [ "$1" = install ]; then
                  # Like the newer proot-distro: installing what exists is an error.
                  if [ -d "${'$'}D" ]; then echo "Error: container 'debian' already exists." >&2; exit 1; fi
                  mkdir -p "${'$'}D"; exit 0
                fi
                [ -d "${'$'}D" ] || exit 1
                while [ "$1" != "--" ]; do shift; done; shift; exec "$@"
            """.trimIndent())
            val script = File(root, "setup.sh").apply { writeText(termuxSetupScript(AgentCli.entries.toSet(), url, token, bridge)) }
            val pb = ProcessBuilder("bash", script.path).redirectErrorStream(true)
            pb.environment().apply { put("HOME", home.path); put("PREFIX", prefix.path); put("PATH", bin.path + ":" + get("PATH")) }
            val proc = pb.start()
            val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(out, 0, proc.waitFor())
            assertTrue(out, out.contains("Notesis: not set up: OpenCode"))
            assertTrue(out, out.contains("not everything was set up"))
            assertFalse(out, out.contains("Notesis: done."))
            // The two that installed are connected; the one that did not has no config written for a program that is not there.
            val calls = log.readLines()
            assertTrue(calls.contains("claude mcp add --scope user notesis -- node ${File(home, ".notesis/notesis-mcp.js").path}"))
            assertTrue(File(home, ".codex/config.toml").readText().contains("[mcp_servers.notesis]"))
            assertFalse(File(home, ".config/opencode/opencode.json").exists())
            assertEquals(token, File(home, ".notesis/token").readText())
            assertTrue(File(home, ".bashrc").readText().contains("alias notesis-agents="))
        } finally {
            root.deleteRecursively()
        }
    }

    /** The bridge, run by node as an agent would run it, against the real server. */
    @Test
    fun `the bridge carries messages both ways and says plainly what went wrong`() {
        assumeTrue(has("node"))
        val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<List<String>, String>>())
        val tools = object : McpTools {
            override fun run(lines: List<String>, client: String): String { seen += lines to client; return "{\"ok\":true,\"result\":{\"lines\":${lines.size}}}" }
            override fun help(topic: String) = "the help"
        }
        val server = AgentServer({ token }, McpProtocol(tools, "1"), lan = false)
        val port = server.start(0)
        val tokenFile = File.createTempFile("token", "").apply { writeText(token); deleteOnExit() }
        fun bridge(): Triple<Process, java.io.Writer, LinkedBlockingQueue<String>> {
            val pb = ProcessBuilder("node", bridgeFile.absolutePath)
            pb.environment().apply { put("NOTESIS_URL", "http://127.0.0.1:$port/mcp"); put("NOTESIS_TOKEN_FILE", tokenFile.path); put("HOME", tokenFile.parent) }
            val p = pb.start()
            val queue = LinkedBlockingQueue<String>()
            Thread { BufferedReader(InputStreamReader(p.inputStream)).forEachLine { queue.put(it) } }.apply { isDaemon = true }.start()
            return Triple(p, p.outputStream.writer(), queue)
        }
        try {
            val (p, input, out) = bridge()
            fun send(line: String) { input.write(line + "\n"); input.flush() }
            fun next(): JSONObject = JSONObject(assertNotNullAndGet(out.poll(10, TimeUnit.SECONDS)))
            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"Codex\"}}}")
            assertEquals("notesis", next().getJSONObject("result").getJSONObject("serverInfo").getString("name"))
            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
            send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
            assertEquals(2, next().getJSONObject("result").getJSONArray("tools").length())
            send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{\"commands\":[\"notes list\",\"toc show A\"]}}}")
            val called = next()
            assertEquals(3, called.getInt("id"))
            assertFalse(called.getJSONObject("result").getBoolean("isError"))
            // The session the app opened went back with the call, so the app knew who asked.
            assertEquals(listOf("notes list", "toc show A") to "Codex", seen.single())
            // A token changed in the app: the bridge says so, as an answer to the request.
            tokenFile.writeText("nts_" + "x".repeat(40))
            send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}")
            val refused = next()
            assertEquals(4, refused.getInt("id"))
            assertTrue(refused.getJSONObject("error").getString("message").contains("refused the token"))
            // The app gone: the same, in words.
            server.stop()
            send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}")
            assertTrue(next().getJSONObject("error").getString("message").contains("not reachable"))
            input.close()
            assertTrue(p.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, p.exitValue())
        } finally {
            server.stop()
        }
    }

    private fun assertNotNullAndGet(line: String?): String { assertNotNull("no answer from the bridge", line); return line!! }

    @Test
    fun `the guide replaces its old self and leaves the owner's lines`() {
        val first = withGuide("Mine.\n", "old guide")
        assertEquals("Mine.\n\n$GUIDE_START\nold guide\n$GUIDE_END\n", first)
        val again = withGuide(first + "More of mine.\n", "new guide")
        assertEquals("Mine.\n\n$GUIDE_START\nnew guide\n$GUIDE_END\nMore of mine.\n", again)
        assertEquals("$GUIDE_START\ng\n$GUIDE_END\n", withGuide("", "g"))
        // Every command the guide names is one there is.
        for (command in Regex("`([a-z]+(?: [a-z-]+)?)[ `]").findAll(AGENT_GUIDE).map { it.groupValues[1] }) {
            if (command in setOf("notesis_help", "notesis_run", "termux-setup-storage", "next")) continue
            assertTrue(command, COMMAND_USAGE.containsKey(command) || COMMAND_USAGE.containsKey(command.substringBefore(' ')))
        }
    }
}
