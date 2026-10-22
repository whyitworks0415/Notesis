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
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose {
            recognizer?.destroy()
        }
    }
    AlertDialog(
        onDismissRequest = { listening = false; recognizer?.cancel(); onDismiss() },
        title = { Text("Dictation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!available) {
                    Text("This device has no speech recognition service.")
                    return@Column
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    DICTATION_LANGUAGES.forEach { (tag, label) ->
                        SettingsChoiceChip(selected = language == tag, onClick = { if (!listening) language = tag },
                            label = label, modifier = Modifier.padding(end = Ui.Space.s))
                    }
                }
                Row {
                    TextButton(onClick = {
                        if (listening) {
                            listening = false
                            recognizer?.stopListening()
                        } else {
                            listening = true
                            recognizer?.startListening(intent())
                        }
                    }) { Text(if (listening) "■ Stop" else "● Start listening") }
                    TextButton(enabled = transcript.isNotEmpty() && !listening, onClick = { transcript = "" }) { Text("Clear") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(transcript.ifEmpty { if (listening) "Listening…" else "What you say appears here." },
                    style = MaterialTheme.typography.bodyMedium)
                if (partial.isNotBlank()) Text(partial, color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = transcript.isNotBlank(), onClick = {
                listening = false
                recognizer?.cancel()
                onInsert(transcript)
            }) { Text("Add to page") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = transcript.isNotBlank(), onClick = { onCopy(transcript) }) { Text("Copy") }
                TextButton(onClick = { listening = false; recognizer?.cancel(); onDismiss() }) { Text("Close") }
            }
        },
    )
}
