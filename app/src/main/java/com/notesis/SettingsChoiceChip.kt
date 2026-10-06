package com.notesis

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Observe presses without stealing the chip/button's own click gesture. */
@Composable
internal fun Modifier.settingsPressHighlight(): Modifier {
    var press by remember { mutableIntStateOf(0) }
    return this.spotiGlassMorph(press).pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            press++
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
internal fun SettingsChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) },
        modifier = modifier.settingsPressHighlight())
}
