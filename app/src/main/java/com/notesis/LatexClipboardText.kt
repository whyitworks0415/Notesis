package com.notesis

/**
 * Conservatively turns the mathematical Unicode commonly exposed by a PDF
 * text layer into paste-ready LaTeX. Ordinary prose is deliberately left
 * byte-for-byte alone; this is a clipboard aid, not a general OCR engine.
 */
internal object LatexClipboardText {
    private val superscripts = mapOf(
        '⁰' to "0", '¹' to "1", '²' to "2", '³' to "3", '⁴' to "4",
        '⁵' to "5", '⁶' to "6", '⁷' to "7", '⁸' to "8", '⁹' to "9",
        '⁺' to "+", '⁻' to "-", '⁼' to "=", '⁽' to "(", '⁾' to ")",
        'ⁿ' to "n", 'ⁱ' to "i",
    )

    private val subscripts = mapOf(
        '₀' to "0", '₁' to "1", '₂' to "2", '₃' to "3", '₄' to "4",
        '₅' to "5", '₆' to "6", '₇' to "7", '₈' to "8", '₉' to "9",
        '₊' to "+", '₋' to "-", '₌' to "=", '₍' to "(", '₎' to ")",
        'ₐ' to "a", 'ₑ' to "e", 'ₕ' to "h", 'ᵢ' to "i", 'ⱼ' to "j",
        'ₖ' to "k", 'ₗ' to "l", 'ₘ' to "m", 'ₙ' to "n", 'ₒ' to "o",
        'ₚ' to "p", 'ᵣ' to "r", 'ₛ' to "s", 'ₜ' to "t", 'ᵤ' to "u",
        'ᵥ' to "v", 'ₓ' to "x",
    )

    private val symbols = mapOf(
        '∫' to "\\int", '∬' to "\\iint", '∭' to "\\iiint", '∮' to "\\oint",
        '∑' to "\\sum", '∏' to "\\prod", '∞' to "\\infty",
        '≤' to "\\le", '≥' to "\\ge", '≠' to "\\ne", '≈' to "\\approx",
        '≡' to "\\equiv", '±' to "\\pm", '∓' to "\\mp", '×' to "\\times",
        '÷' to "\\div", '·' to "\\cdot", '∝' to "\\propto",
        '→' to "\\to", '←' to "\\leftarrow", '↔' to "\\leftrightarrow",
        '⇒' to "\\Rightarrow", '⇐' to "\\Leftarrow", '⇔' to "\\Leftrightarrow",
        '∈' to "\\in", '∉' to "\\notin", '⊂' to "\\subset", '⊃' to "\\supset",
        '⊆' to "\\subseteq", '⊇' to "\\supseteq", '∪' to "\\cup", '∩' to "\\cap",
        '∂' to "\\partial", '∇' to "\\nabla", '∀' to "\\forall", '∃' to "\\exists",
        '∅' to "\\emptyset", '⊥' to "\\perp", '∥' to "\\parallel",
        'α' to "\\alpha", 'β' to "\\beta", 'γ' to "\\gamma", 'δ' to "\\delta",
        'ε' to "\\epsilon", 'ζ' to "\\zeta", 'η' to "\\eta", 'θ' to "\\theta",
        'ι' to "\\iota", 'κ' to "\\kappa", 'λ' to "\\lambda", 'μ' to "\\mu",
        'ν' to "\\nu", 'ξ' to "\\xi", 'π' to "\\pi", 'ρ' to "\\rho",
        'σ' to "\\sigma", 'τ' to "\\tau", 'υ' to "\\upsilon", 'φ' to "\\phi",
        'χ' to "\\chi", 'ψ' to "\\psi", 'ω' to "\\omega",
        'Γ' to "\\Gamma", 'Δ' to "\\Delta", 'Θ' to "\\Theta", 'Λ' to "\\Lambda",
        'Ξ' to "\\Xi", 'Π' to "\\Pi", 'Σ' to "\\Sigma", 'Φ' to "\\Phi",
        'Ψ' to "\\Psi", 'Ω' to "\\Omega",
        '½' to "\\frac{1}{2}", '⅓' to "\\frac{1}{3}", '⅔' to "\\frac{2}{3}",
        '¼' to "\\frac{1}{4}", '¾' to "\\frac{3}{4}", 'ℏ' to "\\hbar",
        'ℓ' to "\\ell", '−' to "-", '′' to "'", '″' to "''",
    )

    private val namedFunctions = Regex("(?<![\\\\A-Za-z])(lim|sin|cos|tan|log|ln|exp|max|min)(?![A-Za-z])")

    fun convert(source: String): String {
        if (!looksMathematical(source)) return source
        val converted = convertRange(source, 0, source.length)
            .replace("<=", "\\le ")
            .replace(">=", "\\ge ")
            .replace("!=", "\\ne ")
        return namedFunctions.replace(converted) { "\\${it.value}" }
    }

    private fun looksMathematical(source: String): Boolean =
        source.any { it in symbols || it in superscripts || it in subscripts || it == '√' } ||
            namedFunctions.containsMatchIn(source) ||
            "<=" in source || ">=" in source || "!=" in source

    private fun convertRange(source: String, start: Int, end: Int): String {
        val out = StringBuilder(end - start + 12)
        var i = start
        while (i < end) {
            val char = source[i]
            val script = when {
                char in superscripts -> superscripts
                char in subscripts -> subscripts
                else -> null
            }
            if (script != null) {
                val marker = if (script === superscripts) '^' else '_'
                out.append(marker).append('{')
                while (i < end) {
                    val value = script[source[i]] ?: break
                    out.append(value)
                    i++
                }
                out.append('}')
                continue
            }
            if (char == '√') {
                val opening = i + 1
                if (opening < end && source[opening] == '(') {
                    val closing = matchingParenthesis(source, opening, end)
                    if (closing > opening) {
                        out.append("\\sqrt{")
                            .append(convertRange(source, opening + 1, closing))
                            .append('}')
                        i = closing + 1
                        continue
                    }
                }
                out.append("\\sqrt{}")
                i++
                continue
            }
            val replacement = symbols[char]
            if (replacement != null) {
                out.append(replacement)
                val next = source.getOrNull(i + 1)
                if (replacement.lastOrNull()?.isLetter() == true && next?.isAsciiLetter() == true) {
                    out.append(' ')
                }
            } else {
                out.append(char)
            }
            i++
        }
        return out.toString()
    }

    private fun matchingParenthesis(source: String, opening: Int, end: Int): Int {
        var depth = 0
        for (i in opening until end) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}
