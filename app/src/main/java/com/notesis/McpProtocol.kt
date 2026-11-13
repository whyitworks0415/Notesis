package com.notesis

import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/*
 * The Model Context Protocol, the part an agent talks (Todo.md, C3): JSON-RPC
 * messages in, JSON-RPC answers out, over the plain request/response form of
 * its HTTP transport - no streams. It offers two tools: the commands the
 * terminal runs (NoteCommands.kt), and their help. Which changes are allowed
 * is not decided here (AgentGateway.kt); this only speaks the protocol.
 *
 * Kept free of sockets and Android so every message can be tried as text.
 */

/** What the protocol calls on: running command lines, and the help. */
internal interface McpTools {
    /** [lines] as one batch for [client]; the commands' JSON answer. Blocks while an owner is asked. */
    fun run(lines: List<String>, client: String): String
    fun help(): String
}

/** An answer: the HTTP status, the JSON text (null when there is nothing to say back), the session the client is to use from now on. */
internal class McpReply(val status: Int, val body: String?, val session: String? = null)

internal const val MCP_LATEST = "2025-06-18"
internal val MCP_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

/** The most command lines in one call, and the longest one. */
internal const val MCP_MAX_LINES = 50
internal const val MCP_MAX_LINE = 8_000

internal class McpProtocol(
    private val tools: McpTools,
    private val appVersion: String,
) {
    private val random = SecureRandom()

    /** The name each session's client gave itself, newest kept; a name is what the client says, not proof of who it is. */
    private val sessions = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 32
    }

    /** One POST's body; [session] is the id the client sent back, if it did. */
    fun handle(body: String, session: String?): McpReply {
        val parsed = try { JSONTokener(body).nextValue() } catch (e: Exception) { null }
        return when (parsed) {
            is JSONObject -> {
                val out = single(parsed, clientOf(session))
                if (out == null) McpReply(202, null)
                else McpReply(
                    status = if (out.json.optJSONObject("error")?.optInt("code") in setOf(-32700, -32600)) 400 else 200,
                    body = out.json.toString(),
                    session = out.session,
                )
            }
            is JSONArray -> {
                if (parsed.length() == 0 || parsed.length() > 20) return error(JSONObject.NULL, -32600, "A batch holds 1 to 20 messages.")
                val client = clientOf(session)
                val out = JSONArray()
                for (i in 0 until parsed.length()) {
                    val item = parsed.opt(i) as? JSONObject
                    val answer = if (item == null) Single(errorObject(JSONObject.NULL, -32600, "Not a message.")) else single(item, client)
                    if (answer != null) out.put(answer.json)
                }
                if (out.length() == 0) McpReply(202, null) else McpReply(200, out.toString())
            }
            else -> error(JSONObject.NULL, -32700, "Parse error: the body is not JSON.")
        }
    }

    /** One message's answer, and the session its client is to use from now on, if it opened one. */
    private class Single(val json: JSONObject, val session: String? = null)

    private fun clientOf(session: String?): String = synchronized(sessions) { session?.let { sessions[it] } } ?: "Agent"

    private fun error(id: Any, code: Int, message: String): McpReply =
        McpReply(if (code == -32700 || code == -32600) 400 else 200, errorObject(id, code, message).toString())

    private fun errorObject(id: Any, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message))

    private fun result(id: Any, result: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    /** One message: its answer, or null for a notification or a reply (which are not answered). */
    private fun single(message: JSONObject, client: String): Single? {
        fun bad(message: String) = Single(errorObject(JSONObject.NULL, -32600, message))
        if (message.optString("jsonrpc") != "2.0") return bad("Not a JSON-RPC 2.0 message.")
        val method = message.optString("method", "")
        // A reply from the client to something sent it: nothing is sent that wants one.
        if (method.isEmpty()) return if (message.has("result") || message.has("error")) null else bad("No method.")
        if (!message.has("id")) return null
        val id = message.get("id")
        if (id !is Int && id !is Long && id !is String) return bad("The id is a number or a string.")
        val params = message.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> initialize(id, params)
            "ping" -> Single(result(id, JSONObject()))
            "tools/list" -> Single(result(id, JSONObject().put("tools", toolList())))
            "tools/call" -> Single(call(id, params, client))
            else -> Single(errorObject(id, -32601, "Method not found: $method"))
        }
    }

    private fun initialize(id: Any, params: JSONObject): Single {
        val asked = params.optString("protocolVersion", "")
        val version = if (asked in MCP_VERSIONS) asked else MCP_LATEST
        val name = params.optJSONObject("clientInfo")?.optString("name").orEmpty().filter { !it.isISOControl() }.take(60).trim()
        val session = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        synchronized(sessions) { sessions[session] = name.ifEmpty { "Agent" } }
        return Single(result(id, JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "notesis").put("title", "Notesis").put("version", appVersion))
            .put("instructions", INSTRUCTIONS)), session)
    }

    private fun toolList(): JSONArray = JSONArray()
        .put(JSONObject()
            .put("name", "notesis_help")
            .put("title", "Notesis commands")
            .put("description", "Lists the Notesis note commands and how each is written. Read this first.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false))
            .put("annotations", JSONObject().put("readOnlyHint", true)))
        .put(JSONObject()
            .put("name", "notesis_run")
            .put("title", "Run Notesis commands")
            .put("description", "Runs note commands on the notes in this app: list and read notes, add, remove and move pages, " +
                "set a note's outline, add text and ink, rename, tag and file notes, move them to the trash, and undo. " +
                "Several lines run as one batch: if one fails the ones before it are taken back. " +
                "A note that is open on the screen can only be read. The owner may be asked to allow a change first.")
            .put("inputSchema", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("commands", JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string"))
                    .put("minItems", 1).put("maxItems", MCP_MAX_LINES)
                    .put("description", "Command lines, such as \"notes list\" or \"page add Maths --after 2\".")))
                .put("required", JSONArray().put("commands"))
                .put("additionalProperties", false))
            .put("annotations", JSONObject().put("readOnlyHint", false).put("destructiveHint", false)))

    private fun call(id: Any, params: JSONObject, client: String): JSONObject {
        val name = params.optString("name", "")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return when (name) {
            "notesis_help" -> result(id, text(tools.help(), isError = false))
            "notesis_run" -> {
                val lines = linesOf(args) ?: return errorObject(id, -32602,
                    "commands is a list of 1 to $MCP_MAX_LINES strings of up to $MCP_MAX_LINE characters.")
                val answer = tools.run(lines, client)
                val ok = runCatching { JSONObject(answer).optBoolean("ok") }.getOrDefault(false)
                result(id, text(answer, isError = !ok))
            }
            else -> errorObject(id, -32602, "Unknown tool: ${name.take(60)}")
        }
    }

    /** The lines asked for, or null when they are not 1 to [MCP_MAX_LINES] strings. A lone "command" string is taken too. */
    private fun linesOf(args: JSONObject): List<String>? {
        val array = args.optJSONArray("commands")
        val lines = when {
            array != null -> (0 until array.length()).map { array.opt(it) as? String ?: return null }
            args.opt("command") is String -> listOf(args.getString("command"))
            else -> return null
        }
        if (lines.isEmpty() || lines.size > MCP_MAX_LINES || lines.any { it.length > MCP_MAX_LINE }) return null
        return lines
    }

    private fun text(text: String, isError: Boolean): JSONObject =
        JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", isError)

    private companion object {
        const val INSTRUCTIONS = "Notesis is a handwriting and Markdown note app. Call notesis_help for the commands, then notesis_run. " +
            "A note open on the screen is only read, never changed; deleting only moves a note to the trash; every change can be undone " +
            "with the \"undo\" command. The owner may be asked before a change, so a refusal is normal: do not retry it."
    }
}
