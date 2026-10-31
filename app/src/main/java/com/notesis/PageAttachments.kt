package com.notesis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What the attach dialog is asking for. */
private enum class AttachStep { CHOOSE, COMMENT }

/**
 * Keeps something with page [pageNumber]: a blank practice page laid beside
 * it, or a comment - a few words, maybe a link - set in the margin with a
 * line back to the page.
 */
@Composable
internal fun AttachToPageDialog(
    pageNumber: Int,
    onPractice: () -> Unit,
    onComment: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(AttachStep.CHOOSE) }
    var text by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach to page $pageNumber") },
        text = {
            when (step) {
                AttachStep.CHOOSE -> Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                    AttachChoice("Practice page", "A blank page laid beside this one, to work things out next to it") {
                        onPractice()
                    }
                    AttachChoice("Comment", "A few words, and a link if you like, in the margin with a line to the page") {
                        step = AttachStep.COMMENT
                    }
                }
                AttachStep.COMMENT -> Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                    OutlinedTextField(text, { text = it }, label = { Text("Comment") }, minLines = 3,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it.trim() }, label = { Text("Link (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            when (step) {
                AttachStep.CHOOSE -> Unit
                AttachStep.COMMENT -> TextButton(
                    enabled = text.isNotBlank() || url.isNotBlank(),
                    onClick = { onComment(text.trim(), url) },
                ) { Text("Attach") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (step == AttachStep.CHOOSE) onDismiss() else step = AttachStep.CHOOSE }) {
                Text(if (step == AttachStep.CHOOSE) "Cancel" else "Back")
            }
        },
    )
}

@Composable
private fun AttachChoice(title: String, hint: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Ui.Shape.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = Ui.Space.s, vertical = Ui.Space.s),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * A comment kept with a page, opened from its box in the margin: what it says,
 * its link to follow, and the ways to change it, take it off, or put the
 * page's attachments away.
 */
@Composable
internal fun PageCommentDialog(
    pageNumber: Int,
    attachment: PageAttachment,
    onOpenLink: (String) -> Unit,
    onSave: (PageAttachment) -> Unit,
    onRemove: () -> Unit,
    onHideTabs: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(attachment) { mutableStateOf(attachment.text) }
    var url by remember(attachment) { mutableStateOf(attachment.url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page $pageNumber") },
        text = {
            if (editing) Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                OutlinedTextField(text, { text = it }, label = { Text("Comment") }, minLines = 3,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Link (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth())
            } else Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                if (attachment.text.isNotBlank()) Text(attachment.text, style = MaterialTheme.typography.bodyLarge)
                if (attachment.url.isNotBlank()) Text(
                    attachment.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(Ui.Shape.chip).clickable { onOpenLink(webAddress(attachment.url)) }
                        .padding(vertical = Ui.Space.xs),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                    TextButton(onClick = { editing = true }) { Text("Edit") }
                    TextButton(onClick = onRemove) { Text("Remove") }
                    TextButton(onClick = onHideTabs) { Text("Hide") }
                }
            }
        },
        confirmButton = {
            if (editing) TextButton(
                enabled = text.isNotBlank() || url.isNotBlank(),
                onClick = { onSave(attachment.copy(text = text.trim(), url = url)) },
            ) { Text("Save") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = if (editing) {
            { TextButton(onClick = { editing = false }) { Text("Cancel") } }
        } else null,
    )
}

/** An address as typed, made one a browser can open: "example.com" becomes "https://example.com". */
internal fun webAddress(typed: String): String {
    val trimmed = typed.trim()
    if (trimmed.isEmpty()) return trimmed
    return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) trimmed else "https://$trimmed"
}
