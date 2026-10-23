package io.github.matijasalej.wholeflat

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Translucent activity that asks for the screen capture consent (MediaProjection), which is the
 * only way to get a MediaProjection for AudioPlaybackCapture. CaptureProbe starts it from the HTTP
 * route; with `appops set <pkg> PROJECT_MEDIA allow` the system answers at once without a dialog,
 * otherwise SystemUI shows its dialog on top of this activity and the remote answers it.
 *
 * Starting an activity from the background needs SYSTEM_ALERT_WINDOW (granted through appops);
 * if the system blocks it, CaptureProbe notices that this onCreate never ran.
 */
class CaptureConsentActivity : Activity() {

    companion object {
        const val EXTRA_SESSION = "session"
        private const val REQUEST_PROJECTION = 1
    }

    private var sessionId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getIntExtra(EXTRA_SESSION, -1)
        if (savedInstanceState != null) {
            // Recreated while SystemUI was in front (the result is delivered after this).
            return
        }
        if (!CaptureProbe.onConsentActivity(this, sessionId)) {
            finish()
            return
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
        } catch (e: Exception) {
            AppLog.error("capture", "cannot ask for the screen capture consent", e)
            CaptureProbe.onConsentFailed(sessionId, "cannot ask for the screen capture consent: $e")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PROJECTION) {
            CaptureProbe.onConsentResult(applicationContext, sessionId, resultCode, data)
            finish()
        }
    }

    override fun onDestroy() {
        CaptureProbe.onConsentActivityGone(this)
        super.onDestroy()
    }
}
