package com.notesis

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext

/**
 * Accepts pictures dragged in from another app, which in Android's own split
 * screen is the natural way to bring one over. Each dropped image goes to [onImage].
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.dropImages(onImage: (Uri) -> Unit): Modifier = composed {
    val activity = LocalContext.current as? Activity
    val latest by rememberUpdatedState(onImage)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                // Another app's uri is readable only with the drop's grant.
                runCatching { activity?.requestDragAndDropPermissions(drag) }
                val clip = drag.clipData ?: return false
                var taken = false
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { latest(it); taken = true }
                }
                return taken
            }
        }
    }
    dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.mimeTypes().any { it.startsWith("image/") } },
        target = target,
    )
}
