package com.notesis

import android.content.Context
import java.io.DataOutputStream
import java.io.File
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
class StableInstallCompatibilityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val updates get() = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val fixture get() = File(context.filesDir, "notes/stable-upgrade-fixture")
    @Before fun prepare() {
        updates.edit().clear().commit()
        context.getSharedPreferences("lab", Context.MODE_PRIVATE).edit().clear().commit()
        fixture.deleteRecursively()
    }
    @After fun clean() { updates.edit().clear().commit(); fixture.deleteRecursively() }

    @Test fun labInstallKeepsItsPackageActionsAndUpdateDefaultsSeparate() {
        assertEquals("com.notesis.lab", context.packageName)
        assertTrue(isLabInstall(context))
        assertFalse(AppUpdate.autoCheck(context))
        assertTrue(AppUpdate.isBeta(context))
        assertEquals("com.notesis.lab.widget.OPEN_NOTE", NotesWidget.ACTION_OPEN)
        assertEquals("com.notesis.lab.widget.NEW_NOTE", NotesWidget.ACTION_NEW)
    }

    @Test fun explicitUpdatePreferencesSurviveThePerformanceSettingsLoad() {
        updates.edit().putBoolean("autoCheck", false).putBoolean("beta", true).commit()
        val before = updates.all.toMap()
        LabPerformance.load(context)
        assertEquals(before, updates.all)
        assertFalse(AppUpdate.autoCheck(context))
        assertTrue(AppUpdate.isBeta(context))
        assertFalse(LabPerformance.nativePresent)
    }

    @Test fun existingMetadataAndVersionFourInkAreReadFromTheOriginalDirectory() {
        // The pre-Lab release uses this path, these metadata fields and v4 ink.
        File(fixture, "pages").mkdirs()
        val metadata = File(fixture, "meta.json").apply {
            writeText("""{"title":"Existing notebook","modified":1,"pages":[{"id":"p1","w":1240,"h":1754,"bg":"GRID"}]}""")
        }
        val ink = File(fixture, "pages/p1.bin")
        DataOutputStream(ink.outputStream()).use {
            it.writeInt(0x4E545353); it.writeInt(4); it.writeInt(0)
        }
        val metaBefore = metadata.readBytes()
        val inkBefore = ink.readBytes()
        val store = NoteStore(context)
        val entry = store.list().single { it.id == fixture.name }
        assertEquals("Existing notebook", entry.title)
        val page = store.load(entry.id).pages.single()
        assertEquals("p1", page.id)
        assertEquals(PageBackground.GRID, page.background)
        assertEquals(1240f, page.width, 0f)
        assertTrue(store.loadPage(entry.id, page, 0.1f).isEmpty())
        assertArrayEquals(metaBefore, metadata.readBytes())
        assertArrayEquals(inkBefore, ink.readBytes())
    }
}
