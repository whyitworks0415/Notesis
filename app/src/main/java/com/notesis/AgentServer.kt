package com.notesis

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/*
 * The small HTTP server an agent connects to (Todo.md, C3): POST /mcp with
 * a bearer token, answered by McpProtocol. It listens on this device only
 * (127.0.0.1) unless the owner turns on the same network, and then only
 * private addresses may come in. Every request is checked, in this order,
 * before anything is read or run: who is asking (address), what name they
 * asked for (Host), whether a web page is asking (Origin), and the token.
 *
 * Plain HTTP with no streams and no keep-alive: one request, one answer,
 * the connection closed. Nothing is written to a log.
 */

/** Whether a connection from [peer] may be served: this device always, and with [lan] the private networks as well. */
internal fun isAllowedPeer(peer: InetAddress, lan: Boolean): Boolean {
    if (peer.isLoopbackAddress) return true
    if (!lan) return false
    if (peer.isSiteLocalAddress || peer.isLinkLocalAddress) return true
    val b = peer.address
    // 100.64.0.0/10 (carrier-grade NAT, where Tailscale lives) and fc00::/7 (IPv6 private).
    if (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40) return true
    if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return true
    return false
}

private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

/**
 * Whether the name a request was addressed to is this device's own or an
 * address: localhost or an IP number, never another name - a web page can
 * point its own name at this device and then ask for it (DNS rebinding).
 */
internal fun isAllowedHost(header: String?): Boolean {
    val host = header?.trim().orEmpty()
    if (host.isEmpty() || host.any { it.isISOControl() || it == ' ' }) return false
    val name = if (host.startsWith("[")) {
        val close = host.indexOf(']')
        if (close < 0) return false
        host.substring(1, close)
    } else host.substringBefore(':')
    if (name.equals("localhost", ignoreCase = true)) return true
    if (host.startsWith("[")) return name.isNotEmpty() && name.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    return IPV4.matches(name) && name.split('.').all { it.toInt() in 0..255 }
}

/** A browser sends Origin; a program does not. One from a page served on this device is let through, any other is not. */
internal fun isAllowedOrigin(origin: String?): Boolean {
    if (origin == null) return true
    val host = Regex("^https?://([^/]+)$", RegexOption.IGNORE_CASE).find(origin.trim())?.groupValues?.get(1) ?: return false
    val name = if (host.startsWith("[")) host.substringBefore(']').drop(1) else host.substringBefore(':')
    return name.equals("localhost", ignoreCase = true) || name == "127.0.0.1" || name == "::1"
}

/** The token from an `Authorization: Bearer …` header, or null. */
internal fun bearerToken(header: String?): String? {
    val h = header?.trim() ?: return null
    if (h.length < 8 || !h.substring(0, 7).equals("Bearer ", ignoreCase = true)) return null
    return h.substring(7).trim().ifEmpty { null }
}

/** Equal without the time it takes to tell on where they first differ. */
internal fun sameToken(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

/** The most a request may carry. */
internal const val AGENT_MAX_BODY = 1 shl 20
private const val MAX_HEADER_BYTES = 16 * 1024
private const val CONNECTION_THREADS = 8
private const val READ_TIMEOUT_MS = 15_000
private const val REQUEST_SECONDS = 30L

internal class AgentServer(
    private val token: () -> String,
    private val protocol: McpProtocol,
    /** Whether private networks may connect as well as this device; decided when it starts. */
    private val lan: Boolean,
    /** How long a client has to send a whole request: a slow trickle of bytes cannot hold a connection open. */
    private val requestSeconds: Long = REQUEST_SECONDS,
) {
    private var socket: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null

    /** The port it listens on, or 0 while stopped. */
    @Volatile var port: Int = 0
        private set

    /** Starts listening on [wanted] (0 for any free port). Throws when it cannot. */
    @Synchronized
    fun start(wanted: Int): Int {
        check(socket == null) { "Already running" }
        val bind = if (lan) null else InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(if (bind == null) InetSocketAddress(wanted) else InetSocketAddress(bind, wanted), 16)
        } catch (e: IOException) {
            runCatching { server.close() }
            throw e
        }
        val workers = ThreadPoolExecutor(CONNECTION_THREADS, CONNECTION_THREADS, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "notesis-agent").apply { isDaemon = true } },
            // All busy: turn the caller away rather than queue it behind a change that is waiting for an answer.
            ThreadPoolExecutor.AbortPolicy())
        socket = server
        pool = workers
        port = server.localPort
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: IOException) { break }
                try {
                    workers.execute { serve(client) }
                } catch (e: RejectedExecutionException) {
                    runCatching { respond(client, 503, "Busy", body = null) }
                    runCatching { client.close() }
                }
            }
        }, "notesis-agent-accept").apply { isDaemon = true }.start()
        return port
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        runCatching { pool?.shutdownNow() }
        socket = null
        pool = null
        port = 0
        // Changes waiting for a word will not be answered through this server any more.
        AgentApprovals.waiting().forEach { AgentApprovals.decide(it, false) }
    }

    private fun serve(client: Socket) {
        client.use {
            try {
                client.soTimeout = READ_TIMEOUT_MS
                client.tcpNoDelay = true
                handle(client, System.nanoTime() + TimeUnit.SECONDS.toNanos(requestSeconds))
                drain(client)
            } catch (e: SocketTimeoutException) {
                runCatching { respond(client, 408, "Request Timeout", null) }
            } catch (e: IOException) {
                // The other end went away.
            } catch (e: Exception) {
                runCatching { respond(client, 500, "Internal Server Error", null) }
            }
        }
    }

    /**
     * Reads what the client may still be sending, briefly, before the connection is closed:
     * closing with unread bytes resets it, and the client could lose the answer just given.
     */
    private fun drain(client: Socket) {
        runCatching {
            client.shutdownOutput()
            client.soTimeout = 200
            val sink = ByteArray(4096)
            var left = 64 * 1024
            while (left > 0) { val n = client.getInputStream().read(sink); if (n < 0) break; left -= n }
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val input: InputStream)

    private fun handle(client: Socket, deadline: Long) {
        // Who is asking, before a byte of what they say is read.
        if (!isAllowedPeer(client.inetAddress, lan)) { respond(client, 403, "Forbidden", null); return }
        val input = BufferedInputStream(client.getInputStream())
        val request = readHead(input, deadline) ?: run { respond(client, 400, "Bad Request", null); return }
        val h = request.headers
        if (!isAllowedHost(h["host"])) { respond(client, 403, "Forbidden", null); return }
        if (!isAllowedOrigin(h["origin"])) { respond(client, 403, "Forbidden", null); return }
        val given = bearerToken(h["authorization"])
        val expected = token()
        if (given == null || expected.isEmpty() || !sameToken(given, expected)) {
            respond(client, 401, "Unauthorized", null, "WWW-Authenticate" to "Bearer")
            return
        }
        if (request.path != "/mcp" && request.path != "/mcp/") { respond(client, 404, "Not Found", null); return }
        if (request.method != "POST") { respond(client, 405, "Method Not Allowed", null, "Allow" to "POST"); return }
        val body = readBody(client, request, deadline) ?: return
        val reply = protocol.handle(String(body, Charsets.UTF_8), h["mcp-session-id"])
        if (reply.body == null) { respond(client, reply.status, "Accepted", null); return }
        respond(client, reply.status, if (reply.status == 200) "OK" else "Bad Request", reply.body,
            *listOfNotNull(reply.session?.let { "Mcp-Session-Id" to it }).toTypedArray())
    }

    /** The request line and headers, or null when they are not HTTP or are too large. Header names are lower case. */
    private fun readHead(input: InputStream, deadline: Long): Request? {
        var total = 0
        fun line(): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                if (System.nanoTime() > deadline) throw SocketTimeoutException("Too slow")
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1)
                if (++total > MAX_HEADER_BYTES) return null
                if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1).trimEnd('\r')
                out.write(b)
            }
        }
        val first = line() ?: return null
        val parts = first.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val headers = HashMap<String, String>()
        while (true) {
            val l = line() ?: return null
            if (l.isEmpty()) break
            val colon = l.indexOf(':')
            if (colon <= 0 || headers.size >= 64) return null
            headers[l.substring(0, colon).trim().lowercase()] = l.substring(colon + 1).trim()
        }
        return Request(parts[0], parts[1].substringBefore('?'), headers, input)
    }

    /** The body, or null once an error has been answered. */
    private fun readBody(client: Socket, request: Request, deadline: Long): ByteArray? {
        val h = request.headers
        val chunked = h["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val length = h["content-length"]?.toLongOrNull()
        if (!chunked && length == null) { respond(client, 411, "Length Required", null); return null }
        if (!chunked && (length!! < 0 || length > AGENT_MAX_BODY)) { respond(client, 413, "Payload Too Large", null); return null }
        // curl and others ask first before sending a large body.
        if (h["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            client.getOutputStream().apply { write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); flush() }
        }
        val input = request.input
        if (!chunked) {
            val out = ByteArray(length!!.toInt())
            var read = 0
            while (read < out.size) {
                if (System.nanoTime() > deadline) throw SocketTimeoutException("Too slow")
                val n = input.read(out, read, out.size - read)
                if (n < 0) throw IOException("The body ended early")
                read += n
            }
            return out
        }
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = StringBuilder()
            while (true) {
                if (System.nanoTime() > deadline) throw SocketTimeoutException("Too slow")
                val b = input.read()
                if (b < 0) throw IOException("The body ended early")
                if (b == '\n'.code) break
                if (sizeLine.length > 32) throw IOException("Bad chunk")
                sizeLine.append(b.toChar())
            }
            val size = sizeLine.toString().trim().substringBefore(';').toIntOrNull(16) ?: throw IOException("Bad chunk")
            if (size < 0 || out.size() + size > AGENT_MAX_BODY) { respond(client, 413, "Payload Too Large", null); return null }
            if (size == 0) {
                // Trailers, up to the empty line.
                while (true) {
                    var any = false
                    while (true) { val b = input.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) any = true }
                    if (!any) break
                }
                return out.toByteArray()
            }
            val chunk = ByteArray(size)
            var read = 0
            while (read < size) {
                if (System.nanoTime() > deadline) throw SocketTimeoutException("Too slow")
                val n = input.read(chunk, read, size - read)
                if (n < 0) throw IOException("The body ended early")
                read += n
            }
            out.write(chunk)
            input.read(); input.read() // the CRLF after the chunk
        }
    }

    private fun respond(client: Socket, status: Int, reason: String, body: String?, vararg extra: Pair<String, String>) {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 $status $reason\r\n")
        if (bytes != null) head.append("Content-Type: application/json; charset=utf-8\r\n")
        head.append("Content-Length: ${bytes?.size ?: 0}\r\n")
        head.append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n")
        for ((k, v) in extra) head.append(k).append(": ").append(v.filter { !it.isISOControl() }).append("\r\n")
        head.append("\r\n")
        val out = client.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (bytes != null) out.write(bytes)
        out.flush()
    }
}
