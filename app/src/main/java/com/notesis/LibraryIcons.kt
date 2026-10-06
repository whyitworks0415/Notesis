package com.notesis

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** New library actions use the same outline weight as the existing UI. */
internal object LibraryIcons {
    val Sort: ImageVector by lazy {
        ImageVector.Builder(name = "Notesis.Sort", defaultWidth = 24.dp,
            defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString(
                "M4 6H20M4 12H15M4 18H10"
            ).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val Star: ImageVector by lazy {
        ImageVector.Builder(name = "Notesis.Star", defaultWidth = 24.dp,
            defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(pathData = PathParser().parsePathString(
                "M12 3L14.8 8.7L21 9.6L16.5 14L17.6 20.2L12 17.3L6.4 20.2L7.5 14L3 9.6L9.2 8.7Z"
            ).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
}
