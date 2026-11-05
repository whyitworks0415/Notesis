package com.notesis

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A ground of the user's own: its colours in order, mixed by strength, kept and read back. */
class HomeLookTest {
    private val base = Color(0xFFF7F6F3)

    @Test
    fun `colours are laid out in order along the ground`() {
        val ground = CustomGround(listOf(GroundStop(0xFFFF0000.toInt(), 0.8f, 1f), GroundStop(0xFF0000FF.toInt(), 0.2f, 1f)))
        val stops = ground.colorStops(base)
        assertEquals(listOf(0.2f, 0.8f), stops.map { it.first })
        assertEquals(Color(0xFF0000FF).toArgb(), stops.first().second.toArgb())
    }

    @Test
    fun `no strength is the plain ground, full strength the colour itself`() {
        val none = CustomGround(listOf(GroundStop(0xFF000000.toInt(), 0f, 0f))).colorStops(base).single().second
        assertEquals(base.toArgb(), none.toArgb())
        val full = CustomGround(listOf(GroundStop(0xFF336699.toInt(), 0f, 1f))).colorStops(base).single().second
        assertEquals(0xFF336699.toInt(), full.toArgb())
    }

    @Test
    fun `kept and read back the same, at most four colours`() {
        val ground = CustomGround(listOf(GroundStop(0xFF112233.toInt(), 0.25f, 0.5f), GroundStop(0xFF445566.toInt(), 0.75f, 1f)),
            GroundDirection.DIAGONAL)
        assertEquals(ground, CustomGround.fromJson(ground.toJson()))
        val five = CustomGround(List(5) { GroundStop(0xFF000000.toInt(), it / 4f, 1f) })
        assertEquals(CustomGround.MAX_STOPS, CustomGround.fromJson(five.toJson())!!.stops.size)
    }

    @Test
    fun `words are dark on a light ground and light on a dark one`() {
        val light = lookOn(listOf(0f to Color(0xFFF2F2F2), 1f to Color(0xFFE8E8E8)))
        assertTrue(light.ink.luminance() < 0.2f)
        val dark = lookOn(listOf(0f to Color(0xFF151515)))
        assertTrue(dark.ink.luminance() > 0.6f)
    }
}
