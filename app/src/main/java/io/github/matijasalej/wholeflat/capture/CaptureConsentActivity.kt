package io.github.matijasalej.wholeflat.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import io.github.matijasalej.wholeflat.AppLog

/**
 * A translucent activity that asks for the screen capture consent (MediaProjection), the only way to get a
 * MediaProjection for the capture of another app's audio. With `appops set <package> PROJECT_MEDIA allow`
 * the system answers at once without a dialog (about 0.8 s from the start to the first captured audio);
 * without it SystemUI shows a dialog on top, which the remote answers.
 *
 * Starting an activity from the background needs SYSTEM_ALERT_WINDOW (also granted over adb); when the
 * system blocks it this onCreate never runs and the capture source says so.
 */
class CaptureConsentActivity : Activity() {
    companion object {
        const val EXTRA_ATTEMPT = "attempt"
        private const val REQUEST_PROJECTION = 1
    }

    private var attemptId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attemptId = intent.getIntExtra(EXTRA_ATTEMPT, -1)
        if (savedInstanceState != null) {
            // Recreated while SystemUI was in front (the result is delivered after this).
            return
        }
        val source = CaptureSource.instance
        if (source == null || !source.onConsentActivity(this, attemptId)) {
            finish()
            return
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
        } catch (e: Exception) {
            AppLog.error("capture", "cannot ask for the screen capture consent", e)
            source.onConsentFailed(attemptId, "cannot ask for the screen capture consent: $e")
            finish()
        }
    }

    @Deprecated("The platform's result callback is what this translucent activity needs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PROJECTION) {
            CaptureSource.instance?.onConsentResult(attemptId, resultCode, data)
            finish()
        }
    }

    override fun onDestroy() {
        CaptureSource.instance?.onConsentActivityGone(this)
        super.onDestroy()
    }
}
