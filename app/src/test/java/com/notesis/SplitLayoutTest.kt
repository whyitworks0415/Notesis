package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLayoutTest {
    private val area = SplitRect(0f, 0f, 1000f, 800f)

    @Test fun `side by side halves the width, top and bottom the height`() {
        val side = SplitPreset.SIDE_BY_SIDE.tree().layout(area, gap = 0f)
        assertEquals(SplitRect(0f, 0f, 500f, 800f), side[0])
        assertEquals(SplitRect(500f, 0f, 1000f, 800f), side[1])
        val stacked = SplitPreset.STACKED.tree().layout(area, gap = 0f)
        assertEquals(SplitRect(0f, 0f, 1000f, 400f), stacked[0])
        assertEquals(SplitRect(0f, 400f, 1000f, 800f), stacked[1])
    }

    @Test fun `the gap is left between neighbours, not at the edges`() {
        val three = SplitPreset.THREE_SIDE_BY_SIDE.tree().layout(area, gap = 10f)
        assertEquals(0f, three.getValue(0).left, 0f)
        assertEquals(1000f, three.getValue(2).right, 0.001f)
        assertEquals(10f, three.getValue(1).left - three.getValue(0).right, 0.001f)
        // Three equal shares of what is left after two gaps.
        assertEquals((1000f - 20f) / 3f, three.getValue(1).width, 0.001f)
    }

    @Test fun `a nested split divides one side only`() {
        val rects = SplitPreset.RIGHT_SPLIT.tree().layout(area, gap = 0f)
        assertEquals(SplitRect(0f, 0f, 500f, 800f), rects[0])
        assertEquals(SplitRect(500f, 0f, 1000f, 400f), rects[1])
        assertEquals(SplitRect(500f, 400f, 1000f, 800f), rects[2])
        val four = SplitPreset.FOUR.tree().layout(area, gap = 0f)
        assertEquals(setOf(0, 1, 2, 3), four.keys)
        assertEquals(SplitRect(500f, 400f, 1000f, 800f), four[3])
        // Every preset keeps the opened note as pane 0, in the top left.
        for (preset in SplitPreset.entries) {
            val r = preset.tree().layout(area, 0f).getValue(0)
            assertEquals(0f, r.left, 0f)
            assertEquals(0f, r.top, 0f)
        }
    }

    @Test fun `seams sit between the panes, one per neighbouring pair`() {
        val seams = SplitPreset.RIGHT_SPLIT.tree().seams(area, gap = 10f)
        assertEquals(2, seams.size)
        val outer = seams.first { it.path.isEmpty() }
        assertTrue(outer.sideBySide)
        assertEquals(10f, outer.rect.width, 0.001f)
        val inner = seams.first { it.path == listOf(1) }
        assertTrue(!inner.sideBySide)
        assertEquals(10f, inner.rect.height, 0.001f)
    }

    @Test fun `a seam moves, and stops short of squeezing a pane away`() {
        val tree = SplitPreset.SIDE_BY_SIDE.tree()
        val moved = tree.moveSeam(emptyList(), 0, 0.2f).layout(area, 0f)
        assertEquals(700f, moved.getValue(0).width, 0.01f)
        val pushed = tree.moveSeam(emptyList(), 0, 0.9f).layout(area, 0f)
        assertEquals(1000f * (1f - SPLIT_MIN_SHARE), pushed.getValue(0).width, 0.01f)
        // A seam inside a nested branch moves only that branch.
        val nested = SplitPreset.RIGHT_SPLIT.tree().moveSeam(listOf(1), 0, -0.25f).layout(area, 0f)
        assertEquals(500f, nested.getValue(0).width, 0.01f)
        assertEquals(200f, nested.getValue(1).height, 0.01f)
    }

    @Test fun `splitting a pane the same way as its branch adds a sibling`() {
        val tree = SplitPreset.SIDE_BY_SIDE.tree().splitPane(1, sideBySide = true, newId = 2)
        val rects = tree.layout(area, 0f)
        assertEquals(500f, rects.getValue(0).width, 0.01f)
        assertEquals(250f, rects.getValue(1).width, 0.01f)
        assertEquals(250f, rects.getValue(2).width, 0.01f)
        // The other way: a branch inside.
        val across = SplitPreset.SIDE_BY_SIDE.tree().splitPane(1, sideBySide = false, newId = 2)
        assertEquals(SplitPreset.RIGHT_SPLIT.tree(), across)
    }

    @Test fun `no more than four panes`() {
        val four = SplitPreset.FOUR.tree()
        assertEquals(four, four.splitPane(3, true, 4))
        assertEquals(4, four.paneIds().size)
    }

    @Test fun `closing a pane gives its room to its neighbours, and the last one ends the split`() {
        val three = SplitPreset.RIGHT_SPLIT.tree()
        assertEquals(SplitPreset.SIDE_BY_SIDE.tree(), three.closePane(2))
        assertNull(SplitPreset.SIDE_BY_SIDE.tree().closePane(1))
        // The opened note stays.
        assertEquals(three, three.closePane(0))
        assertEquals(listOf(0, 1), SplitPreset.THREE_SIDE_BY_SIDE.tree().closePane(2)?.paneIds())
    }

    @Test fun `new panes take the lowest free number`() {
        assertEquals(2, SplitPreset.SIDE_BY_SIDE.tree().nextPaneId())
        val gap = SplitPreset.THREE_SIDE_BY_SIDE.tree().closePane(1)!!
        assertEquals(1, gap.nextPaneId())
    }

    @Test fun `a moved seam keeps the preset's shape`() {
        val moved = SplitPreset.RIGHT_SPLIT.tree().moveSeam(emptyList(), 0, 0.1f)
        assertTrue(moved != SplitPreset.RIGHT_SPLIT.tree())
        assertEquals(SplitPreset.RIGHT_SPLIT.tree(), moved.shape())
        assertEquals(1000f, SplitPreset.SIDE_BY_SIDE.tree().seams(area, 8f).single().length, 0f)
    }
}
