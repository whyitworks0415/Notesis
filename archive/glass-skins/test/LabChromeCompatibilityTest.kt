package com.notesis

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabChromeCompatibilityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("lab", Context.MODE_PRIVATE)
    @Before fun clear() { prefs.edit().clear().commit() }
    @After fun restore() { prefs.edit().clear().commit(); LabPerformance.load(context) }

    @Test fun upgradeRestoresOriginalOpticsWithoutResettingOtherExperimentsOrMotionSettings() {
        prefs.edit().putBoolean("efficientGlass", true).putBoolean("writingBoost", false)
            .putBoolean("nativePresent", true).commit()
        val motion = context.getSharedPreferences("pens", Context.MODE_PRIVATE)
        motion.edit().putFloat("motionSpeed", 1.3f).putInt("toolbarMotionMs", 280).commit()
        val before = motion.all.toMap()
        LabPerformance.load(context)
        assertFalse(LabPerformance.efficientGlass)
        assertFalse(prefs.getBoolean("efficientGlass", true))
        assertTrue(prefs.getBoolean("originalChromeV1", false))
        assertFalse(LabPerformance.writingBoost)
        assertTrue(LabPerformance.nativePresent)
        assertEquals(before, motion.all)
    }

    @Test fun originalOpticsAreTheDefaultOnAFreshInstall() {
        LabPerformance.load(context)
        assertFalse(LabPerformance.efficientGlass)
    }

    @Test fun anExplicitOptInAfterMigrationSurvivesReload() {
        LabPerformance.load(context)
        LabPerformance.setEfficientGlass(context, true)
        LabPerformance.load(context)
        assertTrue(LabPerformance.efficientGlass)
        LabPerformance.setEfficientGlass(context, false)
        LabPerformance.load(context)
        assertFalse(LabPerformance.efficientGlass)
    }
}
