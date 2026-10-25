package com.notesis

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * How the laser pointer looks, for every canvas at once - the note, the
 * popup note and each part of a split. Kept between runs.
 */
internal object LaserSettings {
    private var prefs: android.content.SharedPreferences? = null

    var style by mutableIntStateOf(LASER_TRAIL)
        private set
    var color by mutableIntStateOf(LASER_COLORS.first())
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("pens", Context.MODE_PRIVATE)
        prefs = p
        style = p.getInt(KEY_STYLE, LASER_TRAIL).coerceIn(LASER_TRAIL, LASER_DOT)
        color = p.getInt(KEY_COLOR, LASER_COLORS.first())
    }

    fun setStyle(value: Int) {
        style = value
        prefs?.edit()?.putInt(KEY_STYLE, value)?.apply()
    }

    fun setColor(value: Int) {
        color = value
        prefs?.edit()?.putInt(KEY_COLOR, value)?.apply()
    }

    private const val KEY_STYLE = "laserStyle"
    private const val KEY_COLOR = "laserColor"
}

/** The laser's colours: bright enough to be seen over any page. */
internal val LASER_COLORS = listOf(
    0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFD60A.toInt(), 0xFF34C759.toInt(),
    0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF2D55.toInt(), 0xFFFFFFFF.toInt(),
)

private val LASER_STYLES = listOf(
    Triple(LASER_TRAIL, "Trail", "A tail fades away behind the pen. The faster you move, the longer it is."),
    Triple(LASER_STROKE, "Fades after lifting", "The whole line stays while you point and goes a second after the pen lifts."),
    Triple(LASER_DOT, "Dot", "Only a dot under the pen."),
)

/**
 * The laser's panel, opened by tapping the laser again: how it is drawn and
 * in what colour. The same panel as the pen's settings.
 */
@Composable
internal fun LaserPanel(onDismiss: () -> Unit) {
    val panel = rememberToolPanelState()
    ToolPanel(
        title = "Laser pointer",
        state = panel,
        onOutside = { panel.close(onDismiss) },
        maxWidth = 480.dp,
        actions = {
            TextButton(onClick = { panel.close(onDismiss) }, modifier = Modifier.heightIn(min = Ui.Size.touchCompact)) {
                Text("Done")
            }
        },
    ) {
        PanelSectionHeader("Style")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            for ((style, label, _) in LASER_STYLES) {
                SettingsChoiceChip(selected = LaserSettings.style == style, onClick = { LaserSettings.setStyle(style) }, label = label)
            }
        }
        Text(
            LASER_STYLES.first { it.first == LaserSettings.style }.third,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        PanelSectionHeader("Color")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
            for (argb in LASER_COLORS) {
                val chosen = LaserSettings.color == argb
                val color = Color(argb)
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .then(if (chosen) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                        .padding(if (chosen) 5.dp else 0.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(1.dp, Color(0x33000000), CircleShape)
                        .clickable(role = Role.RadioButton) { LaserSettings.setColor(argb) }
                        .semantics { contentDescription = "Laser colour"; selected = chosen },
                )
            }
        }
        Text(
            "The laser is never saved: it points, and it is gone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
