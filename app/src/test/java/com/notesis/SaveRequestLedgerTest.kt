package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveRequestLedgerTest {
    @Test
    fun duplicateGenerationIsKnownWhileQueuedRunningAndCompleted() {
        val ledger = SaveRequestLedger()
        val generation = SaveGeneration(7L, 12L, "note")

        assertFalse(ledger.contains("id", generation))
        ledger.queued("id", generation)
        assertTrue(ledger.contains("id", generation))
        ledger.started("id", generation)
        assertTrue(ledger.contains("id", generation))
        ledger.finished("id", generation, successful = true)
        assertTrue(ledger.contains("id", generation))
    }

    @Test
    fun newerRevisionAndNewDocumentSessionAreNotCoalesced() {
        val ledger = SaveRequestLedger()
        val original = SaveGeneration(3L, 4L, "note")
        ledger.queued("id", original)

        assertFalse(ledger.contains("id", original.copy(editRevision = 5L)))
        assertFalse(ledger.contains("id", original.copy(sessionId = 4L)))
        assertFalse(ledger.contains("id", original.copy(title = "renamed")))
    }

    @Test
    fun failedGenerationCanBeRequestedAgain() {
        val ledger = SaveRequestLedger()
        val generation = SaveGeneration(1L, 2L, "note")
        ledger.queued("id", generation)
        ledger.started("id", generation)
        ledger.finished("id", generation, successful = false)

        assertFalse(ledger.contains("id", generation))
    }

    @Test
    fun newerPendingGenerationSurvivesOlderCompletion() {
        val ledger = SaveRequestLedger()
        val older = SaveGeneration(1L, 8L, "note")
        val newer = older.copy(editRevision = 9L)
        ledger.queued("id", older)
        ledger.started("id", older)
        ledger.queued("id", newer)
        ledger.finished("id", older, successful = true)

        assertTrue(ledger.contains("id", newer))
    }
}
