package io.github.matijasalej.wholeflat.capture

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import io.github.matijasalej.wholeflat.AppLog

/**
 * The notification listener whose access lets [MediaWatch] read the media sessions of other apps. It does
 * nothing with notifications. The access is granted over adb (see MediaWatch).
 */
class MediaWatcher : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLog.add("media", "the listener is connected")
        MediaWatch.connect(applicationContext, ComponentName(this, MediaWatcher::class.java))
    }

    override fun onListenerDisconnected() {
        AppLog.add("media", "the listener is disconnected")
        MediaWatch.disconnect()
        super.onListenerDisconnected()
    }
}
