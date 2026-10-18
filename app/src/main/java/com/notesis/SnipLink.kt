package com.notesis

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A captured region carried to wherever it is to be pasted, with where it
 * came from. Held across notes: capture in one, open another, paste there,
 * and the picture links back to the page it was taken from.
 */
internal object SnipLink {
    data class Snip(
        val bitmap: Bitmap,
        val noteId: String,
        val noteTitle: String,
        val pageId: String,
        val pageIndex: Int,
    )

    var pending by mutableStateOf<Snip?>(null)

    /** The link a copy pasted into note [hereNoteId] carries back to its source. */
    fun linkFor(snip: Snip, hereNoteId: String): String =
        snipLinkTarget(snip.noteId, snip.pageId, snip.pageIndex, hereNoteId)
}

/** Within the same note a page link, which survives reordering; across notes the note and page number. */
internal fun snipLinkTarget(sourceNoteId: String, sourcePageId: String, sourcePageIndex: Int, hereNoteId: String): String =
    if (sourceNoteId == hereNoteId) "page:$sourcePageId" else "note:$sourceNoteId#$sourcePageIndex"
