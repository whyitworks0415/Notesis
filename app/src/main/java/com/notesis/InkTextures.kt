@file:Suppress("RestrictedApi")

package com.notesis

import android.graphics.Bitmap
import android.graphics.Color
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.TextureBitmapStore

/** Shared texture source used by both live and committed Ink renderers. */
object PencilTextureStore : TextureBitmapStore {
    private val graphite: Bitmap by lazy {
        val size = 128
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val alpha = graphiteGrainAlpha(x, y)
            pixels[y * size + x] = Color.argb(alpha, 255, 255, 255)
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
    private val opaque by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }
    }

    override fun get(clientTextureId: String): Bitmap {
        return if (clientTextureId == StockBrushes.pencilUnstableBackgroundTextureId) {
            graphite
        } else {
            // Ink asks only for registered brush texture ids. Returning a tiny
            // opaque tile keeps an unknown future stock layer safe to render.
            opaque
        }
    }
}
