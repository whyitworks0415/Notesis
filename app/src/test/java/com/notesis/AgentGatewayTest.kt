package com.notesis

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentGatewayTest {
    private val notes = listOf(
        NoteMeta("n1", "Maths", 0, 3, 0),
        NoteMeta("n2", "Maths homework", 0, 1, 0),
        NoteMeta("n3", "Diary", 0, 1, 0, trashedAt = 1L),
    )

    @Before fun fresh() { ChangeLog.clear() }
    @After fun clean() { ChangeLog.clear(); AgentApprovals.waiting().forEach { AgentApprovals.decide(it, false) } }

    private fun line(text: String) = describeCommand(commandWords(text)!!, notes)

    @Test
    fun `commands are described by the note's title, as the run would find it`() {
        assertEquals("Rename “Maths homework” to “Algebra notes”", line("note rename homework Algebra notes").text())
        assertEquals("Add a page to “Maths”", line("page add Maths").text())
        assertEquals("Add 3 pages to “Maths”", line("page add n1 --after 2 --count 3").text())
        assertEquals("Remove pages 2-3 from “Maths”", line("page rm Maths 2-3").text())
        assertEquals("Move “Maths” to the folder “School/Maths”", line("note mv Maths /School//Maths/").text())
        assertEquals("Move “Maths” to the top level", line("note mv Maths /").text())
        assertEquals("Set the outline entry of page 1 of “Maths”: “Start here”", line("toc set Maths 1 \"Start here\" --level 1").text())
        assertEquals("Erase all the ink on page 2 of “Maths”", line("ink erase Maths 2 --all").text())
        assertEquals("Erase the ink in an area of page 2 of “Maths”", line("ink erase Maths 2 --rect 0,0,5,5").text())
        assertEquals("Undo the last change", line("undo").text())
        // A note not found is shown as typed; running it will say so.
        assertEquals("Move “Nothing” to the trash", line("note trash Nothing").text())
        assertTrue(line("note trash Maths").changes)
    }

    @Test
    fun `reading is not a change, and nor is what is not a command`() {
        for (read in listOf("notes list --folder X", "note read Maths", "toc show Maths", "export Maths --format md", "help")) {
            assertFalse(read, line(read).changes)
        }
        assertTrue(line("export Maths --to shared").changes)
        assertFalse(line("explode everything").changes)
        assertEquals("Not a command: explode everything", line("explode everything").text())
    }

    @Test
    fun `every command is classified, and only reading is not a change`() {
        // If a command is added to the commands and not described, it would run without asking: this fails first.
        // Written out here rather than taken from READ_COMMANDS, so moving a change into the reads fails this test.
        val reads = setOf("help", "status", "preview", "search", "notes list", "note read", "tags list", "folder list",
            "page image", "toc show", "pdf info", "pdf text", "pdf find", "text list", "ink read", "md outline", "md links", "export")
        assertEquals(reads - "export", READ_COMMANDS)
        for (name in COMMAND_USAGE.keys) {
            val line = describeCommand(name.split(' ') + "Maths", notes)
            assertEquals(name, name !in reads, line.changes)
            assertFalse(name, line.template.startsWith("Not a command"))
        }
    }

    @Test
    fun `a value of an option is not taken for the note`() {
        assertEquals("Add text to page 2 of “Maths”: “x = 3”", line("text add --size 40 Maths 2 \"x = 3\" --color blue").text())
        assertEquals("Draw on page 1 of “Maths”", line("ink add Maths 1 --path \"M 0 0 L 5 5\" --width 3").text())
    }

    @Test
    fun `a template is translated before its places are filled`() {
        val shown = line("page add Maths").text { it.replace("Add a page to", "Seite hinzufügen zu") }
        assertEquals("Seite hinzufügen zu “Maths”", shown)
    }

    private fun gateway(
        memory: MemoryNotes, policy: AgentPolicy, answer: Boolean = true,
        asked: MutableList<List<String>> = mutableListOf(), applied: MutableList<ChangeRecord> = mutableListOf(),
    ) = AgentGateway(
        commands = { NoteCommands(memory) }.let { make -> val core = make(); { core } },
        policy = { policy },
        now = { 1_000L },
        ask = { _, lines -> asked += lines.map { it.text() }; answer },
        applied = { applied += it },
    )

    private fun memory() = MemoryNotes().apply { add("n1", "Maths") }

    @Test
    fun `reading is answered without asking, whatever the policy`() {
        val asked = mutableListOf<List<String>>()
        val out = JSONObject(gateway(memory(), AgentPolicy.ASK, asked = asked).run(listOf("notes list", "toc show Maths"), "Claude Code"))
        assertTrue(out.getBoolean("ok"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `asked first, a change is made only when allowed`() {
        val memory = memory()
        val asked = mutableListOf<List<String>>()
        val applied = mutableListOf<ChangeRecord>()
        val allowed = JSONObject(gateway(memory, AgentPolicy.ASK, true, asked, applied).run(listOf("page add Maths", "notes list"), "Codex"))
        assertTrue(allowed.getBoolean("ok"))
        assertEquals(listOf(listOf("Add a page to “Maths”")), asked)
        assertEquals(4, memory.pages.getValue("n1").size)
        assertEquals("Codex", applied.single().client)
        assertEquals(1, ChangeLog.all().size)

        val refused = JSONObject(gateway(memory, AgentPolicy.ASK, false).run(listOf("page add Maths"), "Codex"))
        assertFalse(refused.getBoolean("ok"))
        assertTrue(refused.getBoolean("denied"))
        assertEquals(4, memory.pages.getValue("n1").size)
        assertEquals(1, ChangeLog.all().size)
    }

    @Test
    fun `made at once, a change asks nothing and leaves a card to take it back`() {
        val memory = memory()
        val asked = mutableListOf<List<String>>()
        val applied = mutableListOf<ChangeRecord>()
        val core = NoteCommands(memory)
        val gate = AgentGateway({ core }, { AgentPolicy.AUTO }, { 5L }, { _, l -> asked += l.map { it.text() }; false }, { applied += it })
        val out = JSONObject(gate.run(listOf("page add Maths", "toc set Maths 1 Start"), "OpenCode"))
        assertTrue(out.getBoolean("ok"))
        assertTrue(asked.isEmpty())
        val record = applied.single()
        assertEquals(5L, record.at)
        assertEquals(2, record.labels.size)
        assertEquals(out.getLong("step"), record.step)
        // The card's Undo takes back exactly this change, and only while it is the latest.
        assertTrue(JSONObject(core.undoIfLast(record.step)).getBoolean("ok"))
        assertEquals(3, memory.pages.getValue("n1").size)
    }

    @Test
    fun `an older change cannot be taken back past a newer one`() {
        val memory = memory()
        val core = NoteCommands(memory)
        val first = JSONObject(core.run("page add Maths")).getLong("step")
        val second = JSONObject(core.run("note rename Maths Algebra")).getLong("step")
        val refused = JSONObject(core.undoIfLast(first))
        assertFalse(refused.getBoolean("ok"))
        assertEquals("Algebra", memory.metas.getValue("n1").title)
        assertTrue(JSONObject(core.undoIfLast(second)).getBoolean("ok"))
        assertTrue(JSONObject(core.undoIfLast(first)).getBoolean("ok"))
        assertEquals(3, memory.pages.getValue("n1").size)
    }

    @Test
    fun `a note on screen is still only read, whatever the owner allowed`() {
        val memory = memory().apply { open = "n1" }
        val out = JSONObject(gateway(memory, AgentPolicy.AUTO).run(listOf("page add Maths"), "Agent"))
        assertFalse(out.getBoolean("ok"))
        assertTrue(out.getString("error").contains("open on screen"))
        assertTrue(ChangeLog.all().isEmpty())
    }

    @Test
    fun `the owner's word is waited for, and no answer is a no`() {
        val lines = listOf(ChangeLine("Add a page to “{0}”", listOf("Maths"), true))
        val answered = CountDownLatch(1)
        var result: Boolean? = null
        val thread = Thread { result = AgentApprovals.request("Agent", lines, waitSeconds = 5); answered.countDown() }
        thread.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (AgentApprovals.waiting().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        val pending = AgentApprovals.waiting().single()
        assertEquals("Agent", pending.client)
        AgentApprovals.decide(pending, true)
        assertTrue(answered.await(3, TimeUnit.SECONDS))
        assertEquals(true, result)
        assertTrue(AgentApprovals.waiting().isEmpty())
        // Unanswered, it is refused and no longer waits.
        assertFalse(AgentApprovals.request("Agent", lines, waitSeconds = 0))
        assertTrue(AgentApprovals.waiting().isEmpty())
    }

    @Test
    fun `too many changes waiting are refused outright`() {
        val lines = listOf(ChangeLine("Undo the last change", emptyList(), true))
        val threads = (1..APPROVAL_QUEUE).map { Thread { AgentApprovals.request("A", lines, waitSeconds = 10) }.also { it.start() } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (AgentApprovals.waiting().size < APPROVAL_QUEUE && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(APPROVAL_QUEUE, AgentApprovals.waiting().size)
        assertFalse(AgentApprovals.request("A", lines, waitSeconds = 10))
        AgentApprovals.waiting().forEach { AgentApprovals.decide(it, false) }
        threads.forEach { it.join(3_000) }
    }

    @Test
    fun `the log keeps the newest first, a bounded number`() {
        repeat(35) { ChangeLog.add("A", it.toLong(), listOf("x$it"), it.toLong()) }
        val all = ChangeLog.all()
        assertEquals(30, all.size)
        assertEquals(listOf("x34"), all.first().labels)
        ChangeLog.markUndone(all.first().id)
        assertTrue(ChangeLog.all().first().undone)
        assertNull(AgentPolicy.entries.firstOrNull { it.saved == "nothing" })
        assertEquals(AgentPolicy.ASK, AgentPolicy.of(null))
        assertEquals(AgentPolicy.AUTO, AgentPolicy.of("auto"))
    }
}
