package com.notesis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinSettingsTest {
    @Test fun `a fresh install starts from the chosen glass`() {
        val now = SkinSettings()
        assertEquals(0.12f, now.frost, 0.001f)
        assertEquals(1f, now.transparency, 0f)
        assertEquals(1f, now.reflection, 0f)
        assertEquals(10f, now.depth, 0f)
        assertEquals(32f, now.spotiglassCorner, 0f)
        assertEquals(1f, now.spotiglassResponse, 0f)
        assertEquals(RefractionDirection.RAISED, now.refractionDirection)
        // Each pair is what its combined slider sets, so the slider reads back where it stands.
        val again = now.withFrost(now.frost).withTransparency(now.transparency).withDepth(now.depth)
        assertEquals(now.vibrancy, again.vibrancy, 0.001f)
        assertEquals(now.spotiglassToolbarOpacity, again.spotiglassToolbarOpacity, 0.001f)
        assertEquals(now.refraction, again.refraction, 0.001f)
        assertTrue(!now.hasOriginalOptics())
        assertTrue(SkinSettings.ORIGINAL.hasOriginalOptics())
    }

    @Test fun `settings saved at the old defaults move to the new ones, set ones stay`() {
        // As 0.60 and before saved them: no reflection, no optics version.
        fun saved(edit: (JSONObject) -> Unit = {}) = JSONObject(SkinSettings.ORIGINAL.copy(accent = 0xFF00AA00.toInt()).toJson())
            .apply { remove("reflection"); remove("opticsVersion"); edit(this) }.toString()
        val untouched = SkinSettings.fromJson(saved())
        assertEquals(SkinSettings().spotiglassClarity, untouched.spotiglassClarity, 0f)
        assertEquals(SkinSettings().reflection, untouched.reflection, 0f)
        assertEquals(SkinSettings().depth, untouched.depth, 0f)
        // Colours are never part of it.
        assertEquals(0xFF00AA00.toInt(), untouched.accent)
        // Tuned by hand: kept, with the edge it was seen with.
        val tuned = SkinSettings.fromJson(saved { it.put("blur", 44.0) })
        assertEquals(44f, tuned.blur, 0f)
        assertEquals(SkinSettings.ORIGINAL.spotiglassClarity, tuned.spotiglassClarity, 0f)
        assertEquals(SkinSettings.LEGACY_REFLECTION, tuned.reflection, 0f)
        // Saved now, read back as it is, even at the old values.
        val round = SkinSettings.fromJson(SkinSettings.ORIGINAL.toJson())
        assertEquals(SkinSettings.ORIGINAL.spotiglassClarity, round.spotiglassClarity, 0f)
        assertEquals(SkinSettings.ORIGINAL.reflection, round.reflection, 0f)
    }

    @Test fun `controls keep the original glass, and the reader's colours`() {
        val tuned = SkinSettings().copy(spotiglassCorner = 8f, spotiglassClarity = 0.2f, accent = 0xFF123456.toInt(), highContrast = true)
        val control = tuned.controlOptics()
        assertEquals(SkinSettings.ORIGINAL.spotiglassCorner, control.spotiglassCorner, 0f)
        assertEquals(SkinSettings.ORIGINAL.spotiglassClarity, control.spotiglassClarity, 0f)
        assertEquals(SkinSettings.ORIGINAL.blur, control.blur, 0f)
        assertEquals(0xFF123456.toInt(), control.accent)
        assertTrue(control.highContrast)
    }

    @Test fun `each combined slider sets its settings together and reads back what it set`() {
        val s = SkinSettings()
        val frosted = s.withFrost(0.5f)
        assertEquals(0.5f, frosted.frost, 0.001f)
        assertEquals(30f, frosted.blur, 0.001f)
        assertEquals(0.6f, frosted.vibrancy, 0.001f)
        val clear = s.withTransparency(0.75f)
        assertEquals(0.75f, clear.transparency, 0f)
        // At the old clarity, the old toolbar opacity, near enough.
        assertEquals(0.205f, clear.spotiglassToolbarOpacity, 0.01f)
        val deep = s.withDepth(12f)
        assertEquals(12f, deep.depth, 0f)
        assertEquals(24f, deep.refraction, 0f)
        // Never past what the shader takes.
        assertEquals(SkinSettings.REFRACTION_RANGE.endInclusive, s.withDepth(28f).refraction, 0f)
        // The defaults sit on their own sliders' marks.
        assertEquals(s.blur, s.withFrost(s.frost).blur, 0.001f)
        assertEquals(s.refraction, s.withDepth(s.depth).refraction, 0.001f)
    }
}
