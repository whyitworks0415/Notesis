package com.notesis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A small, offline Markdown editor for notes that remain portable as note.md. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownNoteScreen(store: NoteStore, note: NoteMeta, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember(note.id) { mutableStateOf("") }
    var loaded by remember(note.id) { mutableStateOf(false) }
    var preview by remember(note.id) { mutableStateOf(false) }
    var dirty by remember(note.id) { mutableStateOf(false) }
    var saveMessage by remember(note.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(note.id) {
        store.loadMarkdownLater(note.id) { result ->
            result.onSuccess { text = it; loaded = true }
                .onFailure { loaded = true; saveMessage = "Markdown 파일을 읽지 못했습니다" }
        }
    }
    fun save() {
        if (!loaded || !dirty) return
        dirty = false
        store.saveMarkdownLater(note.id, note.title, text) { result ->
            result.onFailure { saveMessage = "저장하지 못했습니다" }
        }
    }
    LaunchedEffect(text, loaded) {
        if (!loaded) return@LaunchedEffect
        delay(900)
        save()
    }
    BackHandler {
        save()
        onBack()
    }

    Scaffold(
        topBar = {
            SkinSurface(flush = true) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { save(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로가기")
                    } },
                    title = {
                        Column {
                            Text(note.title, maxLines = 1)
                            Text(if (dirty) "Markdown · 저장 대기" else "Markdown · note.md",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    },
                    actions = {
                        IconButton(onClick = { preview = !preview }) {
                            Icon(if (preview) Icons.Default.Edit else Icons.Default.Visibility,
                                if (preview) "편집" else "미리보기")
                        }
                        TextButton(onClick = { save() }, enabled = dirty) { Text("저장") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (preview) {
            MarkdownPreview(text, Modifier.fillMaxSize().padding(padding))
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                MarkdownToolbar { insertion ->
                    text += insertion
                    dirty = true
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; dirty = true },
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                    placeholder = { Text("# 제목\n\n여기에 Markdown으로 작성하세요…") },
                )
            }
        }
    }
    saveMessage?.let { message ->
        AlertDialog(onDismissRequest = { saveMessage = null }, title = { Text(message) },
            confirmButton = { TextButton(onClick = { saveMessage = null }) { Text("확인") } })
    }
}

@Composable
private fun MarkdownToolbar(onInsert: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("# " to "제목", "**굵게**" to "굵게", "- " to "목록", "- [ ] " to "체크", "```\n\n```" to "코드", "[링크](url)" to "링크")
            .forEach { (syntax, label) -> TextButton(onClick = { onInsert("\n$syntax") }) { Text(label) } }
    }
}

@Composable
private fun MarkdownPreview(text: String, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        markdownLines(text).forEach { line ->
            when {
                line.startsWith("### ") -> Text(line.drop(4), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                line.startsWith("## ") -> Text(line.drop(3), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                line.startsWith("# ") -> Text(line.drop(2), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                line.startsWith("- [ ] ") -> Text("☐ ${line.drop(6)}", fontSize = 17.sp)
                line.startsWith("- [x] ", true) -> Text("☑ ${line.drop(6)}", fontSize = 17.sp)
                line.startsWith("- ") || line.startsWith("* ") -> Text("• ${line.drop(2)}", fontSize = 17.sp)
                line.startsWith("> ") -> Text(line.drop(2), color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .08f)).padding(8.dp))
                line.startsWith("    ") -> Text(line.trimStart(), fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp))
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                else -> Text(inlineMarkdown(line, MaterialTheme.colorScheme.primary), fontSize = 17.sp)
            }
        }
    }
}

private fun markdownLines(text: String): List<String> = text.replace("\r\n", "\n").split('\n')

private fun inlineMarkdown(line: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val regex = Regex("(\\*\\*.+?\\*\\*|`.+?`|\\[.+?\\]\\(.+?\\))")
    regex.findAll(line).forEach { match ->
        append(line.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.trim('*')) }
            token.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace,
                background = Color.LightGray.copy(alpha = .35f))) { append(token.trim('`')) }
            else -> withStyle(SpanStyle(color = linkColor,
                textDecoration = TextDecoration.Underline)) { append(token.substringAfter('[').substringBefore(']')) }
        }
        cursor = match.range.last + 1
    }
    append(line.substring(cursor))
}
