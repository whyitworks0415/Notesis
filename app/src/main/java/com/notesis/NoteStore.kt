package com.notesis

import android.content.Context
import androidx.ink.brush.Brush
import androidx.ink.storage.StrokeInputBatchSerialization
import androidx.ink.strokes.Stroke
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** A note as the list screen needs to know it, without loading any ink. */
data class NoteMeta(
    val id: String,
    val title: String,
    val modified: Long,
    val strokeCount: Int,
)

/**
 * Notes on disk, one directory each. No database: the file tree is the source
 * of truth, so a note survives anything short of deleting the folder.
 *
 * ponytail: lives in filesDir, which means notes are backed up with the app but
 * invisible to file managers. P3 moves the root to a SAF-picked folder so the
 * user's own cloud client can sync it - the layout below does not change.
 */
class NoteStore(context: Context) {

    private val root = File(context.filesDir, "notes").apply { mkdirs() }

    fun list(): List<NoteMeta> =
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { readMeta(it) }
            ?.sortedByDescending { it.modified }
            ?: emptyList()

    fun create(title: String): NoteMeta {
        val id = UUID.randomUUID().toString()
        val meta = NoteMeta(id, title, System.currentTimeMillis(), 0)
        File(root, id).mkdirs()
        writeMeta(meta)
        return meta
    }

    fun rename(id: String, title: String) {
        val meta = readMeta(File(root, id)) ?: return
        writeMeta(meta.copy(title = title, modified = System.currentTimeMillis()))
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    fun save(id: String, strokes: List<Stroke>) {
        val dir = File(root, id)
        if (!dir.isDirectory) return
        // Write beside the real file and rename over it, so a crash mid-write
        // leaves the previous save intact instead of a truncated page.
        val tmp = File(dir, "page.bin.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(strokes.size)
            for (stroke in strokes) {
                val brush = stroke.brush
                out.writeInt(Tool.ofBrushFamily(brush.family).ordinal)
                out.writeInt(brush.colorIntArgb)
                out.writeFloat(brush.size)
                out.writeFloat(brush.epsilon)
                val inputs = ByteArrayOutputStream().also {
                    StrokeInputBatchSerialization.encode(stroke.inputs, it)
                }.toByteArray()
                out.writeInt(inputs.size)
                out.write(inputs)
            }
        }
        tmp.renameTo(File(dir, "page.bin"))
        readMeta(dir)?.let {
            writeMeta(it.copy(modified = System.currentTimeMillis(), strokeCount = strokes.size))
        }
    }

    fun load(id: String): MutableList<Stroke> {
        val file = File(root, "$id/page.bin")
        if (!file.isFile) return mutableListOf()
        val strokes = mutableListOf<Stroke>()
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return mutableListOf()
            repeat(input.readInt()) {
                val tool = Tool.entries[input.readInt()]
                val color = input.readInt()
                val size = input.readFloat()
                val epsilon = input.readFloat()
                val bytes = ByteArray(input.readInt()).also { input.readFully(it) }
                // Only the raw inputs are stored; the mesh is rebuilt here. That
                // is what keeps saved notes readable across ink versions.
                val inputs = StrokeInputBatchSerialization.decode(ByteArrayInputStream(bytes))
                val brush = Brush.createWithColorIntArgb(tool.brushFamily(), color, size, epsilon)
                strokes += Stroke(brush, inputs)
            }
        }
        return strokes
    }

    private fun readMeta(dir: File): NoteMeta? {
        val file = File(dir, "meta.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            NoteMeta(
                id = dir.name,
                title = json.optString("title", "제목 없음"),
                modified = json.optLong("modified"),
                strokeCount = json.optInt("strokeCount"),
            )
        }.getOrNull()
    }

    private fun writeMeta(meta: NoteMeta) {
        val json = JSONObject()
            .put("title", meta.title)
            .put("modified", meta.modified)
            .put("strokeCount", meta.strokeCount)
        File(root, "${meta.id}/meta.json").writeText(json.toString())
    }

    private companion object {
        const val MAGIC = 0x4E545353 // "NTSS"
        const val VERSION = 1
    }
}
