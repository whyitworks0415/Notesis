package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class LatexClipboardTextTest {
    @Test fun ordinaryTextIsNotTouched() {
        assertEquals("극한의 정의를 설명하세요.", LatexClipboardText.convert("극한의 정의를 설명하세요."))
    }

    @Test fun integralAndLimitsBecomeLatex() {
        assertEquals(
            "\\int_{0}^\\infty f(x) dx",
            LatexClipboardText.convert("∫₀^∞ f(x) dx"),
        )
        assertEquals(
            "\\lim_{x}\\to_{0} f(x) = 1",
            LatexClipboardText.convert("limₓ→₀ f(x) = 1"),
        )
    }

    @Test fun scriptsGreekAndRelationsBecomeLatex() {
        assertEquals(
            "\\alpha^{2} + \\beta^{2} \\ge \\gamma^{2}",
            LatexClipboardText.convert("α² + β² ≥ γ²"),
        )
    }

    @Test fun parenthesizedSquareRootKeepsItsExpression() {
        assertEquals("\\sqrt{x^{2}+1}", LatexClipboardText.convert("√(x²+1)"))
    }
}
