package com.notesis

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * When each stroke was written during a recording, kept beside the audio as
 * `<recording>.json`. The strokes and the audio stay exactly as they were - an
 * older recording simply has no timeline and plays as before.
 */
object RecordingTimeline {
    /** [key] is [MaskStudy.keyOf] for the stroke; [atMs] is its offset into the audio. */
    data class Event(val key: String, val atMs: Long)

    fun fileFor(audio: File): File = File(audio.parentFile, audio.nameWithoutExtension + ".json")

    fun load(audio: File): List<Event> = runCatching {
        val array = JSONArray(fileFor(audio).readText())
        (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            Event(entry.getString("k"), entry.getLong("t"))
        }.sortedBy { it.atMs }
    }.getOrDefault(emptyList())

    fun save(audio: File, events: List<Event>) {
        if (events.isEmpty()) return
        val array = JSONArray()
        for (event in events) array.put(JSONObject().put("k", event.key).put("t", event.atMs))
        runCatching { fileFor(audio).writeText(array.toString()) }
    }

    /** Keys of the strokes written in the [windowMs] up to [positionMs]. */
    fun around(events: List<Event>, positionMs: Long, windowMs: Long): List<Event> =
        events.filter { it.atMs in (positionMs - windowMs)..positionMs }
}
