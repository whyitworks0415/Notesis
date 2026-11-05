package com.notesis

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The colours the library itself is laid out in: the ground, the cards on
 * it, and the quiet lines and words between them. The accent stays the
 * user's; a look only sets the paper the library is printed on.
 */
internal data class HomeLookColors(
    /** The ground, top to bottom. */
    val top: Color,
    val bottom: Color,
    /** A card's body, and a page's paper inside it. */
    val card: Color,
    val paper: Color,
    /** Words, quieter words, and the faint lines between things. */
    val ink: Color,
    val quiet: Color,
    val line: Color,
)

/**
 * The library's looks. [DEFAULT] is the app as it was: the theme's own
 * surface. The others are each one idea of a desk, light and dark, chosen
 * to sit under a page without arguing with it: warm paper, cool mist, a
 * green-grey studio and a dusk between peach and lilac.
 */
internal enum class HomeLook(val label: String, private val light: HomeLookColors?, private val dark: HomeLookColors?) {
    DEFAULT("Default", null, null),
    PAPER(
        "Warm paper",
        HomeLookColors(Color(0xFFF6F2EA), Color(0xFFEEE8DC), Color(0xFFFFFDF8), Color(0xFFFFFEFA),
            Color(0xFF2B2722), Color(0xFF6E665A), Color(0xFFE3DCCD)),
        HomeLookColors(Color(0xFF1E1B17), Color(0xFF161411), Color(0xFF2A2621), Color(0xFF332E28),
            Color(0xFFEDE6DA), Color(0xFFB3A99A), Color(0xFF3D372F)),
    ),
    MIST(
        "Mist",
        HomeLookColors(Color(0xFFF0F3F8), Color(0xFFE5E9F3), Color(0xFFFFFFFF), Color(0xFFFFFFFF),
            Color(0xFF1F2430), Color(0xFF5F6779), Color(0xFFDADFEA)),
        HomeLookColors(Color(0xFF141922), Color(0xFF0E1118), Color(0xFF1D2330), Color(0xFF252C3A),
            Color(0xFFE4E8F1), Color(0xFFA3ABBD), Color(0xFF323A4A)),
    ),
    SAGE(
        "Sage",
        HomeLookColors(Color(0xFFF0F3EE), Color(0xFFE5EBE2), Color(0xFFFBFDF9), Color(0xFFFFFFFC),
            Color(0xFF1F261E), Color(0xFF5C6758), Color(0xFFD6DFD2)),
        HomeLookColors(Color(0xFF141914), Color(0xFF0F130F), Color(0xFF1E251E), Color(0xFF263026),
            Color(0xFFE2EADF), Color(0xFFA3B09E), Color(0xFF323C31)),
    ),
    DUSK(
        "Dusk",
        HomeLookColors(Color(0xFFF8F0EB), Color(0xFFEFE7F0), Color(0xFFFFFBFA), Color(0xFFFFFDFC),
            Color(0xFF2A2124), Color(0xFF6F6064), Color(0xFFEADDDA)),
        HomeLookColors(Color(0xFF1E171A), Color(0xFF17121A), Color(0xFF2A2126), Color(0xFF33292F),
            Color(0xFFF0E4E8), Color(0xFFB8A6AD), Color(0xFF3E3237)),
    ),
    ;

    fun colors(dark: Boolean): HomeLookColors? = if (dark) this.dark else light
}

/** The look chosen, kept on this tablet. */
internal object HomeLooks {
    var look by mutableStateOf(HomeLook.DEFAULT)
        private set

    fun load(context: Context) {
        val saved = prefs(context).getString(KEY, null)
        look = HomeLook.entries.firstOrNull { it.name == saved } ?: HomeLook.DEFAULT
    }

    fun set(context: Context, value: HomeLook) {
        look = value
        prefs(context).edit().putString(KEY, value.name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("pens", Context.MODE_PRIVATE)
    private const val KEY = "homeLook"
}

/**
 * The library in its look: its surfaces, words and lines taken from the
 * look, everything else - the accent above all - left as the theme has it.
 */
@Composable
internal fun HomeLookTheme(content: @Composable () -> Unit) {
    val colors = HomeLooks.look.colors(isDarkTheme())
    if (colors == null) {
        content()
        return
    }
    val base = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = base.copy(
            background = colors.top,
            onBackground = colors.ink,
            surface = colors.card,
            onSurface = colors.ink,
            onSurfaceVariant = colors.quiet,
            outlineVariant = colors.line,
            surfaceContainerLowest = colors.paper,
            surfaceContainerLow = colors.card,
            surfaceContainer = colors.card,
        ),
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}

/** The look's ground, or null for the theme's own plain surface. */
@Composable
internal fun homeLookGround(): Brush? = HomeLooks.look.colors(isDarkTheme())?.let { Brush.verticalGradient(listOf(it.top, it.bottom)) }

/** The colour at the top of the look's ground, for what fades into it; null for the theme's surface. */
@Composable
internal fun homeLookTop(): Color? = HomeLooks.look.colors(isDarkTheme())?.top

/** Each look as a little desk with two cards on it, the one in use ringed. */
@Composable
internal fun HomeLookPicker(onPicked: (HomeLook) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = isDarkTheme()
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
        HomeLook.entries.forEach { look ->
            val chosen = HomeLooks.look == look
            val colors = look.colors(dark)
            val ground = colors?.let { Brush.verticalGradient(listOf(it.top, it.bottom)) }
                ?: Brush.verticalGradient(listOf(scheme.surface, scheme.surface))
            val card = colors?.card ?: scheme.surfaceContainerLowest
            Column(
                Modifier
                    .clip(Ui.Shape.control)
                    .clickable(role = Role.RadioButton) { HomeLooks.set(context, look); onPicked(look) }
                    .semantics { contentDescription = look.label; selected = chosen }
                    .padding(Ui.Space.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(72.dp, 54.dp)
                        .clip(Ui.Shape.chip)
                        .background(ground)
                        .border(if (chosen) 2.5.dp else 1.dp, if (chosen) scheme.primary else scheme.outlineVariant, Ui.Shape.chip)
                        .padding(Ui.Space.s),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
                        repeat(2) {
                            Box(Modifier.width(22.dp).height(32.dp).clip(Ui.Shape.badge).background(card))
                        }
                    }
                }
                Text(look.label, style = MaterialTheme.typography.labelMedium,
                    color = if (chosen) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Ui.Space.xs))
            }
        }
    }
}
