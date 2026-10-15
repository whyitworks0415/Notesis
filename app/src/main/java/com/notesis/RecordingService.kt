package com.notesis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps a voice recording going while Notesis is out of sight.
 *
 * Android lets an app use the microphone only while it is in front, unless a
 * foreground service of the microphone type says it is still recording. The
 * recorder itself stays with the note; this service only holds that status,
 * and the notification that comes with it, from the moment recording starts
 * to the moment it stops. Without it, switching to another app for a moment
 * left a stretch of silence in the lecture.
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = runCatching { notification() }.getOrNull()
        if (notification == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }.onFailure { stopSelf() }
        // Recording belongs to the note; a restarted service would have nothing to hold.
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "녹음", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "노트에서 녹음하는 동안 표시됩니다"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("녹음 중")
            .setContentText("Notesis 노트에 녹음하고 있습니다")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 7301

        /** Called as recording starts, while the app is in front - the only time Android allows it. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, RecordingService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RecordingService::class.java)) }
        }
    }
}
