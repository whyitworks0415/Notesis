package com.notesis

import android.content.Context
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

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
    val middle get() = (top + bottom) / 2f
}

/**
 * Sorts a page's strokes into lines of writing.
 *
 * Handing a recogniser a whole page at once gets a whole page's worth of
 * nonsense back - it expects something like a line. Nothing in a note says
 * where the lines are, so they are recovered from where the strokes sit: a
 * stroke joins the line it vertically overlaps, and starts a new one when it
 * does not. Within a line, strokes are read left to right.
 *
 * Returns indices into [boxes], grouped and ordered for reading.
 */
internal fun groupIntoLines(boxes: List<InkBox>): List<List<Int>> {
    if (boxes.isEmpty()) return emptyList()
    val order = boxes.indices.sortedBy { boxes[it].top }
    val lines = mutableListOf<MutableList<Int>>()
    var top = 0f
    var bottom = 0f
    for (index in order) {
        val box = boxes[index]
        val line = lines.lastOrNull()
        // Overlap against the line built so far, measured against the shorter
        // of the two, so a tall stroke does not swallow a short line or the
        // other way round.
        val overlap = minOf(bottom, box.bottom) - maxOf(top, box.top)
        val shorter = minOf(bottom - top, box.height).coerceAtLeast(1f)
        if (line == null || overlap / shorter < LINE_OVERLAP) {
            lines += mutableListOf(index)
            top = box.top
            bottom = box.bottom
        } else {
            line += index
            top = minOf(top, box.top)
            bottom = maxOf(bottom, box.bottom)
        }
    }
    return lines.map { line -> line.sortedBy { boxes[it].left } }
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
        for (tag in LANGUAGES) {
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

    /** Blocking; call it off the main thread. Null means nothing was read. */
    fun textOf(strokes: List<Stroke>): String? {
        if (strokes.isEmpty()) return ""
        if (!prepare()) return null
        val boxes = strokes.map { stroke ->
            val box = stroke.shape.computeBoundingBox() ?: return@map null
            InkBox(box.xMin, box.yMin, box.xMax, box.yMax)
        }
        val usable = boxes.withIndex().filter { it.value != null }
        if (usable.isEmpty()) return ""
        val lines = groupIntoLines(usable.map { it.value!! })
        val out = StringBuilder()
        for (line in lines) {
            val ink = inkOf(line.map { strokes[usable[it].index] }) ?: continue
            for (recognizer in recognizers.values) {
                val text = runCatching {
                    Tasks.await(recognizer.recognize(ink)).candidates.firstOrNull()?.text
                }.getOrNull().orEmpty()
                if (text.isNotBlank()) out.append(text).append('\n')
            }
        }
        return out.toString()
    }

    private fun inkOf(strokes: List<Stroke>): Ink? {
        val builder = Ink.builder()
        val scratch = StrokeInput()
        var points = 0
        for (stroke in strokes) {
            val line = Ink.Stroke.builder()
            for (i in 0 until stroke.inputs.size) {
                val input = stroke.inputs.populate(i, scratch)
                line.addPoint(Ink.Point.create(input.x, input.y, input.elapsedTimeMillis))
                points++
            }
            builder.addStroke(line.build())
        }
        return if (points == 0) null else builder.build()
    }

    fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }

    private companion object {
        /**
         * Both, always. These notes mix Korean and English within a line, and a
         * search index would rather hold two readings of a word than miss it.
         */
        val LANGUAGES = listOf("ko", "en")
    }
}

/** How much of the shorter stroke has to overlap the line to join it. */
private const val LINE_OVERLAP = 0.35f
