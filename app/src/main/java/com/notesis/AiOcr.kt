package com.notesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Reading a selection of handwriting with Claude instead of the on-device
 * recogniser: the ink is drawn as a picture and sent with the person's own
 * API key. Much better at messy, slanted or mixed-language writing than ML
 * Kit, at the cost of a network round trip and the key's own billing. Only
 * the selection the person asked to read is sent, and only when they ask;
 * the search index stays on the device.
 *
 * The key is kept in the app's no-backup folder, so it never leaves the
 * tablet with a device backup or the settings export.
 */
internal object AiOcr {
    /** The models offered: Claude Opus 5.5 by default, the faster and cheaper two beside it. */
    val MODELS = listOf(
        "claude-opus-5-5" to "Claude Opus 5.5",
        "claude-sonnet-5-5" to "Claude Sonnet 5.5",
        "claude-haiku-5-5" to "Claude Haiku 5.5",
    )
    const val DEFAULT_MODEL = "claude-opus-5-5"

    /** The language tag readings from here carry; see [OcrReading.language]. */
    const val LANGUAGE = "ai"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false) && apiKey(context) != null

    fun on(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)
    fun setOn(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ON, value).apply()

    fun model(context: Context): String = prefs(context).getString(KEY_MODEL, null)
        ?.takeIf { id -> MODELS.any { it.first == id } } ?: DEFAULT_MODEL
    fun setModel(context: Context, id: String) = prefs(context).edit().putString(KEY_MODEL, id).apply()

    fun apiKey(context: Context): String? = runCatching { keyFile(context).readText().trim() }.getOrNull()
        ?.takeIf { it.isNotEmpty() }

    fun setApiKey(context: Context, key: String?) {
        val file = keyFile(context)
        if (key.isNullOrBlank()) file.delete() else file.writeText(key.trim())
    }

    /**
     * The handwriting in [strokes] as text, one line per line written.
     * Blocking: call it off the main thread. Fails with a message fit to show.
     */
    fun read(context: Context, strokes: List<Stroke>, languages: List<String>): Result<List<String>> = runCatching {
        val key = apiKey(context) ?: error("No API key set")
        val picture = render(strokes) ?: error("Nothing to read")
        val png = ByteArrayOutputStream().use { out ->
            picture.compress(Bitmap.CompressFormat.PNG, 100, out)
            picture.recycle()
            out.toByteArray()
        }
        val model = model(context)
        val names = languages.map { tag -> InkIndexer.OFFERED.firstOrNull { it.first == tag }?.second ?: tag }
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            // Reading what is there needs little deliberation.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system("You transcribe handwritten notes. Reply with the transcription only: no preamble, " +
                "no comments, no quotation marks, no Markdown. Keep the writer's line breaks, one written " +
                "line per line. Keep the language each word is written in; do not translate. Write math " +
                "and symbols as plain text. If a word is unclear, give the most likely reading.")
            .addUserMessageOfContentBlockParams(listOf(
                ContentBlockParam.ofImage(Base64ImageSource.builder()
                    .data(Base64.encodeToString(png, Base64.NO_WRAP))
                    .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                    .build()),
                ContentBlockParam.ofText("Transcribe this handwriting. It may be in: ${names.joinToString(", ")}."),
            ))
        // A declined request goes to a fallback model of the server's choosing
        // rather than coming back empty. Not offered for Haiku.
        if (model != "claude-haiku-5-5") {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val client = AnthropicOkHttpClient.builder().apiKey(key).build()
        try {
            val response = client.messages().create(builder.build())
            if (response.stopReason().orElse(null) == StopReason.REFUSAL) error("The model declined to read this")
            val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
            text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        } finally {
            client.close()
        }
    }

    /** [strokes] in black-on-white as written, scaled so the longer side is at most [MAX_SIDE_PX]. */
    private fun render(strokes: List<Stroke>): Bitmap? {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (stroke in strokes) {
            val box = stroke.shape.computeBoundingBox() ?: continue
            minX = minOf(minX, box.xMin)
            minY = minOf(minY, box.yMin)
            maxX = maxOf(maxX, box.xMax)
            maxY = maxOf(maxY, box.yMax)
        }
        if (!minX.isFinite() || maxX <= minX || maxY <= minY) return null
        val width = maxX - minX
        val height = maxY - minY
        val scale = minOf(MAX_SIDE_PX / max(width, height), MAX_SCALE)
        val pad = PAD_PX
        val w = (width * scale + pad * 2).toInt().coerceAtLeast(1)
        val h = (height * scale + pad * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val transform = Matrix().apply {
            setTranslate(-minX, -minY)
            postScale(scale, scale)
            postTranslate(pad, pad)
        }
        val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
        for (stroke in strokes) {
            val saved = canvas.save()
            try {
                canvas.concat(transform)
                runCatching { renderer.draw(canvas, stroke, transform) }
            } finally {
                canvas.restoreToCount(saved)
            }
        }
        return bitmap
    }

    private fun prefs(context: Context) = context.getSharedPreferences("pens", Context.MODE_PRIVATE)
    private fun keyFile(context: Context) = File(context.noBackupFilesDir, "ai_ocr_key")

    private const val KEY_ON = "aiOcrOn"
    private const val KEY_MODEL = "aiOcrModel"
    /** Long enough for small writing to stay legible, short of the size the model scales images down from. */
    private const val MAX_SIDE_PX = 1568f
    private const val MAX_SCALE = 4f
    private const val PAD_PX = 24f
}

/** The AI reading's switch, its model and the key it is sent with. */
@Composable
internal fun AiOcrSettings() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AiOcr.on(context)) }
    var model by remember { mutableStateOf(AiOcr.model(context)) }
    var hasKey by remember { mutableStateOf(AiOcr.apiKey(context) != null) }
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        SettingsToggle("Read a selection with Claude", on, { on = it; AiOcr.setOn(context, it) },
            hint = "When you read a lasso selection, the selected ink is sent as a picture to Claude with your " +
                "own Anthropic API key, which reads messy and slanted writing far better. Needs the internet " +
                "and is billed to your key. Search stays on the tablet.")
        if (on) {
            Text("Model", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                AiOcr.MODELS.forEach { (id, label) ->
                    SettingsChoiceChip(selected = model == id, onClick = { model = id; AiOcr.setModel(context, id) },
                        label = label)
                }
            }
            SettingsNote("Opus 5.5 reads best; Sonnet and Haiku are quicker and cost less.")
            if (hasKey) {
                Row {
                    SettingsNote("An API key is saved on this tablet.", Modifier.weight(1f))
                    TextButton(onClick = { AiOcr.setApiKey(context, null); hasKey = false }) { Text("Remove key") }
                }
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.trim() },
                    label = { Text("Anthropic API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(enabled = draft.isNotEmpty(), onClick = {
                    AiOcr.setApiKey(context, draft)
                    draft = ""
                    hasKey = true
                }) { Text("Save key") }
                SettingsNote("Kept on this tablet only, never in backups or the settings file.")
            }
        }
    }
}
