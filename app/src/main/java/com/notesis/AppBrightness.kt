package com.notesis

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * Two brightness controls of the app's own, each on or off by itself:
 *
 * - [screenLevel], the screen's real brightness while the app is in front:
 *   the backlight itself, set on the app's window, so leaving the app hands
 *   the screen back at whatever brightness the system has. Brighter or darker
 *   than the system's, either way.
 * - [level], a veil over everything the app draws, for dimmer than the
 *   screen goes at its lowest; the screen's brightness is left as it is.
 *
 * [FOLLOW_SYSTEM] turns either off.
 */
internal object AppBrightness {
    const val FOLLOW_SYSTEM = -1f
    const val MIN_LEVEL = 0.2f
    /** The darkest the screen itself is set to: low, but never black. */
    const val MIN_SCREEN = 0.02f

    /** The veil's level. */
    var level by mutableFloatStateOf(FOLLOW_SYSTEM)
        private set

    /** The screen's brightness while the app is in front. */
    var screenLevel by mutableFloatStateOf(FOLLOW_SYSTEM)
        private set

    fun load(context: Context) {
        level = prefs(context).getFloat(KEY, FOLLOW_SYSTEM)
        screenLevel = prefs(context).getFloat(SCREEN_KEY, FOLLOW_SYSTEM)
    }

    fun set(context: Context, value: Float) {
        val clean = if (value < 0f) FOLLOW_SYSTEM else value.coerceIn(MIN_LEVEL, 1f)
        level = clean
        prefs(context).edit().putFloat(KEY, clean).apply()
    }

    fun setScreen(context: Context, value: Float) {
        val clean = screenValue(value)
        screenLevel = clean
        prefs(context).edit().putFloat(SCREEN_KEY, clean).apply()
    }

    /** [value] as the window takes it: the system's own when below 0, else within [MIN_SCREEN]..1. */
    fun screenValue(value: Float): Float = if (value < 0f) FOLLOW_SYSTEM else value.coerceIn(MIN_SCREEN, 1f)

    /** Puts both on the activity's window: the screen's brightness, and the veil over its content. */
    fun apply(activity: Activity) {
        val window = activity.window ?: return
        val attributes = window.attributes
        val wanted = if (screenLevel < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else screenLevel
        if (attributes.screenBrightness != wanted) {
            attributes.screenBrightness = wanted
            window.attributes = attributes
        }
        val alpha = veilAlpha(level)
        window.decorView.foreground = if (alpha <= 0) null
            else android.graphics.drawable.ColorDrawable(android.graphics.Color.argb(alpha, 0, 0, 0))
    }

    /** How dark the veil is for [level]: none at full brightness, most of the way to black at the least. */
    fun veilAlpha(level: Float): Int = if (level < 0f) 0 else ((1f - level.coerceIn(0f, 1f)) * 255f).toInt()

    private fun prefs(context: Context) = context.getSharedPreferences("pens", Context.MODE_PRIVATE)
    private const val KEY = "appBrightness"
    private const val SCREEN_KEY = "appScreenBrightness"
}

/** The two controls, each with its own switch and slider. */
@Composable
internal fun AppBrightnessSetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        val screen = AppBrightness.screenLevel
        val ownScreen = screen >= 0f
        SettingsToggle("Screen brightness in this app", ownScreen, { on ->
            // Starting from the screen's brightness now, so turning it on changes nothing until moved.
            AppBrightness.setScreen(context, if (on) currentScreenBrightness(context) else AppBrightness.FOLLOW_SYSTEM)
        }, hint = if (ownScreen) "Sets the screen itself while Notesis is open. Leaving the app gives the screen back its usual brightness."
            else "The screen as bright as the system has it.")
        if (ownScreen) {
            Text("${(screen * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            SkinSlider(
                value = screen,
                onValueChange = { AppBrightness.setScreen(context, it) },
                valueRange = AppBrightness.MIN_SCREEN..1f,
                step = 0.01f,
            )
        }
        val level = AppBrightness.level
        val own = level >= 0f
        SettingsToggle("Dim the app", own, { on ->
            AppBrightness.set(context, if (on) 0.8f else AppBrightness.FOLLOW_SYSTEM)
        }, hint = if (own) "Dims the app itself; the screen's brightness and other apps are left as they are."
            else "The app as bright as the screen.")
        if (own) {
            Text("${(level * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            SkinSlider(
                value = level,
                onValueChange = { AppBrightness.set(context, it) },
                valueRange = AppBrightness.MIN_LEVEL..1f,
                step = 0.01f,
            )
        }
    }
}

/** The system's brightness now, 0 to 1, as a starting point; half when it cannot be read. */
private fun currentScreenBrightness(context: Context): Float = runCatching {
    android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
}.getOrDefault(0.5f).coerceIn(AppBrightness.MIN_SCREEN, 1f)
