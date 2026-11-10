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
    /**
     * Released to be installed by hand: never offered as the app opens, only
     * in About · Help. Marked in the release notes with [AppUpdate.MANUAL_MARK].
     */
    val manual: Boolean = false,
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
    /**
     * Where releases are looked for, in this order. The releases-only
     * repository comes first: the source repository can be private, and then
     * neither its API nor its downloads are open to the app. The source
     * repository is still asked, for builds released before the other existed
     * and for as long as it is public; the newest of what is found counts.
     */
    private val REPOS = listOf("whyitworks0415/Notesis-releases")
    private fun recentUrl(repo: String, page: Int) = "https://api.github.com/repos/$repo/releases?per_page=100&page=$page"

    /**
     * How a release says it is a manual update: this line anywhere in its
     * notes. An HTML comment, so the releases page does not show it; apk.yml
     * adds it when a build is released with update = manual.
     */
    const val MANUAL_MARK = "<!-- update: manual -->"
    private const val APK_TYPE = "application/vnd.android.package-archive"
    private const val TIMEOUT_MS = 15_000

    /** Set once this process has looked, so coming back to the library does not ask again. */
    @Volatile var checkedThisLaunch = false

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun autoCheck(context: Context): Boolean = prefs(context).getBoolean("autoCheck", false)
    fun setAutoCheck(context: Context, on: Boolean) = prefs(context).edit().putBoolean("autoCheck", on).apply()

    /**
     * A beta tester's copy also gets the builds released as manual updates,
     * the ones still being tried; everyone else gets only the automatic
     * ones, announced as the app opens. Turned on with the developer code.
     */
    fun isBeta(context: Context): Boolean = prefs(context).getBoolean("beta", true)
    fun setBeta(context: Context, on: Boolean) = prefs(context).edit().putBoolean("beta", on).apply()

    /**
     * Whether [code] is the developer code, letter for letter in its case;
     * only spaces at either end are let go.
     * Only its digest is kept here: it opens nothing but test builds, which
     * are public anyway, so it needs keeping out of sight rather than safe.
     */
    fun isBetaCode(code: String): Boolean = betaCodeDigest(code) == BETA_CODE_SHA256

    /** [code]'s SHA-256 in hex, spaces at either end left out. */
    fun betaCodeDigest(code: String): String =
        MessageDigest.getInstance("SHA-256").digest(code.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val BETA_CODE_SHA256 = "d8f75beea6cccc096f2657a59735bd3007af997a8762eb696f67d7df28572318"

    /** The newest of [releases] (newest first) this copy takes: any for a beta tester, else the newest automatic one. */
    fun newestFor(releases: List<AppRelease>, beta: Boolean): AppRelease? = releases.firstOrNull { beta || !it.manual }

    /** The newest build this copy takes, or null when none can be read. Network: call off the main thread. */
    fun fetchNewest(beta: Boolean): AppRelease? = newestFor(fetchRecent(), beta)

    /** A build the user said to skip is not offered again on its own; a manual check still shows it. */
    fun skipped(context: Context): Long = prefs(context).getLong("skippedCode", 0L)
    fun skip(context: Context, code: Long) = prefs(context).edit().putLong("skippedCode", code).apply()

    /** Whether the connection in use is one that is paid for by the byte, such as mobile data. */
    fun metered(context: Context): Boolean = runCatching {
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
    }.getOrDefault(true)

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
    /** Whether release notes [markdown] carry [MANUAL_MARK], however it is spaced or cased. */
    fun isManual(markdown: String): Boolean = MANUAL_PATTERN.containsMatchIn(markdown)

    private val MANUAL_PATTERN = Regex("""<!--\s*update\s*:\s*manual\s*-->""", RegexOption.IGNORE_CASE)
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    fun plainNotes(markdown: String): String = markdown.replace(COMMENT, "").lines()
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
    fun fetchLatest(): AppRelease? = fetchRecent().firstOrNull()

    /** The newest releases, newest first, each that carries an APK. Network: call off the main thread. */
    fun fetchRecent(): List<AppRelease> = mergeReleases(REPOS.map { repo ->
        collectReleasePages { page -> fetch(recentUrl(repo, page)) }
    }).take(20)

    /** GitHub's order can bury previews under normal releases; select by version only after filtering. */
    internal fun collectReleasePages(read: (Int) -> String?): List<AppRelease> {
        val found = ArrayList<AppRelease>()
        // A bounded scan prevents one manual check from fetching an unlimited history.
        for (page in 1..5) {
            val json = read(page) ?: break
            val array = runCatching { org.json.JSONArray(json) }.getOrNull() ?: break
            for (i in 0 until array.length()) {
                val release = array.optJSONObject(i) ?: continue
                runCatching { parseRelease(release) }.getOrNull()?.let(found::add)
            }
            if (array.length() < 100) break
        }
        return mergeReleases(listOf(found))
    }

    /**
     * What several repositories found as one list, newest first. A build in
     * more than one is listed once, from the first repository that has it.
     */
    fun mergeReleases(found: List<List<AppRelease>>): List<AppRelease> =
        found.flatten().distinctBy { it.versionCode }.sortedByDescending { it.versionCode }

    /**
     * Whether a check made as the app opens should offer [latest]: a build
     * released as automatic, or a manual one with an automatic build between
     * it and what is installed - that one was due to be offered anyway, and
     * the newest is what it brings.
     */
    fun shouldNotify(latest: AppRelease, installed: Long, recent: () -> List<AppRelease>): Boolean {
        if (latest.versionCode <= installed) return false
        if (!latest.manual) return true
        return recent().any { !it.manual && it.versionCode > installed }
    }

    private fun fetch(address: String): String? = runCatching {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Notesis")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    internal fun parseRelease(release: JSONObject): AppRelease? {
        if (release.optBoolean("draft")) return null
        val tag = release.optString("tag_name")
        if (!isLabReleaseTag(tag)) return null
        val (name, code) = parseTag(tag.removePrefix("lab-").removeSuffix("-preview")) ?: return null
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
            // A pre-release on GitHub is a test build as well as one marked in its notes.
            manual = release.optBoolean("prerelease") || isManual(release.optString("body")),
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
    var ready by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) {
        if (AppUpdate.checkedThisLaunch || !AppUpdate.autoCheck(context)) return@LaunchedEffect
        AppUpdate.checkedThisLaunch = true
        val beta = AppUpdate.isBeta(context)
        val latest = withContext(Dispatchers.IO) { AppUpdate.fetchNewest(beta) } ?: return@LaunchedEffect
        val installed = AppUpdate.installedCode(context)
        if (latest.versionCode <= installed) {
            withContext(Dispatchers.IO) { AppUpdate.clearDownloads(context) }
        } else if (latest.versionCode != AppUpdate.skipped(context)) {
            // A manual release waits in About · Help, unless an automatic one is
            // due; a copy that is not a beta tester's was only shown automatic ones.
            val offer = !beta || withContext(Dispatchers.IO) {
                AppUpdate.shouldNotify(latest, installed) { AppUpdate.fetchRecent() }
            }
            if (offer) {
                // Fetched before it is offered, on an unmetered connection, so
                // Update goes straight to the installer.
                if (!AppUpdate.metered(context)) {
                    ready = withContext(Dispatchers.IO) {
                        runCatching { AppUpdate.download(context, latest) {} }.getOrNull()
                    }
                }
                found = latest
            }
        }
    }
    found?.let { release ->
        UpdateDialog(release, ready = ready, onDismiss = { found = null }, onSkip = {
            AppUpdate.skip(context, release.versionCode)
            found = null
        })
    }
}

/** What the release brings, then the download and the hand-off to the installer. */
@Composable
internal fun UpdateDialog(
    release: AppRelease,
    onDismiss: () -> Unit,
    onSkip: (() -> Unit)? = null,
    /** The APK, when it was fetched before the dialog opened. */
    ready: File? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(if (ready != null) 1f else null) }
    var downloaded by remember { mutableStateOf(ready) }
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
            Column(verticalArrangement = Arrangement.spacedBy(Ui.Space.s)) {
                Text(
                    "Installed ${AppUpdate.installedName(context)} · New ${release.versionName} (${release.versionCode})" +
                        if (release.apkSize > 0) " · %.1fMB".format(release.apkSize / 1_048_576f) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    var beta by remember { mutableStateOf(AppUpdate.isBeta(context)) }
    var askingCode by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<AppRelease?>(null) }
    found?.let { UpdateDialog(it, onDismiss = { found = null }) }
    // Looked for as this opens, without a dialog: a manual update is only
    // ever offered here, so here says when there is one.
    var waiting by remember { mutableStateOf<AppRelease?>(null) }
    LaunchedEffect(beta) {
        waiting = null
        val latest = withContext(Dispatchers.IO) { AppUpdate.fetchNewest(beta) } ?: return@LaunchedEffect
        if (latest.versionCode > AppUpdate.installedCode(context)) waiting = latest
    }

    Text(
        "Notesis Lab ${AppUpdate.installedName(context)} (${AppUpdate.installedCode(context)})",
        style = MaterialTheme.typography.titleSmall,
    )
    waiting?.let { release ->
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "Version ${release.versionName} is ready" + if (release.manual) " (manual update)" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { found = release }) { Text("Update") }
        }
    }
    Row {
        TextButton(enabled = !checking, onClick = {
            checking = true
            status = null
            scope.launch {
                val latest = withContext(Dispatchers.IO) { AppUpdate.fetchNewest(beta) }
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
        Text("Tell me about updates when the app opens", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        SkinSwitch(checked = auto, onCheckedChange = { auto = it; AppUpdate.setAutoCheck(context, it) })
    }
    if (beta) {
        Text(
            "Beta tester: test builds released as manual updates are never announced; they wait here until you update.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { beta = false; AppUpdate.setBeta(context, false) }) { Text("Stop getting test builds") }
    } else {
        Text(
            "Updates are announced when the app opens. With a developer code this copy also gets the test builds released as manual updates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { askingCode = true }) { Text("Enter developer code") }
    }
    if (askingCode) {
        var code by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askingCode = false },
            title = { Text("Developer code") },
            text = {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; wrong = false },
                    singleLine = true,
                    isError = wrong,
                    label = { Text("Code") },
                    supportingText = if (wrong) { { Text("That code isn't right") } } else null,
                )
            },
            confirmButton = {
                TextButton(enabled = code.isNotBlank(), onClick = {
                    if (AppUpdate.isBetaCode(code)) {
                        AppUpdate.setBeta(context, true)
                        beta = true
                        askingCode = false
                    } else wrong = true
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { askingCode = false }) { Text("Cancel") } },
        )
    }
}
