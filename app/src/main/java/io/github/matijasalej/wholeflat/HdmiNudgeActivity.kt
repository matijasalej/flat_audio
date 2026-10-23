package io.github.matijasalej.wholeflat

import android.app.Activity
import android.os.Bundle

/**
 * A moment of this app on the screen of the box and nothing more: it ends itself at once. It is what `TvRenderer` shows when the TV does
 * not come back to its HDMI input after the stream was given back, in case the TV follows the box's claim of the screen (it does not
 * cost anything where it does not; it needs the same permission to start from the background as the capture consent, `SYSTEM_ALERT_WINDOW`).
 */
class HdmiNudgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
