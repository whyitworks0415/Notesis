package com.notesis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * A folder of Markdown files - an Obsidian vault, or any folder - as a list
 * to find a note in by name, open, or start a new one. The files stay where
 * they are and are written there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownVaultScreen(
    vault: MarkdownVault,
    onOpen: (VaultFile) -> Unit,
    onChangeFolder: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var files by remember(vault.tree) { mutableStateOf<List<VaultFile>?>(null) }
    var query by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(true) }
    var naming by remember { mutableStateOf(false) }
    LaunchedEffect(vault.tree) { files = withContext(Dispatchers.IO) { vault.files() } }
    BackHandler(onBack = onBack)
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Scaffold(
        topBar = {
            SkinSurface(flush = true) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Reicons.ArrowBack, "Back to the library") } },
                    title = {
                        Column {
                            Text(vault.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(files?.let { "Markdown folder · ${countNoun(it.size, "note")}" } ?: "Markdown folder",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        TextButton(onClick = { naming = true }) { Text("New note") }
                        TextButton(onClick = onChangeFolder) { Text("Change folder") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Ui.Space.l, vertical = Ui.Space.s),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Find a note") },
                    leadingIcon = { Icon(Reicons.Search, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                )
                SettingsChoiceChip(selected = recent, onClick = { recent = true }, label = "Recent")
                SettingsChoiceChip(selected = !recent, onClick = { recent = false }, label = "A–Z")
            }
            val all = files
            if (all == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val needle = query.trim().lowercase()
            val shown = remember(all, needle, recent) {
                all.filter { needle.isEmpty() || it.path.lowercase().contains(needle) }
                    .let { list -> if (recent) list.sortedByDescending { it.modified } else list.sortedBy { it.path.lowercase() } }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(Ui.Space.l), contentAlignment = Alignment.Center) {
                    Text(if (all.isEmpty()) "No Markdown files in this folder yet. Start one with New note."
                        else "No note called “$query”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Ui.Space.l)) {
                items(shown, key = { it.uri.toString() }) { file ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(file) }
                        .padding(horizontal = Ui.Space.l, vertical = Ui.Space.m)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val folder = file.path.substringBeforeLast('/', "")
                        Text(listOfNotNull(folder.ifBlank { null }, if (file.modified > 0) dates.format(Date(file.modified)) else null)
                            .joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (naming) {
        var name by remember { mutableStateOf(query.trim()) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("New note") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") },
                    modifier = Modifier.widthIn(min = 240.dp))
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    naming = false
                    scope.launch {
                        val made = withContext(Dispatchers.IO) { vault.create(name, "# ${name.trim()}\n\n") }
                        if (made != null) onOpen(made)
                        else android.widget.Toast.makeText(context, "Couldn't make the note in this folder", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}
