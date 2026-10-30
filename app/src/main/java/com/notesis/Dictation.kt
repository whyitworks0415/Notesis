package com.notesis

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Languages offered for dictation: BCP 47 tags the device's recogniser understands. */
internal val DICTATION_LANGUAGES = listOf(
    "ko-KR" to "한국어", "en-US" to "English", "ja-JP" to "日本語", "zh-CN" to "中文", "de-DE" to "Deutsch",
    "fr-FR" to "Français", "es-ES" to "Español", "it-IT" to "Italiano", "pt-BR" to "Português", "ru-RU" to "Русский",
    "vi-VN" to "Tiếng Việt", "th-TH" to "ไทย", "id-ID" to "Indonesia", "ar-SA" to "العربية", "hi-IN" to "हिन्दी",
    "tr-TR" to "Türkçe", "pl-PL" to "Polski", "nl-NL" to "Nederlands", "uk-UA" to "Українська", "sv-SE" to "Svenska",
)

/**
 * Speech to text with the device's own recogniser, for as long as it is left
 * listening: each finished phrase is added to the transcript and listening
 * starts again. The transcript can be put on the page as a text box or copied.
 *
 * ponytail: transcribes live speech only; a saved recording cannot be fed to
 * the platform recogniser on most devices. Transcribing files needs a speech
 * model of our own or a service.
 */
@Composable
internal fun DictationDialog(onInsert: (String) -> Unit, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    var language by remember { mutableStateOf(DICTATION_LANGUAGES.first().first) }
    var listening by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // How loud the voice is now, 0 to 1, for the button's rings.
    var level by remember { mutableFloatStateOf(0f) }
    val recognizer = remember { if (available) SpeechRecognizer.createSpeechRecognizer(context) else null }
    fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) transcript = (transcript + " " + text).trim()
                partial = ""
                // Keep listening until told to stop: a pause ends a phrase, not the dictation.
                if (listening) recognizer.startListening(intent())
            }
            override fun onPartialResults(results: Bundle?) {
                partial = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onError(code: Int) {
                if (listening && (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    recognizer.startListening(intent())
                    return
                }
                listening = false
                error = when (code) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Check your network connection"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "This language isn't supported on this device"
                    else -> "Dictation stopped ($code)"
                }
            }
            override fun onReadyForSpeech(params: Bundle?) { error = null }
            override fun onBeginningOfSpeech() = Unit
            // Roughly -2 dB in silence to 10 dB for speech, on most recognisers.
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose {
            recognizer?.destroy()
        }
    }
    val panel = rememberToolPanelState()
    fun close() {
        listening = false
        recognizer?.cancel()
        panel.close(onDismiss)
    }
    // The microphone's own panel: the bar's glass, grown out of where it was opened.
    ToolPanel(
        title = "Dictation",
        state = panel,
        onOutside = ::close,
        maxWidth = 480.dp,
        actions = {
            IconButton(onClick = ::close) { Icon(Reicons.Close, contentDescription = "Close") }
        },
    ) {
        if (!available) {
            Text("This device has no speech recognition service.")
            return@ToolPanel
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            DICTATION_LANGUAGES.forEach { (tag, label) ->
                SettingsChoiceChip(selected = language == tag, onClick = { if (!listening) language = tag },
                    label = label, modifier = Modifier.padding(end = Ui.Space.s))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            VoiceRecordButton(
                active = listening,
                label = if (listening) "Stop" else "Start listening",
                level = if (listening) level else 0f,
                onClick = {
                    if (listening) {
                        listening = false
                        level = 0f
                        recognizer?.stopListening()
                    } else {
                        listening = true
                        recognizer?.startListening(intent())
                    }
                },
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(transcript.ifEmpty { if (listening) "Listening…" else "What you say appears here." },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
        if (partial.isNotBlank()) Text(partial, color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = transcript.isNotEmpty() && !listening, onClick = { transcript = "" }) { Text("Clear") }
            TextButton(enabled = transcript.isNotBlank(), onClick = { onCopy(transcript) }) { Text("Copy") }
            TextButton(enabled = transcript.isNotBlank(), onClick = {
                listening = false
                recognizer?.cancel()
                val text = transcript
                panel.close { onInsert(text) }
            }) { Text("Add to page") }
        }
    }
}
