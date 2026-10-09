package com.notesis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The sticker collection is the device's own emoji: hundreds of drawings that
 * are already installed, with Unicode names to search by. Nothing to ship.
 */
internal object Stickers {
    private val ranges = listOf(
        0x1F600..0x1F64F, 0x1F300..0x1F5FF, 0x1F680..0x1F6FF, 0x1F900..0x1F9FF, 0x1FA70..0x1FAFF, 0x2600..0x27BF,
    )

    val all: List<String> by lazy {
        val probe = Paint()
        ranges.flatMap { it }.mapNotNull { cp ->
            val s = String(Character.toChars(cp))
            s.takeIf { Character.isDefined(cp) && Character.getType(cp) == Character.OTHER_SYMBOL.toInt() && probe.hasGlyph(it) }
        }
    }

    fun nameOf(sticker: String): String = Character.getName(sticker.codePointAt(0))?.lowercase().orEmpty()

    fun search(query: String): List<String> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return all
        return all.filter { sticker -> val name = nameOf(sticker); words.all { it in name } }
    }

    /** The sticker drawn [px] square, for placing on a page like any picture. */
    fun render(sticker: String, px: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px * 0.8f; textAlign = Paint.Align.CENTER }
        val canvas = Canvas(bitmap)
        val y = px / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(sticker, px / 2f, y, paint)
        return bitmap
    }

    private const val RECENT = "recentStickers"
    private const val MAX_RECENT = 24

    fun recent(context: Context): List<String> =
        context.getSharedPreferences("stickers", Context.MODE_PRIVATE).getString(RECENT, "").orEmpty()
            .split('\n').filter { it.isNotEmpty() }

    fun remember(context: Context, sticker: String) {
        val list = (listOf(sticker) + recent(context).filter { it != sticker }).take(MAX_RECENT)
        context.getSharedPreferences("stickers", Context.MODE_PRIVATE).edit().putString(RECENT, list.joinToString("\n")).apply()
    }
}

@Composable
internal fun StickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val recent = remember { Stickers.recent(context) }
    val shown = remember(query) { Stickers.search(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("스티커") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("영어로 찾기: cat, heart, star…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (query.isBlank() && recent.isNotEmpty()) {
                    Text("최근", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    StickerGrid(recent, Modifier.heightIn(max = 110.dp)) { Stickers.remember(context, it); onPick(it) }
                }
                Text("${shown.size}개", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                StickerGrid(shown, Modifier.heightIn(max = 360.dp)) { Stickers.remember(context, it); onPick(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun StickerGrid(stickers: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(48.dp), modifier) {
        items(stickers) { sticker ->
            Box(Modifier.size(48.dp).clickable { onPick(sticker) }, contentAlignment = Alignment.Center) {
                Text(sticker, fontSize = 28.sp)
            }
        }
    }
}
