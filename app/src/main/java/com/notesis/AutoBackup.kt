package com.notesis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Where automatic backups go. */
internal enum class BackupTarget {
    /** No automatic backup - the default. */
    OFF,
    /** The app's own folder on this tablet, the last few kept. */
    DEVICE,
    /** A folder picked through the system picker: on the tablet, or a cloud app that offers folders. */
    FOLDER,
    /** One file picked in Google Drive (or any app that saves files), written over each time. */
    DRIVE,
}

/**
 * Backs every note up on its own, every day or every week, once switched on.
 * Off unless the person turns it on; the backup is the same archive as
 * "Back up all notes", and restores the same way.
 */
internal object AutoBackup {
    private const val PREFS = "autoBackup"
    private const val WORK = "autoBackup"
    /** How many backups are kept on the tablet or in a folder; the oldest go first. */
    const val KEEP = 5
    const val PREFIX = "Notesis-backup-"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun target(context: Context): BackupTarget =
        runCatching { BackupTarget.valueOf(prefs(context).getString("target", null) ?: "OFF") }.getOrDefault(BackupTarget.OFF)

    fun days(context: Context): Int = prefs(context).getInt("days", 1)
    fun destination(context: Context): Uri? = prefs(context).getString("uri", null)?.let(Uri::parse)
    fun lastAt(context: Context): Long = prefs(context).getLong("lastAt", 0L)
    fun lastResult(context: Context): String? = prefs(context).getString("lastResult", null)

    /** Turns automatic backup on to [target] (or off), every [days] days, and schedules it. */
    fun configure(context: Context, target: BackupTarget, days: Int, uri: Uri?) {
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        prefs(context).edit().putString("target", target.name).putInt("days", days)
            .apply { if (uri != null) putString("uri", uri.toString()) }.apply()
        schedule(context)
    }

    /** Asks the system to wake the backup now and then; it backs up only when one is due. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (target(context) == BackupTarget.OFF) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Whether a backup is due: on, and [days] since the last one. */
    fun due(target: BackupTarget, lastAt: Long, days: Int, now: Long): Boolean =
        target != BackupTarget.OFF && now - lastAt >= days * DAY_MS - SLACK_MS

    /** Backs up now if one is due, or at once with [force]. Off the main thread. */
    fun runIfDue(context: Context, force: Boolean = false): Boolean {
        val target = target(context)
        if (target == BackupTarget.OFF) return false
        val now = System.currentTimeMillis()
        if (!force && !due(target, lastAt(context), days(context), now)) return false
        val result = runCatching { backUp(context, target, now) }
        prefs(context).edit()
            .putString("lastResult", result.exceptionOrNull()?.message?.let { "Failed: $it" } ?: "Backed up")
            .apply { if (result.isSuccess) putLong("lastAt", now) }
            .apply()
        return result.isSuccess
    }

    private fun backUp(context: Context, target: BackupTarget, now: Long) {
        val store = NoteStore(context)
        val ids = store.list().map { it.id }
        val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(now)) + ".zip"
        when (target) {
            BackupTarget.OFF -> Unit
            BackupTarget.DEVICE -> {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Backups").apply { mkdirs() }
                val part = File(dir, "$name.part")
                part.outputStream().use { check(store.exportArchive(ids, it)) { "the notes could not be written" } }
                check(part.renameTo(File(dir, name))) { "the backup could not be kept" }
                dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".zip") }.orEmpty()
                    .sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
            }
            BackupTarget.FOLDER -> {
                val tree = destination(context) ?: error("no folder chosen")
                val resolver = context.contentResolver
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(resolver, parent, "application/zip", name) ?: error("the folder took no new file")
                resolver.openOutputStream(doc, "wt")?.use { check(store.exportArchive(ids, it)) { "the notes could not be written" } }
                    ?: error("the file could not be opened")
                pruneFolder(context, tree)
            }
            BackupTarget.DRIVE -> {
                val file = destination(context) ?: error("no file chosen")
                context.contentResolver.openOutputStream(file, "wt")?.use {
                    check(store.exportArchive(ids, it)) { "the notes could not be written" }
                } ?: error("the file could not be opened")
            }
        }
    }

    /** Keeps the newest [KEEP] of the app's backups in a picked folder. */
    private fun pruneFolder(context: Context, tree: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = ArrayList<Pair<String, String>>()
        runCatching {
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (name.startsWith(PREFIX) && name.endsWith(".zip")) found += id to name
                }
            }
        }
        found.sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
    /** A check that comes a little early still counts, so a daily backup does not slip by a day. */
    private const val SLACK_MS = 2L * 60 * 60 * 1000
}

/** The system's wake-up for [AutoBackup]: backs up when one is due. */
class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        AutoBackup.runIfDue(applicationContext)
        return Result.success()
    }
}
