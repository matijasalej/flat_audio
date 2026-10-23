package io.github.matijasalej.wholeflat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service of type mediaProjection, started by CaptureProbe once the screen capture
 * consent is in. Android 14 wants exactly this order: consent first, then the service in the
 * foreground with this type, and only then MediaProjectionManager.getMediaProjection (which
 * CaptureProbe calls from onStartCommand's hand-over). The capture thread itself is CaptureProbe's.
 *
 * It is a separate service from PlaybackService so that the mediaPlayback service keeps the type
 * it has, and so that this one is started from the foreground (the translucent consent activity).
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_SESSION = "session"
        private const val TAG = "capture-service"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 2
    }

    private var foregroundOk = false

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundOk = true
            AppLog.add(TAG, "in the foreground with type mediaProjection")
        } catch (e: Exception) {
            AppLog.error(TAG, "startForeground(mediaProjection) refused", e)
            CaptureProbe.onServiceFailed("startForeground(mediaProjection) refused: $e")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundOk) {
            CaptureProbe.onServiceStarted(this, intent?.getIntExtra(EXTRA_SESSION, -1) ?: -1)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.add(TAG, "onDestroy")
        CaptureProbe.onServiceDestroyed(this)
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Capture probe", NotificationManager.IMPORTANCE_LOW),
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
            .setContentText("Capture probe: capturing app audio")
            .setSmallIcon(R.drawable.ic_stat_note)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
