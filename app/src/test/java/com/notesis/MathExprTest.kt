package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class MathExprTest {
    private fun calc(text: String, degrees: Boolean = false, x: Double = Double.NaN, ans: Double = 0.0) =
        MathExpr.eval(MathExpr.parse(text), degrees, x, ans)

    @Test fun arithmeticKeepsItsOrder() {
        assertEquals(14.0, calc("2+3×4"), 1e-12)
        assertEquals(20.0, calc("(2+3)×4"), 1e-12)
        assertEquals(-4.0, calc("-2^2"), 1e-12)
        assertEquals(512.0, calc("2^3^2"), 1e-12)
        assertEquals(0.5, calc("2^-1"), 1e-12)
        assertEquals(2.5, calc("10÷4"), 1e-12)
    }

    @Test fun sideBySideMultiplies() {
        assertEquals(2 * PI, calc("2π"), 1e-12)
        assertEquals(6.0, calc("2(1+2)"), 1e-12)
        assertEquals(-3.0, calc("(1+2)(1-2)"), 1e-12)
        assertEquals(6.0, calc("3x", x = 2.0), 1e-12)
    }

    @Test fun rootsLogsAndPowers() {
        assertEquals(3.0, calc("√9"), 1e-12)
        assertEquals(4.0, calc("√(16)"), 1e-12)
        assertEquals(-2.0, calc("cbrt(-8)"), 1e-12)
        assertEquals(-2.0, calc("(-8)^(1/3)"), 1e-12)
        assertEquals(2.0, calc("log(100)"), 1e-12)
        assertEquals(1.0, calc("ln(e)"), 1e-12)
        assertEquals(3.0, calc("log2(8)"), 1e-12)
        assertEquals(3.0, calc("logb(2, 8)"), 1e-12)
        assertEquals(120.0, calc("5!"), 1e-12)
        assertEquals(0.25, calc("25%"), 1e-12)
    }

    @Test fun trigonometryInDegreesAndRadians() {
        assertEquals(0.5, calc("sin(30)", degrees = true), 0.0)
        assertEquals(0.0, calc("sin(180)", degrees = true), 0.0)
        assertEquals(1.0, calc("tan(45)", degrees = true), 1e-12)
        assertEquals(30.0, calc("asin(0.5)", degrees = true), 1e-9)
        assertEquals(0.0, calc("sin(π)"), 1e-15)
        assertTrue(calc("tan(90)", degrees = true).isNaN())
    }

    @Test fun lastAnswerCarriesOver() {
        assertEquals(15.0, calc("Ans×3", ans = 5.0), 1e-12)
    }

    @Test fun numbersAreShownCleanly() {
        assertEquals("0.3", formatNumber(0.1 + 0.2))
        assertEquals("120", formatNumber(120.0))
        assertEquals("1.5E-7", formatNumber(1.5e-7))
        assertEquals("Undefined", formatNumber(Double.NaN))
    }
}
