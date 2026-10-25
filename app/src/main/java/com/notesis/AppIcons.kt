package com.notesis

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The toolbar's icons, in whichever set [AppIconStyle] has chosen. Reading one
 * reads that choice, so the bar redraws when the set is switched.
 */
internal object AppIcons {
    // The pointer and the pen nib point down at the page, as the tools are held.
    private val pointerDown = Lucide.MousePointerClick.flippedVertically()
    private val nibDown = Lucide.PenTool.flippedVertically()

    val read: ImageVector get() = AppIconStyle.pick(pointerDown, Reicons.TouchApp)
    // The pen is a nib and the pencil a pencil, so the two read apart at a glance;
    // the pen used to wear the pencil and the pencil a nib.
    val penTool: ImageVector get() = AppIconStyle.pick(nibDown, Reicons.Create)
    val pencilTool: ImageVector get() = AppIconStyle.pick(Lucide.Pencil, Reicons.Brush)
    val highlighterTool: ImageVector get() = AppIconStyle.pick(Lucide.Highlighter, Reicons.Highlight)

    val back get() = AppIconStyle.pick(Lucide.MoveLeft, Reicons.ArrowBack)
    val search get() = AppIconStyle.pick(Lucide.Search, Reicons.Search)
    val undo get() = AppIconStyle.pick(Lucide.Undo, Reicons.Undo)
    val redo get() = AppIconStyle.pick(Lucide.Redo, Reicons.Redo)
    val mic get() = AppIconStyle.pick(Lucide.Mic, Reicons.Mic)
    val rotate get() = AppIconStyle.pick(Lucide.RotateCcw, Reicons.ScreenRotation)
    val skin get() = AppIconStyle.pick(Lucide.Figma, Reicons.AutoAwesomeMosaic)
    val tools get() = AppIconStyle.pick(Lucide.Calculator, Reicons.Calculate)
    val input get() = AppIconStyle.pick(Lucide.Settings, Reicons.TouchApp)
    val addPage get() = AppIconStyle.pick(Lucide.Plus, Reicons.Add)
    val noteMenu get() = AppIconStyle.pick(Lucide.Ellipsis, Reicons.MoreVert)
    val otherNotes get() = AppIconStyle.pick(Lucide.Menu, Reicons.Menu)
    val palette get() = AppIconStyle.pick(Lucide.Palette, Reicons.Palette)
    val functions get() = AppIconStyle.pick(Lucide.SlidersHorizontal, Reicons.Tune)
    val dock get() = AppIconStyle.pick(Lucide.Pin, Reicons.VerticalAlignTop)
    val undock get() = AppIconStyle.pick(Lucide.PinOff, Reicons.PictureInPictureAlt)
    val capture get() = AppIconStyle.pick(Lucide.SquareDashed, Reicons.CropFree)
    val penSettings get() = AppIconStyle.pick(Lucide.Pencil, Reicons.Create)
    val picture get() = AppIconStyle.pick(Lucide.Image, Reicons.AddPhotoAlternate)
    val clock get() = AppIconStyle.pick(Lucide.Clock, Reicons.Clock)
    val todo get() = AppIconStyle.pick(Lucide.ListChecks, Reicons.Checklist)
    val calendar get() = AppIconStyle.pick(Lucide.Calendar, Reicons.CalendarMonth)
    val layers get() = AppIconStyle.pick(Lucide.Layers, Reicons.CropSquare)
    val editTools get() = AppIconStyle.pick(Lucide.LayoutGrid, Reicons.Tune)
    val camera get() = AppIconStyle.pick(Lucide.Camera, Reicons.Image)
    val zoom get() = AppIconStyle.pick(Lucide.ZoomIn, Reicons.ZoomOutMap)
    val web get() = AppIconStyle.pick(Lucide.Globe, Reicons.Language)
    val latency get() = AppIconStyle.pick(Lucide.Gauge, Reicons.Speed)
    val resetBar get() = AppIconStyle.pick(Lucide.LocateFixed, Reicons.FilterCenterFocus)
    val note get() = AppIconStyle.pick(Lucide.FileText, Reicons.Description)
    val ai get() = AppIconStyle.pick(Lucide.Sparkles, Reicons.AutoAwesome)
    val close get() = AppIconStyle.pick(Lucide.X, Reicons.Close)
    val settings get() = AppIconStyle.pick(Lucide.Settings, Reicons.Settings)
    val text get() = AppIconStyle.pick(Lucide.Type, Reicons.TextFields)
    val touch get() = AppIconStyle.pick(Lucide.Hand, Reicons.TouchApp)
    val appearance get() = AppIconStyle.pick(Lucide.Palette, Reicons.Palette)
    val diagnostics get() = AppIconStyle.pick(Lucide.Gauge, Reicons.BugReport)
    val dragHandle get() = AppIconStyle.pick(Lucide.Menu, Reicons.Menu)
    val hide get() = AppIconStyle.pick(Lucide.EyeOff, Reicons.VisibilityOff)
    val show get() = AppIconStyle.pick(Lucide.Plus, Reicons.Add)
    val reset get() = AppIconStyle.pick(Lucide.History, Reicons.Restore)
    val photo get() = AppIconStyle.pick(Lucide.Image, Reicons.Image)
    val tutorial get() = AppIconStyle.pick(Lucide.CircleHelp, Reicons.Help)
    val split get() = AppIconStyle.pick(Lucide.LayoutGrid, Reicons.AutoAwesomeMosaic)
}
