package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentMarkdownTest {
    @Test
    fun sectionsParagraphsListsAndTablesBecomeMarkdown() {
        val document = PreviewDocument(
            title = "doc",
            kind = PreviewKind.WORD,
            sections = listOf(
                PreviewSection("One", listOf(
                    PreviewBlock.Paragraph("Hello"),
                    PreviewBlock.Paragraph("item", level = 1),
                )),
                PreviewSection("Two", listOf(
                    PreviewBlock.Table(listOf(listOf("a", "b|c"), listOf("1"))),
                )),
            ),
        )
        assertEquals(
            "## One\n\nHello\n\n- item\n## Two\n\n| a | b\\|c |\n| --- | --- |\n| 1 |  |\n",
            document.toMarkdown(),
        )
    }

    @Test
    fun csvKeepsQuotedCommasAndLineBreaks() {
        assertEquals(
            listOf(listOf("a", "b,c"), listOf("1", "x\ny \"q\"")),
            parseCsv("a,\"b,c\"\r\n1,\"x\ny \"\"q\"\"\"\n"),
        )
    }
}
