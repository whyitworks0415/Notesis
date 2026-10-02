package com.notesis

import org.junit.Assert.*
import org.junit.Test

class SharedDocumentTest {
    @Test fun `edits redraw the other viewer and detached viewers receive no more callbacks`() {
        val document = Document(mutableListOf(Page()))
        var mainRedraws = 0
        var popupRedraws = 0
        val main: () -> Unit = { mainRedraws++ }
        val popup: () -> Unit = { popupRedraws++ }
        document.attachCanvas(main)
        document.attachCanvas(popup)
        document.notifyOtherCanvases(main)
        assertEquals(0, mainRedraws)
        assertEquals(1, popupRedraws)
        document.notifyOtherCanvases(popup)
        assertEquals(1, mainRedraws)
        document.detachCanvas(popup)
        document.notifyOtherCanvases(main)
        assertEquals(1, popupRedraws)
    }

    @Test fun `canvas ownership is runtime only and returns to a single viewer on popup close`() {
        val document = Document(mutableListOf(Page()))
        val session = document.saveSessionId
        document.attachCanvas()
        document.attachCanvas()
        assertEquals(2, document.liveCanvasCount)
        assertEquals(session, document.saveSessionId)
        assertEquals(0L, document.editRevision)
        document.detachCanvas()
        assertEquals(1, document.liveCanvasCount)
        document.detachCanvas()
        document.detachCanvas()
        assertEquals(0, document.liveCanvasCount)
    }
}
