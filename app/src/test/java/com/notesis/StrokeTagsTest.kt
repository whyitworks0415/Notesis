package com.notesis

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeTagsTest {
    @Test
    fun packedToolFieldKeepsOrdinalAndTag() {
        val tag = StrokeTags.tag(locked = true, group = StrokeTags.MAX_GROUP)
        val packed = StrokeTags.pack(Tool.DASH_DOT.ordinal, tag)
        assertEquals(Tool.DASH_DOT.ordinal, StrokeTags.ordinalOf(packed))
        assertEquals(tag, StrokeTags.tagOf(packed))
        assertEquals(1, StrokeTags.tagOf(packed) and 1)
        assertEquals(StrokeTags.MAX_GROUP, StrokeTags.tagOf(packed) ushr 1)
    }

    @Test
    fun untaggedFieldIsTheBareOrdinalOldFilesWrote() {
        assertEquals(Tool.PENCIL.ordinal, StrokeTags.pack(Tool.PENCIL.ordinal, 0))
        assertEquals(0, StrokeTags.tagOf(Tool.PENCIL.ordinal))
    }
}
