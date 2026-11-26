package com.notesis

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupGrowthTest {
    @Test fun `opening protects the first frame and cancellation releases the backdrop`() = runBlocking {
        val preparing = CompletableDeferred<Boolean>()
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                preparing.complete(ChromeMotion.busy)
                awaitCancellation()
            }
        }
        val growth = PopupGrowth(shown = false)
        val opening = launch(clock) { growth.open(animate = true) }
        val pausedDuringPreparation = preparing.await()
        opening.cancelAndJoin()
        assertTrue(pausedDuringPreparation)
        assertTrue(growth.isShut())
        assertEquals(0, ChromeMotion.moving)
    }

    @Test fun `a popup composed shut is not closed again`() {
        val growth = PopupGrowth(shown = false)
        assertTrue(growth.isShut())
        // No frame clock here: a close that animated would throw for want of
        // one. A shut popup returns at once, counting no chrome motion.
        runBlocking { growth.close(animate = true) }
        assertTrue(growth.isShut())
        assertEquals(0, ChromeMotion.moving)
    }
}
