package io.github.matijasalej.wholeflat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Plain Views, built in code: D-pad focusable buttons on the left (scrollable), the status on the
 * right, refreshed every second. The capture-probe build adds Capture start and Capture stop, the
 * way to give the screen capture consent from this activity when the HTTP route cannot start the
 * consent activity from the background.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private val refresh = object : Runnable {
        override fun run() {
            statusView.text = AppState.toText()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BuildInfo.init(this)
        window.decorView.setBackgroundColor(BACKGROUND)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val leftScroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.MATCH_PARENT)
            addView(left)
        }
        left.addView(
            TextView(this).apply {
                text = "Whole Flat Audio"
                textSize = 24f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, dp(4))
            },
        )
        left.addView(
            TextView(this).apply {
                text = "v${BuildInfo.versionName}"
                textSize = 14f
                setTextColor(MUTED)
                setPadding(0, 0, 0, dp(20))
            },
        )

        val start = button("Start service") {
            startForegroundService(Intent(this, PlaybackService::class.java))
        }
        left.addView(start)
        left.addView(button("Stop service") { stopService(Intent(this, PlaybackService::class.java)) })
        if (PROBE_MODE) {
            left.addView(button("Capture start") { captureAction("start") { CaptureProbe.start(this, null, null, 60) } })
            left.addView(button("Capture stop") { captureAction("stop") { CaptureProbe.stop(this) } })
        }
        left.addView(button("Sonos discover") {
            sonosAction("discover") { Sonos.discover(this).toString() }
        })
        left.addView(button("Sonos test WAV") {
            sonosAction("play wav") { play("wav") }
        })
        left.addView(button("Sonos test FLAC") {
            sonosAction("play flac") { play("flac") }
        })
        left.addView(button("Sonos stop") {
            sonosAction("stop") {
                val ip = Sonos.lastIp
                if (ip == null) "no Sonos known yet, press Sonos discover first" else Sonos.stop(ip).toString()
            }
        })

        statusView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 15f
            setTextColor(Color.rgb(0xE6, 0xED, 0xF3))
            text = AppState.toText()
        }
        val scroll = ScrollView(this).apply {
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(32)
            }
            addView(statusView)
        }

        root.addView(leftScroll)
        root.addView(scroll)
        setContentView(root)
        start.requestFocus()

        val missing = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (PROBE_MODE && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Capture needs it; `adb shell pm grant` does the same without the dialog.
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        DebugServer.ensureStarted(this)
        if (PROBE_MODE) {
            // The probe has no receiver to set up: opening the app brings up the service (debug server, locks).
            try {
                startForegroundService(Intent(this, PlaybackService::class.java))
            } catch (e: Exception) {
                AppLog.error("main", "could not start the playback service", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun play(fmt: String): String {
        val ip = Sonos.anyIp(this) ?: return "no Sonos found on the network"
        return Sonos.play(this, ip, fmt, "http", 2.0).toString()
    }

    /** Runs a Sonos action off the main thread and puts the (shortened) result on the status screen. */
    private fun sonosAction(name: String, action: () -> String) {
        AppState.lastSonos = "$name ..."
        thread(name = "wf-sonos") {
            val result = try {
                action()
            } catch (e: Exception) {
                AppLog.error("sonos", "$name failed", e)
                "failed: $e"
            }
            AppLog.add("sonos", "$name: $result")
            AppState.lastSonos = "$name: " + if (result.length > 160) result.substring(0, 160) + "..." else result
        }
    }

    /** Runs a capture probe call off the main thread; the result goes to the log and the Capture line. */
    private fun captureAction(name: String, action: () -> JSONObject) {
        thread(name = "wf-capture-ui") {
            try {
                val result = action().toString()
                AppLog.add("capture", "button $name: " + if (result.length > 400) result.substring(0, 400) + "..." else result)
            } catch (e: Exception) {
                AppLog.error("capture", "button $name failed", e)
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(20), 0, dp(20), 0)
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(ACCENT))
                addState(intArrayOf(android.R.attr.state_pressed), rounded(ACCENT))
                addState(intArrayOf(), rounded(BUTTON))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52),
            ).apply { bottomMargin = dp(10) }
            setOnClickListener { _: View -> onClick() }
        }
    }

    private fun rounded(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val BACKGROUND = Color.rgb(0x0F, 0x17, 0x20)
        val BUTTON = Color.rgb(0x24, 0x30, 0x3C)
        val ACCENT = Color.rgb(0x1B, 0x6E, 0xF3)
        val MUTED = Color.rgb(0x8B, 0x98, 0xA5)
    }
}
