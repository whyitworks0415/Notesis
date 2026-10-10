package com.notesis

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore

/** Where exported pictures land, under the shared Pictures folder. */
private val GALLERY_FOLDER = Environment.DIRECTORY_PICTURES + "/Notesis"

/**
 * Writes [bitmap] straight into the gallery as a PNG. Through MediaStore, so
 * from Android 10 on no storage permission is asked for; the entry stays
 * pending until it is complete, and a failed write leaves nothing behind.
 */
fun saveBitmapToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME,
            if (displayName.endsWith(".png", true)) displayName else "$displayName.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_FOLDER)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: return false
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
    }.getOrDefault(false)
    if (!ok) {
        runCatching { resolver.delete(uri, null, null) }
        return false
    }
    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return true
}
