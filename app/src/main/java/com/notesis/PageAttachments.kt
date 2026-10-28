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
import androidx.compose.material3.Text
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
private enum class AttachStep { CHOOSE, COMMENT, OTHER_PAGE }

/**
 * Keeps something with page [pageNumber]: a fresh practice page to work on
 * beside it, a page of another note, or a comment that may carry a link.
 * The first two open in the popup over the note when their tab is tapped;
 * a comment opens in a card.
 */
@Composable
internal fun AttachToPageDialog(
    pageNumber: Int,
    notes: List<NoteMeta>,
    onPractice: () -> Unit,
    onOtherPage: (NoteMeta, Int) -> Unit,
    onComment: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(AttachStep.CHOOSE) }
    var text by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<NoteMeta?>(null) }
    var pageText by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach to page $pageNumber") },
        text = {
            when (step) {
                AttachStep.CHOOSE -> Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                    AttachChoice("Practice page", "A new blank page to work on beside this one, kept as a note of its own") {
                        onPractice()
                    }
                    AttachChoice("A page of another note", "Any page you already have, opened over this one") {
                        step = AttachStep.OTHER_PAGE
                    }
                    AttachChoice("Comment or link", "A few words, a post-it, or a web address") {
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
                AttachStep.OTHER_PAGE -> Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(notes, key = { it.id }) { other ->
                            Text(
                                other.title.ifBlank { "Untitled" } + "  · ${countNoun(other.pageCount, "page")}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (picked?.id == other.id) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(Ui.Shape.chip)
                                    .clickable { picked = other; pageText = "1" }
                                    .padding(horizontal = Ui.Space.s, vertical = 10.dp),
                            )
                        }
                    }
                    picked?.let { other ->
                        OutlinedTextField(pageText, { pageText = it.filter(Char::isDigit).take(5) },
                            label = { Text("Page, 1–${other.pageCount.coerceAtLeast(1)}") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth())
                    }
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
                AttachStep.OTHER_PAGE -> {
                    val other = picked
                    val page = pageText.toIntOrNull()?.takeIf { other != null && it in 1..other.pageCount.coerceAtLeast(1) }
                    TextButton(enabled = other != null && page != null, onClick = {
                        if (other != null && page != null) onOtherPage(other, page - 1)
                    }) { Text("Attach") }
                }
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
 * A comment kept with a page, opened from its tab: what it says, its link to
 * follow, and the ways to change it, take it off, or put the page's tabs away.
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
                    TextButton(onClick = onHideTabs) { Text("Hide tabs") }
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
