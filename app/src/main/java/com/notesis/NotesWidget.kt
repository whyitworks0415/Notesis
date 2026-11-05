package com.notesis

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * The home-screen widget: a button for a new note and the three notes
 * written in last, each opening straight into the note. A locked note is
 * left out - its name is behind the lock too.
 */
class NotesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        L10n.init(context.applicationContext)
        val views = build(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        const val ACTION_NEW = "com.notesis.widget.NEW_NOTE"
        const val ACTION_OPEN = "com.notesis.widget.OPEN_NOTE"
        const val EXTRA_NOTE = "note"
        private val ROWS = intArrayOf(R.id.widget_note_1, R.id.widget_note_2, R.id.widget_note_3)

        /** Brings every placed widget up to date with the library, off the main thread. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, NotesWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            Thread {
                L10n.init(app)
                val views = runCatching { build(app) }.getOrNull() ?: return@Thread
                ids.forEach { runCatching { manager.updateAppWidget(it, views) } }
            }.start()
        }

        private fun build(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_notes)
            views.setTextViewText(R.id.widget_new, L10n.tr("+ New note"))
            views.setOnClickPendingIntent(R.id.widget_new, intent(context, 0, ACTION_NEW, null))
            views.setOnClickPendingIntent(R.id.widget_title, intent(context, 1, Intent.ACTION_MAIN, null))
            val recent = runCatching { NoteStore(context).list().filterNot { it.locked }.take(ROWS.size) }.getOrDefault(emptyList())
            ROWS.forEachIndexed { i, row ->
                val note = recent.getOrNull(i)
                if (note == null) {
                    views.setViewVisibility(row, View.GONE)
                } else {
                    views.setViewVisibility(row, View.VISIBLE)
                    views.setTextViewText(row, note.title.ifBlank { L10n.tr("Untitled") })
                    views.setOnClickPendingIntent(row, intent(context, 10 + i, ACTION_OPEN, note.id))
                }
            }
            return views
        }

        private fun intent(context: Context, code: Int, action: String, note: String?): PendingIntent {
            val open = Intent(context, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (note != null) open.putExtra(EXTRA_NOTE, note)
            return PendingIntent.getActivity(context, code, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
