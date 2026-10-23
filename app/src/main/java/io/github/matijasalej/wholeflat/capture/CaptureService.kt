package io.github.matijasalej.wholeflat.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.matijasalej.wholeflat.AppLog
import io.github.matijasalej.wholeflat.MainActivity
import io.github.matijasalej.wholeflat.R

/**
 * The foreground service the screen capture lives in, of type mediaProjection (and mediaPlayback). Android
 * 14 wants exactly this order: the consent first, then this service in the foreground with that type, and
 * only then `getMediaProjection`, which [CaptureSource] calls once [onStartCommand] has reported in.
 *
 * It is a service of its own so that PlaybackService keeps its type, and so that it is started from the
 * foreground (the translucent consent activity), which also works when the box has just booted.
 */
class CaptureService : Service() {
    companion object {
        const val EXTRA_ATTEMPT = "attempt"
        private const val TAG = "capture-service"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 2
    }

    private var foregroundOk = false

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            goForeground(buildNotification())
            foregroundOk = true
        } catch (e: Exception) {
            AppLog.error(TAG, "startForeground(mediaProjection) refused", e)
            CaptureSource.instance?.onServiceFailed("startForeground(mediaProjection) refused: $e")
            stopSelf()
        }
    }

    @Suppress("InlinedApi")
    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            val projection = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            try {
                startForeground(NOTIFICATION_ID, notification, projection or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                AppLog.add(TAG, "in the foreground with the types mediaProjection and mediaPlayback")
            } catch (e: Exception) {
                AppLog.add(TAG, "both types refused ($e), trying mediaProjection alone")
                startForeground(NOTIFICATION_ID, notification, projection)
                AppLog.add(TAG, "in the foreground with the type mediaProjection")
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundOk) {
            CaptureSource.instance?.onServiceStarted(this, intent?.getIntExtra(EXTRA_ATTEMPT, -1) ?: -1)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.add(TAG, "onDestroy")
        CaptureSource.instance?.onServiceDestroyed(this)
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Capture of the Cast receiver", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Whole Flat Audio")
            .setContentText("Capturing what the Cast receiver plays, for the kitchen")
            .setSmallIcon(R.drawable.ic_stat_note)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
