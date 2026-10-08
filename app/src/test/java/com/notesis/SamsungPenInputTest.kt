package com.notesis

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class SamsungPenInputTest {
    @Test fun `side button actions map to the standard touch actions`() {
        assertEquals(MotionEvent.ACTION_DOWN, samsungSidePenAction(211))
        assertEquals(MotionEvent.ACTION_UP, samsungSidePenAction(212))
        assertEquals(MotionEvent.ACTION_MOVE, samsungSidePenAction(213))
        assertEquals(MotionEvent.ACTION_CANCEL, samsungSidePenAction(214))
    }

    @Test fun `standard actions are left alone`() {
        for (action in listOf(
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_HOVER_MOVE,
        )) assertNull(samsungSidePenAction(action))
    }

    @Test fun `every Galaxy Tab S6 Lite model uses the compatible wet ink`() {
        for (model in listOf("SM-P610", "SM-P615", "SM-P613", "SM-P619", "SM-P620", "SM-P625", "SM-P615N")) {
            assertTrue(model, frontBufferInkUnreliable(model))
        }
    }

    @Test fun `other tablets keep the front buffer`() {
        for (model in listOf("SM-X710", "SM-T870", "SM-P600", "SM-P580", "Pixel Tablet", "")) {
            assertFalse(model, frontBufferInkUnreliable(model))
        }
    }
}
