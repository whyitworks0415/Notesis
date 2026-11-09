package com.notesis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.ink.strokes.Stroke
import java.io.File

/*
 * A picture of a page's ink, kept in the cache folder when a note is left, so
 * the next time it is opened the page shows its writing at once instead of
 * blank paper while every stroke's geometry is rebuilt - which is nearly all
 * of the time a note takes to open. The picture is only ever a stand-in: it is
 * drawn until the real ink is in, and never when it might be out of date.
 */

/** One page to picture: its id, size in page units, the ink as drawn, and how wide the picture is. */
internal class PagePictureRequest(
    val pageId: String, val width: Float, val height: Float, val strokes: List<Stroke>, val pixelWidth: Int,
)

/**
 * Whether a page can have a picture kept at all. Not one with tape on it: the
 * picture is ink alone, and shown before the tape is read in it would uncover
 * what the tape hides. Not one with ink only the GPU can paint (the pencil's
 * graphite, the particle brushes): on a bitmap's canvas it comes out blank.
 * Not an empty one, which opens at once anyway.
 */
internal fun pagePictureWanted(strokes: Int, masks: Int, needsGpu: Boolean, translucentLayers: Boolean): Boolean =
    strokes >= PAGE_PICTURE_MIN_STROKES && masks == 0 && !needsGpu && !translucentLayers

/** What a picture is checked against: the page's ink file as it was when the picture was taken. */
internal fun pageFileStamp(length: Long, modified: Long): String = "$length:$modified"

/** A picture's width in pixels for a view [viewWidth] wide: the screen's, within a memory budget. */
internal fun pagePictureWidth(viewWidth: Int, pageWidth: Float, pageHeight: Float): Int {
    if (viewWidth <= 0 || pageWidth <= 0f || pageHeight <= 0f) return 0
    var width = viewWidth.coerceAtMost(PAGE_PICTURE_MAX_WIDTH)
    // Tall pages are held to the same number of pixels as an A4 page at full width.
    val pixels = width.toLong() * (width * pageHeight / pageWidth).toLong()
    if (pixels > PAGE_PICTURE_MAX_PIXELS) {
        width = kotlin.math.sqrt(PAGE_PICTURE_MAX_PIXELS * pageWidth.toDouble() / pageHeight).toInt()
    }
    return width.coerceAtLeast(1)
}

internal const val PAGE_PICTURE_MIN_STROKES = 40
internal const val PAGE_PICTURE_MAX_WIDTH = 1400
internal const val PAGE_PICTURE_MAX_PIXELS = 3_200_000L
/** How long a picture stays under the real ink once that is in, while its tiles are still being recorded. */
internal const val PAGE_PICTURE_GRACE_MS = 250L
/** Pictures kept across all notes; the oldest go first. */
internal const val PAGE_PICTURE_KEEP = 90

/** Reads and writes the pictures; every call is made off the UI thread. */
internal class PagePictureStore(private val cacheDir: File, private val pageFile: (noteId: String, pageId: String) -> File,
    private val maskFile: (noteId: String, pageId: String) -> File) {

    private val dir = File(cacheDir, "page-pictures")
    private fun picture(noteId: String, pageId: String) = File(dir, "$noteId/$pageId.webp")
    private fun key(noteId: String, pageId: String) = File(dir, "$noteId/$pageId.key")

    private fun stamp(noteId: String, pageId: String): String? =
        pageFile(noteId, pageId).takeIf { it.isFile }?.let { pageFileStamp(it.length(), it.lastModified()) }

    /** The page's picture, if one is kept and the page's ink file is exactly as it was then; else null. */
    fun read(noteId: String, pageId: String): Bitmap? = runCatching {
        val keyFile = key(noteId, pageId)
        val file = picture(noteId, pageId)
        if (!keyFile.isFile || !file.isFile) return null
        if (keyFile.readText() != stamp(noteId, pageId)) return null
        // Tape added since, by any path: no picture, whatever the key says.
        if (maskFile(noteId, pageId).let { it.isFile && it.length() > MASK_HEADER_BYTES }) return null
        BitmapFactory.decodeFile(file.path)
    }.getOrNull()

    /** The page's file was written: whatever picture there was no longer shows it. */
    fun drop(noteId: String, pageId: String) {
        runCatching {
            key(noteId, pageId).delete()
            picture(noteId, pageId).delete()
        }
    }

    /**
     * Pictures [requests] and keeps them for [noteId], replacing the note's
     * others. [stamps] are the pages' files as they were once every save queued
     * before this had been written; a page whose file has changed again by the
     * time its picture is drawn is left without one.
     */
    fun keep(noteId: String, requests: List<PagePictureRequest>, stamps: Map<String, String>) {
        runCatching {
            val noteDir = File(dir, noteId)
            if (!noteDir.isDirectory && !noteDir.mkdirs()) return
            val wanted = requests.map { it.pageId }.toSet()
            noteDir.listFiles()?.forEach { if (it.nameWithoutExtension !in wanted) it.delete() }
            for (request in requests) {
                val before = stamps[request.pageId] ?: continue
                // Already pictured as it is: nothing to draw.
                if (key(noteId, request.pageId).takeIf { it.isFile }?.readText() == before &&
                    picture(noteId, request.pageId).isFile) continue
                drop(noteId, request.pageId)
                val scale = request.pixelWidth / request.width
                val height = (request.height * scale).toInt()
                if (request.pixelWidth <= 0 || height <= 0) continue
                val bitmap = runCatching { rasterizeInk(request.pixelWidth, height, scale, request.strokes) }.getOrNull()
                    ?: continue
                try {
                    val temporary = File(noteDir, "${request.pageId}.tmp")
                    temporary.outputStream().use { out ->
                        @Suppress("DEPRECATION")
                        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                            else Bitmap.CompressFormat.WEBP
                        bitmap.compress(format, 85, out)
                    }
                    // Written to since the picture was started: it shows ink that has changed.
                    if (stamp(noteId, request.pageId) != before || !temporary.renameTo(picture(noteId, request.pageId))) {
                        temporary.delete()
                        continue
                    }
                    key(noteId, request.pageId).writeText(before)
                } finally {
                    bitmap.recycle()
                }
            }
            prune()
        }
    }

    /** The current stamps of [pageIds]' files. */
    fun stamps(noteId: String, pageIds: List<String>): Map<String, String> =
        pageIds.mapNotNull { id -> stamp(noteId, id)?.let { id to it } }.toMap()

    private fun prune() {
        val pictures = dir.listFiles()?.flatMap { note -> note.listFiles { f -> f.extension == "webp" }?.toList().orEmpty() }
            ?: return
        if (pictures.size <= PAGE_PICTURE_KEEP) return
        pictures.sortedBy { it.lastModified() }.take(pictures.size - PAGE_PICTURE_KEEP).forEach { old ->
            old.delete()
            File(old.parentFile, old.nameWithoutExtension + ".key").delete()
        }
    }

    private companion object {
        /** magic, version, count: a tape file of this length holds no tape. */
        const val MASK_HEADER_BYTES = 12L
    }
}

/**
 * How long notes took to open, kept for the diagnostics: from the tap on the
 * note (or, opened some other way, from the canvas being handed the note) to
 * the first frame drawn, to ink on the page (a kept picture, or the real
 * thing), and to the real ink being in. Times are to the draw call, not to
 * light leaving the screen.
 */
internal object NoteOpenClock {
    class Opening(val startedAt: Long, val fromTap: Boolean) {
        var firstFrameMs = -1L
        var inkShownMs = -1L
        var geometryMs = -1L
        var pictureUsed = false
        var strokes = 0
        val done: Boolean get() = geometryMs >= 0L
    }

    @Volatile private var tappedAt = 0L
    private val kept = ArrayDeque<Opening>()

    /** A note was chosen to be opened. */
    fun tapped() { tappedAt = SystemClock.uptimeMillis() }

    /** Starts the clock for a note the canvas has just been given. */
    fun begin(): Opening {
        val now = SystemClock.uptimeMillis()
        val tap = tappedAt
        tappedAt = 0L
        val fromTap = tap != 0L && now - tap in 0..TAP_COUNTS_WITHIN_MS
        return Opening(if (fromTap) tap else now, fromTap)
    }

    @Synchronized fun finished(opening: Opening) {
        kept.addLast(opening)
        while (kept.size > KEPT) kept.removeFirst()
    }

    @Synchronized fun recent(): List<Opening> = kept.toList()

    @Synchronized fun reset() = kept.clear()

    /** One line per opening, newest last, for the report. */
    fun render(): String {
        val openings = recent()
        if (openings.isEmpty()) return "note open: none since the app started"
        return openings.joinToString("\n") { openingLine(it) }
    }

    fun openingLine(opening: Opening): String =
        "note open (${if (opening.fromTap) "from tap" else "from canvas"}): first frame ${opening.firstFrameMs} ms · " +
            "ink shown ${opening.inkShownMs} ms${if (opening.pictureUsed) " (kept picture)" else ""} · " +
            "ink built ${opening.geometryMs} ms · ${opening.strokes} strokes"

    private const val KEPT = 12
    private const val TAP_COUNTS_WITHIN_MS = 15_000L
}
