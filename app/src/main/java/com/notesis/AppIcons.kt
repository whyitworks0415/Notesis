package com.notesis

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The toolbar's icons, in whichever set [AppIconStyle] has chosen. Reading one
 * reads that choice, so the bar redraws when the set is switched.
 */
internal object AppIcons {
    val read: ImageVector get() = Reicons.TouchApp
    // The pen is a nib and the pencil a pencil, so the two read apart at a glance;
    // the pen used to wear the pencil and the pencil a nib.
    val penTool: ImageVector get() = Reicons.Create
    val pencilTool: ImageVector get() = Reicons.Brush
    val highlighterTool: ImageVector get() = Reicons.Highlight

    val back get() = Reicons.ArrowBack
    val search get() = Reicons.Search
    val undo get() = Reicons.Undo
    val redo get() = Reicons.Redo
    val mic get() = Reicons.Mic
    val rotate get() = Reicons.ScreenRotation
    val tools get() = Reicons.Calculate
    val input get() = Reicons.TouchApp
    val addPage get() = Reicons.Add
    val noteMenu get() = Reicons.MoreVert
    val otherNotes get() = Reicons.Menu
    val palette get() = Reicons.Palette
    val functions get() = Reicons.Tune
    val dock get() = Reicons.VerticalAlignTop
    val undock get() = Reicons.PictureInPictureAlt
    val capture get() = Reicons.CropFree
    val penSettings get() = Reicons.Create
    val picture get() = Reicons.AddPhotoAlternate
    val clock get() = Reicons.Clock
    val todo get() = Reicons.Checklist
    val calendar get() = Reicons.CalendarMonth
    val layers get() = Reicons.CropSquare
    val editTools get() = Reicons.Tune
    val camera get() = Reicons.Image
    val zoom get() = Reicons.ZoomOutMap
    val web get() = Reicons.Language
    val latency get() = Reicons.Speed
    val resetBar get() = Reicons.FilterCenterFocus
    val note get() = Reicons.Description
    val ai get() = Reicons.AutoAwesome
    val close get() = Reicons.Close
    val settings get() = Reicons.Settings
    val text get() = Reicons.TextFields
    val touch get() = Reicons.TouchApp
    val appearance get() = Reicons.Palette
    val diagnostics get() = Reicons.BugReport
    val dragHandle get() = Reicons.Menu
    val hide get() = Reicons.VisibilityOff
    val show get() = Reicons.Add
    val reset get() = Reicons.Restore
    val photo get() = Reicons.Image
    val tutorial get() = Reicons.Help
    val split get() = Reicons.AutoAwesomeMosaic
}
