package com.notesis

import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/** Which of a page's masks a study round asks: all, the ones last answered wrong, or the ones due today. */
enum class StudyFilter { ALL, WRONG, DUE }

/** Longest gap between reviews of a mask that keeps being answered right, in days. */
internal const val MAX_REVIEW_INTERVAL = 60

/**
 * Spaced review, in its simplest form: a right answer doubles the gap before
 * the mask is asked again (1, 2, 4, 8 … days, up to [MAX_REVIEW_INTERVAL]); a
 * wrong one brings it back today and starts the gaps over.
 */
internal fun answered(record: MaskRecord, right: Boolean, today: Long): MaskRecord =
    if (right) {
        val interval = (if (record.interval <= 0) 1 else record.interval * 2).coerceAtMost(MAX_REVIEW_INTERVAL)
        record.copy(right = record.right + 1, last = MaskRecord.RESULT_RIGHT, interval = interval, due = today + interval)
    } else {
        record.copy(wrong = record.wrong + 1, last = MaskRecord.RESULT_WRONG, interval = 0, due = today)
    }

/** Due on [today]: never studied, or its next review has come. */
internal fun maskDue(record: MaskRecord, today: Long): Boolean = record.due <= today

/** Today, as days since 1970 in the tablet's time zone. */
internal fun today(): Long {
    val now = System.currentTimeMillis()
    return (now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L
}

/** What has been learnt about one strip of tape: its name and how recall has gone. */
data class MaskRecord(
    val name: String = "",
    val right: Int = 0,
    val wrong: Int = 0,
    /** The most recent answer: [RESULT_NONE], [RESULT_RIGHT] or [RESULT_WRONG]. */
    val last: Int = RESULT_NONE,
    /** Days until it is asked again after a right answer; 0 until one, and again after a wrong one. */
    val interval: Int = 0,
    /** The day (days since 1970) it is next due; 0 for never studied, which is due at once. */
    val due: Long = 0L,
) {
    companion object {
        const val RESULT_NONE = 0
        const val RESULT_RIGHT = 1
        const val RESULT_WRONG = 2
    }
}

/**
 * Study state for a note's tape, kept in its own file beside the note.
 *
 * Masks are saved as strokes only, so a mask is identified by where it starts
 * and how long it is - the raw inputs, which do not change between loads. The
 * tape itself and its file format stay exactly as they were; deleting a mask
 * just leaves an unused record behind, which [prune] clears.
 */
class MaskStudy(private val file: File) {
    private val records = HashMap<String, MaskRecord>()

    init {
        runCatching {
            val json = JSONObject(file.readText())
            for (key in json.keys()) {
                val entry = json.getJSONObject(key)
                records[key] = MaskRecord(
                    name = entry.optString("name"),
                    right = entry.optInt("right"),
                    wrong = entry.optInt("wrong"),
                    last = entry.optInt("last"),
                    interval = entry.optInt("interval"),
                    due = entry.optLong("due"),
                )
            }
        }
    }

    fun record(key: String): MaskRecord = records[key] ?: MaskRecord()

    fun rename(key: String, name: String) = update(key) { it.copy(name = name.trim()) }

    fun answer(key: String, right: Boolean, today: Long = today()) = update(key) { answered(it, right, today) }

    /** Whether the mask [key] is due for review on [today]. */
    fun isDue(key: String, today: Long = today()): Boolean = maskDue(record(key), today)

    /** Totals over the given masks: how many were last answered right, wrong, or not yet. */
    fun summary(keys: Collection<String>): Triple<Int, Int, Int> {
        var right = 0
        var wrong = 0
        var unseen = 0
        for (key in keys) when (record(key).last) {
            MaskRecord.RESULT_RIGHT -> right++
            MaskRecord.RESULT_WRONG -> wrong++
            else -> unseen++
        }
        return Triple(right, wrong, unseen)
    }

    /** Drops records for masks that no longer exist on any loaded page. */
    fun prune(live: Set<String>) {
        if (records.keys.retainAll(live)) save()
    }

    private inline fun update(key: String, change: (MaskRecord) -> MaskRecord) {
        records[key] = change(record(key))
        save()
    }

    private fun save() {
        val json = JSONObject()
        for ((key, record) in records) {
            if (record == MaskRecord()) continue
            json.put(key, JSONObject()
                .put("name", record.name)
                .put("right", record.right)
                .put("wrong", record.wrong)
                .put("last", record.last)
                .put("interval", record.interval)
                .put("due", record.due))
        }
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.writeText(json.toString())
                temp.delete()
            }
        }
    }

    companion object {
        /** A key that survives reloading: page, input count and where the tape starts. */
        fun keyOf(pageId: String, stroke: Stroke): String {
            val inputs = stroke.inputs
            if (inputs.size == 0) return "$pageId:0"
            val first = inputs.populate(0, StrokeInput())
            return "$pageId:${inputs.size}:${(first.x * 10f).roundToInt()}:${(first.y * 10f).roundToInt()}"
        }
    }
}

/** A run through one page's tape: each strip is hidden, recalled, then marked. */
data class StudySession(
    val pageIndex: Int,
    /** Mask indices on the page, in the order they are asked. */
    val queue: List<Int>,
    val position: Int = 0,
    /** Whether the current mask has been lifted to check the answer. */
    val showing: Boolean = false,
    val right: Int = 0,
    val wrong: Int = 0,
    /** Mask indices answered wrong in this run, for going over just those again. */
    val missed: List<Int> = emptyList(),
) {
    val done: Boolean get() = position >= queue.size
    val current: Int? get() = queue.getOrNull(position)
}
