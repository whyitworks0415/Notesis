package com.notesis

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun widthLabel(width: Float): String = String.format(Locale.US, "%.2f", width)
    .trimEnd('0').trimEnd('.')

internal fun parseWidth(text: String, range: ClosedFloatingPointRange<Float>): Float? =
    text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in range }

internal fun parseColor(text: String): Int? = text.trim().removePrefix("#")
    .takeIf { it.length == 6 && it.all { ch -> ch in "0123456789abcdefABCDEF" } }
    ?.toLongOrNull(16)?.let { (it or 0xFF000000L).toInt() }

@Composable
internal fun WidthControls(mode: EditMode, width: Float, onWidth: (Float) -> Unit) {
    val context = LocalContext.current
    val store = remember { PenStore(context) }
    var favorites by remember(mode) { mutableStateOf(store.favoriteWidths(mode)) }
    var typed by remember(width) { mutableStateOf(widthLabel(width)) }
    val range = PenStore.widthRange(mode)
    val parsed = parseWidth(typed, range)
    Column {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it; parseWidth(it, range)?.let(onWidth) },
            label = { Text("굵기 (${widthLabel(range.start)}–${widthLabel(range.endInclusive)})") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = parsed == null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = parsed != null, onClick = {
                val chosen = parsed ?: return@TextButton
                favorites = if (chosen in favorites) favorites - chosen else (favorites + chosen).sorted()
                store.saveFavoriteWidths(mode, favorites)
            }) { Text(if (parsed in favorites) "★ 해제" else "☆ 즐겨찾기") }
            favorites.forEach { favorite ->
                TextButton(onClick = { onWidth(favorite) }) { Text(widthLabel(favorite)) }
            }
        }
    }
}

@Composable
internal fun FavoriteWidthButton(mode: EditMode, width: Float, onWidth: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("${widthLabel(width)} ▾") }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("굵기 · 즐겨찾기") },
        text = { WidthControls(mode, width, onWidth) },
        confirmButton = { TextButton(onClick = { open = false }) { Text("완료") } },
    )
}

@Composable
internal fun ToolbarSizeButton(size: Int, onSize: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("${size + 1}/4") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            listOf("최대 · 전체 도구", "중간 · 한 줄", "작게 · 필수 도구", "최소 · 버튼 하나")
                .forEachIndexed { index, label ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onSize(index); open = false })
                }
        }
    }
}

@Composable
internal fun ColorInput(color: Int, onColor: (Int) -> Unit) {
    var value by remember(color) { mutableStateOf("%06X".format(color and 0xFFFFFF)) }
    var expanded by remember { mutableStateOf(false) }
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    TextButton(onClick = { expanded = !expanded }) { Text("색상 직접 고르기") }
    if (expanded) {
        SaturationValueField(hsv[0], hsv[1], hsv[2]) { saturation, brightness ->
            onColor(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], saturation, brightness)))
        }
        Spacer(Modifier.height(8.dp))
        GradientStrip((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }, hsv[0] / 360f) {
            onColor(android.graphics.Color.HSVToColor(floatArrayOf(it * 360f, hsv[1], hsv[2])))
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it; parseColor(it)?.let(onColor) },
        label = { Text("색상 #RRGGBB") },
        singleLine = true,
        isError = parseColor(value) == null,
        leadingIcon = { Box(Modifier.size(24.dp).background(Color(color))) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(0xFF000000, 0xFFFFFFFF, 0xFFF44336, 0xFFFFC107, 0xFF4CAF50,
            0xFF2196F3, 0xFFB8A6E8, 0xFFE9E7E2).forEach { swatch ->
            TextButton(onClick = { onColor(swatch.toInt()) }) {
                Box(Modifier.size(26.dp).background(Color(swatch)))
            }
        }
    }
}

@Composable
internal fun TemplateEditor(onDismiss: () -> Unit, onSave: (String, List<Int>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableIntStateOf(0xFF1976D2.toInt()) }
    var colors by remember { mutableStateOf(emptyList<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("색상 템플릿 추가") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("템플릿 이름") }, singleLine = true)
                ColorInput(color) { color = it }
                TextButton(enabled = colors.size < 12 && color !in colors,
                    onClick = { colors = colors + color }) { Text("+ 이 색상 추가 (${colors.size}/12)") }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    colors.forEach { saved ->
                        TextButton(onClick = { colors = colors - saved }) {
                            Box(Modifier.size(30.dp).background(Color(saved)))
                            Text("×")
                        }
                    }
                }
                Text("추가한 색상을 누르면 제거합니다.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && colors.isNotEmpty(),
            onClick = { onSave(name.trim(), colors) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
internal fun HomeBackground(photo: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(photo) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(photo) {
        bitmap = withContext(Dispatchers.IO) {
            photo?.let { runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(it))) { decoder, info, _ ->
                    val scale = (2048f / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }.getOrNull() }
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = null,
        modifier = modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

@Composable
internal fun HomeBackgroundDialog(store: PenStore, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var color by remember { mutableIntStateOf(store.homeColor ?: 0xFFE9E7E2.toInt()) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Validate before replacing the previous background.
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeStream(stream, null, options)
                    check(options.outWidth > 0 && options.outHeight > 0)
                } ?: error("사진을 읽지 못했습니다")
            }.isSuccess }
            if (ok) { store.homePhoto = uri.toString(); onChanged(); onDismiss() }
            else error = "사진을 읽지 못했습니다. 다른 사진을 선택해 주세요."
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("노트 목록 배경") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            ColorInput(color) { color = it }
            TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("사진 선택") }
            TextButton(onClick = { store.homePhoto = null; store.homeColor = null; onChanged(); onDismiss() }) { Text("기본 배경으로") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            store.homeColor = color; store.homePhoto = null; onChanged(); onDismiss()
        }) { Text("색상 적용") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
