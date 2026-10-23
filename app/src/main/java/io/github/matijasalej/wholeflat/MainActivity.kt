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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Plain Views, built in code, for a remote control: a column of focusable rows on the left (toggles,
 * two sliders, the TV address, the test button), the status on the right (which source plays, the level of
 * the capture, the kitchen's lag), refreshed every second.
 * Left and right on a slider change its value.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var bannerView: TextView
    private lateinit var startButton: Button
    private lateinit var tvLabel: TextView
    private lateinit var kitchenToggle: Button
    private lateinit var captureToggle: Button
    private lateinit var fixedVolumeToggle: Button
    private lateinit var screenOffToggle: Button
    private lateinit var wakeToggle: Button
    private lateinit var offsetLabel: TextView
    private lateinit var offsetBar: SeekBar
    private lateinit var balanceLabel: TextView
    private lateinit var balanceBar: SeekBar
    private lateinit var tvField: EditText
    private lateinit var testButton: Button

    private var testRunning = false

    private val refresh = object : Runnable {
        override fun run() {
            statusView.text = AppState.toText()
            val banner = AppState.banner()
            bannerView.text = banner ?: ""
            bannerView.visibility = if (banner == null) View.GONE else View.VISIBLE
            syncControls()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BuildInfo.init(this)
        Settings.init(this)
        Storage.init(this)
        window.decorView.setBackgroundColor(BACKGROUND)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(40), dp(24), dp(40), dp(24))
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        left.addView(
            TextView(this).apply {
                text = "Whole Flat Audio"
                textSize = 24f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, dp(2))
            },
        )
        left.addView(
            TextView(this).apply {
                text = "v${BuildInfo.versionName}"
                textSize = 14f
                setTextColor(MUTED)
                setPadding(0, 0, 0, dp(14))
            },
        )

        // The first control is harmless: a press on OK on a fresh start must not switch a setting.
        startButton = button("Start service") { startReceiver() }
        left.addView(startButton)

        kitchenToggle = toggle("kitchen_enabled", "Kitchen")
        captureToggle = toggle("capture_enabled", "Capture of the Cast receiver")
        fixedVolumeToggle = toggle("living_fixed_volume", "Living room fixed volume")
        screenOffToggle = toggle("tv_screen_off", "TV screen off")
        wakeToggle = toggle("tv_wake", "Wake the TV")
        left.addView(kitchenToggle)
        left.addView(captureToggle)
        left.addView(fixedVolumeToggle)
        left.addView(screenOffToggle)
        left.addView(wakeToggle)

        offsetLabel = label("")
        offsetBar = slider(OFFSET_STEPS) { progress ->
            val value = (progress - OFFSET_STEPS / 2) * OFFSET_STEP_MS
            offsetLabel.text = offsetText(value)
            Settings.set("kitchen_offset_ms", value)
        }
        left.addView(offsetLabel)
        left.addView(offsetBar)

        balanceLabel = label("")
        balanceBar = slider(BALANCE_STEPS) { progress ->
            val value = progress - BALANCE_STEPS / 2
            balanceLabel.text = balanceText(value)
            Settings.set("kitchen_balance", value)
        }
        left.addView(balanceLabel)
        left.addView(balanceBar)

        tvLabel = label("")
        left.addView(tvLabel)
        tvField = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            hint = "automatic"
            textSize = 17f
            isFocusable = true
            setPadding(dp(14), 0, dp(14), 0)
            background = focusBackground()
            layoutParams = rowParams()
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    Settings.set("tv_ip", text.toString().trim())
                    clearFocus()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) Settings.set("tv_ip", text.toString().trim())
            }
        }
        left.addView(tvField)

        testButton = button("Test: clicks in both rooms (20 s)") { toggleTest() }
        left.addView(testButton)
        left.addView(button("Stop service") { stopService(Intent(this, PlaybackService::class.java)) })

        val leftScroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(dp(430), LinearLayout.LayoutParams.MATCH_PARENT)
            addView(left)
        }

        statusView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextColor(Color.rgb(0xE6, 0xED, 0xF3))
            text = AppState.toText()
        }
        val scroll = ScrollView(this).apply {
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(statusView)
        }
        // a warning on top of the status: Spotify refusing playback, low storage
        bannerView = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.rgb(0xFF, 0xD1, 0x66))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.rgb(0x4A, 0x2B, 0x12))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(28)
            }
            addView(bannerView)
            addView(scroll)
        }

        root.addView(leftScroll)
        root.addView(right)
        setContentView(root)
        startButton.requestFocus()
        syncControls(force = true)
        startReceiver()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        DebugServer.ensureStarted(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    // ---- controls ------------------------------------------------------------------------------

    private fun toggle(key: String, name: String): Button {
        val b = button(name) {
            val current = Settings.toJson().optBoolean(key, true)
            Settings.set(key, !current)
            syncControls(force = true)
        }
        b.tag = name
        return b
    }

    private fun slider(steps: Int, onChange: (Int) -> Unit): SeekBar {
        return SeekBar(this).apply {
            max = steps
            keyProgressIncrement = 1
            isFocusable = true
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = focusBackground()
            layoutParams = rowParams()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(MUTED)
            setPadding(dp(4), dp(8), 0, dp(2))
        }
    }

    private fun offsetText(valueMs: Int): String =
        "Kitchen offset: " + (if (valueMs > 0) "+" else "") + valueMs + " ms" +
            if (valueMs > 0) "  (kitchen later)" else if (valueMs < 0) "  (kitchen earlier)" else ""

    private fun balanceText(value: Int): String =
        "Kitchen balance: " + (if (value > 0) "+" else "") + value + "  (Sonos volume added to the phone's)"

    /** Puts the stored settings on the controls (they can also change over HTTP). */
    private fun syncControls(force: Boolean = false) {
        val v = Settings.values
        kitchenToggle.text = "Kitchen: " + onOff(v.kitchenEnabled)
        captureToggle.text = "Capture of the Cast receiver: " + onOff(v.captureEnabled)
        fixedVolumeToggle.text = "Living room fixed volume: " + onOff(v.livingFixedVolume)
        screenOffToggle.text = "TV screen off: " + onOff(v.tvScreenOff)
        wakeToggle.text = "Wake the TV: " + onOff(v.tvWake)
        val offsetProgress = v.kitchenOffsetMs / OFFSET_STEP_MS + OFFSET_STEPS / 2
        if (force || !offsetBar.hasFocus()) {
            offsetBar.progress = offsetProgress.coerceIn(0, OFFSET_STEPS)
        }
        offsetLabel.text = offsetText(v.kitchenOffsetMs)
        if (force || !balanceBar.hasFocus()) {
            balanceBar.progress = (v.kitchenBalance + BALANCE_STEPS / 2).coerceIn(0, BALANCE_STEPS)
        }
        balanceLabel.text = balanceText(v.kitchenBalance)
        if (!tvField.hasFocus() && tvField.text.toString() != v.tvIp) tvField.setText(v.tvIp)
        tvLabel.text = if (v.tvIp.isNotEmpty()) {
            "TV address: ${v.tvIp} (typed in)"
        } else {
            "TV address: " + (AppState.tv?.currentIp()?.let { "$it (found automatically)" } ?: "looking for it ...") +
                "  Type one here to override."
        }
        testButton.text = if (testRunning) "Stop the test" else "Test: clicks in both rooms (20 s)"
    }

    private fun onOff(on: Boolean): String = if (on) "ON" else "OFF"

    /** The receiver runs in a foreground service; opening the app starts it (a press on the button does too). */
    private fun startReceiver() {
        try {
            startForegroundService(Intent(this, PlaybackService::class.java))
        } catch (e: Exception) {
            AppLog.error("ui", "cannot start the service", e)
        }
    }

    private fun toggleTest() {
        val starting = !testRunning
        thread(name = "wf-test") {
            val ok = try {
                if (starting) NativeBridge.calibStart(TEST_SECONDS) else {
                    NativeBridge.calibStop()
                    true
                }
            } catch (t: Throwable) {
                AppLog.error("ui", "test signal failed", t)
                false
            }
            handler.post {
                testRunning = starting && ok
                if (testRunning) {
                    handler.postDelayed({
                        testRunning = false
                        syncControls()
                    }, TEST_SECONDS * 1000L + 4000L)
                }
                syncControls()
            }
        }
    }

    // ---- looks ---------------------------------------------------------------------------------

    private fun button(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(18), 0, dp(18), 0)
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(ACCENT))
                addState(intArrayOf(android.R.attr.state_pressed), rounded(ACCENT))
                addState(intArrayOf(), rounded(BUTTON))
            }
            layoutParams = rowParams()
            setOnClickListener { _: View -> onClick() }
        }
    }

    private fun focusBackground(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(ACCENT_DIM))
        addState(intArrayOf(), rounded(BUTTON))
    }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(8) }

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
        val ACCENT_DIM = Color.rgb(0x2A, 0x4F, 0x8F)
        val MUTED = Color.rgb(0x8B, 0x98, 0xA5)

        /** The offset slider: -300..+300 ms in steps of 5. */
        const val OFFSET_STEP_MS = 5
        const val OFFSET_STEPS = 120

        /** The balance slider: -20..+20. */
        const val BALANCE_STEPS = 40
        const val TEST_SECONDS = 20
    }
}
