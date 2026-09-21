@file:Suppress("RestrictedApi")

package com.notesis

import android.graphics.Bitmap
import android.graphics.Color
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.TextureBitmapStore
import kotlin.math.abs

/** Shared texture source used by both live and committed Ink renderers. */
object PencilTextureStore : TextureBitmapStore {
    private val graphite: Bitmap by lazy {
        val size = 64
        val pixels = IntArray(size * size)
        var state = 0x51F15EED
        for (y in 0 until size) for (x in 0 until size) {
            // Deterministic fine grain plus a faint paper-direction band. A
            // fixed tile means the wet stroke and the saved stroke match.
            state = state * 1664525 + 1013904223
            val noise = (state ushr 24) and 0xFF
            val fibre = abs(((x * 11 + y * 3) % 29) - 14) * 3
            val alpha = (112 + noise / 2 - fibre).coerceIn(28, 225)
            pixels[y * size + x] = Color.argb(alpha, 255, 255, 255)
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    override fun get(clientTextureId: String): Bitmap {
        return if (clientTextureId == StockBrushes.pencilUnstableBackgroundTextureId) {
            graphite
        } else {
            // Ink asks only for registered brush texture ids. Returning a tiny
            // opaque tile keeps an unknown future stock layer safe to render.
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
            }
        }
    }
}
