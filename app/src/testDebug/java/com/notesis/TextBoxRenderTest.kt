package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Text boxes with Markdown, LaTeX and their look are drawn, measured and never fail on what is written. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextBoxRenderTest {
    @Test fun formulasTakeTheRoomTheyNeed() {
        val plain = layoutTex("x", 40f, 0xFF000000.toInt(), display = false)
        val fraction = layoutTex("\\frac{a}{b}", 40f, 0xFF000000.toInt(), display = false)
        val display = layoutTex("\\sum_{i=1}^{n} \\frac{1}{i^2}", 40f, 0xFF000000.toInt(), display = true)
        assertTrue(plain.width > 0f)
        assertTrue(fraction.ascent + fraction.descent > plain.ascent + plain.descent)
        assertTrue(display.ascent + display.descent > fraction.ascent + fraction.descent)
        val matrix = layoutTex("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}", 40f, 0xFF000000.toInt(), display = true)
        assertTrue(matrix.ascent + matrix.descent > 80f)
    }

    @Test fun markdownAndMathMakeATallerBox() {
        val plain = textBoxHeight(TextBoxContent("x", markdown = true))
        val math = textBoxHeight(TextBoxContent("\$\$\\int_0^1 \\frac{x^2}{2}\\,dx\$\$", markdown = true))
        assertTrue("$math > $plain", math > plain)
        val table = textBoxHeight(TextBoxContent("| a | b |\n|---|:-:|\n| 1 | \$x\$ |\n| 2 | **y** |", markdown = true))
        assertTrue(table > plain * 2)
    }

    @Test fun spacingAndBorderChangeTheBox() {
        val text = "one line\nanother line\nand a third"
        val base = textBoxHeight(TextBoxContent(text))
        assertTrue(textBoxHeight(TextBoxContent(text, lineSpacing = 2f)) > base)
        assertTrue(textBoxHeight(TextBoxContent(text, paragraphSpacing = 30f)) > base)
        val bordered = renderTextBox(TextBoxContent(text, border = 0xFF000000.toInt(), borderWidth = 6f))
        val unbordered = renderTextBox(TextBoxContent(text))
        assertEquals(unbordered.width + 24, bordered.width)
    }

    @Test fun anythingWrittenIsDrawn() {
        for (source in listOf("\$\\foo{\$", "\$\$\\begin{cases}\$\$", "\$x^\$", "**unclosed", "| a |\n|--|", "```\ncode", "\$\$\nx\n",
            "- [x] done\n  - nested\n1. one\n> quote\n---\n==mark== <u>u</u> [[Note]] \\*literal\\*")) {
            val bitmap = renderTextBox(TextBoxContent(source, markdown = true, align = TextBoxContent.ALIGN_JUSTIFY, indent = 1f))
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
        }
    }
}
