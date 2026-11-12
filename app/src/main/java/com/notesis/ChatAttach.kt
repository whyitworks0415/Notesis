package com.notesis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/*
 * A note, or part of it, sent to the AI chat in the web panel (Todo.md, P1-P3):
 * which pages, with or without the handwriting, as a PDF or as a picture, and
 * the handwriting as text beside it for the chat to read.
 */

/** Which pages go to the chat. */
internal enum class ChatPages(val label: String) { THIS("This page"), SOME("Pages…"), ALL("Whole note") }

/** What the pages are sent as. A picture is of one page only. */
internal enum class ChatFormat(val label: String) { PDF("PDF"), PICTURE("Picture") }

/** What was asked for on the sheet. */
internal data class ChatRequest(
    /** Page indices, from 0, in order. */
    val pages: List<Int>,
    /** The ink on them, or the pages as they are underneath it. */
    val withInk: Boolean,
    /** The recognised handwriting, beside the pages, as a text file. */
    val handwritingText: Boolean,
    val format: ChatFormat,
)

/**
 * The pages a range typed on the sheet names, from 0, in order and each once:
 * "3", "3-5", "1, 4-6, 9" in page numbers from 1, spaces and either comma
 * allowed, a range either way round. Null when it names nothing, or a page
 * the note does not have.
 */
internal fun parsePageRange(text: String, count: Int): List<Int>? {
    if (count <= 0) return null
    val pages = sortedSetOf<Int>()
    val parts = text.split(',', '，', ';').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    for (part in parts) {
        val ends = part.split('-', '–', '~').map { it.trim() }
        val numbers = ends.map { it.toIntOrNull() ?: return null }
        when (numbers.size) {
            1 -> numbers[0].let { if (it !in 1..count) return null; pages += it - 1 }
            2 -> {
                val (a, b) = numbers[0] to numbers[1]
                if (a !in 1..count || b !in 1..count) return null
                for (p in minOf(a, b)..maxOf(a, b)) pages += p - 1
            }
            else -> return null
        }
    }
    return pages.toList()
}

/** The pages the sheet's choice stands for; null where a typed range does not read. */
internal fun chatPages(choice: ChatPages, current: Int, count: Int, typed: String): List<Int>? = when (choice) {
    ChatPages.THIS -> if (current in 0 until count) listOf(current) else null
    ChatPages.ALL -> if (count > 0) (0 until count).toList() else null
    ChatPages.SOME -> parsePageRange(typed, count)
}

/**
 * The text file of the handwriting sent beside the pages: each page with its
 * number, then what was read on it; pages with nothing read are left out.
 * Null when nothing was read on any of them.
 */
internal fun handwritingText(title: String, pages: List<Pair<Int, String?>>): String? {
    val read = pages.mapNotNull { (index, text) -> text?.trim()?.takeIf { it.isNotEmpty() }?.let { index to it } }
    if (read.isEmpty()) return null
    return buildString {
        append(title).append('\n')
        append("Handwriting read by Notesis, page by page. It may contain misreadings.\n")
        for ((index, text) in read) {
            append("\n--- Page ").append(index + 1).append(" ---\n").append(text).append('\n')
        }
    }.trimEnd() + "\n"
}

/** A name the chat is given for a file: the note's title, made safe, and what it is. */
internal fun chatFileName(title: String, suffix: String, extension: String): String {
    val safe = title.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else ' ' }
        .joinToString("").trim().replace(Regex("\\s+"), " ").take(60).ifBlank { "Note" }
    return if (suffix.isBlank()) "$safe.$extension" else "$safe - $suffix.$extension"
}

/**
 * The sheet that asks what to send: opened from the chat's menu beside a
 * note. [current] and [count] are the note's page in view and how many it
 * has; [hasHandwriting] whether any of it has been read for search.
 */
@Composable
internal fun ChatAttachSheet(
    current: Int,
    count: Int,
    hasHandwriting: Boolean,
    onDismiss: () -> Unit,
    onSend: (ChatRequest) -> Unit,
) {
    val panel = rememberToolPanelState()
    var choice by remember { mutableStateOf(ChatPages.THIS) }
    var typed by remember { mutableStateOf("") }
    var withInk by remember { mutableStateOf(true) }
    var asText by remember { mutableStateOf(hasHandwriting) }
    var format by remember { mutableStateOf(ChatFormat.PDF) }
    val pages = chatPages(choice, current, count, typed)
    // A picture is of one page; more pages go as a PDF.
    val shownFormat = if (pages != null && pages.size > 1) ChatFormat.PDF else format
    ToolPanel(
        title = "Send to the chat",
        state = panel,
        onOutside = { panel.close(onDismiss) },
        actions = {
            TextButton(onClick = { panel.close(onDismiss) }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) {
                Text("Cancel")
            }
        },
    ) {
        PanelSectionHeader("Pages")
        SegmentedControl(ChatPages.entries.map { it.label }, choice.ordinal, { choice = ChatPages.entries[it] },
            Modifier.fillMaxWidth())
        if (choice == ChatPages.SOME) {
            OutlinedTextField(
                value = typed, onValueChange = { typed = it.take(80) },
                label = { Text("Pages, such as 1-3, 5") },
                isError = typed.isNotBlank() && pages == null,
                supportingText = { Text(if (typed.isNotBlank() && pages == null) "This note has pages 1 to $count." else "Of $count pages") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PanelSectionHeader("What goes")
        PanelSwitchRow("Handwriting", withInk, hint = if (withInk) "The pages as they look, with what is written on them."
            else "The pages as they are underneath the writing: the PDF or the paper alone.") { withInk = it }
        PanelSwitchRow("Handwriting as text", asText && hasHandwriting,
            hint = if (hasHandwriting) "What the handwriting says, read for search, in a text file beside the pages, so the chat can read it."
                else "Nothing in this note has been read yet. Index the handwriting from the note's card first.") {
            if (hasHandwriting) asText = it
        }
        PanelSectionHeader("Sent as")
        SegmentedControl(ChatFormat.entries.map { it.label }, shownFormat.ordinal, {
            format = ChatFormat.entries[it]
        }, Modifier.fillMaxWidth())
        if (pages != null && pages.size > 1 && format == ChatFormat.PICTURE) {
            Text("A picture is of one page; more pages go as a PDF.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                enabled = pages != null && pages.isNotEmpty(),
                onClick = {
                    val request = ChatRequest(pages.orEmpty(), withInk, asText && hasHandwriting, shownFormat)
                    panel.close { onSend(request) }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(when {
                    pages == null -> "Send"
                    pages.size == 1 -> "Send page ${pages[0] + 1}"
                    else -> "Send ${pages.size} pages"
                })
            }
        }
    }
}
