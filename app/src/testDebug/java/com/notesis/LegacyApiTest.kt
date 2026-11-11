package com.notesis

import android.app.Activity
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The oldest Android versions the app installs on. What runs at start and in
 * the settings must not call something those versions do not have, and what
 * they cannot draw must fall away rather than fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 31])
class LegacyApiTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun theRefreshRateIsReadWithoutAnApiTheVersionLacks() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue(DisplayRate.refreshRate(activity) >= 1f)
        // The two frame watches that read it: starting and stopping them must not throw.
        MotionStats.start(activity)
        MotionStats.stop(activity)
        JankWatch.start(activity)
        JankWatch.stop(activity)
        PerfRecorder.start(activity, "test")
        PerfRecorder.stop(activity)
        PerfRecorder.report?.let { assertTrue(it.startsWith("Notesis performance recording")) }
        PerfRecorder.clear()
    }

    @Test fun strokesTakeThePlainestPathUnlessAnotherIsChosen() {
        context.getSharedPreferences("pens", Context.MODE_PRIVATE).edit().clear().commit()
        val store = PenStore(context)
        assertEquals(INK_PATH_AUTO, store.inkPath)
        assertTrue(store.compatWetInk)
        assertTrue(store.compatInkInWindow)
        // Echoing the switches back, as the note screen does, is not a choice.
        store.compatWetInk = true
        store.compatInkInWindow = true
        assertEquals(INK_PATH_AUTO, store.inkPath)
        store.inkPath = INK_PATH_FRONT
        assertEquals(false, store.compatWetInk)
    }

    @Test fun motionBlurIsNotBuiltWhereItCannotBeDrawn() {
        val effect = motionBlurEffect(BlurPart.POPUPS, 6000f, 6000f, 12f)
        if (android.os.Build.VERSION.SDK_INT < 31) assertNull(effect)
    }
}
