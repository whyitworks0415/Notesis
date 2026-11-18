package com.notesis

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the app turns with the tablet. Off, it stays the way it was held
 * when it was switched off - the exact side, not just upright or sideways -
 * however the tablet is turned after, and it opens that way next time too:
 * a tablet laid on a desk or held at a slant while writing otherwise flips the
 * page under the pen.
 */
internal object ScreenRotation {
    private const val PREFS = "screen"
    private const val KEY_FOLLOWS = "rotateWithTablet"
    private const val KEY_LOCKED = "lockedOrientation"

    /** True while the app turns with the tablet (the default). */
    var follows by mutableStateOf(true)
        private set

    /** The side it is held to while [follows] is off. */
    private var locked = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        follows = prefs.getBoolean(KEY_FOLLOWS, true)
        locked = prefs.getInt(KEY_LOCKED, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    /** Asks the window for the orientation chosen; called as the activity starts and on each change. */
    fun apply(activity: Activity) {
        activity.requestedOrientation =
            if (follows || locked == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else locked
    }

    /** Switches turning with the tablet on or off; off keeps the side the screen is on now. */
    fun setFollows(activity: Activity, on: Boolean) {
        follows = on
        if (!on) {
            // Activity.getDisplay is Android 11+; the window manager's display answers on 10 too.
            @Suppress("DEPRECATION")
            val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) activity.display?.rotation
                ?: Surface.ROTATION_0 else activity.windowManager.defaultDisplay.rotation
            val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            locked = lockedOrientation(rotation, landscape)
        }
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FOLLOWS, on).putInt(KEY_LOCKED, locked).apply()
        apply(activity)
    }

    /** Switches it the other way, for a quick toggle; does nothing where [context] is not in an activity. */
    fun toggle(context: Context) {
        var host: Context? = context
        while (host is android.content.ContextWrapper && host !is Activity) host = host.baseContext
        (host as? Activity)?.let { setFollows(it, !follows) }
    }

    /** "Portrait" or "Landscape" for the side it is held to, for the settings to say. */
    fun lockedLabel(): String = when (locked) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> "Landscape"
        else -> "Portrait"
    }
}

/**
 * The orientation that keeps the screen exactly as it is: [rotation] is the
 * display's turn from its natural way up (a [Surface] ROTATION_ value) and
 * [landscapeNow] whether the screen is wider than tall at that turn. Whether
 * the tablet is naturally upright follows from the two together; a tablet
 * that is naturally sideways turns the other way round.
 */
internal fun lockedOrientation(rotation: Int, landscapeNow: Boolean): Int {
    val quarterTurn = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    val naturallyUpright = quarterTurn == landscapeNow
    return if (naturallyUpright) when (rotation) {
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else when (rotation) {
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}
