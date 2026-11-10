package com.notesis

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabGuardLifecycleTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var guard: LabRunGuard
    @Before fun clear() {
        context.getSharedPreferences("lab", Context.MODE_PRIVATE).edit().clear().commit()
        restart()
    }

    private fun restart() { guard = LabRunGuard().apply { load(context) } }

    @Test fun crashesBeforeTheActivityStartsStillTriggerTheGuard() {
        assertFalse(guard.nativeOff)
        // Each reload represents a process that died before onStart could run.
        restart()
        assertEquals(1, guard.uncleanRuns)
        restart()
        assertEquals(2, guard.uncleanRuns)
        assertTrue(guard.nativeOff)
    }

    @Test fun nativeRecoveryWaitsUntilTheNextProcessStart() {
        restart()
        restart()
        guard.reset(context)
        assertTrue(guard.nativeOff)
        guard.load(context)
        assertTrue(guard.nativeOff)
        restart()
        assertFalse(guard.nativeOff)
        assertEquals(0, guard.uncleanRuns)
    }

    @Test fun aCleanStopBreaksTheSequenceOfUncleanRuns() {
        restart()
        assertEquals(1, guard.uncleanRuns)
        guard.left(context)
        restart()
        assertEquals(0, guard.uncleanRuns)
        assertFalse(guard.nativeOff)
    }

    @Test fun activityRecreationDoesNotCountAsAnotherProcessStart() {
        repeat(3) { guard.load(context) }
        assertEquals(0, guard.uncleanRuns)
        assertFalse(guard.nativeOff)
    }
}
