package com.notesis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One build on the repository's releases page, as far as updating needs it. */
internal data class AppRelease(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex, when GitHub reported a digest for the file. */
    val sha256: String?,
    val notes: String,
)

/**
 * Updating an APK installed from GitHub without going back to the browser:
 * ask the releases API for the newest build, fetch its APK into the cache, and
 * hand it to the system installer, which still asks the user to confirm.
 *
 * Every release is tagged `v<versionName>-<versionCode>` by apk.yml, so the
 * tag alone says whether it is newer than what is running.
 */
internal object AppUpdate {
    private const val LATEST_URL = "https://api.github.com/repos/whyitworks0415/Notesis/releases/latest"
    private const val APK_TYPE = "application/vnd.android.package-archive"
    private const val TIMEOUT_MS = 15_000

    /** Set once this process has looked, so coming back to the library does not ask again. */
    @Volatile var checkedThisLaunch = false

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun autoCheck(context: Context): Boolean = prefs(context).getBoolean("autoCheck", true)
    fun setAutoCheck(context: Context, on: Boolean) = prefs(context).edit().putBoolean("autoCheck", on).apply()

    /** A build the user said to skip is not offered again on its own; a manual check still shows it. */
    fun skipped(context: Context): Long = prefs(context).getLong("skippedCode", 0L)
    fun skip(context: Context, code: Long) = prefs(context).edit().putLong("skippedCode", code).apply()

    fun installedCode(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)

    fun installedName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

    /** `v0.45.2-113` → ("0.45.2", 113); anything else is not one of ours. */
    fun parseTag(tag: String): Pair<String, Long>? {
        val match = TAG.matchEntire(tag.trim()) ?: return null
        val code = match.groupValues[2].toLongOrNull() ?: return null
        return match.groupValues[1] to code
    }

    private val TAG = Regex("""v?(.+)-(\d+)""")

    /** The release notes are Markdown; the dialog shows them as plain lines. */
    fun plainNotes(markdown: String): String = markdown.lines()
        .dropWhile { it.isBlank() }
        .let { lines -> if (lines.firstOrNull()?.startsWith("# ") == true) lines.drop(1) else lines }
        .map { line ->
            val bare = line.replace("**", "").replace("`", "")
            val indent = bare.takeWhile { it == ' ' }
            val rest = bare.drop(indent.length)
            when {
                rest.startsWith("#") -> rest.trimStart('#').trim()
                rest.startsWith("- ") -> indent + "• " + rest.drop(2)
                else -> bare
            }
        }
        .joinToString("\n")
        .trim()

    /** The newest release, or null when it cannot be read or carries no APK. Network: call off the main thread. */
    fun fetchLatest(): AppRelease? = runCatching {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Notesis")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            parseRelease(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun parseRelease(json: String): AppRelease? {
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val (name, code) = parseTag(release.optString("tag_name")) ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        return AppRelease(
            versionName = name,
            versionCode = code,
            apkUrl = apk.optString("browser_download_url").ifBlank { return null },
            apkSize = apk.optLong("size"),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
            notes = plainNotes(release.optString("body")),
        )
    }

    private fun updateDir(context: Context) = File(context.cacheDir, "updates")

    /** Drops an APK left behind by an earlier update, once nothing is waiting to be installed. */
    fun clearDownloads(context: Context) {
        updateDir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Fetches the release's APK, checking its size, digest and that it really
     * is a newer Notesis before it is handed on. Network: call off the main thread.
     */
    fun download(context: Context, release: AppRelease, onProgress: (Float) -> Unit): File {
        val dir = updateDir(context).apply { mkdirs() }
        clearDownloads(context)
        val target = File(dir, "Notesis-${release.versionName}-${release.versionCode}.apk")
        val partial = File(dir, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "Notesis")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("Server responded ${connection.responseCode}")
            val total = release.apkSize.takeIf { it > 0 } ?: connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (release.apkSize > 0 && read != release.apkSize) error("The downloaded file size doesn't match")
        } catch (e: Throwable) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && hex != release.sha256) {
            partial.delete()
            error("The file is corrupted")
        }
        val archive = context.packageManager.getPackageArchiveInfo(partial.path, 0)
        if (archive == null || archive.packageName != context.packageName || archive.longVersionCode <= installedCode(context)) {
            partial.delete()
            error("Not a new version of Notesis")
        }
        if (!partial.renameTo(target)) error("Couldn't save the file")
        return target
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where "install unknown apps" is allowed for Notesis. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Opens the system installer on [apk]; it asks the user, then replaces this app with its data kept. */
    fun install(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

/**
 * The check made as the library opens: once per launch, only when turned on,
 * and silent unless there is a newer build the user has not skipped.
 */
@Composable
internal fun LaunchUpdateCheck() {
    val context = LocalContext.current
    var found by remember { mutableStateOf<AppRelease?>(null) }
    LaunchedEffect(Unit) {
        if (AppUpdate.checkedThisLaunch || !AppUpdate.autoCheck(context)) return@LaunchedEffect
        AppUpdate.checkedThisLaunch = true
        val latest = withContext(Dispatchers.IO) { AppUpdate.fetchLatest() } ?: return@LaunchedEffect
        val installed = AppUpdate.installedCode(context)
        if (latest.versionCode <= installed) {
            withContext(Dispatchers.IO) { AppUpdate.clearDownloads(context) }
        } else if (latest.versionCode != AppUpdate.skipped(context)) {
            found = latest
        }
    }
    found?.let { release ->
        UpdateDialog(release, onDismiss = { found = null }, onSkip = {
            AppUpdate.skip(context, release.versionCode)
            found = null
        })
    }
}

/** What the release brings, then the download and the hand-off to the installer. */
@Composable
internal fun UpdateDialog(release: AppRelease, onDismiss: () -> Unit, onSkip: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    val downloading = progress != null && downloaded == null && failure == null

    fun installNow(apk: File) {
        if (!AppUpdate.canInstall(context)) {
            needsPermission = true
            AppUpdate.openInstallPermission(context)
            return
        }
        needsPermission = false
        if (!AppUpdate.install(context, apk)) failure = "Couldn't open the installer"
    }

    fun start() {
        failure = null
        progress = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdate.download(context, release) { progress = it } }
            }
            result.onSuccess { downloaded = it; installNow(it) }
                .onFailure { failure = it.message?.takeIf { m -> m.isNotBlank() } ?: "Download failed" }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("New version ${release.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Installed ${AppUpdate.installedName(context)} · New ${release.versionName} (${release.versionCode})" +
                        if (release.apkSize > 0) " · %.1fMB".format(release.apkSize / 1_048_576f) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    )
                }
                progress?.let { p ->
                    if (downloaded == null && failure == null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (needsPermission) {
                    Text(
                        "Turn on \"Allow from this source\" in the settings screen that opened, then come back and tap Install. This is needed only the first time.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text(
                    "Your notes and settings stay as they are. Tap Update once on the install screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            val apk = downloaded
            when {
                apk != null -> TextButton(onClick = { installNow(apk) }) { Text("Install") }
                downloading -> TextButton(onClick = {}, enabled = false) { Text("Downloading") }
                failure != null -> TextButton(onClick = ::start) { Text("Try again") }
                else -> TextButton(onClick = ::start) { Text("Update") }
            }
        },
        dismissButton = {
            Row {
                if (onSkip != null && progress == null) TextButton(onClick = onSkip) { Text("Skip this version") }
                TextButton(onClick = onDismiss, enabled = !downloading) { Text("Later") }
            }
        },
    )
}

/** The version line, a manual check and the automatic-check switch, for the help dialog. */
@Composable
internal fun AppVersionSection(onLicenses: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(AppUpdate.autoCheck(context)) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<AppRelease?>(null) }
    found?.let { UpdateDialog(it, onDismiss = { found = null }) }

    Text(
        "Notesis ${AppUpdate.installedName(context)} (${AppUpdate.installedCode(context)})",
        style = MaterialTheme.typography.titleSmall,
    )
    Row {
        TextButton(enabled = !checking, onClick = {
            checking = true
            status = null
            scope.launch {
                val latest = withContext(Dispatchers.IO) { AppUpdate.fetchLatest() }
                checking = false
                when {
                    latest == null -> status = "Couldn't check for updates. Check your internet connection."
                    latest.versionCode > AppUpdate.installedCode(context) -> found = latest
                    else -> status = "You're up to date"
                }
            }
        }) { Text(if (checking) "Checking…" else "Check for updates") }
        TextButton(onClick = onLicenses) { Text("Open-source licenses") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Check for updates when the app opens", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        SkinSwitch(checked = auto, onCheckedChange = { auto = it; AppUpdate.setAutoCheck(context, it) })
    }
}
