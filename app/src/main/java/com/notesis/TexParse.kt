package com.notesis

/**
 * LaTeX formulas, read into a tree that [TexLayout] draws. It is the part of
 * LaTeX people write in notes - scripts, fractions, roots, Greek and the usual
 * symbols, big operators with limits, accents, \left ... \right, matrices,
 * cases and aligned lines, \text and the letter styles - not TeX itself: no
 * macros of one's own, no packages. Nothing written is ever refused: a
 * command it does not know is drawn as its name, in red, and the rest of the
 * formula is drawn as usual. Pure, so it is tested on the JVM.
 */
internal sealed class TexNode {
    /** One symbol or word: [text] as it is drawn, how it spaces ([kind]) and its letters' style. */
    data class Glyph(val text: String, val kind: TexKind, val font: TexFont = TexFont.AUTO) : TexNode()
    data class Row(val items: List<TexNode>) : TexNode()
    data class Scripts(val base: TexNode, val sup: TexNode?, val sub: TexNode?) : TexNode()
    /** A fraction; no [bar] for \binom's inside. [style] is set by \dfrac and \tfrac. */
    data class Fraction(val num: TexNode, val den: TexNode, val bar: Boolean = true, val style: TexStyle? = null) : TexNode()
    data class Radical(val body: TexNode, val index: TexNode?) : TexNode()
    data class Accent(val body: TexNode, val accent: TexAccent) : TexNode()
    /** [body] between delimiters grown to its height; "" for none (\left.). */
    data class Fenced(val left: String, val body: TexNode, val right: String) : TexNode()
    /** A delimiter at a fixed size (\big, \Big, \bigg, \Bigg): [scale] times the text's height. */
    data class Sized(val delimiter: String, val scale: Float, val kind: TexKind) : TexNode()
    /** \sum, \int, \lim ...: drawn larger in a display; [limits] puts its scripts over and under. */
    data class BigOp(val symbol: String, val limits: Boolean, val word: Boolean = false, val integral: Boolean = false) : TexNode()
    /** Rows of cells, each column [align]ed l, c or r; [gap] between columns in em; [spread] the space between rows. */
    data class Grid(
        val rows: List<List<TexNode>>, val align: List<Char>, val left: String, val right: String,
        val gap: Float, val style: TexStyle?, val spread: Float = 0.25f,
    ) : TexNode()
    data class Space(val em: Float) : TexNode()
    /** [body] in another colour and/or style (\color, \textcolor, \displaystyle ...). */
    data class Styled(val body: TexNode, val color: Int? = null, val style: TexStyle? = null) : TexNode()
    data class Boxed(val body: TexNode) : TexNode()
    /** \overset, \underset, \stackrel: [base] with small [over] above and/or [under] below. */
    data class Stack(val base: TexNode, val over: TexNode?, val under: TexNode?) : TexNode()
    /** What could not be read, drawn as written. */
    data class Error(val text: String) : TexNode()
}

/** How a piece spaces itself from its neighbours, as TeX's atom classes. */
internal enum class TexKind { ORD, OP, BIN, REL, OPEN, CLOSE, PUNCT, INNER }

/** How letters are drawn: AUTO is italic for a Latin letter or a small Greek one, upright otherwise. */
internal enum class TexFont { AUTO, ROMAN, ITALIC, BOLD, BOLD_ITALIC, BLACKBOARD, SCRIPT, FRAKTUR, SANS, MONO, TEXT, TEXT_BOLD, TEXT_ITALIC }

internal enum class TexStyle { DISPLAY, TEXT, SCRIPT, SCRIPTSCRIPT }

internal enum class TexAccent { HAT, WIDEHAT, CHECK, TILDE, WIDETILDE, BAR, OVERLINE, UNDERLINE, VEC, OVERARROW, OVERLEFTARROW, DOT, DDOT, BREVE, ACUTE, GRAVE, OVERBRACE, UNDERBRACE }

/** Reads [source]; never throws. */
internal fun parseTex(source: String): TexNode = TexReader(source).readAll()

private class TexReader(private val s: String) {
    private var i = 0
    /** How deep in braces this is, so a runaway formula cannot recurse without end. */
    private var depth = 0

    fun readAll(): TexNode {
        val (rows, _) = readRows(stopAtEnd = null, stopAtBrace = false)
        // Lines apart by \\ at the top are centred under each other, as in gathered.
        if (rows.size == 1 && rows[0].size == 1) return rows[0][0]
        val columns = rows.maxOf { it.size }
        val align = if (columns == 1) listOf('c') else List(columns) { if (it % 2 == 0) 'r' else 'l' }
        return TexNode.Grid(rows, align, "", "", 0f, TexStyle.DISPLAY, spread = 0.4f)
    }

    // ---- rows and cells ----------------------------------------------------

    /**
     * Cells apart by & and rows by \\, up to \end{[stopAtEnd]} or a closing
     * brace ([stopAtBrace]) or the end. Returns the rows and whether it ended
     * at what it was looking for.
     */
    private fun readRows(stopAtEnd: String?, stopAtBrace: Boolean): Pair<List<List<TexNode>>, Boolean> {
        val rows = mutableListOf<List<TexNode>>()
        var cells = mutableListOf<TexNode>()
        var row = mutableListOf<TexNode>()
        var closed = false
        while (true) {
            skipSpaces()
            if (i >= s.length) break
            val c = s[i]
            if (c == '}' ) {
                if (stopAtBrace) { i++; closed = true; break }
                i++; continue // a stray brace is left out
            }
            if (c == '&') { i++; cells += row(row); row = mutableListOf(); continue }
            if (s.startsWith("\\\\", i)) {
                i += 2
                skipOptional() // \\[2pt]
                cells += row(row); row = mutableListOf()
                rows += cells; cells = mutableListOf()
                continue
            }
            if (stopAtEnd != null && s.startsWith("\\end", i) && !isLetter(i + 4)) {
                val save = i
                i += 4
                val name = readBraced()
                if (name == stopAtEnd) { closed = true; break }
                i = save + 4 // another \end: shown as an error and carried on past
                row += TexNode.Error("\\end{$name}")
                continue
            }
            if (s.startsWith("\\right", i) && !isLetter(i + 6)) {
                // A \right with no \left: drawn as a plain delimiter.
                i += 6
                row += delimiterGlyph(readDelimiter(), TexKind.CLOSE)
                continue
            }
            readAtom(row)
        }
        if (row.isNotEmpty() || cells.isNotEmpty() || rows.isEmpty()) { cells += row(row); rows += cells }
        // A trailing \\ leaves an empty last row; it draws nothing.
        if (rows.size > 1 && rows.last().all { it == EMPTY }) rows.removeAt(rows.lastIndex)
        return rows to closed
    }

    private fun row(items: List<TexNode>): TexNode = when (items.size) {
        0 -> EMPTY
        1 -> items[0]
        else -> TexNode.Row(items.toList())
    }

    /** A group's content up to its closing brace, as one node. */
    private fun readGroupBody(): TexNode {
        if (depth > 60) { val rest = s.substring(i); i = s.length; return TexNode.Error(rest) }
        depth++
        val items = mutableListOf<TexNode>()
        while (true) {
            skipSpaces()
            if (i >= s.length) break
            if (s[i] == '}') { i++; break }
            if (s[i] == '&' || s.startsWith("\\\\", i)) {
                // Outside an environment these only break lines at the top; inside a group they are left out.
                i += if (s[i] == '&') 1 else 2
                continue
            }
            readAtom(items)
        }
        depth--
        return row(items)
    }

    // ---- atoms -------------------------------------------------------------

    /** One piece with its scripts, added to [into]. */
    private fun readAtom(into: MutableList<TexNode>) {
        val c = s[i]
        when {
            c == '^' || c == '_' -> {
                // Scripts with nothing before them sit on an empty base.
                val base = if (into.isNotEmpty()) into.removeAt(into.lastIndex) else EMPTY
                into += readScripts(base)
                return
            }
            c == '\'' -> {
                var primes = 0
                while (i < s.length && s[i] == '\'') { primes++; i++ }
                val base = if (into.isNotEmpty()) into.removeAt(into.lastIndex) else EMPTY
                into += attachPrimes(base, primes)
                return
            }
        }
        val node = readPiece() ?: return
        // \color{red} colours what follows it in the group.
        if (node is TexNode.Styled && node.body === EMPTY) {
            val rest = mutableListOf<TexNode>()
            while (true) {
                skipSpaces()
                if (i >= s.length || s[i] == '}' || s[i] == '&' || s.startsWith("\\\\", i) ||
                    s.startsWith("\\end", i) || s.startsWith("\\right", i)) break
                readAtom(rest)
            }
            into += node.copy(body = row(rest))
            return
        }
        skipSpaces()
        if (i < s.length && (s[i] == '^' || s[i] == '_' || s[i] == '\'')) {
            into += node
            readAtom(into)
            return
        }
        into += node
    }

    private fun attachPrimes(base: TexNode, primes: Int): TexNode {
        val prime = TexNode.Glyph("′".repeat(primes), TexKind.ORD, TexFont.ROMAN)
        return when {
            base is TexNode.Scripts && base.sup == null -> base.copy(sup = prime)
            base is TexNode.Scripts -> base.copy(sup = TexNode.Row(listOf(prime, base.sup!!)))
            else -> TexNode.Scripts(base, prime, null)
        }
    }

    /** ^ and _ after [base], in either order, and primes. */
    private fun readScripts(base: TexNode): TexNode {
        var sup: TexNode? = (base as? TexNode.Scripts)?.sup
        var sub: TexNode? = (base as? TexNode.Scripts)?.sub
        val core = (base as? TexNode.Scripts)?.base ?: base
        while (true) {
            skipSpaces()
            if (i >= s.length) break
            when (s[i]) {
                '^' -> { i++; val arg = readArgument(); sup = if (sup == null) arg else TexNode.Row(listOf(sup, arg)) }
                '_' -> { i++; val arg = readArgument(); sub = if (sub == null) arg else TexNode.Row(listOf(sub, arg)) }
                '\'' -> {
                    var primes = 0
                    while (i < s.length && s[i] == '\'') { primes++; i++ }
                    val prime = TexNode.Glyph("′".repeat(primes), TexKind.ORD, TexFont.ROMAN)
                    sup = if (sup == null) prime else TexNode.Row(listOf(sup, prime))
                }
                else -> break
            }
        }
        return TexNode.Scripts(core, sup, sub)
    }

    /** What a command or a character makes, or null for what draws nothing (a stray modifier). */
    private fun readPiece(): TexNode? {
        val c = s[i]
        if (c == '{') { i++; return readGroupBody().let { if (it is TexNode.Row || it === EMPTY) it else TexNode.Row(listOf(it)) } }
        if (c == '\\') return readCommand()
        i++
        if (c == '~') return TexNode.Space(0.33f)
        if (c.isHighSurrogate() && i < s.length) return TexNode.Glyph("$c${s[i++]}", TexKind.ORD, TexFont.ROMAN)
        if (c.isDigit() || (c == '.' && i < s.length && s[i].isDigit())) {
            // A number stays together, its decimal point with it.
            val start = i - 1
            while (i < s.length && (s[i].isDigit() || (s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit()))) i++
            return TexNode.Glyph(s.substring(start, i), TexKind.ORD, TexFont.ROMAN)
        }
        return charGlyph(c)
    }

    private fun readCommand(): TexNode? {
        i++ // the backslash
        if (i >= s.length) return TexNode.Glyph("\\", TexKind.ORD, TexFont.ROMAN)
        val name = if (s[i].isLetter() && s[i].code < 128) {
            val start = i
            while (i < s.length && s[i].isLetter() && s[i].code < 128) i++
            // \operatorname* and the like.
            if (i < s.length && s[i] == '*' && s.substring(start, i) in STARRED) i++
            s.substring(start, i)
        } else s[i++].toString()

        SPACES[name]?.let { return TexNode.Space(it) }
        SYMBOLS[name]?.let { (text, kind) -> return TexNode.Glyph(text, kind, if (name in GREEK_LOWER) TexFont.AUTO else TexFont.ROMAN) }
        FUNCTIONS[name]?.let { return TexNode.Glyph(it, TexKind.OP, TexFont.ROMAN) }
        LIMIT_WORDS[name]?.let { return limitsModifier(TexNode.BigOp(it, limits = true, word = true)) }
        BIG_OPERATORS[name]?.let { symbol ->
            val integral = name in INTEGRALS
            return limitsModifier(TexNode.BigOp(symbol, limits = !integral, integral = integral))
        }
        FONTS[name]?.let { font ->
            return if (font == TexFont.TEXT || font == TexFont.TEXT_BOLD || font == TexFont.TEXT_ITALIC) {
                TexNode.Glyph(readRawBraced().ifEmpty { " " }, TexKind.ORD, font)
            } else withFont(readArgument(), font)
        }
        ACCENTS[name]?.let { return TexNode.Accent(readArgument(), it) }
        BIG_SIZES[name]?.let { (scale, kind) -> return TexNode.Sized(readDelimiter(), scale, kind) }
        STYLES[name]?.let { return TexNode.Styled(EMPTY, style = it) }
        return when (name) {
            "frac", "cfrac" -> TexNode.Fraction(readArgument(), readArgument())
            "dfrac" -> TexNode.Fraction(readArgument(), readArgument(), style = TexStyle.DISPLAY)
            "tfrac" -> TexNode.Fraction(readArgument(), readArgument(), style = TexStyle.TEXT)
            "binom" -> TexNode.Fenced("(", TexNode.Fraction(readArgument(), readArgument(), bar = false), ")")
            "dbinom" -> TexNode.Fenced("(", TexNode.Fraction(readArgument(), readArgument(), bar = false, style = TexStyle.DISPLAY), ")")
            "tbinom" -> TexNode.Fenced("(", TexNode.Fraction(readArgument(), readArgument(), bar = false, style = TexStyle.TEXT), ")")
            "sqrt" -> {
                skipSpaces()
                val index = if (i < s.length && s[i] == '[') readOptionalNode() else null
                TexNode.Radical(readArgument(), index)
            }
            "left" -> readFenced()
            "middle" -> delimiterGlyph(readDelimiter(), TexKind.REL)
            "begin" -> readEnvironment(readBraced())
            "operatorname" -> TexNode.Glyph(readRawBraced().ifEmpty { "?" }, TexKind.OP, TexFont.ROMAN)
            "operatorname*" -> limitsModifier(TexNode.BigOp(readRawBraced().ifEmpty { "?" }, limits = true, word = true))
            "color" -> TexNode.Styled(EMPTY, color = texColor(readRawBraced()))
            "textcolor" -> {
                val color = texColor(readRawBraced())
                // An empty {} is a node of its own, not the marker that colours the rest.
                TexNode.Styled(readArgument().let { if (it === EMPTY) TexNode.Row(emptyList()) else it }, color = color)
            }
            "boxed", "fbox" -> TexNode.Boxed(readArgument())
            "overset", "stackrel" -> { val over = readArgument(); TexNode.Stack(readArgument(), over, null) }
            "underset" -> { val under = readArgument(); TexNode.Stack(readArgument(), null, under) }
            "pmod" -> TexNode.Row(listOf(TexNode.Space(0.6f), TexNode.Glyph("(", TexKind.OPEN, TexFont.ROMAN),
                TexNode.Glyph("mod", TexKind.ORD, TexFont.ROMAN), TexNode.Space(0.33f), readArgument(),
                TexNode.Glyph(")", TexKind.CLOSE, TexFont.ROMAN)))
            "bmod" -> TexNode.Glyph("mod", TexKind.BIN, TexFont.ROMAN)
            "mod" -> TexNode.Row(listOf(TexNode.Space(0.6f), TexNode.Glyph("mod", TexKind.ORD, TexFont.ROMAN), TexNode.Space(0.33f)))
            "not" -> {
                skipSpaces()
                val next = if (i < s.length) readPiece() else null
                when {
                    next is TexNode.Glyph && next.text == "=" -> TexNode.Glyph("≠", TexKind.REL, TexFont.ROMAN)
                    next is TexNode.Glyph && next.text == "∈" -> TexNode.Glyph("∉", TexKind.REL, TexFont.ROMAN)
                    next is TexNode.Glyph -> next.copy(text = next.text + "̸")
                    else -> next
                }
            }
            "limits", "nolimits", "displaylimits" -> null // only after a big operator, read there
            "hspace", "hspace*", "vspace", "phantom", "label", "tag", "notag", "nonumber" -> { readRawBraced(); null }
            "quad" -> TexNode.Space(1f)
            "qquad" -> TexNode.Space(2f)
            "{" -> TexNode.Glyph("{", TexKind.OPEN, TexFont.ROMAN)
            "}" -> TexNode.Glyph("}", TexKind.CLOSE, TexFont.ROMAN)
            "|" -> TexNode.Glyph("‖", TexKind.ORD, TexFont.ROMAN)
            "\\" -> null
            "%", "$", "&", "#", "_" -> TexNode.Glyph(name, TexKind.ORD, TexFont.ROMAN)
            else -> TexNode.Error("\\$name")
        }
    }

    /** \limits or \nolimits right after a big operator. */
    private fun limitsModifier(op: TexNode.BigOp): TexNode.BigOp {
        skipSpaces()
        return when {
            s.startsWith("\\limits", i) && !isLetter(i + 7) -> { i += 7; op.copy(limits = true) }
            s.startsWith("\\nolimits", i) && !isLetter(i + 9) -> { i += 9; op.copy(limits = false) }
            s.startsWith("\\displaylimits", i) && !isLetter(i + 14) -> { i += 14; op }
            else -> op
        }
    }

    private fun readFenced(): TexNode {
        val left = readDelimiter()
        depth++
        val items = mutableListOf<TexNode>()
        var right = ""
        while (true) {
            skipSpaces()
            if (i >= s.length) break
            if (s.startsWith("\\right", i) && !isLetter(i + 6)) { i += 6; right = readDelimiter(); break }
            if (s[i] == '}') break // the group ends first: the \right was left out
            if (s[i] == '&' || s.startsWith("\\\\", i) || (s.startsWith("\\end", i) && !isLetter(i + 4))) break
            readAtom(items)
        }
        depth--
        return TexNode.Fenced(left, row(items), right)
    }

    private fun readEnvironment(name: String): TexNode {
        val bare = name.removeSuffix("*")
        var spec: String? = null
        if (bare == "array" || bare == "subarray") spec = readRawBraced()
        if (bare == "alignat") readRawBraced()
        val (rows, _) = readRows(stopAtEnd = name, stopAtBrace = false)
        val columns = rows.maxOf { it.size }.coerceAtLeast(1)
        fun all(c: Char) = List(columns) { c }
        val alternating = List(columns) { if (it % 2 == 0) 'r' else 'l' }
        return when (bare) {
            "matrix" -> TexNode.Grid(rows, all('c'), "", "", 1f, TexStyle.TEXT)
            "pmatrix" -> TexNode.Grid(rows, all('c'), "(", ")", 1f, TexStyle.TEXT)
            "bmatrix" -> TexNode.Grid(rows, all('c'), "[", "]", 1f, TexStyle.TEXT)
            "Bmatrix" -> TexNode.Grid(rows, all('c'), "{", "}", 1f, TexStyle.TEXT)
            "vmatrix" -> TexNode.Grid(rows, all('c'), "|", "|", 1f, TexStyle.TEXT)
            "Vmatrix" -> TexNode.Grid(rows, all('c'), "‖", "‖", 1f, TexStyle.TEXT)
            "smallmatrix" -> TexNode.Grid(rows, all('c'), "", "", 0.5f, TexStyle.SCRIPT, spread = 0.1f)
            "cases" -> TexNode.Grid(rows, all('l'), "{", "", 1f, TexStyle.TEXT)
            "dcases" -> TexNode.Grid(rows, all('l'), "{", "", 1f, TexStyle.DISPLAY, spread = 0.4f)
            "rcases" -> TexNode.Grid(rows, all('l'), "", "}", 1f, TexStyle.TEXT)
            "aligned", "align", "split", "alignat", "eqnarray" -> TexNode.Grid(rows, alternating, "", "", 0f, TexStyle.DISPLAY, spread = 0.4f)
            "gathered", "gather", "multline", "equation" -> TexNode.Grid(rows, all('c'), "", "", 0f, TexStyle.DISPLAY, spread = 0.4f)
            "array", "subarray" -> {
                val aligns = spec.orEmpty().filter { it == 'l' || it == 'c' || it == 'r' }.toList()
                TexNode.Grid(rows, List(columns) { aligns.getOrElse(it) { 'c' } }, "", "", 1f, TexStyle.TEXT)
            }
            else -> TexNode.Row(listOf(TexNode.Error("\\begin{$name}"),
                TexNode.Grid(rows, all('c'), "", "", 1f, TexStyle.TEXT)))
        }
    }

    // ---- arguments ---------------------------------------------------------

    /** A command's argument: a {group}, or the one piece after it (\frac12). */
    private fun readArgument(): TexNode {
        skipSpaces()
        if (i >= s.length) return EMPTY
        val c = s[i]
        if (c == '{') { i++; return readGroupBody() }
        if (c == '}' || c == '&' || c == '^' || c == '_') return EMPTY
        if (c == '\\') return readCommand() ?: EMPTY
        if (c.isHighSurrogate() && i + 1 < s.length) { i += 2; return TexNode.Glyph(s.substring(i - 2, i), TexKind.ORD, TexFont.ROMAN) }
        i++
        return if (c.isDigit()) TexNode.Glyph(c.toString(), TexKind.ORD, TexFont.ROMAN) else charGlyph(c)
    }

    /** [x] after \sqrt, read as a formula. */
    private fun readOptionalNode(): TexNode {
        i++ // [
        val items = mutableListOf<TexNode>()
        while (true) {
            skipSpaces()
            if (i >= s.length) break
            if (s[i] == ']') { i++; break }
            readAtom(items)
        }
        return row(items)
    }

    private fun skipOptional() {
        skipSpaces()
        if (i < s.length && s[i] == '[') {
            val end = s.indexOf(']', i)
            i = if (end < 0) s.length else end + 1
        }
    }

    /** A {name}'s letters as written, spaces and all; a single character when there are no braces. */
    private fun readRawBraced(): String {
        skipSpaces()
        if (i >= s.length) return ""
        if (s[i] != '{') return s[i++].toString()
        var level = 0
        val start = i + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++ // \{ inside stays a brace of the text
                '{' -> level++
                '}' -> { level--; if (level == 0) { i++; return unescapeTexText(s.substring(start, i - 1)) } }
            }
            i++
        }
        return unescapeTexText(s.substring(start))
    }

    private fun readBraced(): String = readRawBraced().trim()

    /** The delimiter after \left, \right or \big: a character, or a command naming one; "" for ".". */
    private fun readDelimiter(): String {
        skipSpaces()
        if (i >= s.length) return ""
        val c = s[i]
        if (c == '\\') {
            val start = ++i
            if (i < s.length && !s[i].isLetter()) { i++ } else while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i)
            return DELIMITERS[name] ?: SYMBOLS[name]?.first ?: ""
        }
        i++
        return when (c) { '.' -> ""; '<' -> "⟨"; '>' -> "⟩"; else -> c.toString() }
    }

    private fun delimiterGlyph(d: String, kind: TexKind): TexNode =
        if (d.isEmpty()) EMPTY else TexNode.Glyph(d, kind, TexFont.ROMAN)

    private fun skipSpaces() {
        // % is not a comment here: in a note "50%" means per cent, and dropping the rest would lose it.
        while (i < s.length && (s[i].isWhitespace())) i++
    }

    private fun isLetter(at: Int) = at < s.length && s[at].isLetter() && s[at].code < 128
}

private val EMPTY = TexNode.Row(emptyList())

/** A character typed as itself: its spacing class, and whether it is a letter that slants. */
private fun charGlyph(c: Char): TexNode {
    val text = when (c) { '-' -> "−"; '*' -> "∗"; '<' -> "<"; '>' -> ">"; else -> c.toString() }
    val kind = when (c) {
        '+', '-', '*', '±', '×', '÷', '·', '∘', '∪', '∩', '∓', '⊕', '⊗' -> TexKind.BIN
        '=', '<', '>', ':', '≤', '≥', '≠', '≈', '≡', '∼', '→', '←', '⇒', '⇔', '∈', '∉', '⊂', '⊃', '⊆', '⊇', '∝', '↦' -> TexKind.REL
        ',', ';' -> TexKind.PUNCT
        '(', '[' -> TexKind.OPEN
        ')', ']', '!', '?' -> TexKind.CLOSE
        else -> TexKind.ORD
    }
    val font = if (c in 'a'..'z' || c in 'A'..'Z' || c in 'α'..'ω') TexFont.AUTO else TexFont.ROMAN
    return TexNode.Glyph(text, kind, font)
}

/** [node] with its letters in [font] (\mathbf{x}, \mathbb{R}). */
private fun withFont(node: TexNode, font: TexFont): TexNode = when (node) {
    is TexNode.Glyph -> if (node.font == TexFont.AUTO || node.font == TexFont.ROMAN) node.copy(font = font) else node
    is TexNode.Row -> TexNode.Row(node.items.map { withFont(it, font) })
    is TexNode.Scripts -> node.copy(base = withFont(node.base, font))
    is TexNode.Fraction -> node.copy(num = withFont(node.num, font), den = withFont(node.den, font))
    is TexNode.Accent -> node.copy(body = withFont(node.body, font))
    is TexNode.Styled -> node.copy(body = withFont(node.body, font))
    else -> node
}

private fun unescapeTexText(text: String): String =
    text.replace("\\{", "{").replace("\\}", "}").replace("\\%", "%").replace("\\$", "$").replace("\\&", "&")
        .replace("\\_", "_").replace("\\#", "#").replace("~", " ").replace("\\ ", " ")

/** A colour LaTeX names (red, blue, ...) or #RRGGBB / RRGGBB; black when it is neither. */
internal fun texColor(name: String): Int {
    val n = name.trim().lowercase()
    TEX_COLORS[n]?.let { return it }
    val hex = n.removePrefix("#")
    if (hex.length == 6 && hex.all { it in '0'..'9' || it in 'a'..'f' }) return (0xFF000000 or hex.toLong(16)).toInt()
    return 0xFF000000.toInt()
}

private val TEX_COLORS = mapOf(
    "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFD32F2F.toInt(), "green" to 0xFF2E7D32.toInt(),
    "blue" to 0xFF1565C0.toInt(), "cyan" to 0xFF00838F.toInt(), "magenta" to 0xFFC2185B.toInt(), "yellow" to 0xFFF9A825.toInt(),
    "orange" to 0xFFEF6C00.toInt(), "purple" to 0xFF6A1B9A.toInt(), "violet" to 0xFF7B1FA2.toInt(), "brown" to 0xFF6D4C41.toInt(),
    "gray" to 0xFF757575.toInt(), "grey" to 0xFF757575.toInt(), "darkgray" to 0xFF424242.toInt(), "lightgray" to 0xFFBDBDBD.toInt(),
    "pink" to 0xFFEC407A.toInt(), "teal" to 0xFF00796B.toInt(), "olive" to 0xFF827717.toInt(), "lime" to 0xFF9E9D24.toInt(),
)

private val SPACES = mapOf("," to 3f / 18, "thinspace" to 3f / 18, ":" to 4f / 18, ">" to 4f / 18, "medspace" to 4f / 18,
    ";" to 5f / 18, "thickspace" to 5f / 18, "!" to -3f / 18, "negthinspace" to -3f / 18, " " to 0.33f, "enspace" to 0.5f)

private val STARRED = setOf("operatorname", "hspace", "vspace")

private val GREEK_LOWER = setOf("alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "zeta", "eta", "theta", "vartheta",
    "iota", "kappa", "lambda", "mu", "nu", "xi", "pi", "varpi", "rho", "varrho", "sigma", "varsigma", "tau", "upsilon", "phi",
    "varphi", "chi", "psi", "omega")

/** Commands that are one symbol: what is drawn, and how it spaces. */
private val SYMBOLS: Map<String, Pair<String, TexKind>> = buildMap {
    fun ord(vararg pairs: Pair<String, String>) = pairs.forEach { (k, v) -> put(k, v to TexKind.ORD) }
    fun bin(vararg pairs: Pair<String, String>) = pairs.forEach { (k, v) -> put(k, v to TexKind.BIN) }
    fun rel(vararg pairs: Pair<String, String>) = pairs.forEach { (k, v) -> put(k, v to TexKind.REL) }
    ord("alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ϵ", "varepsilon" to "ε", "zeta" to "ζ",
        "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
        "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ",
        "upsilon" to "υ", "phi" to "ϕ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ",
        "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃", "nexists" to "∄",
        "emptyset" to "∅", "varnothing" to "∅", "hbar" to "ℏ", "ell" to "ℓ", "aleph" to "ℵ", "beth" to "ℶ", "Re" to "ℜ", "Im" to "ℑ",
        "wp" to "℘", "angle" to "∠", "measuredangle" to "∡", "triangle" to "△", "square" to "□", "Box" to "□", "blacksquare" to "■",
        "diamond" to "◇", "Diamond" to "◇", "lozenge" to "◊", "star" to "⋆", "degree" to "°", "circ" to "∘", "prime" to "′",
        "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱", "neg" to "¬", "lnot" to "¬",
        "top" to "⊤", "bot" to "⊥", "flat" to "♭", "natural" to "♮", "sharp" to "♯", "clubsuit" to "♣", "diamondsuit" to "♢",
        "heartsuit" to "♡", "spadesuit" to "♠", "checkmark" to "✓", "dagger" to "†", "ddagger" to "‡", "S" to "§", "P" to "¶",
        "imath" to "ı", "jmath" to "ȷ", "backslash" to "∖", "vert" to "|", "Vert" to "‖", "surd" to "√", "colon" to ":",
        "therefore" to "∴", "because" to "∵", "copyright" to "©", "pounds" to "£", "euro" to "€", "yen" to "¥")
    bin("pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷", "cdot" to "⋅", "ast" to "∗", "bullet" to "∙", "cap" to "∩",
        "cup" to "∪", "sqcap" to "⊓", "sqcup" to "⊔", "wedge" to "∧", "land" to "∧", "vee" to "∨", "lor" to "∨",
        "oplus" to "⊕", "ominus" to "⊖", "otimes" to "⊗", "oslash" to "⊘", "odot" to "⊙", "setminus" to "∖", "smallsetminus" to "∖",
        "uplus" to "⊎", "amalg" to "⨿", "wr" to "≀", "bigcirc" to "◯", "triangleleft" to "◁", "triangleright" to "▷",
        "lhd" to "⊲", "rhd" to "⊳", "ltimes" to "⋉", "rtimes" to "⋊", "divideontimes" to "⋇", "dotplus" to "∔")
    rel("le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "leqslant" to "⩽", "geqslant" to "⩾", "ne" to "≠", "neq" to "≠",
        "lt" to "<", "gt" to ">", "approx" to "≈", "approxeq" to "≊", "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "cong" to "≅",
        "propto" to "∝", "asymp" to "≍", "doteq" to "≐", "ll" to "≪", "gg" to "≫", "lll" to "⋘", "ggg" to "⋙", "prec" to "≺",
        "succ" to "≻", "preceq" to "⪯", "succeq" to "⪰", "in" to "∈", "notin" to "∉", "ni" to "∋", "owns" to "∋",
        "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇", "subsetneq" to "⊊", "supsetneq" to "⊋",
        "nsubseteq" to "⊈", "sqsubseteq" to "⊑", "sqsupseteq" to "⊒", "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "gets" to "←", "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
        "implies" to "⟹", "impliedby" to "⟸", "iff" to "⟺", "longrightarrow" to "⟶", "longleftarrow" to "⟵",
        "Longrightarrow" to "⟹", "Longleftarrow" to "⟸", "longleftrightarrow" to "⟷", "Longleftrightarrow" to "⟺",
        "mapsto" to "↦", "longmapsto" to "⟼", "uparrow" to "↑", "downarrow" to "↓", "updownarrow" to "↕", "Uparrow" to "⇑",
        "Downarrow" to "⇓", "nearrow" to "↗", "searrow" to "↘", "swarrow" to "↙", "nwarrow" to "↖", "hookrightarrow" to "↪",
        "hookleftarrow" to "↩", "rightharpoonup" to "⇀", "leftharpoonup" to "↼", "rightleftharpoons" to "⇌",
        "leadsto" to "⇝", "mid" to "∣", "nmid" to "∤", "parallel" to "∥", "nparallel" to "∦", "perp" to "⊥", "models" to "⊨",
        "vdash" to "⊢", "dashv" to "⊣", "vDash" to "⊨", "bowtie" to "⋈", "smile" to "⌣", "frown" to "⌢", "nless" to "≮",
        "ngtr" to "≯", "nleq" to "≰", "ngeq" to "≱", "nsim" to "≁", "ncong" to "≇", "triangleq" to "≜", "coloneqq" to "≔",
        "eqqcolon" to "≕", "lesssim" to "≲", "gtrsim" to "≳", "sqsubset" to "⊏", "sqsupset" to "⊐")
    put("langle", "⟨" to TexKind.OPEN); put("rangle", "⟩" to TexKind.CLOSE)
    put("lbrace", "{" to TexKind.OPEN); put("rbrace", "}" to TexKind.CLOSE)
    put("lbrack", "[" to TexKind.OPEN); put("rbrack", "]" to TexKind.CLOSE)
    put("lfloor", "⌊" to TexKind.OPEN); put("rfloor", "⌋" to TexKind.CLOSE)
    put("lceil", "⌈" to TexKind.OPEN); put("rceil", "⌉" to TexKind.CLOSE)
    put("lvert", "|" to TexKind.OPEN); put("rvert", "|" to TexKind.CLOSE)
    put("lVert", "‖" to TexKind.OPEN); put("rVert", "‖" to TexKind.CLOSE)
}

private val DELIMITERS = mapOf("{" to "{", "}" to "}", "|" to "‖", "langle" to "⟨", "rangle" to "⟩", "lbrace" to "{",
    "rbrace" to "}", "lbrack" to "[", "rbrack" to "]", "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉",
    "vert" to "|", "Vert" to "‖", "lvert" to "|", "rvert" to "|", "lVert" to "‖", "rVert" to "‖", "backslash" to "\\",
    "uparrow" to "↑", "downarrow" to "↓")

/** Upright function names, spaced as operators. */
private val FUNCTIONS = listOf("sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "arccot", "sinh", "cosh",
    "tanh", "coth", "sech", "csch", "log", "ln", "lg", "exp", "deg", "dim", "ker", "arg", "hom", "sgn", "tr", "rank", "span", "Tr")
    .associateWith { it } + mapOf("Pr" to "Pr")

/** Words whose scripts go over and under in a display, as \lim's do. */
private val LIMIT_WORDS = listOf("lim", "liminf", "limsup", "max", "min", "sup", "inf", "det", "gcd", "lcm", "argmax", "argmin")
    .associateWith { when (it) { "liminf" -> "lim inf"; "limsup" -> "lim sup"; "argmax" -> "arg max"; "argmin" -> "arg min"; else -> it } }

private val BIG_OPERATORS = mapOf("sum" to "∑", "prod" to "∏", "coprod" to "∐", "bigcup" to "⋃", "bigcap" to "⋂",
    "bigvee" to "⋁", "bigwedge" to "⋀", "bigoplus" to "⨁", "bigotimes" to "⨂", "bigodot" to "⨀", "biguplus" to "⨄",
    "bigsqcup" to "⨆", "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮", "oiint" to "∯", "intop" to "∫", "smallint" to "∫")

private val INTEGRALS = setOf("int", "iint", "iiint", "oint", "oiint", "intop", "smallint")

private val FONTS = mapOf("mathrm" to TexFont.ROMAN, "rm" to TexFont.ROMAN, "mathup" to TexFont.ROMAN, "mathit" to TexFont.ITALIC,
    "mathbf" to TexFont.BOLD, "bf" to TexFont.BOLD, "boldsymbol" to TexFont.BOLD_ITALIC, "bm" to TexFont.BOLD_ITALIC,
    "mathbb" to TexFont.BLACKBOARD, "Bbb" to TexFont.BLACKBOARD, "mathcal" to TexFont.SCRIPT, "mathscr" to TexFont.SCRIPT,
    "mathfrak" to TexFont.FRAKTUR, "mathsf" to TexFont.SANS, "mathtt" to TexFont.MONO, "text" to TexFont.TEXT,
    "textrm" to TexFont.TEXT, "textnormal" to TexFont.TEXT, "mbox" to TexFont.TEXT, "textbf" to TexFont.TEXT_BOLD,
    "textit" to TexFont.TEXT_ITALIC, "emph" to TexFont.TEXT_ITALIC, "textsf" to TexFont.TEXT, "texttt" to TexFont.TEXT)

private val ACCENTS = mapOf("hat" to TexAccent.HAT, "widehat" to TexAccent.WIDEHAT, "check" to TexAccent.CHECK,
    "tilde" to TexAccent.TILDE, "widetilde" to TexAccent.WIDETILDE, "bar" to TexAccent.BAR, "overline" to TexAccent.OVERLINE,
    "underline" to TexAccent.UNDERLINE, "vec" to TexAccent.VEC, "overrightarrow" to TexAccent.OVERARROW,
    "overleftarrow" to TexAccent.OVERLEFTARROW, "dot" to TexAccent.DOT, "ddot" to TexAccent.DDOT, "breve" to TexAccent.BREVE,
    "acute" to TexAccent.ACUTE, "grave" to TexAccent.GRAVE, "overbrace" to TexAccent.OVERBRACE, "underbrace" to TexAccent.UNDERBRACE)

private val BIG_SIZES = buildMap {
    for ((word, scale) in listOf("big" to 1.2f, "Big" to 1.8f, "bigg" to 2.4f, "Bigg" to 3f)) {
        put(word, scale to TexKind.ORD); put(word + "l", scale to TexKind.OPEN)
        put(word + "r", scale to TexKind.CLOSE); put(word + "m", scale to TexKind.REL)
    }
}

private val STYLES = mapOf("displaystyle" to TexStyle.DISPLAY, "textstyle" to TexStyle.TEXT,
    "scriptstyle" to TexStyle.SCRIPT, "scriptscriptstyle" to TexStyle.SCRIPTSCRIPT)

/** The double-struck (\mathbb) form of [c]: the letterlike ones where Unicode has them, else the mathematical alphabet. */
internal fun blackboardOf(c: Char): String = when (c) {
    'C' -> "ℂ"; 'H' -> "ℍ"; 'N' -> "ℕ"; 'P' -> "ℙ"; 'Q' -> "ℚ"; 'R' -> "ℝ"; 'Z' -> "ℤ"
    in 'A'..'Z' -> String(Character.toChars(0x1D538 + (c - 'A')))
    in 'a'..'z' -> String(Character.toChars(0x1D552 + (c - 'a')))
    in '0'..'9' -> String(Character.toChars(0x1D7D8 + (c - '0')))
    else -> c.toString()
}

/** The script (\mathcal) form of a capital, from the letterlike block where it is there. */
internal fun scriptOf(c: Char): String = when (c) {
    'B' -> "ℬ"; 'E' -> "ℰ"; 'F' -> "ℱ"; 'H' -> "ℋ"; 'I' -> "ℐ"; 'L' -> "ℒ"; 'M' -> "ℳ"; 'R' -> "ℛ"
    'e' -> "ℯ"; 'g' -> "ℊ"; 'o' -> "ℴ"
    in 'A'..'Z' -> String(Character.toChars(0x1D49C + (c - 'A')))
    in 'a'..'z' -> String(Character.toChars(0x1D4B6 + (c - 'a')))
    else -> c.toString()
}

/** The Fraktur (\mathfrak) form, likewise. */
internal fun frakturOf(c: Char): String = when (c) {
    'C' -> "ℭ"; 'H' -> "ℌ"; 'I' -> "ℑ"; 'R' -> "ℜ"; 'Z' -> "ℨ"
    in 'A'..'Z' -> String(Character.toChars(0x1D504 + (c - 'A')))
    in 'a'..'z' -> String(Character.toChars(0x1D51E + (c - 'a')))
    else -> c.toString()
}
