package com.notesis

import android.content.Context
import android.content.Intent
import android.os.Looper
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The whole way an agent comes in: the service starts the server, a client speaks MCP to it over
 * real HTTP, and the commands run on real note files - asked about or at once, as set, never on
 * a note on screen, never without the token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentServiceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val note get() = File(context.filesDir, "notes/agent-fixture")
    private var controller: org.robolectric.android.controller.ServiceController<AgentService>? = null
    private val owner = Any()

    @Before fun prepare() {
        note.deleteRecursively()
        File(note, "pages").mkdirs()
        File(note, "meta.json").writeText("""{"title":"Fixture","modified":1,"pages":[{"id":"p1","w":1240,"h":1754}]}""")
        SharedNotes.resetForTests()
        TerminalSession.resetForTests()
        context.getSharedPreferences("agent", Context.MODE_PRIVATE).edit().clear().commit()
        ChangeLog.clear()
        OpenNotes.clear(owner)
    }

    @After fun clean() {
        controller?.destroy()
        OpenNotes.clear(owner)
        AgentApprovals.waiting().forEach { AgentApprovals.decide(it, false) }
        note.deleteRecursively()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun startService(): Int {
        AgentSettings.setEnabled(context, true)
        controller = Robolectric.buildService(AgentService::class.java, Intent(context, AgentService::class.java)).create().startCommand(0, 1)
        idle()
        assertTrue(AgentRuntime.running)
        return AgentRuntime.port
    }

    private fun post(port: Int, body: String, token: String? = AgentSettings.token(context), session: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5_000
        c.readTimeout = 15_000
        c.setRequestProperty("Content-Type", "application/json")
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        session?.let { c.setRequestProperty("Mcp-Session-Id", it) }
        DataOutputStream(c.outputStream).use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        return code to text
    }

    private fun run(port: Int, session: String?, vararg lines: String): JSONObject {
        val commands = lines.joinToString(",") { "\"$it\"" }
        val (code, text) = post(port, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{\"commands\":[$commands]}}}", session = session)
        assertEquals(text, 200, code)
        val result = JSONObject(text).getJSONObject("result")
        return JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text")).put("isError", result.getBoolean("isError"))
    }

    private fun initialize(port: Int): String {
        val c = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer ${AgentSettings.token(context)}")
        c.setRequestProperty("Content-Type", "application/json")
        DataOutputStream(c.outputStream).use {
            it.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"Claude Code\"}}}".toByteArray())
        }
        assertEquals(200, c.responseCode)
        return c.getHeaderField("Mcp-Session-Id")
    }

    private fun pageCount(): Int = JSONObject(File(note, "meta.json").readText()).getJSONArray("pages").length()

    @Test fun theServiceListensAndAnswersOnlyWithTheToken() {
        val port = startService()
        assertNotNull(shadowOf(controller!!.get()).lastForegroundNotification)
        assertEquals(200, post(port, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").first)
        assertEquals(401, post(port, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", token = "wrong").first)
        assertEquals(401, post(port, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", token = null).first)
        // A new token stops the old one at once.
        val old = AgentSettings.token(context)
        AgentSettings.regenerateToken(context)
        assertEquals(401, post(port, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", token = old).first)
        assertEquals(200, post(port, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").first)
    }

    @Test fun madeAtOnce_aChangeIsAnnouncedAndItsUndoTakesItBack() {
        AgentSettings.setPolicy(context, AgentPolicy.AUTO)
        val port = startService()
        val session = initialize(port)
        val read = run(port, session, "notes list")
        assertTrue(read.toString(), read.getBoolean("ok"))
        val made = run(port, session, "page add Fixture")
        assertTrue(made.toString(), made.getBoolean("ok"))
        assertEquals(2, pageCount())
        idle()
        assertEquals("Claude Code changed your notes", Notices.current?.text)
        assertEquals(1, ChangeLog.all().size)
        // The pill's Undo.
        Notices.current!!.onAction!!.invoke()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (pageCount() != 1 && System.nanoTime() < deadline) { Thread.sleep(20); idle() }
        assertEquals(1, pageCount())
        idle()
        assertTrue(ChangeLog.all().first().undone)
    }

    @Test fun askedFirst_theChangeWaitsAndAnAnswerDecidesIt() {
        AgentSettings.setPolicy(context, AgentPolicy.ASK)
        val port = startService()
        val session = initialize(port)
        for (allow in listOf(false, true)) {
            var result: JSONObject? = null
            val client = Thread { result = run(port, session, "page add Fixture") }
            client.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (AgentApprovals.waiting().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            val pending = AgentApprovals.waiting().single()
            assertEquals("Claude Code", pending.client)
            assertEquals("Add a page to “Fixture”", pending.lines.single().text())
            // Nothing is changed while it waits.
            assertEquals("Nothing is changed while it waits", if (allow) 1 else 1, pageCount())
            AgentApprovals.decide(pending, allow)
            client.join(10_000)
            assertEquals(allow, result!!.getBoolean("ok"))
            if (!allow) assertTrue(result!!.getBoolean("denied"))
            assertEquals(if (allow) 2 else 1, pageCount())
        }
        // Reading was never asked about.
        assertTrue(run(port, session, "notes list").getBoolean("ok"))
    }

    // On screen where no screen can be held (a part of a split, the reference panel): only read.
    @Test fun aNoteOnScreenThatCannotBeHeldIsOnlyReadWhateverTheOwnerAllowed() {
        AgentSettings.setPolicy(context, AgentPolicy.AUTO)
        val port = startService()
        OpenNotes.set(owner, setOf("agent-fixture"))
        val refused = run(port, null, "page add Fixture")
        assertFalse(refused.getBoolean("ok"))
        assertEquals(refused.toString(), "OPEN_ON_SCREEN", refused.getString("code"))
        assertEquals(1, pageCount())
        assertTrue(run(port, null, "note read Fixture").getBoolean("ok"))
        OpenNotes.clear(owner)
        assertTrue(run(port, null, "page add Fixture").getBoolean("ok"))
    }

    @Test fun stoppingTheServiceClosesThePort() {
        val port = startService()
        controller!!.destroy()
        controller = null
        idle()
        assertFalse(AgentRuntime.running)
        val failed = runCatching { post(port, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}") }
        assertTrue(failed.isFailure)
    }

    @Test fun offInTheSettingsMeansTheServiceDoesNotStart() {
        AgentSettings.setEnabled(context, false)
        controller = Robolectric.buildService(AgentService::class.java, Intent(context, AgentService::class.java)).create().startCommand(0, 1)
        idle()
        assertFalse(AgentRuntime.running)
    }
}
