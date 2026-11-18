package com.notesis

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * The ways Notesis reads writing (the release calls them its five kinds of OCR):
 *
 *  - Handwriting strokes: the pen's own strokes, in the order they were
 *    written, read by ML Kit's digital ink recogniser on the tablet. What
 *    there was before; the best for handwriting, since it knows how each
 *    letter was drawn.
 *  - Text in the picture: the selection drawn as a picture and read by ML
 *    Kit's text recogniser (Korean and Latin) on the tablet. Reads printed
 *    text, a PDF under the pen, pictures on the page, and neat handwriting.
 *  - Both, side by side: the two above, each line offered both ways.
 *  - Scanning: the camera's document scanner makes a note, and its pages
 *    are read the picture way so the note can be searched.
 *  - AI: a small picture of the selection sent to Google's Gemini with the
 *    owner's own (free) key, for text, maths as LaTeX or a table as Markdown.
 *    Kept to few tokens: a low image resolution, a short instruction, no
 *    thinking, and a cap on the answer.
 */

/** How a selection is read; the scanner (see [PageTextOcr]) is the fifth way and makes notes instead. */
internal enum class OcrEngine(val key: String, val label: String, val about: String) {
    INK("ink", "Handwriting strokes", "On the tablet. Reads the strokes as they were written: best for handwriting."),
    PICTURE("picture", "Text in the picture", "On the tablet. Reads the selection as an image: printed text, PDFs, pictures."),
    BOTH("both", "Both, side by side", "On the tablet. Strokes and picture together; each line offered both ways."),
    AI("ai", "AI (Gemini, free key)", "Sends a small picture of the selection to Google Gemini with your own key.");

    companion object {
        fun of(key: String?): OcrEngine = entries.firstOrNull { it.key == key } ?: INK
    }
}

/** The way the lasso's Read last used, kept with the pens: the next selection is read the same way. */
internal fun ocrEngine(context: Context): OcrEngine =
    OcrEngine.of(context.getSharedPreferences("pens", Context.MODE_PRIVATE).getString("ocrEngine", null))

internal fun setOcrEngine(context: Context, engine: OcrEngine) {
    context.getSharedPreferences("pens", Context.MODE_PRIVATE).edit().putString("ocrEngine", engine.key).apply()
}

/** Longer side, in pixels, a selection is drawn at for the on-tablet text recogniser. */
internal const val PICTURE_OCR_SIDE = 1600

/** What the AI is asked to write the selection out as. */
internal enum class AiOcrMode(val key: String, val label: String) {
    TEXT("text", "Text"),
    MATH("math", "Math (LaTeX)"),
    TABLE("table", "Table (Markdown)");

    companion object {
        fun of(key: String?): AiOcrMode = entries.firstOrNull { it.key == key } ?: TEXT
    }
}

/**
 * How finely the AI looks at the picture: the image tokens a request costs
 * follow this, not the picture's size. Low is the default; it reads clear
 * writing, and a smudged line can be asked again at a finer one.
 */
internal enum class AiDetail(val key: String, val label: String, val level: String, val maxSide: Int) {
    LOW("low", "Fewest tokens", "media_resolution_low", 768),
    MEDIUM("medium", "Balanced", "media_resolution_medium", 1024),
    HIGH("high", "Most accurate", "media_resolution_high", 1536);

    companion object {
        fun of(key: String?): AiDetail = entries.firstOrNull { it.key == key } ?: LOW
    }
}

/** The instruction sent with the picture: short, since every word of it is paid for in tokens. */
internal fun aiOcrPrompt(mode: AiOcrMode): String = when (mode) {
    AiOcrMode.TEXT -> "Transcribe all text in the image exactly, keeping its language and line breaks. Output only the text."
    AiOcrMode.MATH -> "Transcribe the math in the image as LaTeX. Output only the LaTeX, no $ or code fences."
    AiOcrMode.TABLE -> "Transcribe the table in the image as a Markdown table. Output only the table."
}

/** The most a request may write back; a page of handwriting fits well inside it. */
internal const val AI_OCR_MAX_OUTPUT = 2048

/** The model asked when the owner has not picked one: Google's alias for its newest light model. */
internal const val AI_OCR_DEFAULT_MODEL = "gemini-flash-lite-latest"

/** A model name safe to put in the request's path. */
internal fun validAiModel(model: String): Boolean = model.isNotBlank() && model.length <= 80 &&
    model.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }

/**
 * The generateContent request for one picture. [lean] adds what keeps it
 * cheap - the per-image resolution and as little thinking as the model
 * allows; a model that refuses either is asked again without them.
 */
internal fun aiOcrRequest(prompt: String, jpegBase64: String, detail: AiDetail, lean: Boolean): String {
    val image = JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpegBase64))
    if (lean) image.put("mediaResolution", JSONObject().put("level", detail.level))
    val config = JSONObject().put("temperature", 0).put("maxOutputTokens", AI_OCR_MAX_OUTPUT)
    if (lean) config.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
    return JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)).put(image))))
        .put("generationConfig", config)
        .toString()
}

/** What came back: the text, the tokens it cost as the service counted them, or why there is none. */
internal data class AiOcrResult(val text: String, val inputTokens: Int, val outputTokens: Int, val error: String? = null)

/** Reads a generateContent answer; the model's own thoughts, if it sent any, are left out. */
internal fun parseAiOcrResponse(body: String): AiOcrResult {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return AiOcrResult("", 0, 0, "The answer could not be read")
    json.optJSONObject("error")?.let { return AiOcrResult("", 0, 0, it.optString("message").take(200).ifBlank { "Request refused" }) }
    val usage = json.optJSONObject("usageMetadata")
    val input = usage?.optInt("promptTokenCount") ?: 0
    val output = (usage?.optInt("candidatesTokenCount") ?: 0) + (usage?.optInt("thoughtsTokenCount") ?: 0)
    val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
    val text = buildString {
        if (parts != null) for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought")) continue
            append(part.optString("text"))
        }
    }.trim().removeSurrounding("```").trim()
    if (text.isEmpty()) {
        val reason = json.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason").orEmpty()
        return AiOcrResult("", input, output, if (reason.isNotBlank() && reason != "STOP") "No text came back ($reason)" else "No text found")
    }
    return AiOcrResult(text, input, output)
}

/** The size a picture is sent at: its longer side at most [maxSide], never enlarged. */
internal fun aiImageSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    val longer = maxOf(width, height).coerceAtLeast(1)
    if (longer <= maxSide) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
    val scale = maxSide.toFloat() / longer
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/** Text read some other way, one line to a line, as the result dialog lists readings; [source] names where it came from. */
internal fun ocrLinesOf(text: String, source: String): List<OcrLine> =
    text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.map { OcrLine(listOf(OcrReading(it, source))) }

/**
 * The strokes' reading and the picture's, line by line: each line offers
 * every distinct reading either way gave, the strokes' first. Lines one way
 * found and the other did not are kept as they are.
 */
internal fun mergeOcrLines(first: List<OcrLine>, second: List<OcrLine>): List<OcrLine> =
    (0 until maxOf(first.size, second.size)).map { i ->
        val readings = (first.getOrNull(i)?.readings.orEmpty() + second.getOrNull(i)?.readings.orEmpty())
        OcrLine(readings.distinctBy { it.text.trim() })
    }.filter { it.readings.isNotEmpty() }

/** The source tags image readings carry, shown beside a reading in place of a language. */
internal const val OCR_SOURCE_PICTURE = "picture"
internal const val OCR_SOURCE_AI = "ai"

/** A Play services task, awaited. */
internal suspend fun <T> Task<T>.awaited(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

/**
 * The AI way: the owner's own Gemini key and model, kept on this tablet only
 * (out of backups and settings exports, never logged), and one request per
 * reading. Nothing is sent until the owner has put a key in and chosen AI.
 */
internal object AiOcr {
    private const val PREFS = "ai-ocr"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(context: Context): String = prefs(context).getString("key", "").orEmpty()
    fun setKey(context: Context, key: String) { prefs(context).edit().putString("key", key.trim()).apply() }
    fun model(context: Context): String = prefs(context).getString("model", null)?.takeIf(::validAiModel) ?: AI_OCR_DEFAULT_MODEL
    fun setModel(context: Context, model: String) {
        val clean = model.trim()
        prefs(context).edit().apply { if (validAiModel(clean)) putString("model", clean) else remove("model") }.apply()
    }
    fun detail(context: Context): AiDetail = AiDetail.of(prefs(context).getString("detail", null))
    fun setDetail(context: Context, detail: AiDetail) { prefs(context).edit().putString("detail", detail.key).apply() }
    fun mode(context: Context): AiOcrMode = AiOcrMode.of(prefs(context).getString("mode", null))
    fun setMode(context: Context, mode: AiOcrMode) { prefs(context).edit().putString("mode", mode.key).apply() }

    /** Reads [picture] (not recycled here). Off the main thread; a failure comes back as [AiOcrResult.error]. */
    suspend fun read(context: Context, picture: Bitmap, mode: AiOcrMode): AiOcrResult = withContext(Dispatchers.IO) {
        val key = key(context)
        if (key.isBlank()) return@withContext AiOcrResult("", 0, 0, "Add your Gemini API key in the note's settings first")
        val detail = detail(context)
        val (width, height) = aiImageSize(picture.width, picture.height, detail.maxSide)
        val scaled = if (width == picture.width && height == picture.height) picture
            else Bitmap.createScaledBitmap(picture, width, height, true)
        val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        if (scaled !== picture) scaled.recycle()
        val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val prompt = aiOcrPrompt(mode)
        val url = URL(ENDPOINT + model(context) + ":generateContent")
        runCatching {
            var answer = post(url, key, aiOcrRequest(prompt, base64, detail, lean = true))
            // A model that does not take the lean settings says so with a 400; asked plainly once.
            if (answer.first == 400) answer = post(url, key, aiOcrRequest(prompt, base64, detail, lean = false))
            val result = parseAiOcrResponse(answer.second)
            if (answer.first in 200..299 || result.error != null) result
            else result.copy(error = "The service answered ${answer.first}")
        }.getOrElse { AiOcrResult("", 0, 0, "No connection to the AI service") }
    }

    private fun post(url: URL, key: String, body: String): Pair<Int, String> {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            // In a header, not the address: an address ends up in logs.
            connection.setRequestProperty("x-goog-api-key", key)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            connection.disconnect()
        }
    }
}
