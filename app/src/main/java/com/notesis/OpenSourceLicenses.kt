package com.notesis

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One piece of someone else's work inside the APK, and where its license text lives in assets. */
internal data class OpenSourceComponent(
    val name: String,
    val owner: String,
    val license: String,
    val url: String,
    /** Asset paths whose text, joined in order, is what the license asks to be shown. */
    val texts: List<String>,
)

private const val APACHE = "licenses/Apache-2.0.txt"

/**
 * Everything bundled that asks for its notice to travel with it. A library
 * added to app/build.gradle.kts, or a source ported into the app, gets a row
 * here in the same change.
 */
internal val OPEN_SOURCE_COMPONENTS = listOf(
    OpenSourceComponent(
        "AndroidX · Jetpack Compose · Ink · Motion Prediction",
        "The Android Open Source Project",
        "Apache License 2.0",
        "https://developer.android.com/jetpack/androidx",
        listOf(APACHE),
    ),
    OpenSourceComponent(
        "Kotlin · kotlinx.coroutines",
        "JetBrains s.r.o. and Kotlin contributors",
        "Apache License 2.0",
        "https://github.com/JetBrains/kotlin",
        listOf(APACHE),
    ),
    OpenSourceComponent(
        "PdfBox-Android 2.0.27.0",
        "Tom Roush · The Apache Software Foundation",
        "Apache License 2.0",
        "https://github.com/TomRoush/PdfBox-Android",
        listOf("licenses/PdfBox-NOTICE.txt", APACHE),
    ),
    OpenSourceComponent(
        "Bouncy Castle 1.72",
        "The Legion of the Bouncy Castle Inc.",
        "MIT (Bouncy Castle License)",
        "https://www.bouncycastle.org",
        listOf("licenses/BouncyCastle.txt"),
    ),
    OpenSourceComponent(
        "Backdrop 1.0.6",
        "Kyant",
        "Apache License 2.0",
        "https://github.com/Kyant0/AndroidLiquidGlass",
        listOf(APACHE),
    ),
    OpenSourceComponent(
        "liquid_glass_easy 4.3.2 (ported to Kotlin · AGSL)",
        "Ahmed Gamil",
        "MIT",
        "https://github.com/AhmeedGamil/liquid_glass_easy",
        listOf("spotiglass/upstream/liquid_glass_easy.LICENSE"),
    ),
    OpenSourceComponent(
        "SpotiFLAC-Mobile (glass bar design ported)",
        "zarzet",
        "MIT",
        "https://github.com/spotiflacapp/SpotiFLAC-Mobile",
        listOf("spotiglass/upstream/SpotiFLAC.LICENSE"),
    ),
    OpenSourceComponent(
        "AnimateIcons 0.12.1 icons (ported to Kotlin) · Lucide",
        "Avijit Dey · Lucide Icons and Contributors · Cole Bemis",
        "MIT · ISC",
        "https://animateicons.in",
        listOf("animateicons/LICENSE", "animateicons/THIRD_PARTY_NOTICES.md"),
    ),
    OpenSourceComponent(
        "Reicon 1.2.5 icons",
        "REICON",
        "MIT",
        "https://github.com/dqev/reicon",
        listOf("reicon/NOTICE", "reicon/LICENSE"),
    ),
    OpenSourceComponent(
        "Document viewer: @rhwp/core · @web-ppt/core · marked · xlsx · DOMPurify · fflate",
        "Edward Kim · MarkedJS · SheetJS · Cure53 and others",
        "MIT · Apache License 2.0 · MPL 2.0",
        "https://www.npmjs.com/package/@rhwp/core",
        listOf("viewer/THIRD_PARTY_NOTICES.txt", "viewer/viewer.js.LEGAL.txt"),
    ),
)

/** Google's on-device recognizer is not open source; it comes under its own terms, linked rather than reproduced. */
private const val ML_KIT_NOTE =
    "ML Kit Digital Ink Recognition, used for handwriting search, is not open source and is covered by Google's ML Kit terms of service " +
        "(https://developers.google.com/ml-kit/terms)."

internal fun licenseText(context: Context, component: OpenSourceComponent): String =
    component.texts.joinToString("\n\n────────\n\n") { path ->
        runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
            .getOrDefault("(Couldn't read $path)")
            .trim()
    }

@Composable
internal fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    var open by remember { mutableStateOf<OpenSourceComponent?>(null) }
    open?.let { LicenseTextDialog(it, onDismiss = { open = null }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licenses") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Notesis includes the open-source software below. Tap an item to read its full license.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OPEN_SOURCE_COMPONENTS.forEach { component ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { open = component }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(component.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${component.owner} · ${component.license}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.height(8.dp))
                Text(ML_KIT_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun LicenseTextDialog(component: OpenSourceComponent, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(component) { licenseText(context, component) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(component.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(component.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
