package com.notesis

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.LocalTextStyle

/** The languages the app's own words come in. Saved by [code]. */
enum class AppLanguage(val code: String, val label: String) {
    ENGLISH("en", "English"),
    KOREAN("ko", "한국어"),
    ;

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

/**
 * The app's words in the language chosen. The UI is written in English, so
 * English needs nothing; Korean comes from a dictionary in assets
 * (i18n/ko.tsv), English line to Korean line. A line may hold places, {0},
 * {1}..., for what the code puts in at run time - "Page {0}" becomes
 * "{0}페이지" - and what fills a place is looked up in turn, so "3 notes"
 * inside a message is Korean too. Anything the dictionary lacks shows in
 * English rather than not at all.
 */
object L10n {
    var language by mutableStateOf(AppLanguage.ENGLISH)
        private set

    private var exact: Map<String, String> = emptyMap()
    private var patterns: List<Pair<Regex, String>> = emptyList()
    private val cache = object : java.util.LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2048
    }
    private var loaded = false

    val korean: Boolean get() = language == AppLanguage.KOREAN

    /** Reads the saved choice and, for Korean, the dictionary. Once, at start. */
    fun init(context: Context) {
        language = AppLanguage.fromCode(prefs(context).getString(KEY, null))
        if (korean) load(context)
    }

    fun choose(context: Context, next: AppLanguage) {
        prefs(context).edit().putString(KEY, next.code).apply()
        if (next == AppLanguage.KOREAN) load(context)
        synchronized(cache) { cache.clear() }
        language = next
    }

    private fun prefs(context: Context) = context.getSharedPreferences("pens", Context.MODE_PRIVATE)

    private fun load(context: Context) {
        if (loaded) return
        val lines = runCatching {
            context.assets.open("i18n/ko.tsv").bufferedReader(Charsets.UTF_8).readLines()
        }.getOrDefault(emptyList())
        val table = HashMap<String, String>(lines.size * 2)
        val templates = ArrayList<Pair<Regex, String>>()
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val en = unescape(line.substring(0, tab))
            val ko = unescape(line.substring(tab + 1))
            if (ko.isEmpty()) continue
            if (PLACE.containsMatchIn(en)) templates += templateRegex(en) to ko else table[en] = ko
        }
        exact = table
        // The longest first: "Delete folder \"{0}\"?" before "Delete {0}?".
        patterns = templates.sortedByDescending { it.first.pattern.length }
        loaded = true
    }

    /** [english] in the language chosen. */
    fun tr(english: String): String {
        if (!korean || english.isEmpty()) return english
        synchronized(cache) { cache[english] }?.let { return it }
        val out = translate(english, depth = 0)
        synchronized(cache) { cache[english] = out }
        return out
    }

    private fun translate(english: String, depth: Int): String {
        exact[english]?.let { return it }
        val trimmed = english.trim()
        if (trimmed != english) exact[trimmed]?.let { return english.replace(trimmed, it) }
        countRule(english)?.let { return it }
        if (depth < 3) for ((regex, ko) in patterns) {
            val match = regex.matchEntire(english) ?: continue
            var result = ko
            match.groupValues.drop(1).forEachIndexed { i, value ->
                result = result.replace("{$i}", translate(value, depth + 1))
            }
            return result
        }
        return english
    }

    /** "3 notes" - [countNoun]'s shape - as Korean counts it: "노트 3개", "3페이지". */
    private fun countRule(text: String): String? {
        val m = COUNT.matchEntire(text) ?: return null
        val n = m.groupValues[1]
        val noun = m.groupValues[2]
        val form = COUNTS[noun] ?: COUNTS[noun.removeSuffix("s")] ?: return null
        return form.replace("#", n)
    }

    /** A place in a line: {0}, {1}... or a format's own, %d, %.1f, %s; %% is a plain %. */
    private val PLACE = Regex("""\{\d+\}|%[-#+0-9.]*[a-zA-Z]""")
    private val COUNT = Regex("""(\d+) ([A-Za-z][A-Za-z ]*?)""")
    /** The nouns the app counts, each as Korean says how many: # is the number. */
    private val COUNTS = mapOf(
        "page" to "#페이지", "note" to "노트 #개", "selected note" to "선택한 노트 #개", "setting" to "설정 #개",
        "item" to "항목 #개", "image" to "이미지 #개", "picture" to "사진 #장", "PDF" to "PDF #개", "word" to "#단어",
        "stroke" to "획 #개", "layer" to "레이어 #개", "file" to "파일 #개", "folder" to "폴더 #개",
        "minute" to "#분", "second" to "#초", "hour" to "#시간", "day" to "#일", "time" to "#번",
        "match" to "#건", "matche" to "#건", "result" to "결과 #개", "card" to "카드 #장", "tab" to "탭 #개",
    )

    /** [en] as a pattern: each place, in the order it comes, is a group; the Korean line names them {0}, {1}... */
    private fun templateRegex(en: String): Regex {
        val sb = StringBuilder()
        var last = 0
        for (m in PLACE.findAll(en)) {
            sb.append(Regex.escape(en.substring(last, m.range.first).replace("%%", "%")))
            sb.append("(.+?)")
            last = m.range.last + 1
        }
        sb.append(Regex.escape(en.substring(last).replace("%%", "%")))
        return Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL)
    }

    private fun unescape(s: String): String = s.replace("\\n", "\n").replace("\\t", "\t")

    private const val KEY = "language"
}

/** [this] in the language chosen; see [L10n]. */
internal val String.tr: String get() = L10n.tr(this)

/**
 * Korean text set as Korean: its locale, so the system's Korean font and
 * line breaking are used, and breaks kept between words rather than inside
 * them, as Korean is read. English is left as the style has it.
 */
@Composable
internal fun TextStyle.forLanguage(): TextStyle =
    if (!L10n.korean) this
    else merge(TextStyle(localeList = KOREAN_LOCALES, lineBreak = LineBreak.Paragraph.copy(wordBreak = androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase)))

private val KOREAN_LOCALES = LocaleList(Locale("ko-KR"))

/**
 * Material's Text, with its words in the language chosen. Declared in this
 * package so every screen's Text resolves here first, without each call
 * site knowing there is more than one language.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        text = L10n.tr(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
        fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
        textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
        softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout,
        style = style.forLanguage(),
    )
}

/** Material's Text for styled text, which is passed through as it is written. */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
        fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
        textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
        softWrap = softWrap, maxLines = maxLines, minLines = minLines, inlineContent = inlineContent,
        onTextLayout = onTextLayout, style = style.forLanguage(),
    )
}

/** Android's Toast, with its message in the language chosen. */
internal object KToast {
    fun makeText(context: Context, text: CharSequence, duration: Int): android.widget.Toast =
        android.widget.Toast.makeText(context, L10n.tr(text.toString()), duration)

    fun makeText(context: Context, resId: Int, duration: Int): android.widget.Toast =
        android.widget.Toast.makeText(context, resId, duration)
}
