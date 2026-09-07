package com.notesis

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** 실제 화면에서 세 컨트롤을 함께 사용하는 예시입니다. */
@Composable
fun LiquidGlassControlsExample() {
    var volume by remember { mutableFloatStateOf(0.45f) }
    var section by remember { mutableIntStateOf(0) }

    LiquidGlassPreviewFrame {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.84f)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            LiquidSegmentedControl(
                segments = listOf("추천", "보관함", "최근"),
                selectedIndex = section,
                onSelected = { section = it },
                modifier = Modifier.fillMaxWidth(),
            )
            LiquidGlassSlider(
                value = volume,
                onValueChange = { volume = it },
                label = "볼륨",
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LiquidGlassButton(onClick = {}) { Text("완료") }
            }
        }
    }
}

@Preview(name = "Liquid controls · Light", widthDp = 600, heightDp = 420)
@Preview(
    name = "Liquid controls · Dark",
    widthDp = 600,
    heightDp = 420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LiquidGlassControlsExamplePreview() {
    LiquidGlassPreviewTheme { MaterialTheme { LiquidGlassControlsExample() } }
}
