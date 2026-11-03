@file:Suppress("RestrictedApi")

package com.notesis

import android.graphics.Bitmap
import android.graphics.Color
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.TextureBitmapStore

/** Shared texture source used by both live and committed Ink renderers. */
object PencilTextureStore : TextureBitmapStore {
    private val graphite: Bitmap by lazy {
        val size = GRAPHITE_TILE
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val alpha = graphiteGrainAlpha(x, y)
            pixels[y * size + x] = Color.argb(alpha, 255, 255, 255)
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
    private val tooth: Bitmap by lazy {
        val size = GRAPHITE_TILE
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = Color.argb(graphiteToothAlpha(x, y), 255, 255, 255)
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
    /** The tooth at each density but the default, made when a pencil at that density first draws. */
    private val toothAt = arrayOfNulls<Bitmap>(GRAPHITE_DENSITY_MAX + 1)

    private fun toothFor(density: Int): Bitmap {
        if (density == GRAPHITE_DENSITY_DEFAULT) return tooth
        synchronized(toothAt) {
            toothAt[density]?.let { return it }
            val size = GRAPHITE_TILE
            val pixels = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) {
                pixels[y * size + x] = Color.argb(graphiteToothAlpha(x, y, density), 255, 255, 255)
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).also { toothAt[density] = it }
        }
    }

    private val opaque by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }
    }

    override fun get(clientTextureId: String): Bitmap {
        return if (clientTextureId == PencilBrushes.GRAPHITE_TEXTURE) {
            tooth
        } else if (clientTextureId.startsWith(PencilBrushes.GRAPHITE_TEXTURE + PencilBrushes.DENSITY_MARK)) {
            toothFor(PencilBrushes.densityOfTexture(clientTextureId) ?: GRAPHITE_DENSITY_DEFAULT)
        } else if (clientTextureId == StockBrushes.pencilUnstableBackgroundTextureId) {
            graphite
        } else {
            // Ink asks only for registered brush texture ids. Returning a tiny
            // opaque tile keeps an unknown future stock layer safe to render.
            opaque
        }
    }
}
