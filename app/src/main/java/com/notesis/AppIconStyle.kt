package com.notesis

import android.content.Context
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class StaticIconSet(val label: String) {
    MATERIAL("Material Symbols"), REICONS("Reicons");

    companion object {
        fun fromName(name: String?): StaticIconSet = entries.firstOrNull { it.name == name } ?: MATERIAL
    }
}

/** One choice across every skin. Old animated-icon preferences are retired. */
internal object AppIconStyle {
    var selected by mutableStateOf(StaticIconSet.MATERIAL)
        private set
    val materialSymbols: Boolean get() = selected == StaticIconSet.MATERIAL

    fun load(context: Context) {
        selected = StaticIconSet.fromName(context.getSharedPreferences("pens", Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun select(context: Context, set: StaticIconSet) {
        selected = set
        context.getSharedPreferences("pens", Context.MODE_PRIVATE).edit().putString(KEY, set.name).apply()
    }

    private const val KEY = "staticIconSet"
}

/** Static glyphs: press and selection motion belongs to their containing control. */
@Composable
internal fun AppIcon(
    vector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = Icon(vector, contentDescription, modifier, tint)

@Composable
internal fun AppIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.materialPressFeedback(interaction, enabled)) {
        AppIcon(icon, contentDescription, tint = tint ?: LocalContentColor.current)
    }
}
