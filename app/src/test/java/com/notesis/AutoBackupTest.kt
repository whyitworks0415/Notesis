package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When an automatic backup is due. */
class AutoBackupTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `off is never due`() {
        assertFalse(AutoBackup.due(BackupTarget.OFF, 0L, 1, 10 * day))
    }

    @Test
    fun `due once the days have gone by, a little early still counting`() {
        val last = 100 * day
        assertFalse(AutoBackup.due(BackupTarget.DEVICE, last, 1, last + day / 2))
        assertTrue(AutoBackup.due(BackupTarget.DEVICE, last, 1, last + day - 60 * 60 * 1000))
        assertFalse(AutoBackup.due(BackupTarget.FOLDER, last, 7, last + 6 * day))
        assertTrue(AutoBackup.due(BackupTarget.FOLDER, last, 7, last + 7 * day))
    }

    @Test
    fun `never backed up is due at once`() {
        assertTrue(AutoBackup.due(BackupTarget.DRIVE, 0L, 7, 100 * day))
    }
}
