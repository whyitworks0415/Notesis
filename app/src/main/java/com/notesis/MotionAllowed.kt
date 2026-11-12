package com.notesis

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the chrome may move at all - popups growing, the selection sliding,
 * panels coming in - watched once for the whole screen and shared from
 * [ProvideSkin]. Null where nothing has provided it yet (a preview).
 */
internal val LocalMotionAllowed = compositionLocalOf<Boolean?> { null }

/** Whether the chrome may move here: the system's animations are on, and nothing is saving power. */
@Composable
internal fun rememberMotionAllowed(): Boolean = LocalMotionAllowed.current ?: rememberSystemMotionAllowed()

/**
 * The animation scales are watched by one ContentObserver per provider.
 * Battery saver has no receiver of its own here: [InteractionGovernor] is the
 * one place in the app that knows about it, and its state is read.
 */
@Composable
internal fun rememberSystemMotionAllowed(): Boolean {
    val context = LocalContext.current
    val animations = remember(context) {
        // Idempotent; MainActivity has normally done it already.
        InteractionGovernor.init(context)
        mutableStateOf(readAnimationsEnabled(context))
    }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animations.value = readAnimationsEnabled(context)
            }
        }
        val resolver = context.contentResolver
        val uris = listOf(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
            Settings.Global.getUriFor(Settings.Global.WINDOW_ANIMATION_SCALE),
        )
        uris.forEach { resolver.registerContentObserver(it, false, observer) }
        onDispose {
            uris.forEach { resolver.unregisterContentObserver(observer) }
        }
    }
    return animations.value && !InteractionGovernor.observedPowerSave
}

private fun readAnimationsEnabled(context: Context): Boolean {
    // Below Android 13 the chrome holds still, as it always has there.
    if (Build.VERSION.SDK_INT < 33) return false
    val resolver = context.contentResolver
    fun scale(name: String): Float = runCatching {
        Settings.Global.getFloat(resolver, name, 1f)
    }.getOrDefault(1f)
    return scale(Settings.Global.ANIMATOR_DURATION_SCALE) > 0f &&
        scale(Settings.Global.TRANSITION_ANIMATION_SCALE) > 0f &&
        scale(Settings.Global.WINDOW_ANIMATION_SCALE) > 0f
}
