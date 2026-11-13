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
        assertTrue(script.contains("npm install -g @anthropic-ai/claude-code @openai/codex\n"))
        assertTrue(script.contains("claude mcp add --scope user notesis -- node \"\$BRIDGE\""))
        assertTrue(script.contains("[mcp_servers.notesis]"))
        assertFalse(script.contains("opencode"))
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
            // proot-distro: install makes the folder; login runs what follows "--" here, with the same input.
            stub("proot-distro", """
                if [ "$1" = install ]; then mkdir -p "${'$'}PREFIX/var/lib/proot-distro/installed-rootfs/debian"; exit 0; fi
                while [ "$1" != "--" ]; do shift; done; shift; exec "$@"
            """.trimIndent())
            val script = File(root, "setup.sh").apply { writeText(termuxSetupScript(AgentCli.entries.toSet(), url, token, bridge)) }
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
            assertEquals(2, calls.count { it == "npm install -g @anthropic-ai/claude-code @openai/codex opencode-ai" })
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
            val opencode = JSONObject(File(home, ".config/opencode/opencode.json").readText()).getJSONObject("mcp").getJSONObject("notesis")
            assertEquals("local", opencode.getString("type"))
            assertEquals(bridgePath, opencode.getJSONArray("command").getString(1))
            assertEquals(1, File(home, ".bashrc").readLines().count { it.contains("alias notesis-agents=") })
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
            override fun help() = "the help"
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
}
