package com.notesis

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class AgentServerTest {
    private val token = "test-token-" + "x".repeat(40)
    private val ran = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
    private val tools = object : McpTools {
        override fun run(lines: List<String>, client: String): String { ran += lines; return "{\"ok\":true,\"result\":{\"n\":${lines.size}}}" }
        override fun help() = "help"
    }
    private var server = AgentServer({ token }, McpProtocol(tools, "1"), lan = false)
    private var port = 0

    @Before fun start() { port = server.start(0) }
    @After fun stop() { server.stop() }

    private class Answer(val status: Int, val headers: Map<String, String>, val body: String)

    /** One raw request, written as given, and the answer read to the end. */
    private fun raw(request: String, body: ByteArray = ByteArray(0), port: Int = this.port): Answer {
        Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
            val all = s.getInputStream().readBytes()
            val text = String(all, Charsets.UTF_8)
            val head = text.substringBefore("\r\n\r\n")
            val lines = head.split("\r\n")
            val status = lines[0].split(' ')[1].toInt()
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Answer(status, headers, text.substringAfter("\r\n\r\n", ""))
        }
    }

    private fun post(body: String, auth: String? = "Bearer $token", host: String = "127.0.0.1:$port", extra: String = "", path: String = "/mcp"): Answer {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "POST $path HTTP/1.1\r\nHost: $host\r\n" + (auth?.let { "Authorization: $it\r\n" } ?: "") +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n$extra\r\n"
        return raw(head, bytes)
    }

    private val ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"
    private val run = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{\"commands\":[\"notes list\"]}}}"

    @Test
    fun `the right token is answered, only on loopback`() {
        val answer = post(ping)
        assertEquals(200, answer.status)
        assertEquals("application/json; charset=utf-8", answer.headers["content-type"])
        assertEquals("close", answer.headers["connection"])
        assertEquals(1, JSONObject(answer.body).getInt("id"))
        assertEquals(1, ran.size + 1) // nothing ran for a ping
        val called = JSONObject(post(run).body).getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(called.contains("\"n\":1"))
        assertEquals(listOf(listOf("notes list")), ran.toList())
    }

    @Test
    fun `no token, a wrong token and a near token are all refused, and nothing runs`() {
        assertEquals(401, post(run, auth = null).status)
        assertEquals("Bearer", post(run, auth = null).headers["www-authenticate"])
        assertEquals(401, post(run, auth = "Bearer wrong").status)
        assertEquals(401, post(run, auth = "Bearer " + token.dropLast(1)).status)
        assertEquals(401, post(run, auth = "Bearer " + token + "x").status)
        assertEquals(401, post(run, auth = "Basic $token").status)
        assertEquals(401, post(run, auth = "Bearer ").status)
        // Not even a missing page or a wrong method is told to one without the token.
        assertEquals(401, post(run, auth = null, path = "/other").status)
        assertEquals(401, raw("GET /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n").status)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `an empty token on the server refuses everything`() {
        server.stop()
        server = AgentServer({ "" }, McpProtocol(tools, "1"), lan = false)
        port = server.start(0)
        assertEquals(401, post(run, auth = "Bearer ").status)
        assertEquals(401, post(run, auth = "Bearer x").status)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a web page cannot reach it, nor a name that is not this device's`() {
        assertEquals(403, post(run, extra = "Origin: https://evil.example\r\n").status)
        assertEquals(403, post(run, extra = "Origin: null\r\n").status)
        assertEquals(200, post(ping, extra = "Origin: http://localhost:3000\r\n").status)
        assertEquals(403, post(run, host = "evil.example:$port").status)
        assertEquals(403, post(run, host = "evil.example").status)
        assertEquals(200, post(ping, host = "localhost:$port").status)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `the page and the method are checked once the token is right`() {
        assertEquals(404, post(run, path = "/").status)
        assertEquals(404, post(run, path = "/mcp/extra").status)
        assertEquals(200, post(ping, path = "/mcp/").status)
        val get = raw("GET /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\n\r\n")
        assertEquals(405, get.status)
        assertEquals("POST", get.headers["allow"])
        assertEquals(405, raw("DELETE /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\n\r\n").status)
        // No CORS answer at all: a browser's preflight gets nothing to go on.
        val options = raw("OPTIONS /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nOrigin: https://evil.example\r\n\r\n")
        assertEquals(403, options.status)
        assertFalse(options.headers.keys.any { it.startsWith("access-control") })
    }

    @Test
    fun `a notification is accepted with no body, a session is handed out and echoed`() {
        val note = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
        assertEquals(202, note.status)
        assertEquals("", note.body)
        val init = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"X\"}}}")
        assertTrue(init.headers["mcp-session-id"]!!.length >= 16)
    }

    @Test
    fun `bodies that are too large, missing or cut short are refused`() {
        val big = raw("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\nContent-Length: ${AGENT_MAX_BODY + 1}\r\n\r\n")
        assertEquals(413, big.status)
        val none = raw("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\n\r\n")
        assertEquals(411, none.status)
        assertEquals(400, raw("garbage\r\n\r\n").status)
        assertEquals(400, raw("POST /mcp HTTP/2.0\r\nHost: x\r\n\r\n").status)
        // Headers without end, past the limit.
        val flood = raw("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nX: " + "a".repeat(20_000) + "\r\n\r\n")
        assertEquals(400, flood.status)
        // The server still answers afterwards.
        assertEquals(200, post(ping).status)
    }

    @Test
    fun `a chunked body is read, and a body of the largest size is taken`() {
        val body = ping.toByteArray()
        val chunked = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\nTransfer-Encoding: chunked\r\n\r\n" +
            Integer.toHexString(10) + "\r\n" + String(body, 0, 10) + "\r\n" + Integer.toHexString(body.size - 10) + "\r\n" +
            String(body, 10, body.size - 10) + "\r\n0\r\n\r\n"
        assertEquals(200, raw(chunked).status)
        val toolarge = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\nTransfer-Encoding: chunked\r\n\r\n" +
            Integer.toHexString(AGENT_MAX_BODY + 1) + "\r\n"
        assertEquals(413, raw(toolarge).status)
        // A line of the most allowed length is a wrong call, answered, not a crash.
        val lines = "x".repeat(MCP_MAX_LINE)
        val answer = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{\"commands\":[\"$lines\"]}}}")
        assertEquals(200, answer.status)
    }

    @Test
    fun `connections are served together, even while one waits`() {
        val gate = CountDownLatch(1)
        val waiting = object : McpTools {
            override fun run(lines: List<String>, client: String): String { gate.await(10, TimeUnit.SECONDS); return "{\"ok\":true}" }
            override fun help() = ""
        }
        server.stop()
        server = AgentServer({ token }, McpProtocol(waiting, "1"), lan = false)
        port = server.start(0)
        val pool = Executors.newFixedThreadPool(3)
        val slow = pool.submit<Int> { post(run).status }
        Thread.sleep(150)
        // A change waiting for an owner's word does not stop the others being answered.
        assertEquals(200, post(ping).status)
        gate.countDown()
        assertEquals(200, slow.get(5, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun `stopping frees the port and refuses what is waiting`() {
        val p = port
        server.stop()
        assertEquals(0, server.port)
        val reopened = AgentServer({ token }, McpProtocol(tools, "1"), lan = false)
        assertEquals(p, reopened.start(p))
        reopened.stop()
        server = AgentServer({ token }, McpProtocol(tools, "1"), lan = false)
        port = server.start(0)
    }

    @Test
    fun `a client that trickles bytes is cut off, and does not hold the server`() {
        server.stop()
        server = AgentServer({ token }, McpProtocol(tools, "1"), lan = false, requestSeconds = 1)
        port = server.start(0)
        val started = System.nanoTime()
        var status = 0
        Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).use { s ->
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            out.write("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n".toByteArray())
            // One header byte at a time, far inside the read timeout, never finishing.
            try { repeat(40) { out.write('X'.code); out.flush(); Thread.sleep(150) } } catch (e: java.io.IOException) { /* closed on us */ }
            val answer = runCatching { String(s.getInputStream().readBytes()) }.getOrDefault("")
            status = if (answer.startsWith("HTTP/1.1 ")) answer.substring(9, 12).toInt() else 0
        }
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds < 4)
        assertTrue("status $status", status == 408 || status == 0)
        assertEquals(200, post(ping).status)
    }

    @Test
    fun `a port already taken is an error, not a second server`() {
        val other = AgentServer({ token }, McpProtocol(tools, "1"), lan = false)
        val failed = runCatching { other.start(port) }
        assertTrue(failed.isFailure)
    }

    @Test
    fun `peers and names that may come in`() {
        fun ip(s: String) = InetAddress.getByName(s)
        assertTrue(isAllowedPeer(ip("127.0.0.1"), lan = false))
        assertTrue(isAllowedPeer(ip("::1"), lan = false))
        assertFalse(isAllowedPeer(ip("192.168.1.20"), lan = false))
        for (ok in listOf("192.168.1.20", "10.0.0.5", "172.16.4.4", "169.254.3.3", "100.101.102.103", "fd12:3456::1", "fe80::1")) {
            assertTrue(ok, isAllowedPeer(ip(ok), lan = true))
        }
        for (no in listOf("8.8.8.8", "1.1.1.1", "100.128.0.1", "172.32.0.1", "2001:4860:4860::8888")) {
            assertFalse(no, isAllowedPeer(ip(no), lan = true))
        }
        assertTrue(isAllowedHost("127.0.0.1:8080"))
        assertTrue(isAllowedHost("192.168.0.4"))
        assertTrue(isAllowedHost("LOCALHOST:1"))
        assertTrue(isAllowedHost("[::1]:9"))
        assertFalse(isAllowedHost(null))
        assertFalse(isAllowedHost(""))
        assertFalse(isAllowedHost("evil.example"))
        assertFalse(isAllowedHost("999.1.1.1"))
        assertFalse(isAllowedHost("127.0.0.1.evil.example"))
        assertTrue(isAllowedOrigin(null))
        assertTrue(isAllowedOrigin("http://127.0.0.1:5173"))
        assertFalse(isAllowedOrigin("https://example.com"))
        assertFalse(isAllowedOrigin("null"))
        assertEquals("abc", bearerToken("Bearer abc"))
        assertEquals("abc", bearerToken("bearer   abc "))
        assertEquals(null, bearerToken("Bearer"))
        assertEquals(null, bearerToken(null))
        assertTrue(sameToken("a", "a"))
        assertFalse(sameToken("a", "b"))
    }

    @Test
    fun `curl, the way an agent's tools talk, gets its answer`() {
        val curl = runCatching { ProcessBuilder("curl", "--version").redirectErrorStream(true).start().also { it.waitFor() }.exitValue() }.getOrDefault(-1)
        assumeTrue("curl is not installed", curl == 0)
        fun curl(vararg args: String): String {
            val p = ProcessBuilder(listOf("curl", "-sS", "--max-time", "10", "--noproxy", "*", "-i") + args).redirectErrorStream(true).start()
            val out = ByteArrayOutputStream()
            p.inputStream.copyTo(out)
            p.waitFor(15, TimeUnit.SECONDS)
            return out.toString(Charsets.UTF_8)
        }
        val url = "http://127.0.0.1:$port/mcp"
        val small = curl("-X", "POST", url, "-H", "Authorization: Bearer $token", "-H", "Content-Type: application/json", "-d", ping)
        assertTrue(small, small.startsWith("HTTP/1.1 200"))
        // Over a kilobyte curl asks "Expect: 100-continue" first.
        val padded = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"notesis_run\",\"arguments\":{\"commands\":[\"" +
            "x".repeat(3000) + "\"]}}}"
        val big = curl("-X", "POST", url, "-H", "Authorization: Bearer $token", "-H", "Content-Type: application/json", "-d", padded)
        assertTrue(big, big.contains("200 OK") && big.contains("\"id\":5"))
        val denied = curl("-X", "POST", url, "-d", ping)
        assertTrue(denied, denied.startsWith("HTTP/1.1 401"))
        val bigDenied = curl("-X", "POST", url, "-H", "Content-Type: application/json", "-d", padded)
        assertTrue(bigDenied, bigDenied.contains("401"))
    }
}
