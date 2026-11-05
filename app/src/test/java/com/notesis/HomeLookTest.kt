package com.notesis

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A ground of the user's own: colours put anywhere on the screen, kept and read back. */
class HomeLookTest {
    private val base = Color(0xFFF7F6F3)

    @Test
    fun `a colour shows at its strength where it is put and fades out at its reach`() {
        val red = GroundPoint(0xFFFF0000.toInt(), 0.2f, 0.3f, strength = 1f, spread = 0.4f)
        assertEquals(0xFFFF0000.toInt(), sampleGround(listOf(red), base, 0.2f, 0.3f).toArgb())
        assertEquals(base.toArgb(), sampleGround(listOf(red), base, 0.9f, 0.9f).toArgb())
        val half = sampleGround(listOf(red.copy(strength = 0.5f)), base, 0.2f, 0.3f)
        assertTrue(half.red > base.red - 0.01f && half.green < base.green)
    }

    @Test
    fun `kept and read back the same, at most four colours`() {
        val ground = CustomGround(listOf(
            GroundPoint(0xFF112233.toInt(), 0.25f, 0.75f, 0.5f, 0.6f),
            GroundPoint(0xFF445566.toInt(), 0.9f, 0.1f, 1f, 1.2f),
        ), base = 0xFF202020.toInt())
        assertEquals(ground, CustomGround.fromJson(ground.toJson()))
        val five = CustomGround(List(5) { GroundPoint(0xFF000000.toInt(), it / 4f, 0.5f, 1f) })
        assertEquals(CustomGround.MAX_POINTS, CustomGround.fromJson(five.toJson())!!.points.size)
    }

    @Test
    fun `a ground kept as a line by 0_90_3 becomes points along it`() {
        val old = JSONObject().put("direction", GroundDirection.ACROSS).put("stops", JSONArray(listOf(
            JSONObject().put("color", 0xFF0000FF.toInt()).put("position", 0.25).put("strength", 0.8),
        )))
        val point = CustomGround.fromJson(old)!!.points.single()
        assertEquals(0.25f, point.x, 0.0001f)
        assertEquals(0.5f, point.y, 0.0001f)
        assertEquals(0.8f, point.strength, 0.0001f)
    }

    @Test
    fun `words are dark on a light ground and light on a dark one`() {
        val light = lookOn(listOf(0f to Color(0xFFF2F2F2), 1f to Color(0xFFE8E8E8)))
        assertTrue(light.ink.luminance() < 0.2f)
        val dark = CustomGround(listOf(GroundPoint(0xFF000000.toInt(), 0.5f, 0.5f, 1f, 1.5f)), base = 0xFF101010.toInt())
            .look(dark = false)
        assertTrue(dark.ink.luminance() > 0.6f)
    }
}
