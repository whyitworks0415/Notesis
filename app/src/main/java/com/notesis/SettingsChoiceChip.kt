package com.notesis

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
internal fun SettingsChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    /**
     * For a chip on a coloured bar rather than on the panel: its label and
     * outline in this colour, and a wash of it when picked.
     */
    contentColor: Color? = null,
) {
    if (contentColor == null) {
        FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = modifier)
        return
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            labelColor = contentColor,
            selectedLabelColor = contentColor,
            selectedContainerColor = contentColor.copy(alpha = 0.18f),
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = contentColor.copy(alpha = 0.35f),
            selectedBorderColor = contentColor.copy(alpha = 0.6f),
        ),
    )
}
