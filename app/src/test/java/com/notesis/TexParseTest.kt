package com.notesis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TexParseTest {
    private fun glyph(text: String, kind: TexKind = TexKind.ORD, font: TexFont = TexFont.AUTO) = TexNode.Glyph(text, kind, font)

    @Test
    fun `scripts, fractions and roots`() {
        assertEquals(TexNode.Scripts(glyph("x"), glyph("2", font = TexFont.ROMAN), null), parseTex("x^2"))
        assertEquals(TexNode.Scripts(glyph("a"), glyph("2", font = TexFont.ROMAN), glyph("i")), parseTex("a_i^2"))
        val frac = parseTex("\\frac{a}{b+1}") as TexNode.Fraction
        assertEquals(glyph("a"), frac.num)
        assertTrue(frac.den is TexNode.Row)
        assertEquals(TexNode.Fraction(glyph("1", font = TexFont.ROMAN), glyph("2", font = TexFont.ROMAN)), parseTex("\\frac12"))
        val root = parseTex("\\sqrt[3]{x}") as TexNode.Radical
        assertEquals(glyph("3", font = TexFont.ROMAN), root.index)
        assertEquals(TexStyle.DISPLAY, (parseTex("\\dfrac{1}{2}") as TexNode.Fraction).style)
        // A prime is a superscript, as in TeX.
        assertEquals(TexNode.Scripts(glyph("f"), glyph("′", font = TexFont.ROMAN), null), parseTex("f'"))
    }

    @Test
    fun `symbols, operators and their spacing classes`() {
        val row = parseTex("\\alpha + \\beta \\le 3.14") as TexNode.Row
        assertEquals(listOf("α", "+", "β", "≤", "3.14"), row.items.map { (it as TexNode.Glyph).text })
        assertEquals(TexKind.BIN, (row.items[1] as TexNode.Glyph).kind)
        assertEquals(TexKind.REL, (row.items[3] as TexNode.Glyph).kind)
        assertEquals(TexKind.OP, (parseTex("\\sin") as TexNode.Glyph).kind)
        // A minus is drawn as the real sign.
        assertEquals("−", ((parseTex("a-b") as TexNode.Row).items[1] as TexNode.Glyph).text)
        val sum = (parseTex("\\sum_{i=1}^n i") as TexNode.Row).items[0] as TexNode.Scripts
        assertEquals(TexNode.BigOp("∑", limits = true), sum.base)
        assertEquals(TexNode.BigOp("∫", limits = false, integral = true), (parseTex("\\int_0^1") as TexNode.Scripts).base)
        assertEquals(TexNode.BigOp("lim", limits = true, word = true), (parseTex("\\lim_{x\\to 0}") as TexNode.Scripts).base)
        assertEquals(TexNode.BigOp("∑", limits = false), (parseTex("\\sum\\nolimits_i") as TexNode.Scripts).base)
        assertEquals(5, spacing(TexKind.ORD, TexKind.REL, tight = false))
        assertEquals(0, spacing(TexKind.ORD, TexKind.REL, tight = true))
        assertEquals(3, spacing(TexKind.OP, TexKind.ORD, tight = true))
    }

    @Test
    fun `delimiters, matrices, cases and lines`() {
        val fenced = parseTex("\\left( \\frac12 \\right]") as TexNode.Fenced
        assertEquals("(", fenced.left)
        assertEquals("]", fenced.right)
        assertEquals("", (parseTex("\\left. x \\right|") as TexNode.Fenced).left)
        val matrix = parseTex("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}") as TexNode.Grid
        assertEquals(2, matrix.rows.size)
        assertEquals(2, matrix.rows[1].size)
        assertEquals("(", matrix.left)
        val cases = parseTex("f(x) = \\begin{cases} 1 & x > 0 \\\\ 0 & \\text{otherwise} \\end{cases}") as TexNode.Row
        assertEquals("{", (cases.items.last() as TexNode.Grid).left)
        val lines = parseTex("a &= b \\\\ &= c") as TexNode.Grid
        assertEquals(listOf('r', 'l'), lines.align)
        assertEquals(2, lines.rows.size)
        assertEquals(2, (parseTex("x \\\\ y") as TexNode.Grid).rows.size)
    }

    @Test
    fun `letters in other styles and text`() {
        assertEquals(glyph("R", font = TexFont.BLACKBOARD), parseTex("\\mathbb{R}"))
        assertEquals("ℝ", blackboardOf('R'))
        assertEquals("ℒ", scriptOf('L'))
        assertEquals(glyph("if x > 0", font = TexFont.TEXT), parseTex("\\text{if x > 0}"))
        assertEquals(TexNode.Accent(glyph("v"), TexAccent.VEC), parseTex("\\vec v"))
        val colored = parseTex("\\color{red} x + y") as TexNode.Styled
        assertEquals(0xFFD32F2F.toInt(), colored.color)
        assertTrue(colored.body is TexNode.Row)
        assertEquals(0xFF112233.toInt(), texColor("#112233"))
    }

    @Test
    fun `nothing written is refused or lost`() {
        assertEquals(TexNode.Error("\\foo"), parseTex("\\foo"))
        // Unclosed groups and environments still give what was read.
        assertTrue(parseTex("\\frac{a") is TexNode.Fraction)
        assertTrue(parseTex("\\begin{matrix} a & b") is TexNode.Grid)
        assertTrue(parseTex("\\left( x") is TexNode.Fenced)
        // % is per cent here, not a comment eating the rest.
        assertEquals("%", ((parseTex("50%") as TexNode.Row).items[1] as TexNode.Glyph).text)
        // A runaway nest of braces does not overflow the stack.
        parseTex("{".repeat(5000) + "x")
        parseTex("^_^_''{}}}\\\\&&\\left\\right\\begin{")
        assertFalse(parseTex("") is TexNode.Error)
    }

    @Test
    fun `a text box's look is saved and read back, and old boxes keep their look`() {
        val box = TextBoxContent("Hi", size = 40f, markdown = true, align = TextBoxContent.ALIGN_JUSTIFY, letterSpacing = 0.05f,
            lineSpacing = 1.5f, paragraphSpacing = 12f, indent = 2f, bold = true, italic = true, border = 0xFF112233.toInt(),
            borderWidth = 3f, wrapWidth = 500f, background = 0xFFFFF59D.toInt(), corner = 8f, padding = 10f, font = "serif")
        assertEquals(box, textBoxContentFrom(JSONObject(box.toJson().toString())))
        val old = JSONObject("""{"value":"Old","size":24,"color":-16777216,"html":"","font":"","bg":0,"corner":0,"pad":4,"min":0,"md":false,"wrap":0}""")
        assertEquals(TextBoxContent("Old", size = 24f), textBoxContentFrom(old))
        // A plain box writes nothing it did not have before.
        assertFalse(TextBoxContent("x").toJson().has("align"))
    }

    @Test
    fun `fonts and alignments by the words people use`() {
        assertEquals("serif", fontOf("Serif"))
        assertEquals("monospace", fontOf("mono"))
        assertEquals("", fontOf("default"))
        assertEquals("sans-serif-condensed", fontOf("condensed"))
        assertEquals(null, fontOf("comic"))
        assertEquals(TextBoxContent.ALIGN_CENTER, alignOf("centre"))
        assertEquals("justify", alignName(TextBoxContent.ALIGN_JUSTIFY))
    }
}
