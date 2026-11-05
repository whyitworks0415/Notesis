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
import androidx.compose.material3.AlertDialog
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

/**
 * A folder that fills itself: every note that carries [tag], was written in
 * the last [days] days, is a favourite, or is of one [kind] - each only when
 * set, all of those set at once. Nothing is moved into it; it is a way of
 * looking at the library.
 */
internal data class SmartFolder(
    val id: String,
    val name: String,
    val tag: String? = null,
    /** 0 for any age. */
    val days: Int = 0,
    val favoritesOnly: Boolean = false,
    val kind: NoteKind? = null,
) {
    fun matches(note: NoteMeta, now: Long): Boolean =
        (tag == null || tag in note.tags) &&
            (days <= 0 || now - note.modified <= days * DAY_MS) &&
            (!favoritesOnly || note.favorite) &&
            (kind == null || note.kind == kind)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("days", days)
        .put("favorites", favoritesOnly).apply {
            tag?.let { put("tag", it) }
            kind?.let { put("kind", it.name) }
        }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun fromJson(json: JSONObject): SmartFolder? = runCatching {
            SmartFolder(
                id = json.getString("id"),
                name = json.getString("name"),
                tag = json.optString("tag").takeIf { it.isNotBlank() },
                days = json.optInt("days"),
                favoritesOnly = json.optBoolean("favorites"),
                kind = json.optString("kind").takeIf { it.isNotBlank() }?.let { runCatching { NoteKind.valueOf(it) }.getOrNull() },
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
    var days by remember { mutableStateOf(initial?.days ?: 0) }
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
                    listOf(0 to "Any time", 7 to "The last 7 days", 30 to "The last 30 days").forEach { (value, label) ->
                        SettingsChoiceChip(selected = days == value, onClick = { days = value }, label = label,
                            modifier = Modifier.padding(end = Ui.Space.s))
                    }
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
                onSave(SmartFolder(initial?.id ?: UUID.randomUUID().toString(), name.trim(), tag, days, favorites, kind))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
