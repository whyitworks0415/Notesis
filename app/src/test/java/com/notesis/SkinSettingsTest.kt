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
        assertEquals(32f, now.liquidGlassCorner, 0f)
        assertEquals(1f, now.liquidGlassResponse, 0f)
        assertEquals(RefractionDirection.RAISED, now.refractionDirection)
        // Each pair is what its combined slider sets, so the slider reads back where it stands.
        val again = now.withFrost(now.frost).withTransparency(now.transparency).withDepth(now.depth)
        assertEquals(now.vibrancy, again.vibrancy, 0.001f)
        assertEquals(now.liquidGlassToolbarOpacity, again.liquidGlassToolbarOpacity, 0.001f)
        assertEquals(now.refraction, again.refraction, 0.001f)
        assertTrue(!now.hasOriginalOptics())
        assertTrue(SkinSettings.ORIGINAL.hasOriginalOptics())
    }

    @Test fun `settings saved at the old defaults move to the new ones, set ones stay`() {
        // As 0.60 and before saved them: no reflection, no optics version.
        fun saved(edit: (JSONObject) -> Unit = {}) = JSONObject(SkinSettings.ORIGINAL.copy(accent = 0xFF00AA00.toInt()).toJson())
            .apply { remove("reflection"); remove("opticsVersion"); edit(this) }.toString()
        val untouched = SkinSettings.fromJson(saved())
        assertEquals(SkinSettings().liquidGlassClarity, untouched.liquidGlassClarity, 0f)
        assertEquals(SkinSettings().reflection, untouched.reflection, 0f)
        assertEquals(SkinSettings().depth, untouched.depth, 0f)
        // Colours are never part of it.
        assertEquals(0xFF00AA00.toInt(), untouched.accent)
        // Tuned by hand: kept, with the edge it was seen with.
        val tuned = SkinSettings.fromJson(saved { it.put("blur", 44.0) })
        assertEquals(44f, tuned.blur, 0f)
        assertEquals(SkinSettings.ORIGINAL.liquidGlassClarity, tuned.liquidGlassClarity, 0f)
        assertEquals(SkinSettings.LEGACY_REFLECTION, tuned.reflection, 0f)
        // Saved now, read back as it is, even at the old values.
        val round = SkinSettings.fromJson(SkinSettings.ORIGINAL.toJson())
        assertEquals(SkinSettings.ORIGINAL.liquidGlassClarity, round.liquidGlassClarity, 0f)
        assertEquals(SkinSettings.ORIGINAL.reflection, round.reflection, 0f)
    }

    @Test fun `controls keep the original glass, and the reader's colours`() {
        val tuned = SkinSettings().copy(liquidGlassCorner = 8f, liquidGlassClarity = 0.2f, accent = 0xFF123456.toInt(), highContrast = true)
        val control = tuned.controlOptics()
        assertEquals(SkinSettings.ORIGINAL.liquidGlassCorner, control.liquidGlassCorner, 0f)
        assertEquals(SkinSettings.ORIGINAL.liquidGlassClarity, control.liquidGlassClarity, 0f)
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
        assertEquals(0.205f, clear.liquidGlassToolbarOpacity, 0.01f)
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
        // liquid_glass_widgets' ios27Light lens, the rest set by eye on a device.
        assertEquals(32f, fresh.ios.thickness, 0f)
        assertEquals(1.24f, fresh.ios.refractiveIndex, 0f)
        assertEquals(0f, fresh.ios.blur, 0f)
        assertEquals(3f, fresh.ios.frost, 0f)
        assertEquals(0.58f, fresh.ios.frostOpacity, 0f)
        assertEquals(3f, fresh.ios.saturation, 0f)
        assertEquals(0.58f, fresh.ios.bodyOpacity, 0f)
        assertEquals(2f, fresh.ios.rimLight, 0f)
        assertEquals(0.85f, fresh.ios.rimShade, 0f)
        assertEquals(0.02f, fresh.ios.edgeAbsorption, 0f)
        assertEquals(29f, fresh.ios.corner, 0f)
        // The preset itself is kept as measured.
        assertEquals(14f, IosGlass.PRESET.frost, 0f)
        assertEquals(0.73f, IosGlass.PRESET.frostOpacity, 0f)
        assertEquals(0x87 / 255f, IosGlass.PRESET.bodyOpacity, 0f)
        // Its paraxial rim: a bevel of a third of the thickness in dp, bending thickness × 8 × 0.24 / 3.
        assertEquals(32f / 3f, fresh.ios.bevelDp, 0.001f)
        assertEquals(32f * 8f * 0.24f / 3f, fresh.ios.bendDp, 0.001f)
        // Mornye's own: clarity 0.75 with outlines, its red accent, its panels' corner.
        assertEquals(0.75f, fresh.mornye.clarity, 0f)
        assertEquals(true, fresh.mornye.outlines)
        assertEquals(MornyeAccent.RED, fresh.mornye.accent)
        assertEquals(32f, fresh.mornye.corner, 0f)
        // Every project's own motion is on until switched off.
        assertEquals(ProjectMotion(), fresh.mornye.motion)
        assertEquals(true, fresh.ios.motion.press && fresh.ios.motion.slide && fresh.ios.motion.open)

        val set = fresh.copy(
            liquidStyle = LiquidStyle.MORNYE,
            ios = fresh.ios.copy(thickness = 40f, motion = ProjectMotion(slide = false)),
            mornye = fresh.mornye.copy(clarity = 0.4f, outlines = false, accent = MornyeAccent.TEAL, corner = 24f),
            dynamicColor = false,
            amoled = true,
        )
        val read = SkinSettings.fromJson(set.toJson())
        assertEquals(LiquidStyle.MORNYE, read.liquidStyle)
        assertEquals(set.ios, read.ios)
        assertEquals(set.mornye, read.mornye)
        assertEquals(false, read.dynamicColor)
        assertEquals(true, read.amoled)
        assertEquals(24f, read.glassCorner, 0f)
        // Saved before there were recipes: this app's own, at the projects' numbers.
        val old = SkinSettings.fromJson(JSONObject(set.toJson()).apply {
            remove("liquidStyle"); remove("ios"); remove("mornye")
        }.toString())
        assertEquals(LiquidStyle.NOTESIS, old.liquidStyle)
        assertEquals(IosGlass(), old.ios)
        assertEquals(MornyeGlass(), old.mornye)
        // 0.76 saved the preset as its defaults: those move to the present ones, motion kept.
        val v076 = SkinSettings.fromJson(JSONObject(old.toJson()).apply {
            put("ios", IosGlass.PRESET.copy(motion = ProjectMotion(open = false)).toJson())
        }.toString())
        assertEquals(IosGlass(motion = ProjectMotion(open = false)), v076.ios)
        // ...but a preset the reader changed is theirs.
        val tuned = SkinSettings.fromJson(JSONObject(old.toJson()).apply {
            put("ios", IosGlass.PRESET.copy(frost = 20f).toJson())
        }.toString())
        assertEquals(20f, tuned.ios.frost, 0f)
        assertEquals(0.73f, tuned.ios.frostOpacity, 0f)
        // Kyant0's and QWEA0's recipes are gone: either comes back as this app's own.
        for (gone in listOf("KYANT", "QWEA")) {
            val was = SkinSettings.fromJson(JSONObject(set.toJson()).apply { put("liquidStyle", gone) }.toString())
            assertEquals(LiquidStyle.NOTESIS, was.liquidStyle)
        }
        // Out of range on disk comes back in range.
        val wild = SkinSettings.fromJson(JSONObject(set.toJson()).apply {
            put("mornye", JSONObject().put("clarity", 9.0).put("accent", "NOPE")); put("liquidStyle", "NOPE")
        }.toString())
        assertEquals(1f, wild.mornye.clarity, 0f)
        assertEquals(MornyeAccent.RED, wild.mornye.accent)
        assertEquals(LiquidStyle.NOTESIS, wild.liquidStyle)
        // Controls keep this app's own glass whatever the recipe.
        assertEquals(LiquidStyle.NOTESIS, set.controlOptics().liquidStyle)
        // This app's own glass keeps its own corner and its own motion.
        assertEquals(fresh.liquidGlassCorner, fresh.glassCorner, 0f)
        assertEquals(null, fresh.projectMotion)
    }

    @Test fun `five looks to pick from`() {
        assertEquals(5, Look.all.size)
        assertEquals(listOf("Material", "Glassmorphism", "Liquid glass", "iOS", "Mornye"), Look.all.map { it.label })
    }

    @Test fun `wallpaper colours start on unless an accent was picked before`() {
        assertEquals(true, SkinSettings().dynamicColor)
        assertEquals(true, SkinSettings.fromJson("{}").dynamicColor)
        val picked = JSONObject(SkinSettings().toJson()).apply { remove("dynamicColor"); put("accent", 0xFF00897B.toInt()) }
        assertEquals(false, SkinSettings.fromJson(picked.toString()).dynamicColor)
        val untouched = JSONObject(SkinSettings().toJson()).apply { remove("dynamicColor") }
        assertEquals(true, SkinSettings.fromJson(untouched.toString()).dynamicColor)
    }

    @Test fun `each kind of chrome keeps its own transparency and the popups their dim`() {
        val d = SkinSettings()
        assertEquals(1f, d.clarityOf(ChromeRole.TOOLBAR), 0f)
        assertEquals(1f, d.clarityOf(ChromeRole.POPUP), 0f)
        assertEquals(1f, d.clarityOf(ChromeRole.SIDEBAR), 0f)
        assertEquals(SkinSettings.DEFAULT_POPUP_DIM, d.popupDim, 0f)
        val set = d.copy(toolbarClarity = 0.8f, popupClarity = 0.3f, sidebarClarity = 0.55f, popupDim = 0.25f)
        val back = SkinSettings.fromJson(set.toJson())
        assertEquals(0.8f, back.clarityOf(ChromeRole.TOOLBAR), 0.001f)
        assertEquals(0.3f, back.clarityOf(ChromeRole.POPUP), 0.001f)
        assertEquals(0.55f, back.clarityOf(ChromeRole.SIDEBAR), 0.001f)
        assertEquals(0.25f, back.popupDim, 0.001f)
        // Saved before there were any: the glass as it was.
        val old = SkinSettings.fromJson(JSONObject(d.toJson()).apply {
            remove("toolbarClarity"); remove("popupClarity"); remove("sidebarClarity"); remove("popupDim")
        }.toString())
        assertEquals(1f, old.popupClarity, 0f)
        assertEquals(SkinSettings.DEFAULT_POPUP_DIM, old.popupDim, 0f)
    }

    @Test fun `the iOS rim's light can come from anywhere round the pane`() {
        val d = IosGlass()
        assertEquals(90f, d.lightAngle, 0f)
        assertEquals(false, d.rimAllRound)
        val back = IosGlass.fromJson(d.copy(lightAngle = 0f, rimAllRound = true).toJson())
        assertEquals(0f, back.lightAngle, 0f)
        assertTrue(back.rimAllRound)
        assertEquals(180f, IosGlass.fromJson(d.copy(lightAngle = 400f).toJson()).lightAngle, 0f)
    }

    @Test fun `each kind of chrome keeps its own colour and darkening`() {
        val set = SkinSettings().withRole(ChromeRole.POPUP, clarity = 0.4f, color = 0xFF2C2C2E.toInt(), darken = 0.2f)
            .withRole(ChromeRole.SIDEBAR, darken = 2f)
        val back = SkinSettings.fromJson(set.toJson())
        assertEquals(0.4f, back.clarityOf(ChromeRole.POPUP), 0.001f)
        assertEquals(0xFF2C2C2E.toInt(), back.colorOf(ChromeRole.POPUP))
        assertEquals(0.2f, back.darkenOf(ChromeRole.POPUP), 0.001f)
        // Held to the range: darker, never black.
        assertEquals(SkinSettings.DARKEN_RANGE.endInclusive, back.darkenOf(ChromeRole.SIDEBAR), 0.001f)
        // The toolbar untouched: the theme's colour, not darkened.
        assertEquals(0, back.colorOf(ChromeRole.TOOLBAR))
        assertEquals(0f, back.darkenOf(ChromeRole.TOOLBAR), 0f)
    }
}
