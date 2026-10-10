package com.notesis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

enum class CropShape { RECT, OVAL, FREE }

/**
 * Cuts [source] down to [area] (fractions of its width and height), keeping
 * only what lies inside the rectangle, the ellipse in it, or the free outline
 * [outline] (fractions too). Outside the shape becomes transparent.
 */
internal fun cropBitmap(source: Bitmap, shape: CropShape, area: RectF, outline: List<Offset> = emptyList()): Bitmap {
    val w = source.width
    val h = source.height
    val left = (area.left * w).toInt().coerceIn(0, w - 1)
    val top = (area.top * h).toInt().coerceIn(0, h - 1)
    val right = (area.right * w).toInt().coerceIn(left + 1, w)
    val bottom = (area.bottom * h).toInt().coerceIn(top + 1, h)
    val out = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (shape == CropShape.RECT) {
        canvas.drawBitmap(source, -left.toFloat(), -top.toFloat(), null)
        return out
    }
    // The shape first, then the picture drawn only where the shape is.
    val path = Path()
    if (shape == CropShape.OVAL) path.addOval(0f, 0f, out.width.toFloat(), out.height.toFloat(), Path.Direction.CW)
    else outline.forEachIndexed { i, p ->
        val x = p.x * w - left
        val y = p.y * h - top
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    canvas.drawPath(path, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(source, -left.toFloat(), -top.toFloat(), paint)
    return out
}

/** Rounds [source]'s corners by [corner] (a fraction of its shorter side) and draws a border [border] px wide. */
internal fun decorateBitmap(source: Bitmap, corner: Float, border: Float, borderColor: Int): Bitmap {
    val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val r = corner * minOf(source.width, source.height)
    val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawRoundRect(bounds, r, r, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(source, 0f, 0f, paint)
    if (border > 0f) {
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = border
            color = borderColor
        }
        bounds.inset(border / 2f, border / 2f)
        canvas.drawRoundRect(bounds, maxOf(0f, r - border / 2f), maxOf(0f, r - border / 2f), edge)
    }
    return out
}

/** Drag out a rectangle or ellipse, or draw round what to keep; [onCrop] gets the shape and its area in fractions. */
@Composable
internal fun ImageCropDialog(
    bitmap: Bitmap,
    onCrop: (CropShape, RectF, List<Offset>) -> Unit,
    onDismiss: () -> Unit,
) {
    var shape by remember { mutableStateOf(CropShape.RECT) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    val outline = remember { mutableStateListOf<Offset>() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // The picture is shown fitted; fractions are of the fitted picture.
    fun fitted(): RectF {
        if (box.width == 0) return RectF()
        val scale = minOf(box.width / bitmap.width.toFloat(), box.height / bitmap.height.toFloat())
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        return RectF((box.width - w) / 2f, (box.height - h) / 2f, (box.width + w) / 2f, (box.height + h) / 2f)
    }
    fun fraction(p: Offset): Offset {
        val f = fitted()
        return Offset(((p.x - f.left) / f.width()).coerceIn(0f, 1f), ((p.y - f.top) / f.height()).coerceIn(0f, 1f))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자르기") },
        text = {
            Column {
                Row {
                    listOf(CropShape.RECT to "사각형", CropShape.OVAL to "타원", CropShape.FREE to "자유형").forEach { (s, label) ->
                        SettingsChoiceChip(selected = shape == s, onClick = {
                            shape = s; start = null; end = null; outline.clear()
                        }, label = label, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .height(360.dp)
                        .onSizeChanged { box = it }
                        .pointerInput(shape) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    if (shape == CropShape.FREE) { outline.clear(); outline += p } else { start = p; end = p }
                                },
                                onDrag = { change, _ ->
                                    if (shape == CropShape.FREE) outline += change.position else end = change.position
                                },
                            )
                        },
                ) {
                    Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        val s = start
                        val e = end
                        val accent = Color(0xFF3B7DDD)
                        if (shape != CropShape.FREE && s != null && e != null) {
                            val topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y))
                            val size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y))
                            if (shape == CropShape.RECT) drawRect(accent, topLeft, size, style = Stroke(4f))
                            else drawOval(accent, topLeft, size, style = Stroke(4f))
                        }
                        for (i in 1 until outline.size) drawLine(accent, outline[i - 1], outline[i], 4f)
                    }
                }
            }
        },
        confirmButton = {
            val ready = if (shape == CropShape.FREE) outline.size >= 3 else start != null && end != null && start != end
            TextButton(enabled = ready, onClick = {
                if (shape == CropShape.FREE) {
                    val points = outline.map(::fraction)
                    val area = RectF(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
                    onCrop(shape, area, points)
                } else {
                    val a = fraction(start!!)
                    val b = fraction(end!!)
                    onCrop(shape, RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y)), emptyList())
                }
            }) { Text("자르기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** Rounded corners and a border, baked into a new copy of the picture; and its transparency, which is not. */
@Composable
internal fun ImageStyleDialog(
    opacity: Float,
    onOpacity: (Float) -> Unit,
    onDecorate: (corner: Float, border: Float, color: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var corner by remember { mutableFloatStateOf(0.1f) }
    var border by remember { mutableFloatStateOf(0f) }
    var color by remember { mutableIntStateOf(0xFF424242.toInt()) }
    var alpha by remember { mutableFloatStateOf(opacity) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("사진 스타일") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("투명도 ${(alpha * 100).toInt()}%")
                SkinSlider(alpha, { alpha = it; onOpacity(it) }, 0.1f..1f)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("모서리 ${(corner * 100).toInt()}%")
                SkinSlider(corner, { corner = it }, 0f..0.5f)
                Text("테두리 ${border.toInt()}px")
                SkinSlider(border, { border = it }, 0f..40f)
                if (border > 0f) ColorInput(color) { color = it }
                TextButton(onClick = { onDecorate(corner, border, color) }) { Text("모서리·테두리 적용") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
