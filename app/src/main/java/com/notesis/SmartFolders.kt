package com.notesis

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/**
 * A folder that fills itself: every note that carries [tag], was written in
 * the last [hours] hours, is a favourite, is of one [kind], or is among the
 * last [recentlyOpened] notes opened - each only when set, all of those set
 * at once. Nothing is moved into it; it is a way of looking at the library.
 */
internal data class SmartFolder(
    val id: String,
    val name: String,
    val tag: String? = null,
    /** 0 for any age. */
    val hours: Int = 0,
    val favoritesOnly: Boolean = false,
    val kind: NoteKind? = null,
    /** 0 for no such rule; otherwise 1 to [RecentlyOpened.KEEP]. */
    val recentlyOpened: Int = 0,
) {
    /** [opened] is the notes' ids, the one opened last first. */
    fun matches(note: NoteMeta, now: Long, opened: List<String> = emptyList()): Boolean =
        (tag == null || tag in note.tags) &&
            (hours <= 0 || now - note.modified <= hours * HOUR_MS) &&
            (!favoritesOnly || note.favorite) &&
            (kind == null || note.kind == kind) &&
            (recentlyOpened <= 0 || note.id in opened.take(recentlyOpened))

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("hours", hours)
        .put("favorites", favoritesOnly).put("opened", recentlyOpened).apply {
            tag?.let { put("tag", it) }
            kind?.let { put("kind", it.name) }
        }

    companion object {
        private const val HOUR_MS = 60L * 60 * 1000

        /** The ages a smart folder can be set to, an hour to a month. */
        val AGE_STOPS = intArrayOf(1, 2, 3, 6, 12, 24, 48, 72, 96, 120, 144, 168, 240, 336, 504, 720)

        /** The stop nearest [hours] from above, as an index into [AGE_STOPS]. */
        fun ageStop(hours: Int): Int = AGE_STOPS.indexOfFirst { it >= hours }.let { if (it < 0) AGE_STOPS.size - 1 else it }

        /** "3 hours", "7 days": hours up to a day, days after. */
        fun ageLabel(hours: Int): String =
            if (hours < 24) countNoun(hours, "hour") else countNoun(hours / 24, "day")

        fun fromJson(json: JSONObject): SmartFolder? = runCatching {
            SmartFolder(
                id = json.getString("id"),
                name = json.getString("name"),
                tag = json.optString("tag").takeIf { it.isNotBlank() },
                // Folders made in 0.90.0 kept whole days.
                hours = if (json.has("hours")) json.optInt("hours") else json.optInt("days") * 24,
                favoritesOnly = json.optBoolean("favorites"),
                kind = json.optString("kind").takeIf { it.isNotBlank() }?.let { runCatching { NoteKind.valueOf(it) }.getOrNull() },
                recentlyOpened = json.optInt("opened").coerceIn(0, RecentlyOpened.KEEP),
            )
        }.getOrNull()
    }
}

/** The smart folders, kept with the library's other choices on this tablet. */
internal object SmartFolders {
    private fun prefs(context: Context) = context.getSharedPreferences("pens", Context.MODE_PRIVATE)

    fun load(context: Context): List<SmartFolder> = runCatching {
        val array = JSONArray(prefs(context).getString("smartFolders", "[]"))
        (0 until array.length()).mapNotNull { SmartFolder.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(context: Context, folders: List<SmartFolder>) {
        prefs(context).edit().putString("smartFolders", JSONArray(folders.map { it.toJson() }).toString()).apply()
    }

    /** [folder] added, or put in place of the one with its id. */
    fun put(context: Context, folder: SmartFolder) {
        val list = load(context)
        save(context, if (list.any { it.id == folder.id }) list.map { if (it.id == folder.id) folder else it } else list + folder)
    }

    fun remove(context: Context, id: String) = save(context, load(context).filterNot { it.id == id })
}

/** [list] with [id] moved to the front, as the note opened last, and no longer than [keep]. */
internal fun withOpened(list: List<String>, id: String, keep: Int = RecentlyOpened.KEEP): List<String> =
    (listOf(id) + list.filterNot { it == id }).take(keep)

/** The notes opened last, newest first, for a smart folder of them. Kept on this tablet only. */
internal object RecentlyOpened {
    const val KEEP = 20
    private fun prefs(context: Context) = context.getSharedPreferences("pens", Context.MODE_PRIVATE)

    fun load(context: Context): List<String> =
        prefs(context).getString("recentlyOpened", "").orEmpty().split(',').filter { it.isNotBlank() }

    fun note(context: Context, id: String) {
        prefs(context).edit().putString("recentlyOpened", withOpened(load(context), id).joinToString(",")).apply()
    }
}

/** Makes or changes a smart folder: its name and what puts a note in it. */
@Composable
internal fun SmartFolderDialog(
    initial: SmartFolder?,
    tags: List<String>,
    onDismiss: () -> Unit,
    onSave: (SmartFolder) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var tag by remember { mutableStateOf(initial?.tag) }
    var hours by remember { mutableStateOf(initial?.hours ?: 0) }
    var opened by remember { mutableStateOf(initial?.recentlyOpened ?: 0) }
    var favorites by remember { mutableStateOf(initial?.favoritesOnly ?: false) }
    var kind by remember { mutableStateOf(initial?.kind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New smart folder" else "Edit smart folder") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                Text("Notes that are…", style = MaterialTheme.typography.labelLarge)
                Text("Written in", style = MaterialTheme.typography.labelMedium)
                Row {
                    SettingsChoiceChip(selected = hours == 0, onClick = { hours = 0 }, label = "Any time",
                        modifier = Modifier.padding(end = Ui.Space.s))
                    SettingsChoiceChip(selected = hours > 0, onClick = { if (hours == 0) hours = 24 * 7 },
                        label = "Within a time")
                }
                if (hours > 0) {
                    Text("Written in the last ${SmartFolder.ageLabel(hours)}", style = MaterialTheme.typography.bodyMedium)
                    // Stops rather than a straight scale, so an hour and a month are both easy to land on.
                    SkinSlider(
                        value = SmartFolder.ageStop(hours).toFloat(),
                        onValueChange = { hours = SmartFolder.AGE_STOPS[it.roundToInt().coerceIn(0, SmartFolder.AGE_STOPS.size - 1)] },
                        valueRange = 0f..(SmartFolder.AGE_STOPS.size - 1).toFloat(),
                        step = 1f,
                    )
                }
                Text("Opened recently", style = MaterialTheme.typography.labelMedium)
                Row {
                    SettingsChoiceChip(selected = opened == 0, onClick = { opened = 0 }, label = "Any",
                        modifier = Modifier.padding(end = Ui.Space.s))
                    SettingsChoiceChip(selected = opened > 0, onClick = { if (opened == 0) opened = 5 },
                        label = "Only the last few opened")
                }
                if (opened > 0) {
                    Text("The ${countNoun(opened, "note")} opened last", style = MaterialTheme.typography.bodyMedium)
                    SkinSlider(
                        value = opened.toFloat(),
                        onValueChange = { opened = it.roundToInt().coerceIn(1, RecentlyOpened.KEEP) },
                        valueRange = 1f..RecentlyOpened.KEEP.toFloat(),
                        step = 1f,
                    )
                }
                Text("Kind", style = MaterialTheme.typography.labelMedium)
                Row {
                    listOf(null to "Any", NoteKind.INK to "Handwritten", NoteKind.MARKDOWN to "Markdown").forEach { (value, label) ->
                        SettingsChoiceChip(selected = kind == value, onClick = { kind = value }, label = label,
                            modifier = Modifier.padding(end = Ui.Space.s))
                    }
                }
                SettingsChoiceChip(selected = favorites, onClick = { favorites = !favorites }, label = "Favourites only")
                if (tags.isNotEmpty()) {
                    Text("Tag", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        SettingsChoiceChip(selected = tag == null, onClick = { tag = null }, label = "Any",
                            modifier = Modifier.padding(end = Ui.Space.s))
                        tags.forEach { t ->
                            SettingsChoiceChip(selected = tag == t, onClick = { tag = t }, label = "#$t",
                                modifier = Modifier.padding(end = Ui.Space.s))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(SmartFolder(initial?.id ?: UUID.randomUUID().toString(), name.trim(), tag, hours, favorites, kind, opened))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
