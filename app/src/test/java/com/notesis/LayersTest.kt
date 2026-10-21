package com.notesis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class LayersTest {
    private fun ids(layers: NoteLayers) = layers.list.map { it.id }

    @Test
    fun `add puts a fresh id on top with a fresh name`() {
        val layers = NoteLayers()
        val second = layers.add()
        val third = layers.add()
        assertEquals(listOf(NoteLayers.BASE, second.id, third.id), ids(layers))
        assertEquals(3, ids(layers).toSet().size)
        assertEquals("Layer 2", second.name)
        assertEquals("Layer 3", third.name)
        assertFalse(second.locked)
        assertEquals(1f, second.opacity, 0f)
    }

    @Test
    fun `duplicate sits right above its source under a new id`() {
        val layers = NoteLayers()
        val top = layers.add()
        layers.setOpacity(NoteLayers.BASE, 0.4f)
        layers.setLocked(NoteLayers.BASE, true)
        layers.list[0].visible = false
        val copy = layers.duplicate(NoteLayers.BASE)!!
        assertEquals(listOf(NoteLayers.BASE, copy.id, top.id), ids(layers))
        assertTrue(copy.id != NoteLayers.BASE && copy.id != top.id)
        assertEquals(0.4f, copy.opacity, 0f)
        assertFalse(copy.visible)
        // Duplicated to be worked on: the copy starts unlocked, the source stays locked.
        assertFalse(copy.locked)
        assertTrue(layers.isLocked(NoteLayers.BASE))
        assertEquals("Layer 1 copy", copy.name)
    }

    @Test
    fun `move changes only the order`() {
        val layers = NoteLayers()
        val a = layers.add()
        val b = layers.add()
        assertTrue(layers.move(b.id, 0))
        assertEquals(listOf(b.id, NoteLayers.BASE, a.id), ids(layers))
        assertEquals(0, layers.rank(b.id))
        assertEquals(2, layers.rank(a.id))
        assertFalse(layers.move(b.id, 0))
        // Out of range clamps rather than throwing.
        assertTrue(layers.move(b.id, 99))
        assertEquals(listOf(NoteLayers.BASE, a.id, b.id), ids(layers))
        assertFalse(layers.move(77, 0))
    }

    @Test
    fun `remove and restore put the layer and the pen back`() {
        val layers = NoteLayers()
        val a = layers.add()
        val b = layers.add()
        layers.active = a.id
        layers.writeTo = a.id
        val removed = layers.remove(a.id)!!
        assertEquals(listOf(NoteLayers.BASE, b.id), ids(layers))
        assertEquals(NoteLayers.ALL, layers.active)
        assertNotEquals(a.id, layers.writeTo)
        // While undo can bring it back, its id is not handed out again.
        val c = layers.add()
        assertNotEquals(a.id, c.id)
        layers.remove(c.id)
        layers.restore(removed)
        assertEquals(listOf(NoteLayers.BASE, a.id, b.id), ids(layers))
        assertEquals(a.id, layers.active)
        assertEquals(a.id, layers.writeTo)
        assertSame(a, layers.layer(a.id))
    }

    @Test
    fun `the last layer cannot be removed`() {
        val layers = NoteLayers()
        assertNull(layers.remove(NoteLayers.BASE))
        assertEquals(1, layers.list.size)
    }

    @Test
    fun `delete then undo shows the same ink in the same order`() {
        // Strokes stand in as (stroke, layer) pairs; ownership is by id only.
        val layers = NoteLayers()
        val a = layers.add()
        val b = layers.add()
        val ink = listOf(1 to NoteLayers.BASE, 2 to a.id, 3 to b.id, 4 to a.id)
        val before = layers.shownInOrder(ink) { it.second }
        val removed = layers.remove(a.id)!!
        val kept = ink.filter { it.second != a.id }
        assertEquals(listOf(1, 3), layers.shownInOrder(kept) { it.second }.map { it.first })
        layers.restore(removed)
        assertEquals(before, layers.shownInOrder(kept + ink.filter { it.second == a.id }) { it.second })
    }

    @Test
    fun `lock blocks editing but not targeting`() {
        val layers = NoteLayers()
        val a = layers.add()
        layers.active = NoteLayers.ALL
        layers.writeTo = a.id
        assertTrue(layers.editable(a.id))
        assertTrue(layers.setLocked(a.id, true))
        assertFalse(layers.setLocked(a.id, true))
        assertFalse(layers.editable(a.id))
        assertTrue(layers.editable(NoteLayers.BASE))
        assertEquals(a.id, layers.target)
        layers.active = a.id
        assertFalse(layers.editable(a.id))
        layers.setLocked(a.id, false)
        assertTrue(layers.editable(a.id))
    }

    @Test
    fun `lock and opacity survive a save and a load`() {
        val layers = NoteLayers()
        val a = layers.add()
        layers.setLocked(a.id, true)
        layers.setOpacity(a.id, 0.35f)
        layers.setOpacity(NoteLayers.BASE, 0f)
        a.name = "Answers"
        val read = NoteLayers.fromJson(JSONObject(layers.toJson().toString()))
        assertEquals(ids(layers), ids(read))
        assertTrue(read.layer(a.id)!!.locked)
        assertEquals(0.35f, read.layer(a.id)!!.opacity, 0.0005f)
        assertEquals(0f, read.layer(NoteLayers.BASE)!!.opacity, 0f)
        assertFalse(read.layer(NoteLayers.BASE)!!.locked)
        assertEquals("Answers", read.layer(a.id)!!.name)
        assertTrue(read.isLocked(a.id))
        assertEquals(89, read.look().alphaOf(a.id))
    }

    @Test
    fun `old meta without lock or opacity reads as unlocked and opaque`() {
        val old = JSONObject(
            """{"active":-1,"writeTo":1,"list":[{"id":0,"name":"레이어 1","visible":true},{"id":1,"name":"레이어 2","visible":false}]}""",
        )
        val read = NoteLayers.fromJson(old)
        assertEquals(listOf(0, 1), ids(read))
        assertEquals("레이어 1", read.list[0].name)
        read.list.forEach {
            assertFalse(it.locked)
            assertEquals(1f, it.opacity, 0f)
        }
        assertFalse(read.list[1].visible)
        assertNull(read.translucentLook())
        assertEquals(1, read.target)
    }

    @Test
    fun `bad opacity is clamped`() {
        val json = JSONObject("""{"list":[{"id":0,"name":"x","opacity":3.5},{"id":1,"name":"y","opacity":-1}]}""")
        val read = NoteLayers.fromJson(json)
        assertEquals(1f, read.list[0].opacity, 0f)
        assertEquals(0f, read.list[1].opacity, 0f)
    }

    @Test
    fun `copies carry lock and opacity`() {
        val layers = NoteLayers()
        val a = layers.add()
        layers.setLocked(a.id, true)
        layers.setOpacity(a.id, 0.5f)
        val copy = NoteLayers().apply { copyFrom(layers) }
        assertTrue(copy.isLocked(a.id))
        assertEquals(0.5f, copy.opacityOf(a.id), 0f)
        // The copy is a snapshot: changing the original leaves it alone.
        layers.setOpacity(a.id, 1f)
        assertEquals(0.5f, copy.opacityOf(a.id), 0f)
    }

    @Test
    fun `the look is one object until lock or opacity changes`() {
        val layers = NoteLayers()
        val a = layers.add()
        val first = layers.look()
        assertSame(first, layers.look())
        assertFalse(first.translucent)
        layers.setOpacity(a.id, 0.5f)
        val translucent = layers.look()
        assertTrue(translucent !== first)
        assertTrue(translucent.translucent)
        assertEquals(128, translucent.alphaOf(a.id))
        assertEquals(255, translucent.alphaOf(NoteLayers.BASE))
        // Ids no layer has draw at full strength.
        assertEquals(255, translucent.alphaOf(200))
        // A lock alone hands out a new look, but one that draws the same.
        layers.setLocked(a.id, true)
        val locked = layers.look()
        assertTrue(locked !== translucent)
        assertTrue(sameLook(translucent, locked))
        layers.setOpacity(a.id, 0.6f)
        assertFalse(sameLook(locked, layers.look()))
        assertTrue(sameLook(null, null))
        assertFalse(sameLook(null, locked))
    }

    @Test
    fun `runs keep opaque layers together and translucent ones apart`() {
        val alpha = IntArray(NoteLayers.MAX_ID + 1) { 255 }
        alpha[2] = 128
        alpha[3] = 0
        // (item, layer)
        val items = listOf(1 to 0, 2 to 1, 3 to 2, 4 to 2, 5 to 3, 6 to 0, 7 to 2)
        val runs = layerRuns(items, { it.second }, alpha).map { (a, run) -> a to run.map { it.first } }
        assertEquals(
            listOf(255 to listOf(1, 2), 128 to listOf(3, 4), 255 to listOf(6), 128 to listOf(7)),
            runs,
        )
    }

    @Test
    fun `shown in order drops hidden layers and keeps each layer's own order`() {
        val layers = NoteLayers()
        val a = layers.add()
        val b = layers.add()
        layers.move(b.id, 0)
        layers.layer(a.id)!!.visible = false
        val ink = listOf(1 to NoteLayers.BASE, 2 to a.id, 3 to b.id, 4 to NoteLayers.BASE, 5 to b.id)
        assertEquals(listOf(3, 5, 1, 4), layers.shownInOrder(ink) { it.second }.map { it.first })
    }

    private fun tone(m: FloatArray, r: Int, g: Int, b: Int): IntArray = IntArray(3) { row ->
        (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]).roundToInt().coerceIn(0, 255)
    }

    private fun over(src: IntArray, alpha: Float, dst: IntArray): FloatArray =
        FloatArray(3) { src[it] * alpha + dst[it] * (1f - alpha) }

    @Test
    fun `wet ink colour is the stroke colour when nothing applies`() {
        val argb = 0x80123456.toInt()
        assertEquals(argb, wetInkArgb(argb, 1f, null))
        assertEquals(0x40123456, wetInkArgb(argb, 0.5f, null))
        assertEquals(0x00123456, wetInkArgb(argb, 0f, null))
    }

    @Test
    fun `wet ink over the toned page matches the toned committed page`() {
        val paper = intArrayOf(255, 255, 255)
        for (toneId in listOf(PAGE_TONE_NIGHT, PAGE_TONE_SEPIA)) {
            val m = pageToneMatrix(toneId)!!
            for (color in listOf(0xFF000000.toInt(), 0xFFE53935.toInt(), 0x66F9A825, 0xFF1E88E5.toInt())) {
                for (opacity in listOf(1f, 0.5f, 0.2f)) {
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    val a = (color ushr 24) / 255f * opacity
                    // Committed: the layer composited onto the paper, then the page toned.
                    val blended = over(intArrayOf(r, g, b), a, paper)
                    val dry = tone(m, blended[0].roundToInt(), blended[1].roundToInt(), blended[2].roundToInt())
                    // Wet: the toned colour at the layer's opacity over the toned paper.
                    val wet = wetInkArgb(color, opacity, m)
                    val wetAlpha = (wet ushr 24) / 255f
                    val shown = over(intArrayOf((wet shr 16) and 0xFF, (wet shr 8) and 0xFF, wet and 0xFF),
                        wetAlpha, tone(m, 255, 255, 255))
                    // Exact but for rounding - unless the toned colour itself leaves
                    // 0..255 (a saturated red at night), where a stored colour has to
                    // clamp and only the blended page does not.
                    val clamps = (0..2).any { row ->
                        val v = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]
                        v < 0f || v > 255f
                    }
                    if (clamps) continue
                    val slack = 2.5f
                    for (c in 0..2) assertTrue(
                        "tone $toneId colour ${Integer.toHexString(color)} at $opacity: ${dry[c]} vs ${shown[c]}",
                        abs(dry[c] - shown[c]) <= slack,
                    )
                }
            }
        }
    }

    @Test
    fun `night tone lightens black wet ink and keeps its alpha`() {
        val wet = wetInkArgb(0xFF000000.toInt(), 1f, pageToneMatrix(PAGE_TONE_NIGHT))
        assertEquals(255, wet ushr 24)
        assertTrue(((wet shr 16) and 0xFF) > 200)
        assertNotNull(pageToneMatrix(PAGE_TONE_SEPIA))
    }

    @Test
    fun `no id is free once every one is used or retired`() {
        val layers = NoteLayers()
        repeat(NoteLayers.MAX_LAYERS - 1) { layers.addOrNull() }
        assertEquals(NoteLayers.MAX_LAYERS, layers.list.size)
        assertFalse(layers.canAdd())
        assertNull(layers.addOrNull())
        assertNull(layers.duplicate(NoteLayers.BASE))
    }
}
