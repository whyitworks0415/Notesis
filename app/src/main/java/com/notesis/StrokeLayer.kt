package com.notesis

import androidx.ink.strokes.Stroke

/** Highlighter is paper annotation, so it is composited below text and opaque ink. */
internal fun Stroke.isHighlighterStroke(): Boolean =
    Tool.ofBrushFamily(brush.family) == Tool.HIGHLIGHTER
