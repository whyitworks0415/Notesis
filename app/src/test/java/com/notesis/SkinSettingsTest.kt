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
        assertEquals(14f, now.depth, 0f)
        assertTrue(now.shadows)
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

    @Test fun `version 2's lens moves to the deeper one, a lens set by hand stays`() {
        fun saved(depth: Double, refraction: Double) = JSONObject(SkinSettings().toJson())
            .put("opticsVersion", 2).put("depth", depth).put("refraction", refraction).toString()
        val untouched = SkinSettings.fromJson(saved(10.0, 20.0))
        assertEquals(SkinSettings().depth, untouched.depth, 0f)
        assertEquals(SkinSettings().refraction, untouched.refraction, 0f)
        val tuned = SkinSettings.fromJson(saved(6.0, 12.0))
        assertEquals(6f, tuned.depth, 0f)
        assertEquals(12f, tuned.refraction, 0f)
    }

    @Test fun `shadows can be turned off and stay off`() {
        val off = SkinSettings(shadows = false)
        assertEquals(false, SkinSettings.fromJson(off.toJson()).shadows)
        assertEquals(false, off.controlOptics().shadows)
        // Saved before the switch existed: shadows on.
        assertEquals(true, SkinSettings.fromJson(JSONObject(off.toJson()).apply { remove("shadows") }.toString()).shadows)
    }

    @Test fun `the other recipes start from their projects' numbers and keep what was set`() {
        val fresh = SkinSettings()
        assertEquals(LiquidStyle.NOTESIS, fresh.liquidStyle)
        // Kyant0's catalog: LiquidBottomTabs.
        assertEquals(8f, fresh.kyant.blur, 0f)
        assertEquals(24f, fresh.kyant.refractionHeight, 0f)
        assertEquals(24f, fresh.kyant.refractionAmount, 0f)
        assertEquals(1.5f, fresh.kyant.saturation, 0f)
        assertEquals(0.4f, fresh.kyant.surface, 0f)
        // QWEA0's README and LiquidGlassView.
        assertEquals(QweaMaterial.REGULAR, fresh.qwea.material)
        assertEquals(0.0625f, fresh.qwea.blurAmount, 0f)
        assertEquals(1.4f, fresh.qwea.saturation, 0f)
        assertEquals(24f, fresh.qwea.bevelWidth, 0f)
        assertEquals(12f, fresh.qwea.refractionHeight, 0f)
        assertEquals(0.12f, fresh.qwea.dispersion, 0f)

        val set = fresh.copy(
            liquidStyle = LiquidStyle.QWEA,
            kyant = fresh.kyant.copy(blur = 12f, chromaticAberration = true),
            qwea = fresh.qwea.copy(material = QweaMaterial.CLEAR, bevelWidth = 40f, shadow = true),
        )
        val read = SkinSettings.fromJson(set.toJson())
        assertEquals(LiquidStyle.QWEA, read.liquidStyle)
        assertEquals(set.kyant, read.kyant)
        assertEquals(set.qwea, read.qwea)
        // Saved before there were recipes: this app's own, at the projects' numbers.
        val old = SkinSettings.fromJson(JSONObject(set.toJson()).apply {
            remove("liquidStyle"); remove("kyant"); remove("qwea")
        }.toString())
        assertEquals(LiquidStyle.NOTESIS, old.liquidStyle)
        assertEquals(KyantGlass(), old.kyant)
        assertEquals(QweaGlass(), old.qwea)
        // Out of range on disk comes back in range.
        val wild = SkinSettings.fromJson(JSONObject(set.toJson()).apply {
            put("kyant", JSONObject().put("blur", 900.0)); put("liquidStyle", "NOPE")
        }.toString())
        assertEquals(KyantGlass.BLUR_RANGE.endInclusive, wild.kyant.blur, 0f)
        assertEquals(LiquidStyle.NOTESIS, wild.liquidStyle)
        // Controls keep this app's own glass whatever the recipe.
        assertEquals(LiquidStyle.NOTESIS, set.controlOptics().liquidStyle)
    }

    @Test fun `five looks to pick from`() {
        assertEquals(5, Look.all.size)
        assertEquals(listOf("Material", "Glassmorphism", "Liquid glass", "Liquid glass · Kyant0", "Liquid glass · QWEA0"),
            Look.all.map { it.label })
    }
}
