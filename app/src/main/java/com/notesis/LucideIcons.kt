// Animated icons ported from AnimateIcons (MIT, Avijit Dey); shapes from Lucide (ISC).
package com.notesis

import androidx.compose.ui.geometry.Offset

internal object Lucide {
    val MousePointerClick = AnimIcon(
        "mouse-pointer-click",
        listOf(
            IconPart(
                paths = listOf("M14 4.1 12 6"),
                scale = track(1f, 0.6f, 1.2f, 1f, duration = 0.6f, ease = IconEase.OUT),
                opacity = track(1f, 0.5f, 1f, 0f, 1f, duration = 0.6f, ease = IconEase.OUT),
            ),
            IconPart(
                paths = listOf("m5.1 8-2.9-.8"),
                scale = track(1f, 0.6f, 1.2f, 1f, duration = 0.6f, ease = IconEase.OUT),
                opacity = track(1f, 0.5f, 1f, 0f, 1f, duration = 0.6f, ease = IconEase.OUT),
            ),
            IconPart(
                paths = listOf("m6 12-1.9 2"),
                scale = track(1f, 0.6f, 1.2f, 1f, duration = 0.6f, ease = IconEase.OUT),
                opacity = track(1f, 0.5f, 1f, 0f, 1f, duration = 0.6f, ease = IconEase.OUT),
            ),
            IconPart(
                paths = listOf("M7.2 2.2 8 5.1"),
                scale = track(1f, 0.6f, 1.2f, 1f, duration = 0.6f, ease = IconEase.OUT),
                opacity = track(1f, 0.5f, 1f, 0f, 1f, duration = 0.6f, ease = IconEase.OUT),
            ),
            IconPart(
                paths = listOf("M9.037 9.69a.498.498 0 0 1 .653-.653l11 4.5a.5.5 0 0 1-.074.949l-4.349 1.041a1 1 0 0 0-.74.739l-1.04 4.35a.5.5 0 0 1-.95.074z"),
                scale = track(1f, 0.95f, 1f, duration = 0.5f),
                y = track(0f, 1f, 0f, duration = 0.5f),
            ),
        ),
    )

    val Pencil = AnimIcon(
        "pencil",
        listOf(
            IconPart(
                paths = listOf("M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4"),
                pivot = Offset(3f, 21f),
                rotate = track(0f, -4.5f, 4f, -2f, 0f, duration = 0.7f),
            ),
        ),
    )

    val PenTool = AnimIcon(
        "pen-tool",
        listOf(
            IconPart(
                children = listOf(
                    IconPart(paths = listOf("M15.707 21.293a1 1 0 0 1-1.414 0l-1.586-1.586a1 1 0 0 1 0-1.414l5.586-5.586a1 1 0 0 1 1.414 0l1.586 1.586a1 1 0 0 1 0 1.414z", "m18 13-1.375-6.874a1 1 0 0 0-.746-.776L3.235 2.028a1 1 0 0 0-1.207 1.207L5.35 15.879a1 1 0 0 0 .776.746L13 18", "m2.3 2.3 7.286 7.286")),
                    IconPart(
                        paths = listOf(circle(11f, 11f, 2f)),
                        pivot = Offset(11f, 11f),
                        scale = track(1f, 1.5f, 0.9f, 1f, duration = 0.45f, delay = 0.2f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
                    ),
                ),
                pivot = Offset(11f, 11f),
                rotate = track(0f, -7f, 6f, -3f, 0f, duration = 0.7f),
            ),
        ),
    )

    val Highlighter = AnimIcon(
        "highlighter",
        listOf(
            IconPart(
                paths = listOf("m9 11-6 6v3h9l3-3", "m22 12-4.6 4.6a2 2 0 0 1-2.8 0l-5.2-5.2a2 2 0 0 1 0-2.8L14 4"),
                pivot = Offset(5f, 19f),
                rotate = track(0f, 10f, -6f, 4f, 0f, duration = 0.8f),
            ),
        ),
    )

    val EyeOff = AnimIcon(
        "eye-off",
        listOf(
            IconPart(
                paths = listOf("M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49", "M14.084 14.158a3 3 0 0 1-4.242-4.242", "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143"),
                opacity = track(1f, 0.45f, 1f, duration = 0.7f),
            ),
            IconPart(
                paths = listOf("m2 2 20 20"),
                trim = track(1f, 0f, 1f, duration = 0.45f, delay = 0.1f, times = floatArrayOf(0f, 0.001f, 1f)),
            ),
        ),
    )

    val Eraser = AnimIcon(
        "eraser",
        listOf(
            IconPart(
                paths = listOf("M21 21H8a2 2 0 0 1-1.42-.587l-3.994-3.999a2 2 0 0 1 0-2.828l10-10a2 2 0 0 1 2.829 0l5.999 6a2 2 0 0 1 0 2.828L12.834 21", "m5.082 11.09 8.828 8.828"),
                pivot = Offset(12f, 21f),
                rotate = track(0f, -4f, 4f, -4f, 0f, duration = 0.7f),
                x = track(0f, -1.5f, 1.5f, -1.5f, 0f, duration = 0.7f),
            ),
        ),
    )

    val Shapes = AnimIcon(
        "shapes",
        listOf(
            IconPart(
                paths = listOf("M8.3 10a.7.7 0 0 1-.626-1.079L11.4 3a.7.7 0 0 1 1.198-.043L16.3 8.9a.7.7 0 0 1-.572 1.1Z"),
                pivot = Offset(12f, 6.5f),
                scale = track(1f, 0.5f, 1.12f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.3f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf(rect(3f, 14f, 7f, 7f, 1f)),
                pivot = Offset(6.5f, 17.5f),
                scale = track(1f, 0.5f, 1.12f, 1f, duration = 0.6f, delay = 0.1f, times = floatArrayOf(0f, 0.3f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf(circle(17.5f, 17.5f, 3.5f)),
                pivot = Offset(17.5f, 17.5f),
                scale = track(1f, 0.5f, 1.12f, 1f, duration = 0.6f, delay = 0.2f, times = floatArrayOf(0f, 0.3f, 0.7f, 1f)),
            ),
        ),
    )

    val Type = AnimIcon(
        "type",
        listOf(
            IconPart(paths = listOf("M12 4v16", "M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2")),
            IconPart(
                paths = listOf("M9 20h6"),
                pivot = Offset(12f, 20f),
                scaleX = track(1f, 1.3f, 0.95f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val CircleDashed = AnimIcon(
        "circle-dashed",
        listOf(
            IconPart(
                paths = listOf("M10.1 2.182a10 10 0 0 1 3.8 0", "M13.9 21.818a10 10 0 0 1-3.8 0", "M17.609 3.721a10 10 0 0 1 2.69 2.7", "M2.182 13.9a10 10 0 0 1 0-3.8", "M20.279 17.609a10 10 0 0 1-2.7 2.69", "M21.818 10.1a10 10 0 0 1 0 3.8", "M3.721 6.391a10 10 0 0 1 2.7-2.69", "M6.391 20.279a10 10 0 0 1-2.69-2.7"),
                pivot = Offset(12f, 12f),
                rotate = track(0f, 90f, 0f, duration = 0.7f, times = floatArrayOf(0f, 0.999f, 1f)),
                scale = track(1f, 0.9f, 1f, duration = 0.7f),
            ),
        ),
    )

    val SquareDashed = AnimIcon(
        "square-dashed",
        listOf(
            IconPart(
                paths = listOf("M5 3a2 2 0 0 0-2 2"),
                x = track(0f, -1.5f, 0f, duration = 0.6f),
                y = track(0f, -1.5f, 0f, duration = 0.6f),
            ),
            IconPart(
                paths = listOf("M19 3a2 2 0 0 1 2 2"),
                x = track(0f, 1.5f, 0f, duration = 0.6f),
                y = track(0f, -1.5f, 0f, duration = 0.6f),
            ),
            IconPart(
                paths = listOf("M21 19a2 2 0 0 1-2 2"),
                x = track(0f, 1.5f, 0f, duration = 0.6f),
                y = track(0f, 1.5f, 0f, duration = 0.6f),
            ),
            IconPart(
                paths = listOf("M5 21a2 2 0 0 1-2-2"),
                x = track(0f, -1.5f, 0f, duration = 0.6f),
                y = track(0f, 1.5f, 0f, duration = 0.6f),
            ),
            IconPart(
                paths = listOf("M9 3h1"),
                opacity = track(1f, 0.2f, 1f, duration = 0.4f),
            ),
            IconPart(
                paths = listOf("M9 21h1"),
                opacity = track(1f, 0.2f, 1f, duration = 0.4f, delay = 0.25f),
            ),
            IconPart(
                paths = listOf("M14 3h1"),
                opacity = track(1f, 0.2f, 1f, duration = 0.4f, delay = 0.05f),
            ),
            IconPart(
                paths = listOf("M14 21h1"),
                opacity = track(1f, 0.2f, 1f, duration = 0.4f, delay = 0.2f),
            ),
            IconPart(
                paths = listOf("M3 9v1"),
                opacity = track(1f, 0.2f, 1f, duration = 0.4f, delay = 0.35f),
            ),
            IconPart(
                paths = listOf("M21 9v1"),
                opacity = track(1f, 0.2f, 1f, duration = 0.4f, delay = 0.1f),
            ),
            IconPart(
                paths = listOf("M3 14v1"),
                opacity = track(1f, 0.2f, 1f, duration = 0.4f, delay = 0.3f),
            ),
            IconPart(
                paths = listOf("M21 14v1"),
                opacity = track(1f, 0.2f, 1f, duration = 0.4f, delay = 0.15f),
            ),
        ),
    )

    val Zap = AnimIcon(
        "zap",
        listOf(
            IconPart(
                paths = listOf("M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"),
                rotate = track(0f, -4f, 2f, 0f, duration = 0.6f, ease = IconEase.OUT),
                scale = track(1f, 0.92f, 1.08f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.001f, 0.5005f, 1f), ease = IconEase.OUT),
                opacity = track(1f, 0.4f, 1f, duration = 0.25f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.LINEAR),
                trim = track(1f, 0f, 1f, duration = 0.45f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
            ),
        ),
    )

    val Palette = AnimIcon(
        "palette",
        listOf(
            IconPart(
                children = listOf(
                    IconPart(paths = listOf("M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z")),
                    IconPart(
                        paths = listOf(circle(6.5f, 12.5f, 0.5f)),
                        pivot = Offset(6.5f, 12.5f),
                        scale = track(1f, 0f, 1.8f, 1f, duration = 0.4f, times = floatArrayOf(0f, 0.2f, 0.6f, 1f), ease = IconEase.OUT),
                        fill = true,
                    ),
                    IconPart(
                        paths = listOf(circle(8.5f, 7.5f, 0.5f)),
                        pivot = Offset(8.5f, 7.5f),
                        scale = track(1f, 0f, 1.8f, 1f, duration = 0.4f, delay = 0.08f, times = floatArrayOf(0f, 0.2f, 0.6f, 1f), ease = IconEase.OUT),
                        fill = true,
                    ),
                    IconPart(
                        paths = listOf(circle(13.5f, 6.5f, 0.5f)),
                        pivot = Offset(13.5f, 6.5f),
                        scale = track(1f, 0f, 1.8f, 1f, duration = 0.4f, delay = 0.16f, times = floatArrayOf(0f, 0.2f, 0.6f, 1f), ease = IconEase.OUT),
                        fill = true,
                    ),
                    IconPart(
                        paths = listOf(circle(17.5f, 10.5f, 0.5f)),
                        pivot = Offset(17.5f, 10.5f),
                        scale = track(1f, 0f, 1.8f, 1f, duration = 0.4f, delay = 0.24f, times = floatArrayOf(0f, 0.2f, 0.6f, 1f), ease = IconEase.OUT),
                        fill = true,
                    ),
                ),
                pivot = Offset(12f, 12f),
                rotate = track(0f, -10f, 8f, -4f, 0f, duration = 0.8f),
            ),
        ),
    )

    val SlidersHorizontal = AnimIcon(
        "sliders-horizontal",
        listOf(
            IconPart(
                paths = listOf("M10 5H3"),
                opacity = track(1f, 0.6f, 1f, duration = 0.6f, delay = 0.05f),
            ),
            IconPart(
                paths = listOf("M21 5h-7"),
                opacity = track(1f, 0.6f, 1f, duration = 0.6f, delay = 0.05f),
            ),
            IconPart(
                paths = listOf("M12 19H3"),
                opacity = track(1f, 0.6f, 1f, duration = 0.6f, delay = 0.05f),
            ),
            IconPart(
                paths = listOf("M21 19h-5"),
                opacity = track(1f, 0.6f, 1f, duration = 0.6f, delay = 0.05f),
            ),
            IconPart(
                paths = listOf("M21 12h-9"),
                opacity = track(1f, 0.6f, 1f, duration = 0.6f, delay = 0.05f),
            ),
            IconPart(
                paths = listOf("M8 12H3"),
                opacity = track(1f, 0.6f, 1f, duration = 0.6f, delay = 0.05f),
            ),
            IconPart(
                paths = listOf("M14 3v4"),
                x = track(0f, 3f, 0f, duration = 0.7f, delay = 0.1f),
            ),
            IconPart(
                paths = listOf("M16 17v4"),
                x = track(0f, -3f, 0f, duration = 0.7f),
            ),
            IconPart(
                paths = listOf("M8 10v4"),
                x = track(0f, 3f, 0f, duration = 0.7f, delay = 0.1f),
            ),
        ),
    )

    val MoveLeft = AnimIcon(
        "move-left",
        listOf(
            IconPart(
                paths = listOf("M6 8L2 12L6 16"),
                x = track(0f, 2.5f, -2.5f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.25f, 0.6f, 1f)),
            ),
            IconPart(
                paths = listOf("M2 12H22"),
                x = track(0f, 2.5f, -2.5f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.25f, 0.6f, 1f)),
            ),
        ),
    )

    val Search = AnimIcon(
        "search",
        listOf(
            IconPart(
                paths = listOf(circle(11f, 11f, 8f), "m21 21-4.34-4.34"),
                rotate = track(0f, 6f, -6f, 4f, 0f, duration = 1f),
                x = track(0f, 2f, -2f, 1f, 0f, duration = 1f),
                y = track(0f, -1f, 2f, -1f, 0f, duration = 1f),
            ),
        ),
    )

    val Undo = AnimIcon(
        "undo",
        listOf(
            IconPart(
                paths = listOf("M3 7v6h6", "M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13"),
                pivot = Offset(12f, 17f),
                rotate = track(0f, -12f, 3f, 0f, duration = 0.7f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val Redo = AnimIcon(
        "redo",
        listOf(
            IconPart(
                paths = listOf("M21 7v6h-6", "M3 17a9 9 0 0 1 9-9 9 9 0 0 1 6 2.3l3 2.7"),
                pivot = Offset(12f, 17f),
                rotate = track(0f, 10f, -3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val Mic = AnimIcon(
        "mic",
        listOf(
            IconPart(
                paths = listOf(rect(9f, 2f, 6f, 13f, 3f)),
                pivot = Offset(12f, 8.5f),
                scale = track(1f, 1.1f, 0.96f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf("M12 19v3", "M19 10v2a7 7 0 0 1-14 0v-2"),
                pivot = Offset(12f, 10f),
                scale = track(1f, 1.06f, 0.97f, 1f, duration = 0.6f, delay = 0.1f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val RotateCcw = AnimIcon(
        "rotate-ccw",
        listOf(
            IconPart(
                paths = listOf("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5"),
                pivot = Offset(12f, 12f),
                rotate = track(0f, -360f, 0f, duration = 0.8f, times = floatArrayOf(0f, 0.999f, 1f)),
                scale = track(1f, 0.9f, 1f, duration = 0.8f),
            ),
        ),
    )

    val Figma = AnimIcon(
        "figma",
        listOf(
            IconPart(
                paths = listOf("M5 5.5A3.5 3.5 0 0 1 8.5 2H12v7H8.5A3.5 3.5 0 0 1 5 5.5z"),
                y = track(0f, -3f, 0.6f, 0f, duration = 0.5f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf("M12 2h3.5a3.5 3.5 0 1 1 0 7H12V2z"),
                y = track(0f, -3f, 0.6f, 0f, duration = 0.5f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf("M5 12.5A3.5 3.5 0 0 1 8.5 9H12v7H8.5A3.5 3.5 0 0 1 5 12.5z"),
                y = track(0f, -3f, 0.6f, 0f, duration = 0.5f, delay = 0.1f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf("M12 12.5a3.5 3.5 0 1 1 7 0 3.5 3.5 0 1 1-7 0z"),
                y = track(0f, -3f, 0.6f, 0f, duration = 0.5f, delay = 0.1f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf("M5 19.5A3.5 3.5 0 0 1 8.5 16H12v3.5a3.5 3.5 0 1 1-7 0z"),
                y = track(0f, -3f, 0.6f, 0f, duration = 0.5f, delay = 0.2f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
        ),
    )

    val Calculator = AnimIcon(
        "calculator",
        listOf(
            IconPart(paths = listOf(rect(4f, 2f, 16f, 20f, 2f))),
            IconPart(
                paths = listOf(line(8f, 6f, 16f, 6f)),
                opacity = track(1f, 0f, 1f, duration = 0.25f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
                trim = track(1f, 0f, 1f, duration = 0.4f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
            ),
            IconPart(paths = listOf(line(16f, 14f, 16f, 18f))),
            IconPart(
                paths = listOf("M16 10h.01"),
                pivot = Offset(16f, 10f),
                scale = track(1f, 1.8f, 1f, duration = 0.35f, delay = 0.15f),
            ),
            IconPart(
                paths = listOf("M12 10h.01"),
                pivot = Offset(12f, 10f),
                scale = track(1f, 1.8f, 1f, duration = 0.35f, delay = 0.21f),
            ),
            IconPart(
                paths = listOf("M8 10h.01"),
                pivot = Offset(8f, 10f),
                scale = track(1f, 1.8f, 1f, duration = 0.35f, delay = 0.27f),
            ),
            IconPart(
                paths = listOf("M12 14h.01"),
                pivot = Offset(12f, 14f),
                scale = track(1f, 1.8f, 1f, duration = 0.35f, delay = 0.33f),
            ),
            IconPart(
                paths = listOf("M8 14h.01"),
                pivot = Offset(8f, 14f),
                scale = track(1f, 1.8f, 1f, duration = 0.35f, delay = 0.39f),
            ),
            IconPart(
                paths = listOf("M12 18h.01"),
                pivot = Offset(12f, 18f),
                scale = track(1f, 1.8f, 1f, duration = 0.35f, delay = 0.45f),
            ),
            IconPart(
                paths = listOf("M8 18h.01"),
                pivot = Offset(8f, 18f),
                scale = track(1f, 1.8f, 1f, duration = 0.35f, delay = 0.51f),
            ),
        ),
    )

    val Settings = AnimIcon(
        "settings",
        listOf(
            IconPart(
                paths = listOf("M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915"),
                pivot = Offset(12f, 12f),
                rotate = track(0f, 30f, -6f, 0f, duration = 0.9f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
                scale = track(1f, 1.05f, 1.02f, 1f, duration = 0.9f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf(circle(12f, 12f, 3f)),
                pivot = Offset(12f, 12f),
                scale = track(1f, 1.25f, 0.9f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val Plus = AnimIcon(
        "plus",
        listOf(
            IconPart(
                paths = listOf("M5 12h14M12 5v14"),
                pivot = Offset(12f, 12f),
                rotate = track(0f, 90f, 0f, duration = 0.5f, times = floatArrayOf(0f, 0.999f, 1f)),
                scale = track(1f, 1.2f, 1f, duration = 0.5f),
            ),
        ),
    )

    val Ellipsis = AnimIcon(
        "ellipsis",
        listOf(
            IconPart(
                paths = listOf(circle(5f, 12f, 1f)),
                y = track(0f, -3f, 0f, duration = 0.55f),
            ),
            IconPart(
                paths = listOf(circle(12f, 12f, 1f)),
                y = track(0f, -3f, 0f, duration = 0.55f, delay = 0.12f),
            ),
            IconPart(
                paths = listOf(circle(19f, 12f, 1f)),
                y = track(0f, -3f, 0f, duration = 0.55f, delay = 0.24f),
            ),
        ),
    )

    val Menu = AnimIcon(
        "menu",
        listOf(
            IconPart(
                paths = listOf("M4 6h16"),
                pivot = Offset(20f, 6f),
                scaleX = track(1f, 0.8f, 1f, duration = 0.6f),
            ),
            IconPart(
                paths = listOf("M4 12h16"),
                pivot = Offset(20f, 12f),
                scaleX = track(1f, 0.6f, 1f, duration = 0.6f, delay = 0.1f),
            ),
            IconPart(
                paths = listOf("M4 18h16"),
                pivot = Offset(20f, 18f),
                scaleX = track(1f, 0.4f, 1f, duration = 0.6f, delay = 0.2f),
            ),
        ),
    )

    val X = AnimIcon(
        "x",
        listOf(
            IconPart(
                paths = listOf("M18 6 6 18", "m6 6 12 12"),
                pivot = Offset(12f, 12f),
                rotate = track(0f, 90f, 0f, duration = 0.5f, times = floatArrayOf(0f, 0.999f, 1f)),
                scale = track(1f, 1.2f, 1f, duration = 0.5f),
            ),
        ),
    )

    val Layers = AnimIcon(
        "layers",
        listOf(
            IconPart(
                paths = listOf("M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z"),
                y = track(0f, 3f, 0f, duration = 0.6f),
            ),
            IconPart(paths = listOf("M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12")),
            IconPart(
                paths = listOf("M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17"),
                y = track(0f, -3f, 0f, duration = 0.6f),
            ),
        ),
    )

    val Image = AnimIcon(
        "image",
        listOf(
            IconPart(paths = listOf(rect(3f, 3f, 18f, 18f, 2f))),
            IconPart(
                paths = listOf(circle(9f, 9f, 2f)),
                pivot = Offset(9f, 9f),
                scale = track(1f, 1.35f, 0.9f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf("m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"),
                pivot = Offset(12f, 21f),
                scaleY = track(1f, 1.15f, 0.97f, 1f, duration = 0.6f, delay = 0.08f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val Clock = AnimIcon(
        "clock",
        listOf(
            IconPart(paths = listOf(circle(12f, 12f, 10f))),
            IconPart(
                paths = listOf("M12 6V12"),
                pivot = Offset(12f, 12f),
                rotate = track(0f, 360f, 0f, duration = 0.9f, times = floatArrayOf(0f, 0.999f, 1f)),
            ),
            IconPart(paths = listOf("M12 12L16 14")),
        ),
    )

    val Calendar = AnimIcon(
        "calendar",
        listOf(
            IconPart(
                paths = listOf("M8 2v3"),
                y = track(0f, 1.6f, -0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf("M16 2v3"),
                y = track(0f, 1.6f, -0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf(rect(3f, 3f, 18f, 18f, 2f), "M3 9h18"),
                pivot = Offset(12f, 21f),
                scaleY = track(1f, 1f, 0.96f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.65f, 1f)),
            ),
        ),
    )

    val ListChecks = AnimIcon(
        "list-checks",
        listOf(
            IconPart(
                paths = listOf("M13 5h8"),
                pivot = Offset(13f, 5f),
                scaleX = track(1f, 0.55f, 1f, duration = 0.5f, delay = 0.05f),
            ),
            IconPart(
                paths = listOf("M13 12h8"),
                pivot = Offset(13f, 12f),
                scaleX = track(1f, 0.55f, 1f, duration = 0.5f, delay = 0.17f),
            ),
            IconPart(
                paths = listOf("M13 19h8"),
                pivot = Offset(13f, 19f),
                scaleX = track(1f, 0.55f, 1f, duration = 0.5f, delay = 0.29f),
            ),
            IconPart(
                paths = listOf("m3 17 2 2 4-4"),
                opacity = track(1f, 0f, 1f, duration = 0.25f, delay = 0.25f, times = floatArrayOf(0f, 0.001f, 1f)),
                trim = track(1f, 0f, 1f, duration = 0.4f, delay = 0.25f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
            ),
            IconPart(
                paths = listOf("m3 7 2 2 4-4"),
                opacity = track(1f, 0f, 1f, duration = 0.25f, times = floatArrayOf(0f, 0.001f, 1f)),
                trim = track(1f, 0f, 1f, duration = 0.4f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
            ),
        ),
    )

    val Hand = AnimIcon(
        "hand",
        listOf(
            IconPart(
                paths = listOf("M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2", "M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2", "M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8", "M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"),
                pivot = Offset(12f, 21f),
                rotate = track(0f, -14f, 4f, -10f, 0f, duration = 1f),
            ),
        ),
    )

    val ZoomIn = AnimIcon(
        "zoom-in",
        listOf(
            IconPart(
                children = listOf(
                    IconPart(paths = listOf(circle(11f, 11f, 8f), line(21f, 21f, 16.65f, 16.65f))),
                    IconPart(
                        paths = listOf(line(11f, 8f, 11f, 14f), line(8f, 11f, 14f, 11f)),
                        pivot = Offset(11f, 11f),
                        rotate = track(0f, 90f, 0f, duration = 0.5f, times = floatArrayOf(0f, 0.999f, 1f)),
                        scale = track(1f, 1.2f, 1f, duration = 0.5f),
                    ),
                ),
                pivot = Offset(11f, 11f),
                scale = track(1f, 1.1f, 0.97f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
        ),
    )

    val ZoomOut = AnimIcon(
        "zoom-out",
        listOf(
            IconPart(
                children = listOf(
                    IconPart(paths = listOf(circle(11f, 11f, 8f), line(21f, 21f, 16.65f, 16.65f))),
                    IconPart(
                        paths = listOf(line(8f, 11f, 14f, 11f)),
                        pivot = Offset(11f, 11f),
                        rotate = track(0f, 180f, 0f, duration = 0.5f, times = floatArrayOf(0f, 0.999f, 1f)),
                        scale = track(1f, 0.7f, 1f, duration = 0.5f),
                    ),
                ),
                pivot = Offset(11f, 11f),
                scale = track(1f, 0.9f, 1.03f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
        ),
    )

    val FileText = AnimIcon(
        "file-text",
        listOf(
            IconPart(
                children = listOf(
                    IconPart(paths = listOf("M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5")),
                    IconPart(
                        paths = listOf("M10 9H8"),
                        opacity = track(1f, 0f, 1f, duration = 0.25f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
                        trim = track(1f, 0f, 1f, duration = 0.4f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
                    ),
                    IconPart(
                        paths = listOf("M16 13H8"),
                        opacity = track(1f, 0f, 1f, duration = 0.25f, delay = 0.15f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
                        trim = track(1f, 0f, 1f, duration = 0.4f, delay = 0.15f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
                    ),
                    IconPart(
                        paths = listOf("M16 17H8"),
                        opacity = track(1f, 0f, 1f, duration = 0.25f, delay = 0.3f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
                        trim = track(1f, 0f, 1f, duration = 0.4f, delay = 0.3f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
                    ),
                ),
                pivot = Offset(12f, 12f),
                scale = track(1f, 1.08f, 0.98f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
        ),
    )

    val FilePlus = AnimIcon(
        "file-plus",
        listOf(
            IconPart(
                children = listOf(
                    IconPart(paths = listOf("M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5")),
                    IconPart(
                        paths = listOf("M9 15h6M12 18v-6"),
                        pivot = Offset(12f, 15f),
                        rotate = track(0f, 90f, 0f, duration = 0.5f, times = floatArrayOf(0f, 0.999f, 1f)),
                        scale = track(1f, 1.2f, 1f, duration = 0.5f),
                    ),
                ),
                pivot = Offset(12f, 12f),
                scale = track(1f, 1.08f, 0.98f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
        ),
    )

    val Camera = AnimIcon(
        "camera",
        listOf(
            IconPart(
                paths = listOf("M13.997 4a2 2 0 0 1 1.76 1.05l.486.9A2 2 0 0 0 18.003 7H20a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h1.997a2 2 0 0 0 1.759-1.048l.489-.904A2 2 0 0 1 10.004 4z"),
                pivot = Offset(12f, 13f),
                scaleY = track(1f, 0.97f, 1f, duration = 0.35f),
            ),
            IconPart(
                paths = listOf(circle(12f, 13f, 3f)),
                pivot = Offset(12f, 13f),
                scale = track(1f, 0.6f, 1.15f, 1f, duration = 0.7f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val Globe = AnimIcon(
        "globe",
        listOf(
            IconPart(paths = listOf(circle(12f, 12f, 10f))),
            IconPart(
                paths = listOf("M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"),
                pivot = Offset(12f, 12f),
                scaleX = track(1f, -1f, 1f, duration = 0.9f),
            ),
            IconPart(paths = listOf("M2 12h20")),
        ),
    )

    val Gauge = AnimIcon(
        "gauge",
        listOf(
            IconPart(
                paths = listOf("m12 14 4-4"),
                pivot = Offset(12f, 14f),
                rotate = track(0f, -95f, 25f, -5f, 0f, duration = 0.9f, times = floatArrayOf(0f, 0.3f, 0.65f, 0.85f, 1f)),
            ),
            IconPart(paths = listOf("M3.34 19a10 10 0 1 1 17.32 0")),
        ),
    )

    val Sparkles = AnimIcon(
        "sparkles",
        listOf(
            IconPart(
                paths = listOf("M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z"),
                pivot = Offset(12f, 12f),
                rotate = track(0f, 14f, -8f, 0f, duration = 0.8f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
                scale = track(1f, 1.1f, 0.95f, 1f, duration = 0.8f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf("M20 2v4", "M22 4h-4"),
                pivot = Offset(20f, 4f),
                rotate = track(0f, 45f, 0f, duration = 0.6f, delay = 0.12f),
                scale = track(1f, 1.3f, 1f, duration = 0.6f, delay = 0.12f),
            ),
            IconPart(
                paths = listOf(circle(4f, 20f, 2f)),
                pivot = Offset(4f, 20f),
                scale = track(1f, 1.4f, 0.9f, 1f, duration = 0.5f, delay = 0.25f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val LayoutGrid = AnimIcon(
        "layout-grid",
        listOf(
            IconPart(
                paths = listOf(rect(3f, 3f, 7f, 7f, 1f)),
                pivot = Offset(6.5f, 6.5f),
                scale = track(1f, 1.22f, 0.96f, 1f, duration = 0.5f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf(rect(14f, 3f, 7f, 7f, 1f)),
                pivot = Offset(17.5f, 6.5f),
                scale = track(1f, 1.22f, 0.96f, 1f, duration = 0.5f, delay = 0.08f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf(rect(14f, 14f, 7f, 7f, 1f)),
                pivot = Offset(17.5f, 17.5f),
                scale = track(1f, 1.22f, 0.96f, 1f, duration = 0.5f, delay = 0.16f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf(rect(3f, 14f, 7f, 7f, 1f)),
                pivot = Offset(6.5f, 17.5f),
                scale = track(1f, 1.22f, 0.96f, 1f, duration = 0.5f, delay = 0.24f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val Scan = AnimIcon(
        "scan",
        listOf(
            IconPart(
                paths = listOf("M3 7V5a2 2 0 0 1 2-2h2"),
                x = track(0f, 1.5f, -0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
                y = track(0f, 1.5f, -0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf("M17 3h2a2 2 0 0 1 2 2v2"),
                x = track(0f, -1.5f, 0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
                y = track(0f, 1.5f, -0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf("M21 17v2a2 2 0 0 1-2 2h-2"),
                x = track(0f, -1.5f, 0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
                y = track(0f, -1.5f, 0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf("M7 21H5a2 2 0 0 1-2-2v-2"),
                x = track(0f, 1.5f, -0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
                y = track(0f, -1.5f, 0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val PictureInPicture = AnimIcon(
        "picture-in-picture",
        listOf(
            IconPart(
                paths = listOf("M2 10h6V4", "m2 4 6 6"),
                x = track(0f, 2f, 0f, duration = 0.5f, delay = 0.25f),
                y = track(0f, 2f, 0f, duration = 0.5f, delay = 0.25f),
            ),
            IconPart(paths = listOf("M21 10V7a2 2 0 0 0-2-2h-7", "M3 14v2a2 2 0 0 0 2 2h3")),
            IconPart(
                paths = listOf(rect(12f, 14f, 10f, 7f, 1f)),
                pivot = Offset(17f, 17.5f),
                scale = track(1f, 0.88f, 1.04f, 1f, duration = 0.75f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
                x = track(0f, -2.5f, 0.5f, 0f, duration = 0.75f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
                y = track(0f, -3f, 0.5f, 0f, duration = 0.75f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
        ),
    )

    val Pin = AnimIcon(
        "pin",
        listOf(
            IconPart(
                paths = listOf("M12 17v5", "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"),
                pivot = Offset(12f, 22f),
                rotate = track(0f, -9f, 7f, -3f, 0f, duration = 0.7f),
                y = track(0f, 1f, 0.3f, 0f, duration = 0.5f, times = floatArrayOf(0f, 0.3f, 0.6f, 1f)),
            ),
        ),
    )

    val PinOff = AnimIcon(
        "pin-off",
        listOf(
            IconPart(
                paths = listOf("M12 17v5", "M15 9.34V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H7.89", "M9 9v1.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h11"),
                pivot = Offset(12f, 22f),
                rotate = track(0f, -8f, 6f, -3f, 0f, duration = 0.6f),
                opacity = track(1f, 0.45f, 1f, duration = 0.7f),
            ),
            IconPart(
                paths = listOf("m2 2 20 20"),
                trim = track(1f, 0f, 1f, duration = 0.45f, delay = 0.1f, times = floatArrayOf(0f, 0.001f, 1f)),
            ),
        ),
    )

    val LocateFixed = AnimIcon(
        "locate-fixed",
        listOf(
            IconPart(
                paths = listOf(line(2f, 12f, 5f, 12f)),
                x = track(0f, 1.5f, -0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf(line(19f, 12f, 22f, 12f)),
                x = track(0f, -1.5f, 0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf(line(12f, 2f, 12f, 5f)),
                y = track(0f, 1.5f, -0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf(line(12f, 19f, 12f, 22f)),
                y = track(0f, -1.5f, 0.3f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf(circle(12f, 12f, 7f)),
                pivot = Offset(12f, 12f),
                scale = track(1f, 0.88f, 1.03f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
            IconPart(
                paths = listOf(circle(12f, 12f, 3f)),
                pivot = Offset(12f, 12f),
                scale = track(1f, 0.6f, 1.15f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val PanelLeft = AnimIcon(
        "panel-left",
        listOf(
            IconPart(paths = listOf(rect(3f, 3f, 18f, 18f, 2f))),
            IconPart(
                paths = listOf("M9 3v18"),
                x = track(0f, -4f, 1f, 0f, duration = 0.7f, times = floatArrayOf(0f, 0.45f, 0.8f, 1f)),
            ),
        ),
    )

    val Folder = AnimIcon(
        "folder",
        listOf(
            IconPart(
                paths = listOf("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"),
                y = track(0f, -1.2f, 0.4f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val FolderOpen = AnimIcon(
        "folder-open",
        listOf(
            IconPart(
                paths = listOf("m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2"),
                pivot = Offset(12f, 20f),
                y = track(0f, -1.2f, 0.4f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
        ),
    )

    val Download = AnimIcon(
        "download",
        listOf(
            IconPart(
                paths = listOf("M12 15V3", "m7 10 5 5 5-5"),
                y = track(0f, 4f, -4f, 0f, duration = 0.8f, times = floatArrayOf(0f, 0.4f, 0.5f, 1f)),
                opacity = track(1f, 0f, 0f, 1f, duration = 0.8f, times = floatArrayOf(0f, 0.4f, 0.5f, 1f)),
            ),
            IconPart(
                paths = listOf("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"),
                y = track(0f, 0f, 1f, 0f, duration = 0.8f, times = floatArrayOf(0f, 0.75f, 0.9f, 1f)),
            ),
        ),
    )

    val Upload = AnimIcon(
        "upload",
        listOf(
            IconPart(
                paths = listOf("M12 3v12", "m17 8-5-5-5 5"),
                y = track(0f, -4f, 4f, 0f, duration = 0.8f, times = floatArrayOf(0f, 0.4f, 0.5f, 1f)),
                opacity = track(1f, 0f, 0f, 1f, duration = 0.8f, times = floatArrayOf(0f, 0.4f, 0.5f, 1f)),
            ),
            IconPart(
                paths = listOf("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"),
                y = track(0f, 0f, 1f, 0f, duration = 0.8f, times = floatArrayOf(0f, 0.75f, 0.9f, 1f)),
            ),
        ),
    )

    val Trash = AnimIcon(
        "trash",
        listOf(
            IconPart(
                paths = listOf("M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"),
                pivot = Offset(12f, 22f),
                scaleY = track(1f, 1f, 0.94f, 1f, duration = 0.9f, times = floatArrayOf(0f, 0.55f, 0.75f, 1f), ease = IconEase.OUT),
                y = track(0f, 0f, 1f, 0f, duration = 0.9f, times = floatArrayOf(0f, 0.55f, 0.75f, 1f), ease = IconEase.OUT),
            ),
            IconPart(
                paths = listOf("M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"),
                pivot = Offset(3f, 6f),
                rotate = track(0f, -24f, -24f, 0f, duration = 0.9f, times = floatArrayOf(0f, 0.2f, 0.55f, 1f)),
            ),
        ),
    )

    val Check = AnimIcon(
        "check",
        listOf(
            IconPart(
                paths = listOf("M5 13l4 4L19 7"),
                pivot = Offset(12f, 12f),
                scale = track(1f, 0.9f, 1.12f, 1f, duration = 0.55f, times = floatArrayOf(0f, 0.001f, 0.6004f, 1f)),
                opacity = track(1f, 0f, 1f, duration = 0.25f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
                trim = track(1f, 0f, 1f, duration = 0.5f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
            ),
        ),
    )

    val Minus = AnimIcon(
        "minus",
        listOf(
            IconPart(
                paths = listOf("M5 12h14"),
                pivot = Offset(12f, 12f),
                rotate = track(0f, 180f, 0f, duration = 0.5f, times = floatArrayOf(0f, 0.999f, 1f)),
                scale = track(1f, 0.7f, 1f, duration = 0.5f),
            ),
        ),
    )

    val Sun = AnimIcon(
        "sun",
        listOf(
            IconPart(
                children = listOf(
                    IconPart(
                        paths = listOf(circle(12f, 12f, 4f)),
                        scale = track(1f, 1.18f, 1f, duration = 0.8f),
                    ),
                    IconPart(
                        paths = listOf("M12 2v2"),
                        opacity = track(1f, 0.7f, 1f, duration = 0.8f),
                    ),
                    IconPart(
                        paths = listOf("M12 20v2"),
                        opacity = track(1f, 0.7f, 1f, duration = 0.8f),
                    ),
                    IconPart(
                        paths = listOf("m4.93 4.93 1.41 1.41"),
                        opacity = track(1f, 0.7f, 1f, duration = 0.8f),
                    ),
                    IconPart(
                        paths = listOf("m17.66 17.66 1.41 1.41"),
                        opacity = track(1f, 0.7f, 1f, duration = 0.8f),
                    ),
                    IconPart(
                        paths = listOf("M2 12h2"),
                        opacity = track(1f, 0.7f, 1f, duration = 0.8f),
                    ),
                    IconPart(
                        paths = listOf("M20 12h2"),
                        opacity = track(1f, 0.7f, 1f, duration = 0.8f),
                    ),
                    IconPart(
                        paths = listOf("m6.34 17.66-1.41 1.41"),
                        opacity = track(1f, 0.7f, 1f, duration = 0.8f),
                    ),
                    IconPart(
                        paths = listOf("m19.07 4.93-1.41 1.41"),
                        opacity = track(1f, 0.7f, 1f, duration = 0.8f),
                    ),
                ),
                pivot = Offset(12f, 12f),
                rotate = track(0f, 14f, -6f, 0f, duration = 0.8f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
        ),
    )

    val Moon = AnimIcon(
        "moon",
        listOf(
            IconPart(
                paths = listOf("M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401"),
                pivot = Offset(12f, 12f),
                rotate = track(0f, -16f, 12f, -6f, 0f, duration = 0.8f),
            ),
        ),
    )

    val History = AnimIcon(
        "history",
        listOf(
            IconPart(paths = listOf("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8")),
            IconPart(
                paths = listOf("M3 3v5h5"),
                x = track(0f, -1.5f, 0.5f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
                y = track(0f, -1.5f, 0.5f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.35f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf("M12 7v5"),
                pivot = Offset(12f, 12f),
                rotate = track(0f, -360f, 0f, duration = 0.9f, times = floatArrayOf(0f, 0.999f, 1f)),
            ),
            IconPart(paths = listOf("M12 12l4 2")),
        ),
    )

    val NotebookPen = AnimIcon(
        "notebook-pen",
        listOf(
            IconPart(paths = listOf("M13.4 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7.4")),
            IconPart(
                paths = listOf("M2 6h4"),
                pivot = Offset(4f, 6f),
                scaleX = track(1f, 0.3f, 1f, duration = 0.3f),
            ),
            IconPart(
                paths = listOf("M2 10h4"),
                pivot = Offset(4f, 10f),
                scaleX = track(1f, 0.3f, 1f, duration = 0.3f, delay = 0.08f),
            ),
            IconPart(
                paths = listOf("M2 14h4"),
                pivot = Offset(4f, 14f),
                scaleX = track(1f, 0.3f, 1f, duration = 0.3f, delay = 0.16f),
            ),
            IconPart(
                paths = listOf("M2 18h4"),
                pivot = Offset(4f, 18f),
                scaleX = track(1f, 0.3f, 1f, duration = 0.3f, delay = 0.24f),
            ),
            IconPart(
                paths = listOf("M21.378 5.626a1 1 0 1 0-3.004-3.004l-5.01 5.012a2 2 0 0 0-.506.854l-.837 2.87a.5.5 0 0 0 .62.62l2.87-.837a2 2 0 0 0 .854-.506z"),
                pivot = Offset(13f, 11f),
                rotate = track(0f, -8f, 4f, -4f, 0f, duration = 0.8f),
                x = track(0f, -1.5f, 1f, -1f, 0f, duration = 0.8f),
                y = track(0f, 1f, -0.5f, 0.8f, 0f, duration = 0.8f),
            ),
        ),
    )

    val Wrench = AnimIcon(
        "wrench",
        listOf(
            IconPart(
                paths = listOf("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z"),
                pivot = Offset(15f, 9f),
                rotate = track(0f, -12f, 10f, -6f, 0f, duration = 0.8f),
            ),
        ),
    )

    val House = AnimIcon(
        "house",
        listOf(
            IconPart(
                children = listOf(
                    IconPart(paths = listOf("M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")),
                    IconPart(
                        paths = listOf("M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8"),
                        opacity = track(1f, 0f, 1f, duration = 0.25f, delay = 0.35f, times = floatArrayOf(0f, 0.001f, 1f)),
                        trim = track(1f, 0f, 1f, duration = 0.45f, delay = 0.35f, times = floatArrayOf(0f, 0.001f, 1f), ease = IconEase.OUT),
                    ),
                ),
                pivot = Offset(12f, 21f),
                scale = track(1f, 0.8f, 1.06f, 1f, duration = 0.7f, times = floatArrayOf(0f, 0.35f, 0.75f, 1f)),
            ),
        ),
    )

    val ChevronDown = AnimIcon(
        "chevron-down",
        listOf(
            IconPart(
                paths = listOf("m6 9 6 6 6-6"),
                y = track(0f, 2.5f, -0.625f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
        ),
    )

    val ChevronUp = AnimIcon(
        "chevron-up",
        listOf(
            IconPart(
                paths = listOf("m18 15-6-6-6 6"),
                y = track(0f, -2.5f, 0.625f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
        ),
    )

    val Copy = AnimIcon(
        "copy",
        listOf(
            IconPart(
                paths = listOf(rect(8f, 8f, 14f, 14f, 2f)),
                pivot = Offset(15f, 15f),
                rotate = track(0f, 360f, 0f, duration = 0.9f, times = floatArrayOf(0f, 0.999f, 1f)),
                x = track(0f, -3f, 0f, duration = 0.9f),
                y = track(0f, -3f, 0f, duration = 0.9f),
            ),
            IconPart(
                paths = listOf("M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"),
                x = track(0f, 3f, 0f, duration = 0.9f),
                y = track(0f, 3f, 0f, duration = 0.9f),
            ),
        ),
    )

    val Share = AnimIcon(
        "share",
        listOf(
            IconPart(
                paths = listOf("M12 2v13", "m16 6-4-4-4 4"),
                y = track(0f, 1.5f, -1f, 0f, duration = 0.6f, times = floatArrayOf(0f, 0.3f, 0.7f, 1f)),
            ),
            IconPart(
                paths = listOf("M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8"),
                pivot = Offset(12f, 22f),
                scaleY = track(1f, 0.94f, 1.02f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.3f, 0.7f, 1f)),
            ),
        ),
    )

    val Bookmark = AnimIcon(
        "bookmark",
        listOf(
            IconPart(
                paths = listOf("M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z"),
                pivot = Offset(12f, 12f),
                scaleX = track(1f, 0.97f, 1.02f, 1f, duration = 0.55f, ease = IconEase.OUT),
                scaleY = track(1f, 1.1f, 0.95f, 1f, duration = 0.55f, ease = IconEase.OUT),
                y = track(0f, -4f, 0f, duration = 0.55f, ease = IconEase.OUT),
            ),
        ),
    )

    val Tag = AnimIcon(
        "tag",
        listOf(
            IconPart(
                children = listOf(
                    IconPart(paths = listOf("M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z")),
                    IconPart(
                        paths = listOf(circle(7.5f, 7.5f, 0.5f)),
                        fill = true,
                    ),
                ),
                pivot = Offset(7.5f, 7.5f),
                rotate = track(0f, -12f, 9f, -4f, 0f, duration = 0.8f),
            ),
        ),
    )

    val Lock = AnimIcon(
        "lock",
        listOf(
            IconPart(
                paths = listOf(rect(3f, 11f, 18f, 11f, 2f), "M7 11V7a5 5 0 0 1 10 0v4"),
                pivot = Offset(12f, 12f),
                rotate = track(0f, -2f, 2f, -2f, 0f, duration = 0.55f),
                x = track(0f, -3f, 3f, -3f, 0f, duration = 0.55f),
            ),
        ),
    )

    val File = AnimIcon(
        "file",
        listOf(
            IconPart(
                paths = listOf("M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5"),
                pivot = Offset(12f, 12f),
                scale = track(1f, 1.08f, 0.98f, 1f, duration = 0.6f, times = floatArrayOf(0f, 0.4f, 0.75f, 1f)),
            ),
        ),
    )

    val List = AnimIcon(
        "list",
        listOf(
            IconPart(
                paths = listOf("M3 5h.01"),
                pivot = Offset(3f, 5f),
                scale = track(1f, 1.7f, 1f, duration = 0.4f),
            ),
            IconPart(
                paths = listOf("M3 12h.01"),
                pivot = Offset(3f, 12f),
                scale = track(1f, 1.7f, 1f, duration = 0.4f, delay = 0.12f),
            ),
            IconPart(
                paths = listOf("M3 19h.01"),
                pivot = Offset(3f, 19f),
                scale = track(1f, 1.7f, 1f, duration = 0.4f, delay = 0.24f),
            ),
            IconPart(
                paths = listOf("M8 5h13"),
                pivot = Offset(8f, 5f),
                scaleX = track(1f, 0.55f, 1f, duration = 0.5f, delay = 0.05f),
            ),
            IconPart(
                paths = listOf("M8 12h13"),
                pivot = Offset(8f, 12f),
                scaleX = track(1f, 0.55f, 1f, duration = 0.5f, delay = 0.17f),
            ),
            IconPart(
                paths = listOf("M8 19h13"),
                pivot = Offset(8f, 19f),
                scaleX = track(1f, 0.55f, 1f, duration = 0.5f, delay = 0.29f),
            ),
        ),
    )

    val Monitor = AnimIcon(
        "monitor",
        listOf(
            IconPart(
                paths = listOf(rect(2f, 3f, 20f, 14f, 2f)),
                pivot = Offset(12f, 17f),
                rotate = track(0f, -5f, 4f, -2f, 0f, duration = 0.7f),
            ),
            IconPart(paths = listOf(line(8f, 21f, 16f, 21f), line(12f, 17f, 12f, 21f))),
        ),
    )

    // A pointer with its beam: a short body, the bright spot, and rays from it.
    val Laser = AnimIcon(
        "laser",
        listOf(
            IconPart(paths = listOf("M3 21l9-9", "M5 23l-2-2")),
            IconPart(
                paths = listOf(circle(15f, 9f, 2f)),
                pivot = Offset(15f, 9f),
                scale = track(1f, 1.35f, 0.9f, 1f, duration = 0.6f),
            ),
            IconPart(
                paths = listOf("M15 2v3", "M22 9h-3", "M20 4l-2 2", "M20 14l-2-2", "M10 4l2 2"),
                opacity = track(1f, 0.3f, 1f, duration = 0.6f),
            ),
        ),
    )

    val CircleHelp = AnimIcon(
        "circle-help",
        listOf(
            IconPart(paths = listOf(circle(12f, 12f, 10f))),
            IconPart(
                paths = listOf("M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01"),
                pivot = Offset(12f, 13f),
                rotate = track(0f, -12f, 10f, -4f, 0f, duration = 0.7f),
            ),
        ),
    )
}

internal val ALL_LUCIDE: List<AnimIcon> = listOf(
    Lucide.MousePointerClick,
    Lucide.Pencil,
    Lucide.PenTool,
    Lucide.Highlighter,
    Lucide.EyeOff,
    Lucide.Eraser,
    Lucide.Shapes,
    Lucide.Type,
    Lucide.CircleDashed,
    Lucide.SquareDashed,
    Lucide.Zap,
    Lucide.Palette,
    Lucide.SlidersHorizontal,
    Lucide.MoveLeft,
    Lucide.Search,
    Lucide.Undo,
    Lucide.Redo,
    Lucide.Mic,
    Lucide.RotateCcw,
    Lucide.Figma,
    Lucide.Calculator,
    Lucide.Settings,
    Lucide.Plus,
    Lucide.Ellipsis,
    Lucide.Menu,
    Lucide.X,
    Lucide.Layers,
    Lucide.Image,
    Lucide.Clock,
    Lucide.Calendar,
    Lucide.ListChecks,
    Lucide.Hand,
    Lucide.ZoomIn,
    Lucide.ZoomOut,
    Lucide.FileText,
    Lucide.FilePlus,
    Lucide.Camera,
    Lucide.Globe,
    Lucide.Gauge,
    Lucide.Sparkles,
    Lucide.LayoutGrid,
    Lucide.Scan,
    Lucide.PictureInPicture,
    Lucide.Pin,
    Lucide.PinOff,
    Lucide.LocateFixed,
    Lucide.PanelLeft,
    Lucide.Folder,
    Lucide.FolderOpen,
    Lucide.Download,
    Lucide.Upload,
    Lucide.Trash,
    Lucide.Check,
    Lucide.Minus,
    Lucide.Sun,
    Lucide.Moon,
    Lucide.History,
    Lucide.NotebookPen,
    Lucide.Wrench,
    Lucide.House,
    Lucide.ChevronDown,
    Lucide.ChevronUp,
    Lucide.Copy,
    Lucide.Share,
    Lucide.Bookmark,
    Lucide.Tag,
    Lucide.Lock,
    Lucide.File,
    Lucide.List,
    Lucide.Monitor,
    Lucide.CircleHelp,
    Lucide.Laser,
)
