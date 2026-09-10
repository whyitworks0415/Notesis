package com.notesis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Keep editable text alongside the portable image used by page renderers and PDF export. */
data class TextBoxContent(val text: String, val size: Float = 32f, val color: Int = 0xFF000000.toInt())

internal fun renderTextBox(content: TextBoxContent): Bitmap {
    val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = content.size.coerceIn(12f, 96f) * 2f
        color = content.color
    }
    val maxWidth = 1600
    val desired = Layout.getDesiredWidth(content.text, paint).toInt().coerceIn(1, maxWidth)
    val layout = StaticLayout.Builder.obtain(content.text, 0, content.text.length, paint, desired)
        .setIncludePad(true).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    require(layout.height <= 8192) { "텍스트가 너무 깁니다. 상자를 나눠 주세요." }
    return Bitmap.createBitmap(desired + 16, layout.height + 16, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.translate(8f, 8f)
        layout.draw(canvas)
    }
}

@Composable
internal fun TextBoxDialog(initial: TextBoxContent?, onDismiss: () -> Unit, onSave: (TextBoxContent) -> Unit) {
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var size by remember { mutableStateOf(widthLabel(initial?.size ?: 32f)) }
    var color by remember { mutableIntStateOf(initial?.color ?: 0xFF000000.toInt()) }
    val parsedSize = parseWidth(size, 12f..96f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "텍스트 상자 추가" else "텍스트 편집") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(text, { if (it.length <= 4000) text = it },
                label = { Text("내용") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(size, { size = it }, label = { Text("글자 크기 (12–96)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, isError = parsedSize == null, modifier = Modifier.fillMaxWidth())
            ColorInput(color) { color = it }
            Text("추가 후 텍스트 도구로 상자를 선택해 이동·크기 조절·재편집할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = text.isNotBlank() && parsedSize != null,
            onClick = { onSave(TextBoxContent(text, parsedSize ?: 32f, color)) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
