package com.notesis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SkinSettingsTest {
    @Test fun `what is set reads back as it was set`() {
        val set = SkinSettings(themeMode = AppThemeMode.DARK, content = 0xFF10314F.toInt(), accent = 0xFF00897B.toInt(),
            corner = 20f, highContrast = true, shadows = false, dynamicColor = false, amoled = true, popupDim = 0.3f)
        assertEquals(set, SkinSettings.fromJson(set.toJson()))
    }

    @Test fun `shadows can be turned off and stay off`() {
        val off = SkinSettings(shadows = false)
        assertEquals(false, SkinSettings.fromJson(off.toJson()).shadows)
        // Saved before the switch existed: shadows on.
        assertEquals(true, SkinSettings.fromJson(JSONObject(off.toJson()).apply { remove("shadows") }.toString()).shadows)
    }

    @Test fun `wallpaper colours start on unless an accent was picked before`() {
        assertEquals(true, SkinSettings().dynamicColor)
        assertEquals(true, SkinSettings.fromJson("{}").dynamicColor)
        val picked = JSONObject(SkinSettings().toJson()).apply { remove("dynamicColor"); put("accent", 0xFF00897B.toInt()) }
        assertEquals(false, SkinSettings.fromJson(picked.toString()).dynamicColor)
        val untouched = JSONObject(SkinSettings().toJson()).apply { remove("dynamicColor") }
        assertEquals(true, SkinSettings.fromJson(untouched.toString()).dynamicColor)
    }

    @Test fun `the corner keeps the key it was first saved under, and stays in range`() {
        assertEquals(20f, SkinSettings.fromJson("""{"spotiglassCorner": 20}""").corner, 0f)
        assertEquals(SkinSettings.CORNER_RANGE.endInclusive, SkinSettings.fromJson("""{"spotiglassCorner": 400}""").corner, 0f)
        assertEquals(32f, SkinSettings.fromJson("""{"spotiglassCorner": "x"}""").corner, 0f)
    }

    @Test fun `the glass looks' settings saved while they were out come back as they were`() {
        // Saved by 0.90 and kept untouched by the Material-only versions since.
        val before = """{"blur": 12.5, "liquidStyle": "MORNYE", "mornye": {"accent": "BLUE"}, "accent": 1, "later": 3}"""
        val read = SkinSettings.fromJson(before)
        assertEquals(12.5f, read.blur, 0f)
        assertEquals(LiquidStyle.MORNYE, read.liquidStyle)
        val saved = JSONObject(read.copy(accent = 2).toJson(kept = before))
        assertEquals(12.5, saved.getDouble("blur"), 0.0)
        assertEquals("MORNYE", saved.getString("liquidStyle"))
        assertEquals(2, saved.getInt("accent"))
        // A key this version does not read is kept for the one that does.
        assertEquals(3, saved.getInt("later"))
    }

    @Test fun `an unreadable save is a fresh install's look`() {
        assertEquals(SkinSettings(), SkinSettings.fromJson(null))
        assertEquals(SkinSettings(), SkinSettings.fromJson("not json"))
        assertEquals(SkinSettings(), SkinSettings(corner = 32f).let { SkinSettings.fromJson(it.toJson(kept = "not json")) })
    }
}
