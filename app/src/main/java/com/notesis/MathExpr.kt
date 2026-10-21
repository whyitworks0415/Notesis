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
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * The calculator's mathematics: expressions as written on paper, read into
 * a tree that can be evaluated, differentiated and integrated.
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

    /** Whether [expr] has x in it anywhere. */
    fun hasX(expr: Expr): Boolean = when (expr) {
        is Expr.Num, is Expr.Const -> false
        Expr.X -> true
        is Expr.Neg -> hasX(expr.of)
        is Expr.Bin -> hasX(expr.left) || hasX(expr.right)
        is Expr.Fn -> expr.args.any(::hasX)
        is Expr.Post -> hasX(expr.of)
    }

    // ---- calculus -----------------------------------------------------------------

    /**
     * d/dx of [expr], simplified, with angles in radians as calculus has
     * them. Null where there is no rule for it (x!, |x| at its corner aside,
     * a percentage of x and the like): then only the value at a point is
     * offered, numerically.
     */
    fun derivative(expr: Expr): Expr? = runCatching { simplify(d(expr)) }.getOrNull()

    private fun d(e: Expr): Expr = when (e) {
        is Expr.Num, is Expr.Const -> ZERO
        Expr.X -> ONE
        is Expr.Neg -> Expr.Neg(d(e.of))
        is Expr.Bin -> when (e.op) {
            '+', '-' -> Expr.Bin(e.op, d(e.left), d(e.right))
            '*' -> Expr.Bin('+', Expr.Bin('*', d(e.left), e.right), Expr.Bin('*', e.left, d(e.right)))
            '/' -> if (!hasX(e.right)) {
                // Over a constant: the quotient rule's second half is zero anyway.
                Expr.Bin('/', d(e.left), e.right)
            } else Expr.Bin('/',
                Expr.Bin('-', Expr.Bin('*', d(e.left), e.right), Expr.Bin('*', e.left, d(e.right))),
                Expr.Bin('^', e.right, Expr.Num(2.0)))
            else -> {
                val u = e.left
                val v = e.right
                when {
                    !hasX(v) -> Expr.Bin('*', Expr.Bin('*', v, Expr.Bin('^', u, Expr.Bin('-', v, ONE))), d(u))
                    !hasX(u) -> Expr.Bin('*', Expr.Bin('*', e, fn("ln", u)), d(v))
                    else -> Expr.Bin('*', e, Expr.Bin('+',
                        Expr.Bin('*', d(v), fn("ln", u)),
                        Expr.Bin('/', Expr.Bin('*', v, d(u)), u)))
                }
            }
        }
        is Expr.Post -> if (hasX(e.of)) {
            if (e.op == '%') Expr.Bin('/', d(e.of), Expr.Num(100.0)) else throw MathError("no rule")
        } else ZERO
        is Expr.Fn -> if (e.name == "logb") {
            d(Expr.Bin('/', fn("ln", e.args[1]), fn("ln", e.args[0])))
        } else {
            val u = e.args[0]
            val du = d(u)
            val outer: Expr = when (e.name) {
                "sin" -> fn("cos", u)
                "cos" -> Expr.Neg(fn("sin", u))
                "tan" -> Expr.Bin('/', ONE, Expr.Bin('^', fn("cos", u), Expr.Num(2.0)))
                "asin" -> Expr.Bin('/', ONE, fn("sqrt", Expr.Bin('-', ONE, Expr.Bin('^', u, Expr.Num(2.0)))))
                "acos" -> Expr.Neg(Expr.Bin('/', ONE, fn("sqrt", Expr.Bin('-', ONE, Expr.Bin('^', u, Expr.Num(2.0))))))
                "atan" -> Expr.Bin('/', ONE, Expr.Bin('+', ONE, Expr.Bin('^', u, Expr.Num(2.0))))
                "sinh" -> fn("cosh", u)
                "cosh" -> fn("sinh", u)
                "tanh" -> Expr.Bin('/', ONE, Expr.Bin('^', fn("cosh", u), Expr.Num(2.0)))
                "sqrt" -> Expr.Bin('/', ONE, Expr.Bin('*', Expr.Num(2.0), fn("sqrt", u)))
                "cbrt" -> Expr.Bin('/', ONE, Expr.Bin('*', Expr.Num(3.0), Expr.Bin('^', fn("cbrt", u), Expr.Num(2.0))))
                "ln" -> Expr.Bin('/', ONE, u)
                "log" -> Expr.Bin('/', ONE, Expr.Bin('*', u, fn("ln", Expr.Num(10.0))))
                "log2" -> Expr.Bin('/', ONE, Expr.Bin('*', u, fn("ln", Expr.Num(2.0))))
                "exp" -> e
                "abs" -> Expr.Bin('/', u, e)
                else -> throw MathError("no rule")
            }
            Expr.Bin('*', outer, du)
        }
    }

    /**
     * ∫ from [a] to [b] of [expr] dx, by adaptive Simpson's rule: each stretch
     * is halved until its two halves agree, so smooth parts cost little and
     * steep ones get the samples they need. Angles in radians.
     */
    fun integrate(expr: Expr, a: Double, b: Double, ans: Double = 0.0): Double {
        if (!a.isFinite() || !b.isFinite()) throw MathError("The bounds must be finite numbers")
        if (a == b) return 0.0
        val f = { x: Double -> eval(expr, degrees = false, x = x, ans = ans) }
        val fa = f(a)
        val fb = f(b)
        val m = (a + b) / 2
        val fm = f(m)
        val whole = (b - a) / 6 * (fa + 4 * fm + fb)
        val result = simpson(f, a, b, fa, fm, fb, whole, 1e-11 * maxOf(1.0, abs(whole)), MAX_DEPTH)
        if (!result.isFinite()) throw MathError("The function is undefined somewhere in the interval")
        return result
    }

    private fun simpson(
        f: (Double) -> Double, a: Double, b: Double,
        fa: Double, fm: Double, fb: Double, whole: Double, tolerance: Double, depth: Int,
    ): Double {
        val m = (a + b) / 2
        val lm = (a + m) / 2
        val rm = (m + b) / 2
        val flm = f(lm)
        val frm = f(rm)
        val left = (m - a) / 6 * (fa + 4 * flm + fm)
        val right = (b - m) / 6 * (fm + 4 * frm + fb)
        val delta = left + right - whole
        if (depth <= 0 || abs(delta) <= 15 * tolerance || !delta.isFinite()) return left + right + delta / 15
        return simpson(f, a, m, fa, flm, fm, left, tolerance / 2, depth - 1) +
            simpson(f, m, b, fm, frm, fb, right, tolerance / 2, depth - 1)
    }

    /**
     * The slope at [x] without a formula: central differences at two step
     * sizes, combined (Richardson) so the error of the larger cancels out.
     */
    fun numericDerivative(expr: Expr, x: Double, ans: Double = 0.0): Double {
        val f = { t: Double -> eval(expr, degrees = false, x = t, ans = ans) }
        val h = 1e-3 * maxOf(1.0, abs(x))
        fun central(step: Double) = (f(x + step) - f(x - step)) / (2 * step)
        return (4 * central(h / 2) - central(h)) / 3
    }

    // ---- simplifying and writing out --------------------------------------------

    fun simplify(e: Expr): Expr = when (e) {
        is Expr.Num, is Expr.Const, Expr.X -> e
        is Expr.Neg -> when (val inner = simplify(e.of)) {
            is Expr.Num -> Expr.Num(-inner.value)
            is Expr.Neg -> inner.of
            else -> Expr.Neg(inner)
        }
        is Expr.Post -> Expr.Post(e.op, simplify(e.of))
        is Expr.Fn -> {
            val args = e.args.map(::simplify)
            if (e.name == "ln" && args[0] == E_CONST) ONE else Expr.Fn(e.name, args)
        }
        is Expr.Bin -> {
            val l = simplify(e.left)
            val r = simplify(e.right)
            val ln = (l as? Expr.Num)?.value
            val rn = (r as? Expr.Num)?.value
            when (e.op) {
                '+' -> when {
                    ln != null && rn != null -> Expr.Num(ln + rn)
                    ln == 0.0 -> r
                    rn == 0.0 -> l
                    r is Expr.Neg -> simplify(Expr.Bin('-', l, r.of))
                    else -> Expr.Bin('+', l, r)
                }
                '-' -> when {
                    ln != null && rn != null -> Expr.Num(ln - rn)
                    rn == 0.0 -> l
                    ln == 0.0 -> simplify(Expr.Neg(r))
                    l == r -> ZERO
                    r is Expr.Neg -> simplify(Expr.Bin('+', l, r.of))
                    else -> Expr.Bin('-', l, r)
                }
                '*' -> when {
                    ln != null && rn != null -> Expr.Num(ln * rn)
                    ln == 0.0 || rn == 0.0 -> ZERO
                    ln == 1.0 -> r
                    rn == 1.0 -> l
                    ln == -1.0 -> simplify(Expr.Neg(r))
                    rn == -1.0 -> simplify(Expr.Neg(l))
                    // Numbers to the front, where they are read: x·2 is 2x.
                    rn != null -> simplify(Expr.Bin('*', r, l))
                    ln != null && r is Expr.Bin && r.op == '*' && r.left is Expr.Num ->
                        simplify(Expr.Bin('*', Expr.Num(ln * (r.left as Expr.Num).value), r.right))
                    l is Expr.Neg -> simplify(Expr.Neg(Expr.Bin('*', l.of, r)))
                    r is Expr.Neg -> simplify(Expr.Neg(Expr.Bin('*', l, r.of)))
                    l == r -> Expr.Bin('^', l, Expr.Num(2.0))
                    else -> Expr.Bin('*', l, r)
                }
                '/' -> when {
                    ln == 0.0 -> ZERO
                    // (a/b)/c reads better as a/(b·c).
                    l is Expr.Bin && l.op == '/' -> simplify(Expr.Bin('/', l.left, Expr.Bin('*', l.right, r)))
                    rn == 1.0 -> l
                    ln != null && rn != null && rn != 0.0 && isNice(ln / rn) -> Expr.Num(ln / rn)
                    l == r -> ONE
                    l is Expr.Neg -> simplify(Expr.Neg(Expr.Bin('/', l.of, r)))
                    else -> Expr.Bin('/', l, r)
                }
                else -> when {
                    rn == 0.0 -> ONE
                    rn == 1.0 -> l
                    ln == 1.0 -> ONE
                    ln != null && rn != null && isNice(power(ln, rn)) -> Expr.Num(power(ln, rn))
                    l is Expr.Bin && l.op == '^' && l.right is Expr.Num && rn != null ->
                        Expr.Bin('^', l.left, Expr.Num((l.right as Expr.Num).value * rn))
                    else -> Expr.Bin('^', l, r)
                }
            }
        }
    }

    /** A result worth folding into one number: whole, or short as a decimal. */
    private fun isNice(v: Double): Boolean = v.isFinite() && abs(v * 1e6 - round(v * 1e6)) < 1e-6

    /** [e] written out the way it would be typed, with only the brackets it needs. */
    fun format(e: Expr): String = write(e, 0)

    private fun write(e: Expr, parent: Int): String {
        val own = precedence(e)
        val text = when (e) {
            is Expr.Num -> formatNumber(e.value)
            is Expr.Const -> e.name
            Expr.X -> "x"
            is Expr.Neg -> "-" + write(e.of, 3)
            is Expr.Post -> write(e.of, 5) + e.op
            is Expr.Fn -> {
                val name = when (e.name) {
                    "sqrt" -> "√"
                    else -> e.name
                }
                if (e.name == "sqrt" && (e.args[0] is Expr.Num || e.args[0] == Expr.X)) {
                    name + write(e.args[0], 6)
                } else name + "(" + e.args.joinToString(", ") { write(it, 0) } + ")"
            }
            is Expr.Bin -> when (e.op) {
                '+' -> write(e.left, 1) + " + " + write(e.right, 1)
                '-' -> write(e.left, 1) + " - " + write(e.right, 2)
                '*' -> {
                    val left = write(e.left, 2)
                    val right = write(e.right, 2)
                    // 2x, 2sin(x), 3(x+1); a × between two numbers or after a name.
                    if (e.left is Expr.Num && !right.first().isDigit() && !right.startsWith("-")) left + right
                    else "$left×$right"
                }
                '/' -> write(e.left, 2) + "/" + write(e.right, 3)
                else -> write(e.left, 5) + "^" + write(e.right, 4)
            }
        }
        return if (own < parent) "($text)" else text
    }

    private fun precedence(e: Expr): Int = when (e) {
        is Expr.Num -> if (e.value < 0) 3 else 6
        Expr.X, is Expr.Const, is Expr.Fn -> 6
        is Expr.Post -> 5
        is Expr.Neg -> 3
        is Expr.Bin -> when (e.op) {
            '+', '-' -> 1
            '*', '/' -> 2
            else -> 4
        }
    }

    private val E_CONST = Expr.Const("e", kotlin.math.E)
    private val ZERO = Expr.Num(0.0)
    private val ONE = Expr.Num(1.0)
    private fun fn(name: String, arg: Expr) = Expr.Fn(name, listOf(arg))
    private const val MAX_DEPTH = 48
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
