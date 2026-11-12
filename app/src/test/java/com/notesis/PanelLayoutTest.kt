package com.notesis

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelLayoutTest {
    @Test fun `a pen opens on its colour, a tool without one on its settings`() {
        assertEquals(PenPanelTab.COLOR, firstPenTab(tints = true))
        assertEquals(PenPanelTab.SETTING, firstPenTab(tints = false))
        assertEquals(listOf(PenPanelTab.COLOR, PenPanelTab.SETTING), PenPanelTab.entries.toList())
    }

    @Test fun `a panel on the end edge is square into it and round on its other side`() {
        val edge = edgePanelShape(28.dp, flushEnd = true)
        assertEquals(CornerSize(28.dp), edge.topStart)
        assertEquals(CornerSize(28.dp), edge.bottomStart)
        assertEquals(CornerSize(0.dp), edge.topEnd)
        assertEquals(CornerSize(0.dp), edge.bottomEnd)
        val floating = edgePanelShape(28.dp, flushEnd = false)
        assertEquals(CornerSize(28.dp), floating.topEnd)
        assertEquals(CornerSize(28.dp), floating.bottomEnd)
    }
}
