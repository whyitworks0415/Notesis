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
 * The app's own brightness: the app's window is dimmed by a veil drawn over
 * everything in it, so the screen's own brightness - and every other app -
 * is left as it is. [FOLLOW_SYSTEM] draws no veil.
 */
internal object AppBrightness {
    const val FOLLOW_SYSTEM = -1f
    const val MIN_LEVEL = 0.2f

    var level by mutableFloatStateOf(FOLLOW_SYSTEM)
        private set

    fun load(context: Context) {
        level = prefs(context).getFloat(KEY, FOLLOW_SYSTEM)
    }

    fun set(context: Context, value: Float) {
        val clean = if (value < 0f) FOLLOW_SYSTEM else value.coerceIn(MIN_LEVEL, 1f)
        level = clean
        prefs(context).edit().putFloat(KEY, clean).apply()
    }

    /** Puts [level] on the activity's window, as a veil over its content. */
    fun apply(activity: Activity) {
        val window = activity.window ?: return
        // An earlier version set the screen's own brightness; leave none of that behind.
        val attributes = window.attributes
        if (attributes.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
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
}

/** Follow the system, or a level of the app's own. */
@Composable
internal fun AppBrightnessSetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val level = AppBrightness.level
    val own = level >= 0f
    Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.xs)) {
        SettingsToggle("App brightness", own, { on ->
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
