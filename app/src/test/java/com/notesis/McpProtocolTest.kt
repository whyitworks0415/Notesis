package com.notesis

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    private class Tools : McpTools {
        val calls = mutableListOf<Pair<List<String>, String>>()
        var answer = "{\"ok\":true,\"result\":{}}"
        override fun run(lines: List<String>, client: String): String { calls += lines to client; return answer }
        override fun help() = "help text"
    }

    private val tools = Tools()
    private val mcp = McpProtocol(tools, "9.9")
    private fun send(body: String, session: String? = null) = mcp.handle(body, session)
    private fun json(reply: McpReply) = JSONObject(reply.body!!)

    private fun initialize(version: String = "2025-06-18", client: String = "Claude Code"): McpReply = send(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"$version\"," +
            "\"capabilities\":{},\"clientInfo\":{\"name\":\"$client\",\"version\":\"1\"}}}")

    @Test
    fun `initialize agrees a version and opens a session`() {
        val reply = initialize()
        assertEquals(200, reply.status)
        assertNotNull(reply.session)
        val result = json(reply).getJSONObject("result")
        assertEquals("2025-06-18", result.getString("protocolVersion"))
        assertEquals("notesis", result.getJSONObject("serverInfo").getString("name"))
        assertEquals("9.9", result.getJSONObject("serverInfo").getString("version"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
        assertEquals("2024-11-05", json(initialize("2024-11-05")).getJSONObject("result").getString("protocolVersion"))
        // A version it does not know is answered with the latest it does.
        assertEquals("2025-06-18", json(initialize("1999-01-01")).getJSONObject("result").getString("protocolVersion"))
    }

    @Test
    fun `notifications and replies are not answered`() {
        val reply = send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
        assertEquals(202, reply.status)
        assertNull(reply.body)
        assertEquals(202, send("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}").status)
    }

    @Test
    fun `tools are listed with their schemas`() {
        val tools = json(send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")).getJSONObject("result").getJSONArray("tools")
        assertEquals(listOf("notesis_help", "notesis_run"), (0 until tools.length()).map { tools.getJSONObject(it).getString("name") })
        val run = tools.getJSONObject(1)
        assertEquals("object", run.getJSONObject("inputSchema").getString("type"))
        assertEquals(50, run.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("commands").getInt("maxItems"))
        assertEquals("commands", run.getJSONObject("inputSchema").getJSONArray("required").getString(0))
    }

    @Test
    fun `a call runs the lines for the client the session named`() {
        val session = initialize(client = "OpenCode").session
        val reply = send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\"," +
            "\"arguments\":{\"commands\":[\"notes list\",\"page add Maths\"]}}}", session)
        assertEquals(listOf("notes list", "page add Maths"), tools.calls.single().first)
        assertEquals("OpenCode", tools.calls.single().second)
        val result = json(reply).getJSONObject("result")
        assertFalse(result.getBoolean("isError"))
        assertEquals(7, json(reply).getInt("id"))
        assertEquals("text", result.getJSONArray("content").getJSONObject(0).getString("type"))
        // Without a session the client is "Agent"; a failed answer is flagged.
        tools.answer = "{\"ok\":false,\"error\":\"no\"}"
        val failed = json(send("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{\"command\":\"undo\"}}}"))
        assertTrue(failed.getJSONObject("result").getBoolean("isError"))
        assertEquals("Agent", tools.calls.last().second)
        assertEquals("help text", json(send("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_help\"}}"))
            .getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun `a client's name is cleaned and cut`() {
        val name = "Evil\n\u0007Agent " + "x".repeat(200)
        val session = initialize(client = name.replace("\n", "\\n")).session
        send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{\"commands\":[\"help\"]}}}", session)
        val seen = tools.calls.last().second
        assertTrue(seen.length <= 60)
        assertFalse(seen.any { it.isISOControl() })
    }

    @Test
    fun `wrong calls are answered as errors, not run`() {
        fun code(body: String) = json(send(body)).getJSONObject("error").getInt("code")
        assertEquals(-32601, code("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}"))
        assertEquals(-32602, code("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"rm_rf\"}}"))
        assertEquals(-32602, code("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{}}}"))
        assertEquals(-32602, code("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{\"commands\":[1]}}}"))
        assertEquals(-32602, code("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{\"commands\":[]}}}"))
        val many = (1..51).joinToString(",") { "\"help\"" }
        assertEquals(-32602, code("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{\"commands\":[$many]}}}"))
        val long = "x".repeat(MCP_MAX_LINE + 1)
        assertEquals(-32602, code("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{\"commands\":[\"$long\"]}}}"))
        assertTrue(tools.calls.isEmpty())
    }

    @Test
    fun `bad json and bad messages are 400s`() {
        val notJson = send("not json")
        assertEquals(400, notJson.status)
        assertEquals(-32700, json(notJson).getJSONObject("error").getInt("code"))
        assertEquals(400, send("{\"id\":1,\"method\":\"ping\"}").status)
        assertEquals(400, send("{\"jsonrpc\":\"2.0\",\"id\":[1],\"method\":\"ping\"}").status)
        assertEquals(400, send("[]").status)
        assertEquals(400, send("42").status)
    }

    @Test
    fun `a batch is answered in one array, notifications left out`() {
        val reply = send("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}," +
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}]")
        val array = JSONArray(reply.body)
        assertEquals(2, array.length())
        assertEquals(1, array.getJSONObject(0).getInt("id"))
        assertEquals(202, send("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]").status)
        assertEquals(400, send((1..21).joinToString(",", "[", "]") { "{\"jsonrpc\":\"2.0\",\"id\":$it,\"method\":\"ping\"}" }).status)
    }

    @Test
    fun `ping answers an empty result`() {
        assertEquals(0, json(send("{\"jsonrpc\":\"2.0\",\"id\":\"p\",\"method\":\"ping\"}")).getJSONObject("result").length())
    }
}
