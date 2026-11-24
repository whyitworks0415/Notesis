package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiveTest {

    /** The text shown, with each span written around what it covers, so a case reads at a glance. */
    private fun render(source: String, definitions: Map<String, String> = emptyMap()): String {
        val text = inlineText(source, definitions = definitions)
        val opens = HashMap<Int, MutableList<String>>()
        val closes = HashMap<Int, MutableList<String>>()
        for (s in text.spans) {
            val name = s.kind.name.lowercase() + (s.target?.let { "=$it" } ?: "")
            opens.getOrPut(s.start) { mutableListOf() } += "<$name>"
            closes.getOrPut(s.end) { mutableListOf() }.add(0, "</${s.kind.name.lowercase()}>")
        }
        return buildString {
            for (i in 0..text.shown.length) {
                closes[i]?.forEach { append(it) }
                opens[i]?.forEach { append(it) }
                if (i < text.shown.length) append(text.shown[i])
            }
        }
    }

    @Test
    fun `emphasis follows CommonMark`() {
        assertEquals("<bold>bold</bold> and <italic>it</italic>", render("**bold** and *it*"))
        assertEquals("<bold><italic>both</italic></bold>", render("***both***").replace("<italic><bold>", "<bold><italic>").replace("</bold></italic>", "</italic></bold>"))
        assertEquals("<bold>a <italic>b</italic> c</bold>", render("**a *b* c**"))
        assertEquals("<italic>a <bold>b</bold> c</italic>", render("*a **b** c*"))
        // An underscore inside a word is a letter, a star is not.
        assertEquals("snake_case_name", render("snake_case_name"))
        assertEquals("a<italic>b</italic>c", render("a*b*c"))
        assertEquals("<italic>under</italic>", render("_under_"))
        // A star with spaces either side is a star.
        assertEquals("2 * 3 * 4", render("2 * 3 * 4"))
        assertEquals("*not closed", render("*not closed"))
        assertEquals("<strike>gone</strike> <highlight>marked</highlight>", render("~~gone~~ ==marked=="))
        assertEquals("~one~", render("~one~"))
    }

    @Test
    fun `escapes and code keep marks as they are`() {
        assertEquals("*not italic*", render("\\*not italic\\*"))
        assertEquals("<code>a *b* c</code>", render("`a *b* c`"))
        assertEquals("<code>has `tick`</code>", render("`` has `tick` ``"))
        assertEquals("`open", render("`open"))
    }

    @Test
    fun `links of every kind`() {
        assertEquals("<link=https://x.org>site</link>", render("[site](https://x.org)"))
        assertEquals("<link=https://x.org><bold>bold</bold> site</link>", render("[**bold** site](https://x.org \"Title\")"))
        assertEquals("<link=https://ref.org>text</link>", render("[text][r]", mapOf("r" to "https://ref.org")))
        assertEquals("<link=https://ref.org>r</link>", render("[r]", mapOf("r" to "https://ref.org")))
        assertEquals("[no def]", render("[no def]"))
        assertEquals("see <link=https://a.com/x>https://a.com/x</link>.", render("see https://a.com/x."))
        assertEquals("(<link=https://a.com>https://a.com</link>)", render("(https://a.com)"))
        assertEquals("<link=https://a.com>https://a.com</link>", render("<https://a.com>"))
        assertEquals("<link=mailto:me@a.com>me@a.com</link>", render("<me@a.com>"))
        assertEquals("<wikilink=Note>Note</wikilink>", render("[[Note]]"))
        assertEquals("<wikilink=Note>shown</wikilink>", render("[[Note|shown]]"))
        assertEquals("<wikilink=Note#Part>Note › Part</wikilink>", render("[[Note#Part]]"))
        assertEquals("<embed=pic.png|300>pic.png</embed>", render("![[pic.png|300]]"))
        assertEquals("<image=a.png>alt</image>", render("![alt](a.png)"))
    }

    @Test
    fun `obsidian marks, math, footnotes, tags and html`() {
        assertEquals("<math>x^2</math> costs $5 and $6", render("\$x^2\$ costs \$5 and \$6"))
        assertEquals("<math=display>a+b</math>", render("\$\$a+b\$\$"))
        assertEquals("a<footnote_ref=1>1</footnote_ref>", render("a[^1]"))
        assertEquals("<tag=work>#work</tag> a#b", render("#work a#b"))
        inlineText("seen %%hidden%%shown").let { text ->
            assertEquals("seen shown", text.shown)
            assertEquals("hidden", text.spans.single { it.kind == InlineKind.COMMENT }.target)
        }
        assertEquals("<underline>u</underline> H<sub>2</sub>O x<sup>2</sup> <kbd>Ctrl</kbd>", render("<u>u</u> H<sub>2</sub>O x<sup>2</sup> <kbd>Ctrl</kbd>"))
        assertEquals("a\nb", render("a<br>b"))
        assertEquals("<span>x</span>", render("<span>x</span>"))
    }

    @Test
    fun `a tap on shown text lands on the right source character`() {
        val text = inlineText("**ab** [[N|cd]]", base = 10)
        assertEquals("ab cd", text.shown)
        assertEquals(12, text.sourceAt(0))
        assertEquals(13, text.sourceAt(1))
        assertEquals(text.shown.length, text.sourceOf.size - 1)
        assertEquals(10 + "**ab** [[N|".length, text.sourceAt(3))
    }

    @Test
    fun `blocks cover the note and know their kinds`() {
        val note = """
            ---
            tags: [a]
            ---
            # Title
            Para line
            - item
              - nested
            1. one
            > [!tip] Hi
            > body
            ```kotlin
            val x = 1

            ```
            | a | b |
            | :- | -: |
            | 1 | 2 |
            ${'$'}${'$'}
            x
            ${'$'}${'$'}
            ***
            Setext
            ===
            [^1]: foot
            [r]: https://r.org
            %% hidden
            still %%

                indented code
        """.trimIndent()
        val blocks = liveBlocks(note)
        val kinds = blocks.map { it.kind }
        assertEquals(listOf(LiveKind.FRONT_MATTER, LiveKind.HEADING, LiveKind.PARAGRAPH, LiveKind.LIST_ITEM, LiveKind.LIST_ITEM,
            LiveKind.LIST_ITEM, LiveKind.QUOTE, LiveKind.CODE, LiveKind.TABLE, LiveKind.MATH, LiveKind.RULE, LiveKind.HEADING,
            LiveKind.FOOTNOTE, LiveKind.LINK_DEF, LiveKind.COMMENT, LiveKind.BLANK, LiveKind.CODE), kinds)
        assertEquals(listOf(0, 1, 0), blocks.filter { it.kind == LiveKind.LIST_ITEM }.map { it.level })
        assertEquals("kotlin", blocks[7].info)
        assertEquals(1, blocks[11].level)
        assertEquals(INDENTED, blocks.last().info)
        // Every character is in a block or is the break between two.
        for ((a, b) in blocks.zipWithNext()) assertEquals(a.end + 1, b.start)
        assertEquals(0, blocks.first().start)
        assertEquals(note.length, blocks.last().end)
        assertEquals(7, blockAt(blocks, blocks[7].start + 3))
    }

    @Test
    fun `a dash line under text is a heading, alone it is a rule`() {
        assertEquals(listOf(LiveKind.HEADING), liveBlocks("Title\n---").map { it.kind })
        assertEquals(listOf(LiveKind.BLANK, LiveKind.RULE), liveBlocks("\n---").map { it.kind })
        assertEquals(listOf(LiveKind.RULE), liveBlocks("- - -").map { it.kind })
    }

    @Test
    fun `tables are read, edited and written back aligned`() {
        val table = parseTable("| Name | 점수 |\n| :--- | ---: |\n| Kim | 9 |\n| a\\|b | `x|y` |")!!
        assertEquals(listOf("Name", "점수"), table.header)
        assertEquals(listOf(TableAlign.LEFT, TableAlign.RIGHT), table.align)
        assertEquals(listOf("a|b", "`x|y`"), table.rows[1])
        val edited = table.withColumn(2).withCell(-1, 2, "Note").withRow(0).withCell(0, 0, "Lee").withAlign(2, TableAlign.CENTER)
        val text = formatTable(edited)
        val again = parseTable(text)!!
        assertEquals(listOf("Name", "점수", "Note"), again.header)
        assertEquals("Lee", again.rows[0][0])
        assertEquals("a|b", again.rows[2][0])
        assertEquals(TableAlign.CENTER, again.align[2])
        // Every line is as wide as the others, Hangul counted as two.
        assertEquals(1, text.split('\n').map { displayWidth(it) }.distinct().size)
        assertEquals(2, again.withoutColumn(2).columns)
        assertEquals(2, again.withoutRow(0).rows.size)
    }

    @Test
    fun `the slash menu makes blocks where it was typed`() {
        assertEquals(0 to "he", slashQueryAt("/he", 3))
        assertNull(slashQueryAt("a/b", 3))
        assertNull(slashQueryAt("http://x", 8))
        assertEquals(SlashItem.H1, slashMatches("head").first())
        assertTrue(SlashItem.TABLE in slashMatches("표"))
        val heading = applySlash("Words /h1", 6, 9, SlashItem.H1)
        assertEquals("# Words ", heading.text)
        val code = applySlash("/code", 0, 5, SlashItem.CODE)
        assertEquals("```\n\n```", code.text)
        assertEquals(3, code.start)
        val after = applySlash("text /tab", 5, 9, SlashItem.TABLE)
        assertTrue(after.text.startsWith("text \n| "))
        val foot = applySlash("Fact /foot", 5, 10, SlashItem.FOOTNOTE)
        assertEquals("Fact [^1]\n\n[^1]: ", foot.text)
        assertEquals(foot.text.length, foot.start)
        // Handwriting: an embed of its picture on a line of its own, the cursor on the line after.
        assertTrue(SlashItem.INK in slashMatches("필기"))
        val ink = applySlash("/ink", 0, 4, SlashItem.INK, inkFileName(7))
        assertEquals("![[ink-7.png]]\n", ink.text)
        assertEquals(ink.text.length, ink.start)
        assertTrue(isInkName("ink-7.png") && !isInkName("photo.png"))
        assertEquals("ink-7.png.ink", inkStrokesName("ink-7.png"))
    }

    @Test
    fun `blocks move, copy and go`() {
        val text = "a\nb\nc"
        val blocks = liveBlocks(text)
        assertEquals("b\na\nc", moveBlock(text, blocks, 1, -1)!!.text)
        assertEquals("a\nc\nb", moveBlock(text, blocks, 1, 1)!!.text)
        assertNull(moveBlock(text, blocks, 0, -1))
        assertEquals("a\nb\nb\nc", duplicateBlock(text, blocks, 1)!!.text)
        assertEquals("a\nc", deleteBlock(text, blocks, 1)!!.text)
        assertEquals("a\nb", deleteBlock(text, blocks, 2)!!.text)
        assertEquals("# b", turnInto("- b", 0, 3, BlockStyle.H1).text)
        assertEquals("- [ ] b", turnInto("## b", 0, 4, BlockStyle.TASK).text)
        assertEquals("    - a", indentLines("- a", 0, 0, outdent = false).text)
        assertEquals("- a", indentLines("    - a", 4, 4, outdent = true).text)
    }

    @Test
    fun `the n-th task is ticked, also in a quote, not in code`() {
        val text = "- [ ] one\n```\n- [ ] code\n```\n> - [x] quoted\n1. [ ] two"
        assertEquals("- [x] one\n```\n- [ ] code\n```\n> - [x] quoted\n1. [ ] two", toggleNthTask(text, 0))
        assertEquals("- [ ] one\n```\n- [ ] code\n```\n> - [ ] quoted\n1. [ ] two", toggleNthTask(text, 1))
        assertEquals("- [ ] one\n```\n- [ ] code\n```\n> - [x] quoted\n1. [x] two", toggleNthTask(text, 2))
        assertNull(toggleNthTask(text, 3))
    }

    @Test
    fun `undo takes back a run of typing at once`() {
        val history = EditHistory()
        fun s(t: String) = EditHistory.State(t, t.length, t.length)
        history.record(s(""), s("a"), typed = true, now = 0)
        history.record(s("a"), s("ab"), typed = true, now = 300)
        history.record(s("ab"), s("**ab**"), typed = false, now = 400)
        assertEquals("ab", history.undo(s("**ab**"))!!.text)
        assertEquals("", history.undo(s("ab"))!!.text)
        assertFalse(history.canUndo)
        assertEquals("ab", history.redo(s(""))!!.text)
        assertTrue(history.canRedo)
        history.record(s("ab"), s("abc"), typed = true, now = 5_000)
        assertFalse(history.canRedo)
    }

    @Test
    fun `parts of blocks`() {
        assertEquals(ListParts("  ", "-", false, 8), listParts("  - [ ] x"))
        assertEquals(ListParts("", "2.", null, 3), listParts("2. y"))
        assertEquals(2 to "Title", headingContent("# Title ##").let { it.first to it.second })
        assertEquals(listOf(1, 2), quoteLines("> a\n> > b").map { it.depth })
        assertEquals("b", quoteLines("> a\n> > b")[1].text)
        assertEquals(listOf("tags" to "a, b", "date" to "2026-11-12"),
            frontMatterProperties("---\ntags:\n  - a\n  - b\ndate: 2026-11-12\n---"))
        assertEquals("val x = 1", codeBody("```kotlin\nval x = 1\n```", "kotlin").second)
        assertEquals("x", mathBody("$$\nx\n$$"))
        assertNotNull(codeTokens("val s = \"x\" // c", "kotlin").firstOrNull { it.third == CodeToken.COMMENT })
        assertEquals("Pasted image 20261112093005 2.png",
            attachmentName("PNG", java.util.Calendar.getInstance().apply { set(2026, 10, 12, 9, 30, 5) }.timeInMillis,
                setOf("pasted image 20261112093005.png")))
        assertEquals("pic.png" to 300, embedParts("pic.png|300"))
    }

    @Test
    fun `pictures go on a line of their own`() {
        assertEquals("![[a.png]]", insertEmbed("", 0, "a.png").text)
        assertEquals("text\n![[a.png]]\nmore", insertEmbed("text\nmore", 2, "a.png").text)
        assertEquals("x\n![[a.png]]", insertEmbed("x\n", 2, "a.png").text)
    }

    @Test
    fun `the source view dims all three marks of bold italic`() {
        val spans = markdownSpans("***both***")
        assertEquals(listOf(0 until 3, 7 until 10), spans.filter { it.kind == MdKind.MARKER }.map { it.start until it.end })
        assertTrue(spans.any { it.kind == MdKind.BOLD } && spans.any { it.kind == MdKind.ITALIC })
    }

    @Test
    fun `the reading page reaches only the viewer and the note's files`() {
        assertEquals(ReadResource.Asset("viewer/md-read.js"), markdownReadResource("https://markdown.notesis.invalid/viewer/md-read.js"))
        assertEquals(ReadResource.NoteFile("Pasted image 1.png"),
            markdownReadResource("https://markdown.notesis.invalid/viewer/file/Pasted%20image%201.png"))
        assertNull(markdownReadResource("https://markdown.notesis.invalid/viewer/file/..%2Fmeta.json"))
        assertNull(markdownReadResource("https://markdown.notesis.invalid/etc/passwd"))
        assertNull(markdownReadResource("https://other.example/viewer/md-read.js"))
    }
}
