package com.notesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    @Test fun choicesTravel() {
        assertTrue(SettingsBackup.portable("pens", "skinSettings"))
        assertTrue(SettingsBackup.portable("pens", "toolbarTools"))
        assertTrue(SettingsBackup.portable("pens", "widths:PEN"))
        assertTrue(SettingsBackup.portable("clock", "autoStudy"))
        assertTrue(SettingsBackup.portable("clock", "alarms"))
    }

    @Test fun thisDeviceAndThisNoteStay() {
        assertFalse(SettingsBackup.portable("pens", "lastPage:abc"))
        assertFalse(SettingsBackup.portable("pens", "layout:abc"))
        assertFalse(SettingsBackup.portable("pens", "diagnosticId"))
        assertFalse(SettingsBackup.portable("pens", "homePhoto"))
        assertFalse(SettingsBackup.portable("pens", "compatWetInk"))
        assertFalse(SettingsBackup.portable("clock", "timerEndAt"))
        assertFalse(SettingsBackup.portable("clock", "studyDays"))
        assertFalse(SettingsBackup.portable("noteLock", "anything"))
    }
}
