package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {
    private fun kinds(text: String) = markdownSpans(text).map { it.kind to text.substring(it.start, it.end) }

    @Test fun `a heading is one span with its marks apart`() {
        val spans = kinds("## Title **bold**")
        assertTrue(MdKind.HEADING to "## Title **bold**" in spans)
        assertTrue(MdKind.MARKER to "## " in spans)
        assertTrue(MdKind.BOLD to "bold" in spans)
    }

    @Test fun `emphasis, code, strike, highlight and tags`() {
        val spans = kinds("a *it* and `co*de*` ~~gone~~ ==mark== #tag/sub")
        assertTrue(MdKind.ITALIC to "it" in spans)
        assertTrue(MdKind.CODE to "co*de*" in spans)
        // Nothing is read inside code.
        assertTrue(spans.none { it.first == MdKind.ITALIC && it.second == "de" })
        assertTrue(MdKind.STRIKE to "gone" in spans)
        assertTrue(MdKind.HIGHLIGHT to "mark" in spans)
        assertTrue(MdKind.TAG to "#tag/sub" in spans)
    }

    @Test fun `a heading mark is no tag, a number is no tag`() {
        assertTrue(tagsIn("# Title\n#2 and #ok").let { it == setOf("ok") })
    }

    @Test fun `wiki links show their name or alias, the rest is marks`() {
        val text = "see [[Calculus#Limits|limits]] and [[Plain]]"
        val spans = markdownSpans(text)
        val links = spans.filter { it.kind == MdKind.WIKILINK }
        assertEquals(listOf("limits", "Plain"), links.map { text.substring(it.start, it.end) })
        assertEquals(listOf("Calculus", "Plain"), links.map { it.target })
        assertEquals(setOf("calculus", "plain"), wikiTargets(text))
    }

    @Test fun `links keep their words and hide their address`() {
        val text = "[**site**](https://example.com)"
        val spans = kinds(text)
        assertTrue(MdKind.LINK to "**site**" in spans)
        assertTrue(MdKind.BOLD to "site" in spans)
        assertTrue(MdKind.MARKER to "](https://example.com)" in spans)
    }

    @Test fun `tasks, done tasks and bullets`() {
        val text = "- [ ] open\n- [x] done\n* bullet\n12. twelve"
        val spans = kinds(text)
        assertTrue(MdKind.TASK_OPEN to "[ ] " in spans)
        assertTrue(MdKind.TASK_DONE to "[x] " in spans)
        assertTrue(MdKind.DONE_TEXT to "done" in spans)
        assertTrue(MdKind.BULLET to "* " in spans)
        assertTrue(MdKind.BULLET to "12. " in spans)
    }

    @Test fun `code fences hold their lines as code`() {
        val text = "```kotlin\nval x = **1**\n```\nafter *it*"
        val spans = kinds(text)
        assertTrue(MdKind.CODE_BLOCK to "val x = **1**" in spans)
        assertTrue(spans.none { it.first == MdKind.BOLD })
        assertTrue(MdKind.ITALIC to "it" in spans)
    }

    @Test fun `front matter is kept apart`() {
        val text = "---\ntags: a\n---\n# Title"
        val spans = kinds(text)
        assertTrue(MdKind.FRONT_MATTER to "---\ntags: a\n---" in spans)
        assertTrue(spans.any { it.first == MdKind.HEADING })
    }

    @Test fun `blocks to read`() {
        val text = "# Title\n\nOne\ntwo\n\n- [ ] task\n  - inner\n1. first\n> quote\n> more\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n---\n```py\nprint()\n```"
        val blocks = markdownBlocks(text)
        assertEquals(MdBlock.Heading(1, "Title", 0), blocks[0])
        assertEquals("One\ntwo", (blocks[1] as MdBlock.Paragraph).text)
        val task = blocks[2] as MdBlock.Item
        assertEquals(false, task.task)
        assertEquals('[', text[task.taskAt])
        assertEquals(1, (blocks[3] as MdBlock.Item).depth)
        assertEquals("1.", (blocks[4] as MdBlock.Item).marker)
        assertEquals("quote\nmore", (blocks[5] as MdBlock.Quote).text)
        val table = blocks[6] as MdBlock.Table
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
        assertTrue(blocks[7] is MdBlock.Rule)
        assertEquals(MdBlock.Code("py", "print()", text.indexOf("```py")), blocks[8])
    }

    @Test fun `outline lists the headings`() {
        assertEquals(listOf("A", "B"), outlineOf("# A\ntext\n## B").map { it.title })
    }

    @Test fun `wrapping toggles on and off`() {
        val on = toggleWrap("make bold here", 5, 9, "**")
        assertEquals("make **bold** here", on.text)
        assertEquals(7 to 11, on.start to on.end)
        val off = toggleWrap(on.text, on.start, on.end, "**")
        assertEquals("make bold here", off.text)
        // With nothing selected the marks go either side of the cursor, and come off again.
        assertEquals(MdEdit("ab****", 4, 4), toggleWrap("ab", 2, 2, "**"))
        assertEquals(MdEdit("ab", 2, 2), toggleWrap("ab****", 4, 4, "**"))
    }

    @Test fun `line styles step and toggle`() {
        val h1 = toggleLineStyle("title", 0, 0, LineStyle.HEADING)
        assertEquals("# title", h1.text)
        assertEquals("## title", toggleLineStyle(h1.text, 3, 3, LineStyle.HEADING).text)
        assertEquals("title", toggleLineStyle("### title", 0, 0, LineStyle.HEADING).text)
        assertEquals("- [ ] a\n- [ ] b", toggleLineStyle("a\nb", 0, 3, LineStyle.TASK).text)
        assertEquals("- [ ] a", toggleLineStyle("- a", 0, 0, LineStyle.TASK).text)
        assertEquals("a", toggleLineStyle("> a", 0, 0, LineStyle.QUOTE).text)
        assertEquals("1. a\n2. b", toggleLineStyle("a\nb", 0, 3, LineStyle.NUMBERED).text)
    }

    @Test fun `lists carry on after a break and end on an empty item`() {
        assertEquals(MdEdit("- a\n- ", 6), continueAfterBreak("- a\n", 4))
        assertEquals(MdEdit("  - [x] a\n  - [ ] ", 18), continueAfterBreak("  - [x] a\n", 10))
        assertEquals(MdEdit("9. a\n10. ", 9), continueAfterBreak("9. a\n", 5))
        assertEquals(MdEdit("> a\n> ", 6), continueAfterBreak("> a\n", 4))
        assertEquals(MdEdit("- a\n", 4), continueAfterBreak("- a\n- \n", 7))
        assertNull(continueAfterBreak("plain\n", 6))
        assertNull(continueAfterBreak("# head\n", 7))
    }

    @Test fun `tasks tick and untick`() {
        val text = "- [ ] a"
        assertEquals("- [x] a", toggleTask(text, 2))
        assertEquals("- [ ] a", toggleTask("- [x] a", 2))
        assertEquals(text, toggleTask(text, 0))
    }

    @Test fun `a link being typed is offered names and finished`() {
        val text = "see [[Cal"
        val (start, typed) = wikiQueryAt(text, text.length)!!
        assertEquals("Cal", typed)
        assertEquals(MdEdit("see [[Calculus]]", 16), completeWiki(text, start, text.length, "Calculus"))
        assertNull(wikiQueryAt("see [[done]] ", 13))
        assertEquals(listOf("Calculus", "Precalc"), suggestNames(listOf("Precalc", "Calculus", "Other"), "cal"))
    }

    @Test fun `words are counted in any script`() {
        assertEquals(4, wordCount("치환 적분 is fun"))
    }
}
