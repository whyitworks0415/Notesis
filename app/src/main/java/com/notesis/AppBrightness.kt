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
 * The app's own screen brightness, over the system's while the app is in
 * front: for a dim room without touching the tablet's setting, or a bright
 * page outside. [FOLLOW_SYSTEM] leaves the system's as it is.
 */
internal object AppBrightness {
    const val FOLLOW_SYSTEM = -1f
    const val MIN_LEVEL = 0.02f

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

    /** Puts [level] on the activity's window. */
    fun apply(activity: Activity) {
        val window = activity.window ?: return
        val attributes = window.attributes
        val wanted = if (level < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else level
        if (attributes.screenBrightness == wanted) return
        attributes.screenBrightness = wanted
        window.attributes = attributes
    }

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
            AppBrightness.set(context, if (on) 0.6f else AppBrightness.FOLLOW_SYSTEM)
        }, hint = if (own) "Only while Notesis is in front; the tablet's own setting is left as it is."
            else "Following the tablet's brightness.")
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
