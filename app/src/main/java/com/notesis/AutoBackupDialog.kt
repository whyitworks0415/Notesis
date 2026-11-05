package com.notesis

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns automatic backup on or off, and says where it goes and how often.
 * Google Drive offers a single file here rather than a folder: Android lets
 * an app keep writing to a file it was given in Drive, not to a Drive folder.
 */
@Composable
internal fun AutoBackupDialog(onDismiss: () -> Unit, onReport: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(AutoBackup.target(context)) }
    var days by remember { mutableStateOf(AutoBackup.days(context)) }
    var uri by remember { mutableStateOf(AutoBackup.destination(context)) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(AutoBackup.lastResult(context)) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked: Uri? ->
        if (picked != null) { uri = picked; target = BackupTarget.FOLDER }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { picked: Uri? ->
        if (picked != null) { uri = picked; target = BackupTarget.DRIVE }
    }
    val last = AutoBackup.lastAt(context).takeIf { it > 0 }
        ?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automatic backup") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                Text("Every note, backed up on its own as the same file \"Back up all notes\" makes. Off until you turn it on.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                SettingsChoiceChip(selected = target == BackupTarget.OFF, onClick = { target = BackupTarget.OFF }, label = "Off")
                SettingsChoiceChip(selected = target == BackupTarget.DEVICE, onClick = { target = BackupTarget.DEVICE },
                    label = "On this tablet")
                if (target == BackupTarget.DEVICE) Text("Kept in Android/data/com.notesis/files/Backups, the last ${AutoBackup.KEEP}. " +
                    "Removed with the app, so this alone does not keep notes safe from losing the tablet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                SettingsChoiceChip(selected = target == BackupTarget.FOLDER, onClick = { pickFolder.launch(null) },
                    label = "In a folder (tablet or cloud)")
                if (target == BackupTarget.FOLDER) Text("A new file each time, the last ${AutoBackup.KEEP} kept. " +
                    "Cloud apps that offer folders in the picker, such as OneDrive or Dropbox, work here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                SettingsChoiceChip(selected = target == BackupTarget.DRIVE,
                    onClick = { pickFile.launch("Notesis-backup.zip") }, label = "One file in Google Drive")
                if (target == BackupTarget.DRIVE) Text("Pick Drive in the picker and save. That one file is written over each time.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (target != BackupTarget.OFF) Row {
                    SettingsChoiceChip(selected = days == 1, onClick = { days = 1 }, label = "Every day",
                        modifier = Modifier.padding(end = Ui.Space.s))
                    SettingsChoiceChip(selected = days == 7, onClick = { days = 7 }, label = "Every week")
                }
                Text(
                    (if (last != null) L10n.tr("Last backup: {0}").replace("{0}", last) else L10n.tr("No backup yet")) +
                        (status?.let { " · " + L10n.tr(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AutoBackup.configure(context, target, days, uri)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (target != BackupTarget.OFF) TextButton(enabled = !running, onClick = {
                    AutoBackup.configure(context, target, days, uri)
                    running = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { AutoBackup.runIfDue(context, force = true) }
                        running = false
                        status = AutoBackup.lastResult(context)
                        onReport(if (ok) "Backed up" else "Backup failed")
                    }
                }) { Text(if (running) "Backing up…" else "Back up now") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
