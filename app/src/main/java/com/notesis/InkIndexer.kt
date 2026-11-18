package com.notesis

import android.content.Context
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea

/**
 * A stroke's bounding box, in page units. Plain numbers rather than a RectF so
 * the line-grouping below can be tested without a device.
 */
internal data class InkBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val height get() = bottom - top
    val width get() = right - left
    val middle get() = (top + bottom) / 2f
}

/**
 * A line of handwriting: its strokes in the order they go to the recogniser,
 * and how far the line is tilted, in radians (positive down to the right).
 */
internal data class InkLine(val strokes: List<Int>, val tilt: Float)

/**
 * Sorts a page's strokes into lines of writing.
 *
 * Handing a recogniser a whole page at once gets a whole page's worth of
 * nonsense back - it expects something like a line. Nothing in a note says
 * where the lines are, so they are recovered from where the strokes sit.
 *
 * Lines are grown stroke to stroke, not against one band per line: two
 * strokes are on one line when they sit side by side at much the same height,
 * or when one sits just above the other over the same place - the parts of a
 * Hangul syllable stacked in its square, a dot over an i. A line that slopes
 * stays one line, since each stroke only has to agree with its neighbours.
 * A stroke much taller than the writing round it (a bracket down the margin)
 * links nothing itself, and joins the line it overlaps most.
 *
 * Within a line, the letters - strokes that touch or stack - are read left to
 * right, and each letter's strokes in the order they were written, which the
 * recogniser leans on: sorted by position, a syllable's strokes came out of
 * order and it read as something else, or nothing.
 *
 * [boxes] are in the order the strokes were written.
 */
internal fun inkLines(boxes: List<InkBox>): List<InkLine> {
    if (boxes.isEmpty()) return emptyList()
    val n = boxes.size
    // The size of one stroke of this writing: half a letter, more or less.
    val unit = boxes.map { maxOf(it.width, it.height) }.sorted()[n / 2].coerceAtLeast(1f)
    val typicalHeight = boxes.map { it.height }.sorted()[n / 2].coerceAtLeast(1f)
    val outsized = BooleanArray(n) { boxes[it].height > typicalHeight * OUTSIZED_HEIGHT && boxes[it].height > unit * 2.5f }

    val line = UnionFind(n)
    val letter = UnionFind(n)
    for (i in 0 until n) {
        if (outsized[i]) continue
        val a = boxes[i]
        for (j in i + 1 until n) {
            if (outsized[j]) continue
            val b = boxes[j]
            val vOverlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            val hOverlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val shorter = minOf(a.height, b.height).coerceAtLeast(1f)
            val narrower = minOf(a.width, b.width).coerceAtLeast(1f)
            val hGap = -hOverlap
            val vGap = -vOverlap
            // Stacked over the same place and close: one letter, so one line.
            val stacked = hOverlap >= narrower * STACK_OVERLAP && vGap <= unit * STACK_GAP
            // Touching or crossing: one letter.
            val touching = hGap <= unit * TOUCH_GAP && vGap <= unit * TOUCH_GAP
            // Side by side at the same height, near enough to be one line.
            val besides = vOverlap >= shorter * LINE_OVERLAP && hGap <= unit * WORD_GAP
            if (stacked || touching || besides) line.join(i, j)
            if (stacked || touching) letter.join(i, j)
        }
    }
    // Words far apart on one line: pieces side by side, with nothing of one
    // over the other, are one line when their facing ends are at much the
    // same height. Only the ends are compared, so a line written sloping is
    // not joined to the one below it because their whole spans overlap.
    val roots = (0 until n).filter { !outsized[it] }.map { line.find(it) }.distinct()
    val members = roots.associateWith { root -> (0 until n).filter { !outsized[it] && line.find(it) == root } }
    val reach = unit * END_REACH
    fun span(strokes: List<Int>): Pair<Float, Float> = strokes.minOf { boxes[it].top } to strokes.maxOf { boxes[it].bottom }
    for (x in roots.indices) for (y in x + 1 until roots.size) {
        var a = members.getValue(roots[x])
        var b = members.getValue(roots[y])
        if (a.minOf { boxes[it].left } > b.minOf { boxes[it].left }) a = b.also { b = a }
        val aRight = a.maxOf { boxes[it].right }
        val bLeft = b.minOf { boxes[it].left }
        if (bLeft <= aRight) continue
        val (topA, bottomA) = span(a.filter { boxes[it].right >= aRight - reach })
        val (topB, bottomB) = span(b.filter { boxes[it].left <= bLeft + reach })
        val overlap = minOf(bottomA, bottomB) - maxOf(topA, topB)
        val shorter = minOf(bottomA - topA, bottomB - topB).coerceAtLeast(1f)
        if (overlap >= shorter * SAME_BAND) line.join(roots[x], roots[y])
    }
    val groups = LinkedHashMap<Int, MutableList<Int>>()
    for (i in 0 until n) if (!outsized[i]) groups.getOrPut(line.find(i)) { mutableListOf() } += i
    // A tall stroke goes with the line it overlaps most, or on its own.
    for (i in 0 until n) {
        if (!outsized[i]) continue
        val box = boxes[i]
        val best = groups.entries.maxByOrNull { (_, members) ->
            members.sumOf { m -> (minOf(box.bottom, boxes[m].bottom) - maxOf(box.top, boxes[m].top)).coerceAtLeast(0f).toDouble() }
        }
        val overlaps = best != null && best.value.any { m ->
            minOf(box.bottom, boxes[m].bottom) > maxOf(box.top, boxes[m].top)
        }
        if (best != null && overlaps) best.value += i else groups.getOrPut(-1 - i) { mutableListOf() } += i
    }

    return groups.values.map { members ->
        // Letters left to right, each letter's strokes as written.
        val letters = members.groupBy { if (outsized[it]) -1 - it else letter.find(it) }.values.map { it.sorted() }
        val ordered = letters.sortedBy { strokes -> strokes.minOf { boxes[it].left } }
        val centres = ordered.map { strokes ->
            val left = strokes.minOf { boxes[it].left }
            val right = strokes.maxOf { boxes[it].right }
            val top = strokes.minOf { boxes[it].top }
            val bottom = strokes.maxOf { boxes[it].bottom }
            floatArrayOf((left + right) / 2f, (top + bottom) / 2f)
        }
        val firstMiddle = centres.firstOrNull()?.get(1) ?: 0f
        Triple(InkLine(ordered.flatten(), lineTilt(centres, unit)), firstMiddle,
            members.minOf { boxes[it].left })
    }
        // Lines top to bottom by where each begins, so a sloping line is read
        // where it starts, not by the lowest point it reaches.
        .sortedWith(compareBy({ it.second }, { it.third }))
        .map { it.first }
}

/** [inkLines] without the tilt: indices into [boxes], grouped and ordered for reading. */
internal fun groupIntoLines(boxes: List<InkBox>): List<List<Int>> = inkLines(boxes).map { it.strokes }

/**
 * How far a line of letters centred at [centres] slopes: a least-squares fit
 * through them, kept to [MAX_TILT] and taken as level below [MIN_TILT] or
 * with too few letters, too close together, to tell.
 */
internal fun lineTilt(centres: List<FloatArray>, unit: Float): Float {
    if (centres.size < 3) return 0f
    val span = centres.maxOf { it[0] } - centres.minOf { it[0] }
    if (span < unit * 4f) return 0f
    val mx = centres.sumOf { it[0].toDouble() } / centres.size
    val my = centres.sumOf { it[1].toDouble() } / centres.size
    var sxx = 0.0
    var sxy = 0.0
    for (c in centres) {
        val dx = c[0] - mx
        sxx += dx * dx
        sxy += dx * (c[1] - my)
    }
    if (sxx <= 0.0) return 0f
    val angle = kotlin.math.atan(sxy / sxx).toFloat()
    if (kotlin.math.abs(angle) < MIN_TILT) return 0f
    return angle.coerceIn(-MAX_TILT, MAX_TILT)
}

private class UnionFind(size: Int) {
    private val parent = IntArray(size) { it }
    fun find(i: Int): Int {
        var x = i
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }
    fun join(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
    }
}

/**
 * How well a reading fits the language that made it: the share of its letters
 * in that language's script. A Korean model reading English writing returns
 * Hangul-looking guesses and an English one reading Korean returns Latin
 * ones; the reading in the script its model expects is almost always the
 * right one. Digits, punctuation and spaces fit any language.
 */
internal fun scriptFit(text: String, language: String): Float {
    var letters = 0
    var fitting = 0
    for (ch in text) {
        if (!ch.isLetter()) continue
        letters++
        if (inScript(ch, language)) fitting++
    }
    return if (letters == 0) 0.5f else fitting.toFloat() / letters
}

private fun inScript(ch: Char, language: String): Boolean {
    val block = Character.UnicodeScript.of(ch.code)
    return when (language.substringBefore('-')) {
        "ko" -> block == Character.UnicodeScript.HANGUL
        "ja" -> block == Character.UnicodeScript.HIRAGANA || block == Character.UnicodeScript.KATAKANA ||
            block == Character.UnicodeScript.HAN
        "zh" -> block == Character.UnicodeScript.HAN
        "ru", "uk" -> block == Character.UnicodeScript.CYRILLIC
        "el" -> block == Character.UnicodeScript.GREEK
        "ar", "fa" -> block == Character.UnicodeScript.ARABIC
        "hi" -> block == Character.UnicodeScript.DEVANAGARI
        "th" -> block == Character.UnicodeScript.THAI
        else -> block == Character.UnicodeScript.LATIN
    }
}

/**
 * Reads a page's handwriting so it can be searched.
 *
 * The models are downloaded once, per language, and the note is run through
 * every one of them: this feeds a search index, where finding the line at all
 * matters more than reading it perfectly, and a note with Korean and English on
 * the same page is the normal case rather than the exception.
 */
class InkIndexer(private val context: Context) {

    private val recognizers = mutableMapOf<String, DigitalInkRecognizer>()

    /**
     * Downloads what is missing. Returns false when nothing can be read yet -
     * no network on first run, most likely - so the caller can leave the page
     * unindexed and try again rather than write an empty index over a good one.
     */
    fun prepare(): Boolean {
        var ready = false
        val wanted = languages(context)
        // A language taken out of the settings stops being read: the models
        // used to stay for as long as the app ran, and went on reading.
        recognizers.keys.filter { it !in wanted }.forEach { tag -> recognizers.remove(tag)?.close() }
        for (tag in wanted) {
            if (recognizers.containsKey(tag)) {
                ready = true
                continue
            }
            val identifier = runCatching {
                DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
            }.getOrNull() ?: continue
            val model = DigitalInkRecognitionModel.builder(identifier).build()
            val downloaded = runCatching {
                val manager = RemoteModelManager.getInstance()
                if (Tasks.await(manager.isModelDownloaded(model)) != true) {
                    Tasks.await(manager.download(model, DownloadConditions.Builder().build()))
                }
                true
            }.getOrDefault(false)
            if (!downloaded) continue
            recognizers[tag] = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build(),
            )
            ready = true
        }
        return ready
    }

    /**
     * Blocking; call it off the main thread. Null means nothing was read.
     * [firstReading] keeps only the first language that reads each line, for
     * showing to a person; the search index keeps every reading.
     * [keepGoing] is asked before each line; false stops there and returns
     * null, so background indexing gives way when the page is touched again.
     */
    fun textOf(
        strokes: List<Stroke>,
        firstReading: Boolean = false,
        keepGoing: () -> Boolean = { true },
    ): String? {
        if (strokes.isEmpty()) return ""
        if (!prepare()) return null
        val out = StringBuilder()
        var before = ""
        for (line in linesOf(strokes)) {
            if (!keepGoing()) return null
            val readings = readLine(line, before, perLanguage = 1)
            if (readings.isEmpty()) continue
            before = readings.first().text
            // For a person, the best reading; for the search index, each
            // language's own, so a word is found whichever way it was read.
            if (firstReading) out.append(readings.first().text).append('\n')
            else readings.forEach { out.append(it.text).append('\n') }
        }
        return out.toString()
    }

    /**
     * Each line of [strokes], read in every language set, with up to
     * [perLanguage] readings from each, the likeliest first - for a person to
     * choose from and correct. Blocking; null when no model can be used yet.
     */
    fun readingsOf(strokes: List<Stroke>, perLanguage: Int = 3): List<OcrLine>? {
        if (strokes.isEmpty()) return emptyList()
        if (!prepare()) return null
        var before = ""
        return linesOf(strokes).mapNotNull { line ->
            val readings = readLine(line, before, perLanguage)
            if (readings.isEmpty()) return@mapNotNull null
            before = readings.first().text
            OcrLine(readings)
        }
    }

    /** [strokes] in lines, each with its strokes in the order to be read and its tilt. */
    private fun linesOf(strokes: List<Stroke>): List<Pair<List<Stroke>, Float>> {
        val boxes = strokes.mapNotNull { stroke ->
            stroke.shape.computeBoundingBox()?.let { stroke to InkBox(it.xMin, it.yMin, it.xMax, it.yMax) }
        }
        if (boxes.isEmpty()) return emptyList()
        return inkLines(boxes.map { it.second }).map { line -> line.strokes.map { boxes[it].first } to line.tilt }
    }

    /**
     * One line read in every language set: each language's readings, the
     * likeliest first - the top reading in the script its model expects (see
     * [scriptFit]), then the other languages' top readings, then the rest.
     * [before] is the text of the line above, given as context.
     */
    private fun readLine(line: Pair<List<Stroke>, Float>, before: String, perLanguage: Int): List<OcrReading> {
        val sheet = inkOf(line.first, line.second) ?: return emptyList()
        val recognition = runCatching {
            RecognitionContext.builder()
                .setPreContext(before.takeLast(PRE_CONTEXT_CHARS))
                .setWritingArea(WritingArea(sheet.width.coerceAtLeast(1f), sheet.height.coerceAtLeast(1f)))
                .build()
        }.getOrNull()
        val tops = ArrayList<Pair<OcrReading, Float>>()
        val rest = ArrayList<OcrReading>()
        for ((order, tag) in languages(context).withIndex()) {
            val recognizer = recognizers[tag] ?: continue
            val candidates = runCatching {
                val result = if (recognition != null) recognizer.recognize(sheet.ink, recognition)
                    else recognizer.recognize(sheet.ink)
                Tasks.await(result).candidates
            }.getOrNull().orEmpty()
            candidates.take(perLanguage).forEachIndexed { rank, candidate ->
                val text = candidate.text.trim()
                if (text.isEmpty()) return@forEachIndexed
                val reading = OcrReading(text, tag)
                // The language order breaks a tie, the first one first.
                if (rank == 0) tops += reading to (scriptFit(text, tag) - order * 0.001f) else rest += reading
            }
        }
        val ordered = tops.sortedByDescending { it.second }.map { it.first } + rest
        return ordered.distinctBy { it.text }
    }

    /** A line as the recogniser is handed it, and the size of the space it fills. */
    private class Sheet(val ink: Ink, val width: Float, val height: Float)

    /**
     * [strokes] as one piece of ink, turned level by [tilt] about the first
     * point: a line written sloping is read as one written straight, which
     * the recogniser was trained on.
     */
    private fun inkOf(strokes: List<Stroke>, tilt: Float = 0f): Sheet? {
        val builder = Ink.builder()
        val scratch = StrokeInput()
        var points = 0
        val cos = kotlin.math.cos(-tilt)
        val sin = kotlin.math.sin(-tilt)
        var originX = Float.NaN
        var originY = Float.NaN
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        // Each stroke's times start again at zero; the recogniser reads the
        // ink as one piece of writing, in time, and took every stroke as
        // written at once. Laid end to end, a little apart, in the order given.
        var base = 0L
        for (stroke in strokes) {
            val line = Ink.Stroke.builder()
            var last = 0L
            for (i in 0 until stroke.inputs.size) {
                val input = stroke.inputs.populate(i, scratch)
                last = input.elapsedTimeMillis
                if (originX.isNaN()) {
                    originX = input.x
                    originY = input.y
                }
                val dx = input.x - originX
                val dy = input.y - originY
                val x = originX + dx * cos - dy * sin
                val y = originY + dx * sin + dy * cos
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                line.addPoint(Ink.Point.create(x, y, base + last))
                points++
            }
            builder.addStroke(line.build())
            base += last + STROKE_GAP_MS
        }
        return if (points == 0) null else Sheet(builder.build(), maxX - minX, maxY - minY)
    }

    fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }

    companion object {
        /**
         * Korean and English by default: these notes mix them within a line, and
         * a search index would rather hold two readings of a word than miss it.
         */
        private val DEFAULT_LANGUAGES = listOf("ko", "en")

        /** What can be picked: ML Kit language tags with how they are shown. */
        val OFFERED = listOf(
            "ko" to "한국어", "en" to "English", "ja" to "日本語", "zh-Hani-CN" to "中文(简体)",
            "zh-Hani-TW" to "中文(繁體)", "de" to "Deutsch", "fr" to "Français", "es" to "Español",
            "it" to "Italiano", "pt" to "Português", "ru" to "Русский", "vi" to "Tiếng Việt",
            "th" to "ไทย", "id" to "Bahasa Indonesia", "ar" to "العربية", "hi" to "हिन्दी",
            "tr" to "Türkçe", "pl" to "Polski", "nl" to "Nederlands", "uk" to "Українська",
            "cs" to "Čeština", "el" to "Ελληνικά", "hu" to "Magyar", "fa" to "فارسی",
        )

        /** The languages handwriting is read in, first one first. */
        fun languages(context: Context): List<String> =
            context.getSharedPreferences("pens", Context.MODE_PRIVATE).getString("ocrLanguages", null)
                ?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_LANGUAGES

        fun setLanguages(context: Context, tags: List<String>) {
            context.getSharedPreferences("pens", Context.MODE_PRIVATE).edit()
                .putString("ocrLanguages", tags.distinct().joinToString(",")).apply()
        }
    }
}

/** One way a line of handwriting was read: the text, and the language that read it. */
data class OcrReading(val text: String, val language: String)

/** A line of handwriting and its readings, the first the one shown by default. */
data class OcrLine(val readings: List<OcrReading>)

/** How much of the line above is handed over as context; the recogniser reads up to twenty characters. */
private const val PRE_CONTEXT_CHARS = 20

/** The time put between strokes laid end to end for the recogniser; a quick pen lift. */
private const val STROKE_GAP_MS = 120L

/** How much of the shorter of two strokes side by side has to overlap for them to be one line. */
private const val LINE_OVERLAP = 0.35f
/** Pieces of writing whose facing ends overlap this much of the shorter in height are one line, however far apart. */
private const val SAME_BAND = 0.5f
/** How far in from a piece's end, in stroke sizes, counts as its end. */
private const val END_REACH = 2f
/** Side by side: no further apart than this many stroke sizes (see [inkLines]). */
private const val WORD_GAP = 3.5f
/** Stacked: over this share of the narrower stroke, and no further apart than [STACK_GAP] stroke sizes. */
private const val STACK_OVERLAP = 0.3f
private const val STACK_GAP = 0.6f
/** Touching: boxes this close, in stroke sizes, either way. */
private const val TOUCH_GAP = 0.12f
/** A stroke this many times the writing's height round it links nothing itself. */
private const val OUTSIZED_HEIGHT = 2.6f
/** Tilts below this are taken as level; above [MAX_TILT] the fit is not trusted further. */
private const val MIN_TILT = 0.05f
private const val MAX_TILT = 0.6f
