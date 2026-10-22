package io.github.matijasalej.wholeflat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the receiver after a reboot. This only works because the app targets Android 14 (SDK 34):
 * from SDK 35 on, a BOOT_COMPLETED receiver may no longer start a mediaPlayback foreground service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        AppLog.add("boot", "BOOT_COMPLETED, starting the playback service")
        try {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        } catch (e: Exception) {
            AppLog.error("boot", "could not start the playback service", e)
        }
    }
}
