package com.notesis

import org.json.JSONObject

/*
 * Where an agent's command lines meet the owner's rules (Todo.md, C2 and
 * C3). Reading is always answered. A change is asked about first, or made
 * at once and kept as a card with an Undo, as the owner chose; a refusal,
 * a wait that ran out or a full queue is a "no" the agent is told in words.
 * The commands themselves, and their safety rules (a note open on screen is
 * only read, deleting is to the trash, everything can be undone), are
 * NoteCommands; this only decides whether to run them.
 */
internal class AgentGateway(
    private val commands: () -> NoteCommands,
    private val policy: () -> AgentPolicy,
    private val now: () -> Long = System::currentTimeMillis,
    /** Asks the owner; true when allowed. Blocks the connection's thread until the word comes. */
    private val ask: (client: String, lines: List<ChangeLine>) -> Boolean = { client, lines -> AgentApprovals.request(client, lines) },
    /** Hears of a change that was made, for the card that takes it back. */
    private val applied: (ChangeRecord) -> Unit = {},
) {
    /** [lines] as one batch from [client]; the answer as the commands give it, JSON. */
    fun run(lines: List<String>, client: String): String {
        val core = commands()
        val preview = core.preview(lines)
        val changes = preview.filter { it.changes }
        if (changes.isNotEmpty() && policy() == AgentPolicy.ASK) {
            if (!ask(client, changes)) {
                return JSONObject().put("ok", false).put("denied", true)
                    .put("error", "The owner did not allow this change, or did not answer in time. Nothing was changed.").toString()
            }
        }
        val answer = core.runAll(lines)
        runCatching {
            val json = JSONObject(answer)
            if (json.optBoolean("ok") && json.has("step")) {
                val labels = json.optJSONArray("changes")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                applied(ChangeLog.add(client, now(), labels, json.getLong("step")))
            }
        }
        return answer
    }
}
