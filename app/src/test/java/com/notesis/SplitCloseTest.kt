package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Closing a part of a split screen by dragging its seam, and what is left. */
class SplitCloseTest {
    private val two = SplitPreset.SIDE_BY_SIDE.tree()

    @Test
    fun `a seam dragged near an edge closes the side it covered`() {
        assertEquals(0, two.seamCloses(emptyList(), 0, -0.46f))
        assertEquals(1, two.seamCloses(emptyList(), 0, 0.46f))
        assertNull(two.seamCloses(emptyList(), 0, 0.2f))
    }

    @Test
    fun `closing the other side leaves the note alone`() {
        assertEquals(SplitNode.Pane(0), two.removePanes(setOf(1)))
    }

    @Test
    fun `closing the note opened first hands the screen to the one beside it`() {
        val promoted = two.promoteAfterClosing(setOf(0))
        assertEquals(1, promoted?.newMain)
        assertNull(promoted?.tree)
        val three = SplitPreset.THREE_SIDE_BY_SIDE.tree()
        val left = three.promoteAfterClosing(setOf(0))
        assertEquals(1, left?.newMain)
        assertEquals(listOf(0, 2), left?.tree?.paneIds())
    }

    @Test
    fun `closing a side that holds two parts closes both`() {
        val right = SplitPreset.RIGHT_SPLIT.tree()
        val side = right.seamChild(emptyList(), 0, 1)!!
        assertEquals(SplitNode.Pane(0), right.removePanes(side.paneIds().toSet()))
    }

    @Test
    fun `swapping trades the two sides and their shares`() {
        val uneven = SplitNode.Branch(true, listOf(SplitNode.Pane(0), SplitNode.Pane(1)), listOf(3f, 1f))
        val swapped = uneven.swapAt(emptyList(), 0) as SplitNode.Branch
        assertEquals(listOf(1, 0), swapped.paneIds())
        assertEquals(listOf(1f, 3f), swapped.weights)
    }
}
