package com.notesis

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID

/**
 * What a bug report needs from this app, and nothing from the notes: a stable
 * random id to quote, the build and device, and the tail of the crash log.
 */
internal object Diagnostics {
    fun id(context: Context): String {
        val prefs = context.getSharedPreferences("pens", Context.MODE_PRIVATE)
        return prefs.getString("diagnosticId", null) ?: UUID.randomUUID().toString().take(8).uppercase()
            .also { prefs.edit().putString("diagnosticId", it).apply() }
    }

    fun report(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val crash = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash.log")
        val tail = if (crash.isFile) crash.readText().takeLast(MAX_LOG_CHARS) else "(오류 기록 없음)"
        return buildString {
            appendLine("진단 ID: ${id(context)}")
            appendLine("앱: ${info?.versionName} (${info?.longVersionCode})")
            appendLine("기기: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("---- 최근 오류 기록 ----")
            append(tail)
        }
    }

    fun share(context: Context) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Notesis 진단 ${id(context)}")
            putExtra(Intent.EXTRA_TEXT, report(context))
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "진단 정보 공유")) }
    }

    private const val MAX_LOG_CHARS = 20_000
}

/** Diagnostics to share, the id to quote, how to take the system's own report, and a short guide. */
@Composable
internal fun HelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val id = remember { Diagnostics.id(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("도움말 · 진단") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("진단 ID $id", style = MaterialTheme.typography.titleSmall)
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(id)) }) { Text("ID 복사") }
                    TextButton(onClick = { Diagnostics.share(context) }) { Text("진단 정보 공유") }
                }
                Text("진단 정보에는 앱 버전, 기기, 최근 오류 기록만 들어가며 노트 내용은 들어가지 않습니다.",
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("시스템 버그 리포트", style = MaterialTheme.typography.titleSmall)
                Text("앱 진단과 별개로, 기기 전체의 기록이 필요하면 설정 › 휴대전화 정보 › 소프트웨어 정보에서 빌드 번호를 일곱 번 눌러 " +
                    "개발자 옵션을 켠 뒤, 개발자 옵션 › 버그 신고 받기로 만들 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("자주 쓰는 동작", style = MaterialTheme.typography.titleSmall)
                Text(HELP_TEXT, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

private val HELP_TEXT = """
• 두 손가락 두 번 탭: 실행 취소 · 세 손가락 두 번 탭: 다시 실행 (손가락·제스처에서 변경)
• 세 손가락 위로: 참조 패널 · 아래로: 닫기
• 올가미: 이동·크기·회전, 복사·잘라내기·붙여넣기, 그룹·잠금, 링크, 라이브러리에 추가
• 원을 그리고 멈추기: 올가미로 전환 (펜 설정에서 켜기)
• 획 끝에서 멈추기: 도형으로 (펜 설정에서 켜기)
• 마지막 페이지에서 더 당기기: 새 페이지
• Ctrl+Z / Ctrl+Shift+Z 실행 취소·다시 실행, Ctrl+1~9 도구, Ctrl+Alt+G 쪽 이동, Ctrl+F 검색
• 홈: Ctrl+N 새 노트, Ctrl+Shift+N 새 폴더
""".trim()
