package com.notesis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush as Gradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The chrome, without a tablet.
 *
 * Everything the look settings can do to a control is a pure composable away
 * from the ink engine, so it can be looked at in Android Studio's preview pane
 * - split view, no emulator, no device, redrawn as the file is edited. That is
 * the only kind of testing this app has that does not need hardware: latency
 * and the pen cannot be judged anywhere but on the tablet, and colour, contrast
 * and layout barely need to leave the laptop.
 *
 * Each preview is one setting, so a change that only looks right in one of
 * them shows up as soon as it is made rather than on the next install.
 */
@Preview(name = "Controls · Material", widthDp = 420, heightDp = 340)
@Composable
private fun PreviewMaterial() = ChromeSample(SkinSettings())

@Preview(name = "Controls · High contrast", widthDp = 420, heightDp = 340)
@Composable
private fun PreviewHighContrast() = ChromeSample(SkinSettings(highContrast = true))

@Preview(name = "Controls · Green accent", widthDp = 420, heightDp = 340)
@Composable
private fun PreviewGreenAccent() = ChromeSample(SkinSettings(accent = 0xFF00A03C.toInt(), dynamicColor = false))

@Preview(name = "Controls · Deep ink", widthDp = 420, heightDp = 340)
@Composable
private fun PreviewInk() = ChromeSample(
    SkinSettings(accent = 0xFF0A84FF.toInt(), content = 0xFF10314F.toInt(), dynamicColor = false),
)

@Preview(name = "Controls · Dark mode", widthDp = 420, heightDp = 340)
@Composable
private fun PreviewDark() = ChromeSample(SkinSettings(themeMode = AppThemeMode.DARK))

/**
 * One panel with one of everything the settings screen can change: the two
 * controls, a filled button and an outlined one, and two icons - the tool in
 * hand and one that is not.
 */
@Composable
private fun ChromeSample(look: SkinSettings) {
    ProvideSkin(look) {
        MaterialTheme(
            colorScheme = skinColors(lookScheme(null, look), look),
            shapes = Ui.shapes,
        ) {
            var amount by remember { mutableFloatStateOf(0.42f) }
            var on by remember { mutableStateOf(true) }
            // Something busy behind, so the panel's edge and shadow can be judged.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Gradient.linearGradient(
                            listOf(Color(0xFF6A5AE0), Color(0xFF39C3C9), Color(0xFFF2A65A)),
                        ),
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                SkinSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Reicons.Edit, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Icon(
                                Reicons.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Width", style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.height(6.dp))
                        SkinSlider(
                            value = amount,
                            onValueChange = { amount = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Pressure", style = MaterialTheme.typography.bodyLarge)
                            SkinSwitch(on) { on = it }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {}) { Text("Apply") }
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(onClick = {}) {
                                Icon(Reicons.Delete, contentDescription = null)
                                Text(" Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}
