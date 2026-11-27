package com.notesis

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A formula laid out: [width] across, [ascent] above its baseline and
 * [descent] below, both positive. [italic] is how far a slanted last letter
 * leans past [width], where a superscript after it starts.
 */
internal abstract class TexBox {
    var width = 0f
    var ascent = 0f
    var descent = 0f
    open val italic: Float get() = 0f
    /** Draws with the baseline's left end at ([x], [y]). */
    abstract fun draw(canvas: Canvas, x: Float, y: Float)
}

/**
 * [source] (without its $ marks) laid out at [size] pixels in [color]; a
 * [display] formula is set as $$...$$ is, larger operators and limits under
 * them. Text in \text{} takes [textFace], the box's own font.
 */
internal fun layoutTex(source: String, size: Float, color: Int, display: Boolean, textFace: Typeface? = null): TexBox =
    TexLayouter(size, textFace).layout(parseTex(source), TexCtx(if (display) TexStyle.DISPLAY else TexStyle.TEXT, color))

/**
 * A formula inside text: it takes the room it needs on its line and draws
 * itself in the text's size and colour, so a heading's formula is larger and
 * a coloured line's formula takes its colour.
 */
internal class TexSpan(val source: String, val display: Boolean) : ReplacementSpan() {
    private var box: TexBox? = null
    private var boxSize = 0f
    private var boxColor = 0
    private var boxFace: Typeface? = null

    private fun boxFor(paint: Paint): TexBox {
        val cached = box
        if (cached != null && boxSize == paint.textSize && boxColor == paint.color && boxFace == paint.typeface) return cached
        return layoutTex(source, paint.textSize, paint.color, display, paint.typeface).also {
            box = it; boxSize = paint.textSize; boxColor = paint.color; boxFace = paint.typeface
        }
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val laid = boxFor(paint)
        // A display formula keeps some air above and below, so formulas on lines of their own do not touch.
        val margin = paint.textSize * if (display) 0.3f else 0.08f
        if (fm != null) {
            val own = paint.fontMetricsInt
            fm.ascent = min(min(fm.ascent, own.ascent), -ceil(laid.ascent + margin).toInt())
            fm.top = min(min(fm.top, own.top), fm.ascent)
            fm.descent = max(max(fm.descent, own.descent), ceil(laid.descent + margin).toInt())
            fm.bottom = max(max(fm.bottom, own.bottom), fm.descent)
        }
        return ceil(laid.width + pad(paint) * 2).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        boxFor(paint).draw(canvas, x + pad(paint), y.toFloat())
    }

    /** A little room either side of a formula in a line, as TeX's math surround; none around a display. */
    private fun pad(paint: Paint) = if (display) 0f else paint.textSize * 0.06f
}

// ---- laying out ---------------------------------------------------------------

private data class TexCtx(val style: TexStyle, val color: Int) {
    fun scale() = when (style) { TexStyle.DISPLAY, TexStyle.TEXT -> 1f; TexStyle.SCRIPT -> 0.7f; TexStyle.SCRIPTSCRIPT -> 0.5f }
    fun script() = copy(style = if (style == TexStyle.DISPLAY || style == TexStyle.TEXT) TexStyle.SCRIPT else TexStyle.SCRIPTSCRIPT)
    fun scriptScript() = copy(style = TexStyle.SCRIPTSCRIPT)
    fun fractionInside() = copy(style = when (style) {
        TexStyle.DISPLAY -> TexStyle.TEXT; TexStyle.TEXT -> TexStyle.SCRIPT; else -> TexStyle.SCRIPTSCRIPT
    })
    /** The smaller of this and [other], as a matrix written in a superscript stays small. */
    fun atMost(other: TexStyle) = copy(style = if (other.ordinal > style.ordinal) other else style)
}

private class TexLayouter(private val base: Float, private val textFace: Typeface?) {
    private fun size(ctx: TexCtx) = max(base * ctx.scale(), 4f)

    fun layout(node: TexNode, ctx: TexCtx): TexBox = when (node) {
        is TexNode.Glyph -> glyph(node, ctx)
        is TexNode.Row -> row(node.items, ctx, afterOrd = false)
        is TexNode.Scripts -> scripts(node, ctx)
        is TexNode.Fraction -> fraction(node, ctx)
        is TexNode.Radical -> radical(node, ctx)
        is TexNode.Accent -> accent(node, ctx)
        is TexNode.Fenced -> fenced(node, ctx)
        is TexNode.Sized -> delimiter(node.delimiter, node.scale * size(ctx), ctx)
        is TexNode.BigOp -> bigOp(node, ctx)
        is TexNode.Grid -> grid(node, ctx)
        is TexNode.Space -> SpaceBox(node.em * size(ctx))
        is TexNode.Styled -> layout(node.body, ctx.copy(color = node.color ?: ctx.color).let { c -> node.style?.let { c.copy(style = it) } ?: c })
        is TexNode.Boxed -> boxed(node, ctx)
        is TexNode.Stack -> stack(node, ctx)
        is TexNode.Error -> GlyphBox(node.text, paint(ctx, Typeface.create(Typeface.SERIF, Typeface.NORMAL), ERROR_RED))
    }

    // ---- glyphs ----------------------------------------------------------

    private fun paint(ctx: TexCtx, face: Typeface, color: Int = ctx.color) =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = face; textSize = size(ctx); this.color = color
        }

    private fun glyph(node: TexNode.Glyph, ctx: TexCtx): TexBox {
        val text = node.text
        val slants = text.length == 1 && (text[0] in 'a'..'z' || text[0] in 'A'..'Z' || text[0] in 'α'..'ω' || text[0] in "ϵϑϖϱςϕ")
        return when (node.font) {
            TexFont.AUTO -> GlyphBox(text, paint(ctx, if (slants) SERIF_ITALIC else SERIF), slanted = slants)
            TexFont.ROMAN -> GlyphBox(text, paint(ctx, SERIF))
            TexFont.ITALIC -> GlyphBox(text, paint(ctx, SERIF_ITALIC), slanted = true)
            TexFont.BOLD -> GlyphBox(text, paint(ctx, SERIF_BOLD))
            TexFont.BOLD_ITALIC -> GlyphBox(text, paint(ctx, if (slants) SERIF_BOLD_ITALIC else SERIF_BOLD), slanted = slants)
            TexFont.BLACKBOARD -> mapped(text, ctx, ::blackboardOf, SERIF_BOLD)
            TexFont.SCRIPT -> mapped(text, ctx, ::scriptOf, SERIF_ITALIC)
            TexFont.FRAKTUR -> mapped(text, ctx, ::frakturOf, SERIF)
            TexFont.SANS -> GlyphBox(text, paint(ctx, Typeface.SANS_SERIF))
            TexFont.MONO -> GlyphBox(text, paint(ctx, Typeface.MONOSPACE))
            TexFont.TEXT -> GlyphBox(text, paint(ctx, textFace ?: Typeface.DEFAULT))
            TexFont.TEXT_BOLD -> GlyphBox(text, paint(ctx, Typeface.create(textFace ?: Typeface.DEFAULT, Typeface.BOLD)))
            TexFont.TEXT_ITALIC -> GlyphBox(text, paint(ctx, Typeface.create(textFace ?: Typeface.DEFAULT, Typeface.ITALIC)), slanted = true)
        }
    }

    /** Letters in a mathematical alphabet where the fonts on the device have it, else in [fallback]. */
    private fun mapped(text: String, ctx: TexCtx, map: (Char) -> String, fallback: Typeface): TexBox {
        val p = paint(ctx, SERIF)
        val out = StringBuilder()
        var plain = false
        for (c in text) {
            val m = map(c)
            if (m != c.toString() && p.hasGlyph(m)) out.append(m) else { out.append(c); if (m != c.toString()) plain = true }
        }
        return GlyphBox(out.toString(), if (plain) paint(ctx, fallback) else p)
    }

    // ---- rows ------------------------------------------------------------

    /** Pieces side by side with TeX's spacing between them. [afterOrd]: as if something stood before the first. */
    fun row(items: List<TexNode>, ctx: TexCtx, afterOrd: Boolean): TexBox {
        if (items.isEmpty()) return SpaceBox(0f)
        val kinds = items.map { if (it is TexNode.Space) null else kindOf(it) }.toMutableList()
        // A binary sign with nothing to join on its left is a sign of what follows (-x), as in TeX.
        var prev: TexKind? = if (afterOrd) TexKind.ORD else null
        for (n in kinds.indices) {
            val k = kinds[n] ?: continue
            if (k == TexKind.BIN && (prev == null || prev in BIN_BLOCKERS)) kinds[n] = TexKind.ORD
            if ((k == TexKind.REL || k == TexKind.CLOSE || k == TexKind.PUNCT) && prev == TexKind.BIN) {
                val p = (n - 1 downTo 0).first { kinds[it] != null }
                kinds[p] = TexKind.ORD
            }
            prev = kinds[n]
        }
        val last = kinds.indices.lastOrNull { kinds[it] != null }
        if (last != null && kinds[last] == TexKind.BIN) kinds[last] = TexKind.ORD

        val em = size(ctx)
        val tight = ctx.style == TexStyle.SCRIPT || ctx.style == TexStyle.SCRIPTSCRIPT
        val placed = ArrayList<Placed>(items.size)
        var x = 0f
        var before: TexKind? = if (afterOrd) TexKind.ORD else null
        for ((n, item) in items.withIndex()) {
            val box = layout(item, ctx)
            val kind = kinds[n]
            if (kind != null) {
                if (before != null) x += spacing(before, kind, tight) * em / 18f
                before = kind
            }
            placed += Placed(box, x, 0f)
            x += box.width
        }
        return CompositeBox(placed, x).also { it.lastItalic = placed.lastOrNull()?.box?.italic ?: 0f }
    }

    private fun kindOf(node: TexNode): TexKind = when (node) {
        is TexNode.Glyph -> node.kind
        is TexNode.Scripts -> kindOf(node.base)
        is TexNode.BigOp -> TexKind.OP
        is TexNode.Fenced -> TexKind.INNER
        is TexNode.Sized -> node.kind
        is TexNode.Styled -> (node.body as? TexNode.Row)?.items?.singleOrNull()?.let(::kindOf)
            ?: if (node.body is TexNode.Row) TexKind.ORD else kindOf(node.body)
        is TexNode.Stack -> kindOf(node.base)
        else -> TexKind.ORD
    }

    // ---- scripts -----------------------------------------------------------

    private fun scripts(node: TexNode.Scripts, ctx: TexCtx): TexBox {
        val baseBox = layout(node.base, ctx)
        val op = node.base as? TexNode.BigOp
        val s = size(ctx)
        val sctx = ctx.script()
        val ss = size(sctx)
        val sup = node.sup?.let { layout(it, sctx) }
        val sub = node.sub?.let { layout(it, sctx) }
        if (op != null && op.limits && ctx.style == TexStyle.DISPLAY) return limits(baseBox, sup, sub, s)
        val isChar = node.base is TexNode.Glyph
        var u = if (isChar) 0f else baseBox.ascent - 0.386f * ss
        var v = if (isChar) 0f else baseBox.descent + 0.05f * ss
        val xHeight = 0.431f * s
        if (sup != null) u = maxOf(u, if (ctx.style == TexStyle.DISPLAY) 0.413f * s else 0.363f * s, sup.descent + 0.25f * xHeight)
        if (sub != null && sup == null) v = maxOf(v, 0.15f * s, sub.ascent - 0.8f * xHeight)
        if (sub != null && sup != null) {
            v = max(v, 0.247f * s)
            val rule = 0.04f * s
            val gap = (u - sup.descent) - (sub.ascent - v)
            if (gap < 4 * rule) {
                v += 4 * rule - gap
                val psi = 0.8f * xHeight - (u - sup.descent)
                if (psi > 0) { u += psi; v -= psi }
            }
        }
        val placed = mutableListOf(Placed(baseBox, 0f, 0f))
        val supX = baseBox.width + baseBox.italic
        val subX = baseBox.width - if (op?.integral == true) baseBox.italic + 0.12f * s else 0f
        var width = baseBox.width
        if (sup != null) { placed += Placed(sup, supX, -u); width = max(width, supX + sup.width) }
        if (sub != null) { placed += Placed(sub, subX, v); width = max(width, subX + sub.width) }
        return CompositeBox(placed, width + 0.05f * s)
    }

    /** Scripts over and under, as \sum's in a display. */
    private fun limits(base: TexBox, sup: TexBox?, sub: TexBox?, s: Float): TexBox {
        val width = maxOf(base.width, sup?.width ?: 0f, sub?.width ?: 0f)
        val placed = mutableListOf(Placed(base, (width - base.width) / 2, 0f))
        if (sup != null) {
            val shift = base.ascent + max(0.111f * s, 0.2f * s - sup.descent) + sup.descent
            placed += Placed(sup, (width - sup.width) / 2, -shift)
        }
        if (sub != null) {
            val shift = base.descent + max(0.167f * s, 0.6f * s - sub.ascent) + sub.ascent
            placed += Placed(sub, (width - sub.width) / 2, shift)
        }
        return CompositeBox(placed, width, extra = 0.1f * s)
    }

    // ---- fractions and roots -------------------------------------------------

    private fun fraction(node: TexNode.Fraction, outer: TexCtx): TexBox {
        val ctx = node.style?.let { outer.copy(style = it) } ?: outer
        val inner = ctx.fractionInside()
        val num = layout(node.num, inner)
        val den = layout(node.den, inner)
        val s = size(ctx)
        val axis = AXIS * s
        val t = max(0.04f * s, 1f)
        val display = ctx.style == TexStyle.DISPLAY
        var u = if (display) 0.677f * s else if (node.bar) 0.394f * s else 0.444f * s
        var v = if (display) 0.686f * s else 0.345f * s
        if (node.bar) {
            val phi = if (display) 3 * t else t
            val above = (u - num.descent) - (axis + t / 2)
            if (above < phi) u += phi - above
            val below = (v - den.ascent) + axis - t / 2
            if (below < phi) v += phi - below
        } else {
            val clear = if (display) 7 * t else 3 * t
            val gap = (u - num.descent) - (den.ascent - v)
            if (gap < clear) { u += (clear - gap) / 2; v += (clear - gap) / 2 }
        }
        val pad = 0.12f * s
        val inside = max(num.width, den.width)
        val placed = listOf(Placed(num, pad + (inside - num.width) / 2, -u), Placed(den, pad + (inside - den.width) / 2, v))
        val color = ctx.color
        return CompositeBox(placed, inside + 2 * pad, draw = if (!node.bar) null else { canvas, x, y ->
            canvas.drawRect(x + pad, y - axis - t / 2, x + pad + inside, y - axis + t / 2, fill(color))
        }).also {
            it.ascent = max(it.ascent, axis + t / 2)
        }
    }

    private fun radical(node: TexNode.Radical, ctx: TexCtx): TexBox {
        val body = layout(node.body, ctx)
        val s = size(ctx)
        val t = max(0.04f * s, 1f)
        val phi = if (ctx.style == TexStyle.DISPLAY) 0.431f * s else t
        val clearance = t + phi / 4
        val top = max(body.ascent, 0.68f * s) + clearance
        val bottom = max(body.descent, 0.12f * s)
        val height = top + bottom
        val signWidth = (0.52f + 0.08f * min(height / s, 4f)) * s
        val index = node.index?.let { layout(it, ctx.scriptScript()) }
        val kern = index?.let { max(0f, it.width - 0.5f * signWidth + 0.05f * s) } ?: 0f
        val indexBaseline = bottom - 0.6f * height - (index?.descent ?: 0f)
        val placed = mutableListOf(Placed(body, kern + signWidth + 0.04f * s, 0f))
        if (index != null) placed += Placed(index, kern + max(0f, 0.5f * signWidth - index.width) * 0.5f, indexBaseline)
        val width = kern + signWidth + body.width + 0.1f * s
        val color = ctx.color
        return CompositeBox(placed, width, draw = { canvas, x, y ->
            val p = stroke(color, t)
            val x0 = x + kern
            val yb = y + bottom
            val hook = min(height, 1.2f * s)
            val path = Path().apply {
                moveTo(x0 + 0.02f * s, yb - 0.42f * hook)
                lineTo(x0 + 0.16f * signWidth, yb - 0.5f * hook)
            }
            canvas.drawPath(path, p)
            canvas.drawLine(x0 + 0.16f * signWidth, yb - 0.5f * hook, x0 + 0.46f * signWidth, yb, stroke(color, t * 2.1f))
            val rest = Path().apply {
                moveTo(x0 + 0.46f * signWidth, yb)
                lineTo(x0 + signWidth, y - top)
                lineTo(x + width - 0.02f * s, y - top)
            }
            canvas.drawPath(rest, p)
        }).also {
            it.ascent = max(it.ascent, top + t)
            it.descent = max(it.descent, bottom + t)
        }
    }

    // ---- accents ---------------------------------------------------------------

    private fun accent(node: TexNode.Accent, ctx: TexCtx): TexBox {
        val body = layout(node.body, ctx)
        val s = size(ctx)
        val t = max(0.045f * s, 1f)
        val color = ctx.color
        val skew = if (body.italic > 0f || (node.body as? TexNode.Glyph)?.let { it.font == TexFont.AUTO } == true) 0.06f * s else 0f
        when (node.accent) {
            TexAccent.UNDERLINE -> {
                val at = body.descent + 0.12f * s
                return CompositeBox(listOf(Placed(body, 0f, 0f)), body.width, draw = { c, x, y ->
                    c.drawRect(x, y + at, x + body.width, y + at + t, fill(color))
                }).also { it.descent = max(it.descent, at + t) }
            }
            TexAccent.UNDERBRACE -> {
                val at = body.descent + 0.1f * s
                val h = 0.25f * s
                return CompositeBox(listOf(Placed(body, 0f, 0f)), body.width, draw = { c, x, y ->
                    c.drawPath(horizontalBrace(x, x + body.width, y + at, y + at + h), stroke(color, t))
                }).also { it.descent = max(it.descent, at + h + t) }
            }
            else -> Unit
        }
        val wide = node.accent in setOf(TexAccent.WIDEHAT, TexAccent.WIDETILDE, TexAccent.OVERLINE, TexAccent.OVERARROW,
            TexAccent.OVERLEFTARROW, TexAccent.OVERBRACE)
        val accentWidth = when {
            wide -> max(body.width, 0.5f * s)
            node.accent == TexAccent.BAR -> max(0.42f * s, body.width * 0.8f)
            node.accent == TexAccent.VEC -> max(0.45f * s, min(body.width, 0.6f * s))
            node.accent == TexAccent.DOT -> 0.12f * s
            node.accent == TexAccent.DDOT -> 0.32f * s
            else -> 0.42f * s
        }
        val height = when (node.accent) {
            TexAccent.HAT, TexAccent.CHECK -> 0.2f * s
            TexAccent.WIDEHAT -> 0.24f * s
            TexAccent.TILDE, TexAccent.WIDETILDE -> 0.14f * s
            TexAccent.BAR, TexAccent.OVERLINE -> t
            TexAccent.VEC, TexAccent.OVERARROW, TexAccent.OVERLEFTARROW -> 0.2f * s
            TexAccent.DOT, TexAccent.DDOT -> 0.1f * s
            TexAccent.BREVE -> 0.14f * s
            TexAccent.ACUTE, TexAccent.GRAVE -> 0.18f * s
            TexAccent.OVERBRACE -> 0.25f * s
            else -> 0.2f * s
        }
        val gap = if (node.accent == TexAccent.OVERLINE) 0.12f * s else 0.08f * s
        val baseTop = max(body.ascent, 0.45f * s)
        val width = max(body.width, accentWidth)
        val bodyX = (width - body.width) / 2
        val left = (width - accentWidth) / 2 + if (wide) 0f else skew
        val accent = node.accent
        return CompositeBox(listOf(Placed(body, bodyX, 0f)), width, draw = { c, x, y ->
            val bottom = y - baseTop - gap
            val topY = bottom - height
            val l = x + left
            val r = l + accentWidth
            val mid = (l + r) / 2
            val p = stroke(color, t)
            when (accent) {
                TexAccent.HAT, TexAccent.WIDEHAT -> c.drawPath(Path().apply { moveTo(l, bottom); lineTo(mid, topY); lineTo(r, bottom) }, p)
                TexAccent.CHECK -> c.drawPath(Path().apply { moveTo(l, topY); lineTo(mid, bottom); lineTo(r, topY) }, p)
                TexAccent.TILDE, TexAccent.WIDETILDE -> c.drawPath(Path().apply {
                    moveTo(l, bottom - height * 0.1f)
                    cubicTo(l + accentWidth * 0.25f, topY - height * 0.4f, l + accentWidth * 0.5f, bottom + height * 0.3f, mid, (topY + bottom) / 2)
                    cubicTo(l + accentWidth * 0.75f, topY - height * 0.3f, r - accentWidth * 0.05f, bottom + height * 0.4f, r, topY + height * 0.1f)
                }, p)
                TexAccent.BAR, TexAccent.OVERLINE -> c.drawRect(l, bottom - t, r, bottom, fill(color))
                TexAccent.VEC, TexAccent.OVERARROW -> arrow(c, l, r, (topY + bottom) / 2, height, p, right = true)
                TexAccent.OVERLEFTARROW -> arrow(c, l, r, (topY + bottom) / 2, height, p, right = false)
                TexAccent.DOT -> c.drawCircle(mid, bottom - height / 2, 0.055f * s, fill(color))
                TexAccent.DDOT -> {
                    c.drawCircle(l + 0.055f * s, bottom - height / 2, 0.055f * s, fill(color))
                    c.drawCircle(r - 0.055f * s, bottom - height / 2, 0.055f * s, fill(color))
                }
                TexAccent.BREVE -> c.drawArc(RectF(l, topY - height, r, bottom), 20f, 140f, false, p)
                TexAccent.ACUTE -> c.drawLine(mid - accentWidth * 0.15f, bottom, mid + accentWidth * 0.2f, topY, p)
                TexAccent.GRAVE -> c.drawLine(mid + accentWidth * 0.15f, bottom, mid - accentWidth * 0.2f, topY, p)
                TexAccent.OVERBRACE -> c.drawPath(horizontalBrace(l, r, bottom, topY), p)
                else -> Unit
            }
        }).also { it.ascent = max(it.ascent, baseTop + gap + height + t) }
    }

    private fun arrow(c: Canvas, l: Float, r: Float, y: Float, h: Float, p: Paint, right: Boolean) {
        c.drawLine(l, y, r, y, p)
        val tip = if (right) r else l
        val back = if (right) -h * 0.6f else h * 0.6f
        c.drawLine(tip, y, tip + back, y - h * 0.45f, p)
        c.drawLine(tip, y, tip + back, y + h * 0.45f, p)
    }

    /** A brace lying on its side from [l] to [r], its point toward [tipY]. */
    private fun horizontalBrace(l: Float, r: Float, edgeY: Float, tipY: Float): Path = Path().apply {
        val mid = (l + r) / 2
        val m = (edgeY + tipY) / 2
        val q = min((r - l) * 0.1f, kotlin.math.abs(tipY - edgeY))
        moveTo(l, edgeY)
        quadTo(l, m, l + q, m)
        lineTo(mid - q, m)
        quadTo(mid, m, mid, tipY)
        quadTo(mid, m, mid + q, m)
        lineTo(r - q, m)
        quadTo(r, m, r, edgeY)
    }

    // ---- delimiters ------------------------------------------------------------

    private fun fenced(node: TexNode.Fenced, ctx: TexCtx): TexBox {
        val body = layout(node.body, ctx)
        val s = size(ctx)
        val axis = AXIS * s
        val half = max(body.ascent - axis, body.descent + axis)
        val target = max(half * 2 * 0.901f, half * 2 - 0.5f * s)
        val left = delimiter(node.left, target, ctx)
        val right = delimiter(node.right, target, ctx)
        val placed = listOf(Placed(left, 0f, 0f), Placed(body, left.width, 0f), Placed(right, left.width + body.width, 0f))
        return CompositeBox(placed, left.width + body.width + right.width)
    }

    /** [d] drawn [wanted] tall (never less than a letter's height), centred on the maths axis. */
    private fun delimiter(d: String, wanted: Float, ctx: TexCtx): TexBox {
        val s = size(ctx)
        if (d.isEmpty()) return SpaceBox(0.12f * s)
        val h = max(wanted, 1.08f * s)
        val axis = AXIS * s
        val grow = min(h / s, 5f)
        val width = when (d) {
            "(", ")" -> (0.36f + 0.05f * grow) * s
            "[", "]", "⌊", "⌋", "⌈", "⌉" -> 0.34f * s
            "{", "}" -> (0.44f + 0.03f * grow) * s
            "|" -> 0.26f * s
            "‖" -> 0.42f * s
            "⟨", "⟩" -> (0.36f + 0.04f * grow) * s
            "/", "\\" -> (0.3f + 0.12f * grow) * s
            else -> return GlyphBox(d, paint(ctx, SERIF))
        }
        val color = ctx.color
        return object : TexBox() {
            init { this.width = width; ascent = axis + h / 2; descent = h / 2 - axis }
            override fun draw(canvas: Canvas, x: Float, y: Float) {
                drawDelimiter(canvas, d, x, y - axis - h / 2, width, h, s, color)
            }
        }
    }

    private fun drawDelimiter(c: Canvas, d: String, x: Float, top: Float, w: Float, h: Float, s: Float, color: Int) {
        val thin = max(0.05f * s, 1f)
        val bold = max(0.075f * s, 1.3f)
        val bottom = top + h
        val mid = top + h / 2
        val pad = 0.08f * s
        val mirrored = d in setOf(")", "]", "}", "⌋", "⌉", "⟩")
        c.save()
        if (mirrored) { c.translate(2 * x + w, 0f); c.scale(-1f, 1f) }
        val l = x + pad
        val r = x + w - pad
        when (d) {
            "(", ")" -> {
                // A crescent: thick at its middle, a little less at its ends, as a parenthesis of a text face.
                val th = bold * 1.25f * (1f + 0.1f * min(h / s - 1f, 4f))
                val tip = r
                val end = thin * 1.1f
                val path = Path().apply {
                    moveTo(tip, top)
                    quadTo(2 * l - tip, mid, tip, bottom)
                    lineTo(tip - end, bottom - end * 0.2f)
                    quadTo(2 * (l + th) - (tip - end), mid, tip - end, top + end * 0.2f)
                    close()
                }
                c.drawPath(path, fill(color))
            }
            "[", "]" -> {
                c.drawRect(l, top, l + bold, bottom, fill(color))
                c.drawRect(l, top, r, top + thin, fill(color))
                c.drawRect(l, bottom - thin, r, bottom, fill(color))
            }
            "⌊", "⌋" -> {
                c.drawRect(l, top, l + bold, bottom, fill(color))
                c.drawRect(l, bottom - thin, r, bottom, fill(color))
            }
            "⌈", "⌉" -> {
                c.drawRect(l, top, l + bold, bottom, fill(color))
                c.drawRect(l, top, r, top + thin, fill(color))
            }
            "{", "}" -> {
                val spine = (l + r) / 2
                val q = min((r - l) * 0.9f, h / 6)
                val path = Path().apply {
                    moveTo(r, top)
                    quadTo(spine, top, spine, top + q)
                    lineTo(spine, mid - q)
                    quadTo(spine, mid, l, mid)
                    quadTo(spine, mid, spine, mid + q)
                    lineTo(spine, bottom - q)
                    quadTo(spine, bottom, r, bottom)
                }
                c.drawPath(path, stroke(color, bold))
            }
            "|" -> c.drawRect(x + w / 2 - thin * 0.7f, top, x + w / 2 + thin * 0.7f, bottom, fill(color))
            "‖" -> {
                c.drawRect(x + w * 0.32f - thin * 0.7f, top, x + w * 0.32f + thin * 0.7f, bottom, fill(color))
                c.drawRect(x + w * 0.68f - thin * 0.7f, top, x + w * 0.68f + thin * 0.7f, bottom, fill(color))
            }
            "⟨", "⟩" -> c.drawPath(Path().apply { moveTo(r, top); lineTo(l, mid); lineTo(r, bottom) }, stroke(color, thin * 1.2f))
            "/" -> c.drawLine(r, top, l, bottom, stroke(color, thin * 1.2f))
            "\\" -> c.drawLine(l, top, r, bottom, stroke(color, thin * 1.2f))
        }
        c.restore()
    }

    // ---- big operators ---------------------------------------------------------

    private fun bigOp(node: TexNode.BigOp, ctx: TexCtx): TexBox {
        if (node.word) return GlyphBox(node.symbol, paint(ctx, SERIF))
        val s = size(ctx)
        val display = ctx.style == TexStyle.DISPLAY
        val grow = when {
            node.integral -> if (display) 2.0f else 1.35f
            else -> if (display) 1.5f else 1.1f
        }
        val p = paint(ctx, if (node.integral) SERIF_ITALIC else SERIF).apply { textSize = s * grow }
        val glyph = GlyphBox(node.symbol, p, slanted = node.integral)
        // Centred on the axis, as TeX sets them.
        val axis = AXIS * s
        val shift = (glyph.ascent - glyph.descent) / 2 - axis
        return ShiftBox(glyph, shift)
    }

    // ---- grids -----------------------------------------------------------------

    private fun grid(node: TexNode.Grid, ctx: TexCtx): TexBox {
        val s = size(ctx)
        val cctx = ctx.atMost(node.style ?: TexStyle.TEXT)
        val cs = size(cctx)
        val columns = node.rows.maxOfOrNull { it.size } ?: 0
        val cells = node.rows.map { line ->
            line.mapIndexed { j, cell ->
                // In aligned lines, the side after & starts as if something stood before it, so "= b" spaces as a relation.
                val afterAmp = node.gap == 0f && j % 2 == 1
                if (afterAmp) row(listOf(cell).flatMap { (it as? TexNode.Row)?.items ?: listOf(it) }, cctx, afterOrd = true)
                else layout(cell, cctx)
            }
        }
        val colWidth = FloatArray(columns)
        for (row in cells) row.forEachIndexed { j, b -> colWidth[j] = max(colWidth[j], b.width) }
        val rowAscent = cells.map { r -> max(0.7f * cs, r.maxOfOrNull { it.ascent } ?: 0f) }
        val rowDescent = cells.map { r -> max(0.3f * cs, r.maxOfOrNull { it.descent } ?: 0f) }
        val gapY = node.spread * s
        val height = rowAscent.sum() + rowDescent.sum() + gapY * (cells.size - 1).coerceAtLeast(0)
        val colX = FloatArray(columns)
        var x = 0f
        for (j in 0 until columns) {
            colX[j] = x
            x += colWidth[j]
            if (j < columns - 1) x += if (node.gap > 0f) node.gap * s else if (j % 2 == 1) 1f * s else 0f
        }
        val inside = x
        val axis = AXIS * s
        val top = -axis - height / 2
        val hasLeft = node.left.isNotEmpty()
        val hasRight = node.right.isNotEmpty()
        val left = if (hasLeft) delimiter(node.left, height + 0.1f * s, ctx) else null
        val right = if (hasRight) delimiter(node.right, height + 0.1f * s, ctx) else null
        val pad = if (hasLeft || hasRight) 0.15f * s else 0f
        val start = (left?.width ?: 0f) + pad
        val placed = mutableListOf<Placed>()
        if (left != null) placed += Placed(left, 0f, 0f)
        var y = top
        cells.forEachIndexed { i, row ->
            y += rowAscent[i]
            row.forEachIndexed { j, b ->
                val align = node.align.getOrElse(j) { 'c' }
                val dx = when (align) { 'l' -> 0f; 'r' -> colWidth[j] - b.width; else -> (colWidth[j] - b.width) / 2 }
                placed += Placed(b, start + colX[j] + dx, y)
            }
            y += rowDescent[i] + gapY
        }
        if (right != null) placed += Placed(right, start + inside + pad, 0f)
        val width = start + inside + pad + (right?.width ?: 0f)
        return CompositeBox(placed, width).also {
            it.ascent = max(it.ascent, -top)
            it.descent = max(it.descent, top + height)
        }
    }

    private fun boxed(node: TexNode.Boxed, ctx: TexCtx): TexBox {
        val body = layout(node.body, ctx)
        val s = size(ctx)
        val pad = 0.25f * s
        val t = max(0.04f * s, 1f)
        val color = ctx.color
        return CompositeBox(listOf(Placed(body, pad, 0f)), body.width + 2 * pad, draw = { c, x, y ->
            c.drawRect(x + t / 2, y - body.ascent - pad, x + body.width + 2 * pad - t / 2, y + body.descent + pad, stroke(color, t))
        }).also {
            it.ascent = body.ascent + pad + t
            it.descent = body.descent + pad + t
        }
    }

    private fun stack(node: TexNode.Stack, ctx: TexCtx): TexBox {
        val base = layout(node.base, ctx)
        val s = size(ctx)
        val over = node.over?.let { layout(it, ctx.script()) }
        val under = node.under?.let { layout(it, ctx.script()) }
        val width = maxOf(base.width, over?.width ?: 0f, under?.width ?: 0f)
        val placed = mutableListOf(Placed(base, (width - base.width) / 2, 0f))
        if (over != null) placed += Placed(over, (width - over.width) / 2, -(max(base.ascent, 0.45f * s) + 0.1f * s + over.descent))
        if (under != null) placed += Placed(under, (width - under.width) / 2, base.descent + 0.1f * s + under.ascent)
        return CompositeBox(placed, width)
    }

    companion object {
        private val SERIF: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        private val SERIF_ITALIC: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        private val SERIF_BOLD: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        private val SERIF_BOLD_ITALIC: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        /** The maths axis, where a fraction's bar sits and what delimiters centre on, in em. */
        private const val AXIS = 0.25f
        private const val ERROR_RED = 0xFFCC3333.toInt()
        private val BIN_BLOCKERS = setOf(TexKind.BIN, TexKind.OP, TexKind.REL, TexKind.OPEN, TexKind.PUNCT)
    }
}

/** TeX's space between two pieces, in eighteenths of an em; [tight] in scripts, where only the thin ones stay. */
internal fun spacing(left: TexKind, right: TexKind, tight: Boolean): Int {
    val thin = 3
    val med = if (tight) 0 else 4
    val thick = if (tight) 0 else 5
    val thinLoose = if (tight) 0 else 3
    return when (left) {
        TexKind.ORD -> when (right) { TexKind.OP -> thin; TexKind.BIN -> med; TexKind.REL -> thick; TexKind.INNER -> thinLoose; else -> 0 }
        TexKind.OP -> when (right) { TexKind.ORD, TexKind.OP -> thin; TexKind.REL -> thick; TexKind.INNER -> thinLoose; else -> 0 }
        TexKind.BIN -> when (right) { TexKind.ORD, TexKind.OP, TexKind.OPEN, TexKind.INNER -> med; else -> 0 }
        TexKind.REL -> when (right) { TexKind.ORD, TexKind.OP, TexKind.OPEN, TexKind.INNER -> thick; else -> 0 }
        TexKind.OPEN -> 0
        TexKind.CLOSE -> when (right) { TexKind.OP -> thin; TexKind.BIN -> med; TexKind.REL -> thick; TexKind.INNER -> thinLoose; else -> 0 }
        TexKind.PUNCT -> when (right) { TexKind.CLOSE -> 0; else -> thinLoose }
        TexKind.INNER -> when (right) { TexKind.OP -> thin; TexKind.BIN -> med; TexKind.REL -> thick; TexKind.CLOSE -> 0; else -> thinLoose }
    }
}

// ---- boxes --------------------------------------------------------------------

private class Placed(val box: TexBox, val dx: Float, val dy: Float)

private class SpaceBox(w: Float) : TexBox() {
    init { width = w }
    override fun draw(canvas: Canvas, x: Float, y: Float) = Unit
}

private class GlyphBox(private val text: String, private val paint: TextPaint, slanted: Boolean = false) : TexBox() {
    private val overhang: Float

    init {
        width = paint.measureText(text)
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        ascent = max(-bounds.top.toFloat(), 0f)
        descent = max(bounds.bottom.toFloat(), 0f)
        overhang = if (slanted) max(0f, bounds.right - width) + paint.textSize * 0.02f else 0f
    }

    override val italic: Float get() = overhang
    override fun draw(canvas: Canvas, x: Float, y: Float) = canvas.drawText(text, x, y, paint)
}

/** [child] moved [shift] down (up when negative). */
private class ShiftBox(private val child: TexBox, private val shift: Float) : TexBox() {
    init { width = child.width; ascent = child.ascent - shift; descent = child.descent + shift }
    override val italic: Float get() = child.italic
    override fun draw(canvas: Canvas, x: Float, y: Float) = child.draw(canvas, x, y + shift)
}

/** Boxes at their places, and what else is drawn with them (a bar, a sign); [extra] room above and below. */
private class CompositeBox(
    private val children: List<Placed>,
    w: Float,
    extra: Float = 0f,
    private val draw: ((Canvas, Float, Float) -> Unit)? = null,
) : TexBox() {
    var lastItalic = 0f

    init {
        width = w
        for (p in children) {
            ascent = max(ascent, p.box.ascent - p.dy)
            descent = max(descent, p.box.descent + p.dy)
        }
        ascent += extra
        descent += extra
    }

    override val italic: Float get() = lastItalic
    override fun draw(canvas: Canvas, x: Float, y: Float) {
        for (p in children) p.box.draw(canvas, x + p.dx, y + p.dy)
        draw?.invoke(canvas, x, y)
    }
}

private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color; style = Paint.Style.STROKE; strokeWidth = width
    strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
}
