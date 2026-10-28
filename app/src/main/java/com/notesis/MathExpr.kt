package com.notesis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * The calculator's mathematics: expressions as written on paper, read into
 * a tree that can be evaluated.
 *
 * What is understood: + − × ÷ ^, brackets, a minus in front, x! and x%,
 * √ before a value, π and e, the variable x, the last answer as Ans, and the
 * functions sin cos tan asin acos atan sinh cosh tanh sqrt cbrt ln log
 * (base 10) log2 logb(base, x) exp abs. A number or a bracket next to a name
 * or a bracket multiplies: 2π, 3x, 2sin(x), (1+x)(1−x).
 */
internal sealed interface Expr {
    data class Num(val value: Double) : Expr
    /** π or e, kept by name so a result reads 2π, eˣ, and ln(e) can be 1. */
    data class Const(val name: String, val value: Double) : Expr
    data object X : Expr
    data class Neg(val of: Expr) : Expr
    /** One of + - * / ^. */
    data class Bin(val op: Char, val left: Expr, val right: Expr) : Expr
    data class Fn(val name: String, val args: List<Expr>) : Expr
    /** ! or %. */
    data class Post(val op: Char, val of: Expr) : Expr
}

/** A mistake in what was typed, said the way the calculator shows it. */
internal class MathError(message: String) : Exception(message)

internal object MathExpr {
    val FUNCTIONS = setOf(
        "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
        "sqrt", "cbrt", "ln", "log", "log2", "logb", "exp", "abs",
    )

    fun parse(text: String): Expr = Parser(normalize(text)).parseAll()

    /** The symbols the keypad shows, turned into what the parser reads. */
    fun normalize(text: String): String = text
        .replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-')
        .replace("π", "pi").replace("√", "sqrt ")

    /**
     * [expr] worked out. Angles are in degrees when [degrees], else radians;
     * [x] is the variable's value, [ans] the previous answer.
     */
    fun eval(expr: Expr, degrees: Boolean = false, x: Double = Double.NaN, ans: Double = 0.0): Double {
        fun angleIn(v: Double) = if (degrees) v * PI / 180.0 else v
        fun angleOut(v: Double) = if (degrees) v * 180.0 / PI else v
        fun go(e: Expr): Double = when (e) {
            is Expr.Num -> if (e.value.isNaN()) ans else e.value
            is Expr.Const -> e.value
            Expr.X -> x
            is Expr.Neg -> -go(e.of)
            is Expr.Bin -> {
                val a = go(e.left)
                val b = go(e.right)
                when (e.op) {
                    '+' -> a + b
                    '-' -> a - b
                    '*' -> a * b
                    '/' -> a / b
                    else -> power(a, b)
                }
            }
            is Expr.Post -> {
                val v = go(e.of)
                if (e.op == '%') v / 100.0 else factorial(v)
            }
            is Expr.Fn -> {
                val v = go(e.args[0])
                when (e.name) {
                    "sin" -> exactTrig(sin(angleIn(v)))
                    "cos" -> exactTrig(cos(angleIn(v)))
                    "tan" -> {
                        val c = exactTrig(cos(angleIn(v)))
                        if (c == 0.0) Double.NaN else exactTrig(sin(angleIn(v))) / c
                    }
                    "asin" -> angleOut(asin(v))
                    "acos" -> angleOut(acos(v))
                    "atan" -> angleOut(atan(v))
                    "sinh" -> sinh(v)
                    "cosh" -> cosh(v)
                    "tanh" -> tanh(v)
                    "sqrt" -> sqrt(v)
                    "cbrt" -> cbrt(v)
                    "ln" -> ln(v)
                    "log" -> log10(v)
                    "log2" -> log2(v)
                    "logb" -> ln(go(e.args[1])) / ln(v)
                    "exp" -> exp(v)
                    "abs" -> abs(v)
                    else -> throw MathError("Unknown function: ${e.name}")
                }
            }
        }
        return go(expr)
    }
}

/** a^b, with an odd root of a negative number real as on paper: (-8)^(1/3) = -2. */
internal fun power(a: Double, b: Double): Double {
    if (a < 0 && !isWhole(b)) {
        val inverse = 1.0 / b
        if (isWhole(inverse) && inverse.toLong() % 2L != 0L) return -(-a).pow(b)
    }
    return a.pow(b)
}

/** n! for whole n up to 170, beyond which a Double cannot hold it. */
internal fun factorial(n: Double): Double {
    if (!isWhole(n) || n < 0) throw MathError("Factorial (!) only works on whole numbers 0 or greater")
    if (n > 170) return Double.POSITIVE_INFINITY
    var result = 1.0
    for (i in 2..n.toInt()) result *= i
    return result
}

private fun isWhole(v: Double) = v.isFinite() && v == floor(v)

/** sin 180° is 0, not 1.2e-16: what is a rounding hair from a whole step is that step. */
private fun exactTrig(v: Double): Double = when {
    abs(v) < 1e-15 -> 0.0
    abs(v - 1) < 1e-15 -> 1.0
    abs(v + 1) < 1e-15 -> -1.0
    abs(v - 0.5) < 1e-15 -> 0.5
    abs(v + 0.5) < 1e-15 -> -0.5
    else -> v
}

/** A result for the display: up to 12 significant figures, no trailing zeros, E for the very large and small. */
internal fun formatNumber(v: Double): String {
    if (v.isNaN()) return "Undefined"
    if (v == Double.POSITIVE_INFINITY) return "∞"
    if (v == Double.NEGATIVE_INFINITY) return "-∞"
    val clean = if (abs(v) < 1e-13) 0.0 else v
    val magnitude = abs(clean)
    if (magnitude != 0.0 && (magnitude >= 1e12 || magnitude < 1e-6)) {
        val text = "%.10e".format(java.util.Locale.ROOT, clean)
        val mantissa = text.substringBefore('e').trimEnd('0').trimEnd('.')
        val exponent = text.substringAfter('e').toInt()
        return "${mantissa}E$exponent"
    }
    val rounded = java.math.BigDecimal(clean).round(java.math.MathContext(12))
    val plain = rounded.stripTrailingZeros().toPlainString()
    return if (plain == "-0") "0" else plain
}

private class Parser(private val text: String) {
    private var at = 0

    fun parseAll(): Expr {
        if (text.isBlank()) throw MathError("Enter an expression")
        val e = expr()
        skip()
        if (at < text.length) throw MathError("The expression doesn't end before '${text[at]}'")
        return e
    }

    private fun skip() {
        while (at < text.length && text[at].isWhitespace()) at++
    }

    private fun peek(): Char? {
        skip()
        return text.getOrNull(at)
    }

    private fun expr(): Expr {
        var left = term()
        while (true) {
            val c = peek() ?: return left
            if (c != '+' && c != '-') return left
            at++
            left = Expr.Bin(c, left, term())
        }
    }

    private fun term(): Expr {
        var left = unary()
        while (true) {
            val c = peek() ?: return left
            when {
                c == '*' || c == '/' -> {
                    at++
                    left = Expr.Bin(c, left, unary())
                }
                // Side by side multiplies: 2x, 2π, 3(x+1), (x+1)(x-1).
                c == '(' || c.isLetter() || c.isDigit() || c == '.' -> left = Expr.Bin('*', left, power())
                else -> return left
            }
        }
    }

    private fun unary(): Expr {
        val c = peek()
        if (c == '-') {
            at++
            return Expr.Neg(unary())
        }
        if (c == '+') {
            at++
            return unary()
        }
        return power()
    }

    private fun power(): Expr {
        val base = postfix()
        if (peek() == '^') {
            at++
            return Expr.Bin('^', base, unary())
        }
        return base
    }

    private fun postfix(): Expr {
        var e = primary()
        while (true) {
            val c = peek()
            if (c == '!' || c == '%') {
                at++
                e = Expr.Post(c, e)
            } else return e
        }
    }

    private fun primary(): Expr {
        val c = peek() ?: throw MathError("The expression is incomplete")
        if (c == '(') {
            at++
            val inner = expr()
            if (peek() == ')') at++ // a missing last bracket is forgiven, as calculators do
            return inner
        }
        if (c.isDigit() || c == '.') return number()
        if (c.isLetter()) {
            val start = at
            while (at < text.length && (text[at].isLetterOrDigit())) at++
            val name = text.substring(start, at)
            return named(name.lowercase(), start)
        }
        throw MathError("'$c' can't go here")
    }

    private fun number(): Expr {
        val start = at
        while (at < text.length && (text[at].isDigit() || text[at] == '.')) at++
        // 1.5E3 / 2e-4: an E straight after digits and before a digit or sign is an exponent.
        if (at < text.length && (text[at] == 'E') && at + 1 < text.length &&
            (text[at + 1].isDigit() || ((text[at + 1] == '-' || text[at + 1] == '+') && at + 2 < text.length && text[at + 2].isDigit()))
        ) {
            at += 2
            while (at < text.length && text[at].isDigit()) at++
        }
        val value = text.substring(start, at).toDoubleOrNull() ?: throw MathError("Can't read the number")
        return Expr.Num(value)
    }

    private fun named(name: String, start: Int): Expr {
        when (name) {
            "pi" -> return Expr.Const("π", PI)
            "e" -> return Expr.Const("e", kotlin.math.E)
            "x" -> return Expr.X
            "ans" -> return Expr.Num(Double.NaN) // filled in at evaluation
        }
        if (name in MathExpr.FUNCTIONS) {
            val args = mutableListOf<Expr>()
            if (peek() == '(') {
                at++
                args += expr()
                while (peek() == ',') {
                    at++
                    args += expr()
                }
                if (peek() == ')') at++
            } else {
                // √2, sin x: a function name straight before a value takes it.
                args += power()
            }
            val wanted = if (name == "logb") 2 else 1
            if (args.size != wanted) {
                throw MathError(if (name == "logb") "Enter it as logb(base, value)" else "$name takes one value")
            }
            return Expr.Fn(name, args)
        }
        // xx, 2xsin: a run of letters that is not one name, read as names side by side.
        at = start
        // Longest function names first, so "expx" is exp(x) and not e·xp·x.
        for (known in MathExpr.FUNCTIONS.sortedByDescending { it.length } + listOf("ans", "pi", "x", "e")) {
            if (text.regionMatches(start, known, 0, known.length, ignoreCase = true)) {
                at = start + known.length
                return named(known, start)
            }
        }
        throw MathError("Unknown name: $name")
    }
}
