package com.notesis

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Contrast after compositing, rather than the RGB of a translucent label. */
internal fun chromeContrast(foreground: Color, background: Color): Float {
    val ground = background.copy(alpha = 1f)
    val ink = foreground.compositeOver(ground).luminance()
    val paper = ground.luminance()
    return (maxOf(ink, paper) + 0.05f) / (minOf(ink, paper) + 0.05f)
}

/** Keep the chosen hue and move only as far towards black/white as reading requires. */
internal fun readableChrome(foreground: Color, background: Color, minimum: Float = 4.5f): Color {
    val ink = foreground.compositeOver(background.copy(alpha = 1f)).copy(alpha = 1f)
    if (chromeContrast(ink, background) >= minimum) return ink
    val end = if (chromeContrast(Color.White, background) > chromeContrast(Color.Black, background)) Color.White else Color.Black
    var low = 0f
    var high = 1f
    repeat(16) {
        val t = (low + high) / 2f
        if (chromeContrast(lerp(ink, end, t), background) >= minimum) high = t else low = t
    }
    return lerp(ink, end, high)
}

/** Quiet mineral surfaces; wallpaper colours remain in the accent roles. No live effects. */
internal fun materialStudioScheme(base: ColorScheme, dark: Boolean): ColorScheme {
    val surfaces = if (dark) base.copy(
        background = Color(0xFF101319), surface = Color(0xFF141820),
        surfaceDim = Color(0xFF101319), surfaceBright = Color(0xFF333D4D),
        surfaceContainerLowest = Color(0xFF0B0E13), surfaceContainerLow = Color(0xFF1B2029),
        surfaceContainer = Color(0xFF212733), surfaceContainerHigh = Color(0xFF29313E),
        surfaceContainerHighest = Color(0xFF333D4D),
        onSurface = Color(0xFFF1F4FA), onBackground = Color(0xFFF1F4FA),
        onSurfaceVariant = Color(0xFFBAC5D5), surfaceVariant = Color(0xFF29313E),
        outline = Color(0xFF8997AC), outlineVariant = Color(0xFF465165),
    ) else base.copy(
        background = Color(0xFFF4F5F8), surface = Color(0xFFFBFCFE),
        surfaceDim = Color(0xFFDCE1EA), surfaceBright = Color.White,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F2F6),
        surfaceContainer = Color(0xFFEBEEF3), surfaceContainerHigh = Color(0xFFE3E7EF),
        surfaceContainerHighest = Color(0xFFDCE1EA),
        onSurface = Color(0xFF1C2230), onBackground = Color(0xFF1C2230),
        onSurfaceVariant = Color(0xFF515C6C), surfaceVariant = Color(0xFFE3E7EF),
        outline = Color(0xFF69768A), outlineVariant = Color(0xFFBDC6D5),
    )
    return readableScheme(surfaces)
}

/** All action pairs and ordinary text must also work on the highest raised surface. */
internal fun readableScheme(base: ColorScheme): ColorScheme {
    val raised = base.surfaceContainerHighest.copy(alpha = 1f)
    // A little headroom for state layers and the translucent popup window.
    val primary = readableChrome(base.primary, raised, 5.5f)
    val secondary = readableChrome(base.secondary, raised, 5.5f)
    val tertiary = readableChrome(base.tertiary, raised, 5.5f)
    val error = readableChrome(base.error, raised, 5.5f)
    return base.copy(
        primary = primary, onPrimary = readableChrome(base.onPrimary, primary),
        secondary = secondary, onSecondary = readableChrome(base.onSecondary, secondary),
        tertiary = tertiary, onTertiary = readableChrome(base.onTertiary, tertiary),
        error = error, onError = readableChrome(base.onError, error),
        onPrimaryContainer = readableChrome(base.onPrimaryContainer, base.primaryContainer),
        onSecondaryContainer = readableChrome(base.onSecondaryContainer, base.secondaryContainer),
        onTertiaryContainer = readableChrome(base.onTertiaryContainer, base.tertiaryContainer),
        onErrorContainer = readableChrome(base.onErrorContainer, base.errorContainer),
        onSurface = readableChrome(base.onSurface, raised),
        onBackground = readableChrome(base.onBackground, base.background),
        onSurfaceVariant = readableChrome(base.onSurfaceVariant, raised),
    )
}


internal fun chromeTypography(): Typography = Typography().let { base -> base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
) }
