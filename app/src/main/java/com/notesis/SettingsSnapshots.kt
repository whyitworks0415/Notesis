package com.notesis

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Settings saved on this tablet under a name, to come back to. */
internal data class SettingsSnapshot(val file: File, val name: String, val savedAt: Long)

/**
 * Settings kept on the tablet itself: the same file [SettingsBackup] writes,
 * saved under a name in the app's own storage instead of handed to the
 * person to keep. Bringing one back sets what it holds and leaves the rest.
 */
internal object SettingsSnapshots {
    private fun dir(context: Context) = File(context.filesDir, "settings-snapshots")

    fun list(context: Context): List<SettingsSnapshot> =
        dir(context).listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val root = JSONObject(file.readText())
                    SettingsSnapshot(file, root.optString(NAME).ifBlank { file.nameWithoutExtension },
                        root.optLong(SAVED_AT, file.lastModified()))
                }.getOrNull()
            }
            .sortedByDescending { it.savedAt }

    /** Saves the settings as they are now under [name]; how many settings went in, or null. */
    fun save(context: Context, name: String, appVersion: String): Int? = runCatching {
        val bytes = ByteArrayOutputStream()
        val count = SettingsBackup.export(context, bytes, appVersion)
        val now = System.currentTimeMillis()
        val root = JSONObject(bytes.toString(Charsets.UTF_8.name()))
            .put(NAME, name.trim().ifEmpty { "Settings" }.take(60))
            .put(SAVED_AT, now)
        val folder = dir(context).apply { mkdirs() }
        File(folder, "$now.json").writeText(root.toString(2))
        count
    }.getOrNull()

    /** Sets what [snapshot] holds; how many settings, or null when it cannot be read. */
    fun restore(context: Context, snapshot: SettingsSnapshot): Int? = runCatching {
        snapshot.file.inputStream().use { SettingsBackup.import(context, it) }
    }.getOrNull()

    fun delete(snapshot: SettingsSnapshot) {
        snapshot.file.delete()
    }

    private const val NAME = "name"
    private const val SAVED_AT = "savedAt"
}

/**
 * Puts the toolbar back as a fresh install has it: its tools and their order
 * at every size, across the top, at the size and scale it starts at, with
 * its own motion. Where it was moved to and whether it was pinned are the
 * note screen's, which [onResetToolbar] puts back.
 */
internal fun resetToolbar(context: Context) {
    ToolbarLayout.set(context, ToolbarConfig())
    ToolbarOrientation.set(false)
    ToolbarOrientation.setSide(false)
    ToolbarScale.choose(ToolbarScale.DEFAULT)
    ToolbarMotion.update(ToolbarMotion.DEFAULT_MS, ToolbarCurve.DEFAULT)
}

/**
 * The settings sidebar's Save & reset: settings saved on the tablet under a
 * name and brought back, the same as a file to keep or carry elsewhere, and
 * the toolbar put back as it came.
 */
@Composable
internal fun SettingsSaveAndReset(onResetToolbar: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val snapshots = remember(revision) { SettingsSnapshots.list(context) }
    var naming by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<SettingsSnapshot?>(null) }
    var resetting by remember { mutableStateOf(false) }
    val version = remember { AppUpdate.installedName(context) }

    fun reload(count: Int?) {
        if (count == null) {
            Toast.makeText(context, "Not a Notesis settings file", Toast.LENGTH_SHORT).show()
            return
        }
        StudyClock.reload(context)
        Toast.makeText(context, "Loaded ${countNoun(count, "setting")}", Toast.LENGTH_SHORT).show()
        // Pens, skin, gestures and the bars all read their settings as the
        // screen is built, so it is built again.
        (context as? Activity)?.recreate()
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        StudyClock.save()
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { SettingsBackup.export(context, it, version) } }.getOrNull()
            }
            Toast.makeText(context, if (count != null) "Saved ${countNoun(count, "setting")}" else "Couldn't save settings",
                Toast.LENGTH_SHORT).show()
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        StudyClock.save()
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { SettingsBackup.import(context, it) } }.getOrNull()
            }
            reload(count)
        }
    }

    SettingsSectionHeader("Saved settings")
    SettingsNote("Pens, tools, gestures, the look and the toolbar, saved on this tablet. Notes are not part of it.")
    TextButton(onClick = { naming = true }) { Text("+ Save current settings") }
    if (snapshots.isEmpty()) {
        Text("Nothing saved yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
        snapshots.forEach { snapshot ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(snapshot.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(format.format(Date(snapshot.savedAt)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                TextButton(onClick = { restoring = snapshot }) { Text("Load") }
                IconButton(onClick = { SettingsSnapshots.delete(snapshot); revision++ }) {
                    Icon(Reicons.Close, contentDescription = "Delete ${snapshot.name}")
                }
            }
        }
    }
    SettingsSectionHeader("Settings file")
    SettingsNote("The same settings as a file, to keep elsewhere or open on another tablet.")
    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        TextButton(onClick = { saveFile.launch("Notesis settings.json") }) { Text("Save to a file") }
        TextButton(onClick = { openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
            Text("Open a file")
        }
    }
    SettingsSectionHeader("Toolbar")
    SettingsNote("Puts the toolbar back as it came: its tools at every size, across the top, its scale and motion, and its place on the screen.")
    TextButton(onClick = { resetting = true }) { Text("Reset toolbar") }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save current settings") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name") }, placeholder = { Text("e.g. Lecture, Exam, Drawing") })
            },
            confirmButton = {
                TextButton(onClick = {
                    naming = false
                    StudyClock.save()
                    val count = SettingsSnapshots.save(context, name, version)
                    Toast.makeText(context, if (count != null) "Saved ${countNoun(count, "setting")}" else "Couldn't save settings",
                        Toast.LENGTH_SHORT).show()
                    revision++
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
    restoring?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("Load \"${snapshot.name}\"?") },
            text = { Text("Your settings change to the ones saved in it. The screen reloads; your notes stay as they are.") },
            confirmButton = {
                TextButton(onClick = {
                    restoring = null
                    StudyClock.save()
                    reload(SettingsSnapshots.restore(context, snapshot))
                }) { Text("Load") }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } },
        )
    }
    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            title = { Text("Reset the toolbar?") },
            text = { Text("Its tools, size, direction, scale, motion and place go back to how they came. Other settings stay.") },
            confirmButton = {
                TextButton(onClick = {
                    resetting = false
                    resetToolbar(context)
                    onResetToolbar()
                    Toast.makeText(context, "Toolbar reset", Toast.LENGTH_SHORT).show()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text("Cancel") } },
        )
    }
}
