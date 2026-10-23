package io.github.matijasalej.wholeflat

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Feasibility probe (branch capture-probe): can this app capture the audio of the official Spotify
 * TV app with Android's AudioPlaybackCapture on a Chromecast with Google TV (Android 14), and what
 * can be done with the copy? Driven over HTTP by [DebugServer], see its route list (/capture/...).
 *
 * One [Session] per /capture/start:
 *  1. CaptureConsentActivity (translucent) asks for the screen capture consent. With
 *     `appops set <pkg> PROJECT_MEDIA allow` the system grants it without a dialog, otherwise
 *     SystemUI shows its dialog and the remote answers it.
 *  2. The consent result starts CaptureService, a foreground service of type mediaProjection.
 *     Android 14 wants that order: consent, service in the foreground, then getMediaProjection.
 *  3. [onServiceStarted] gets the MediaProjection and starts the capture thread, which builds the
 *     AudioRecord (48 kHz stereo 16 bit) from an AudioPlaybackCaptureConfiguration matching the
 *     uid of the app (match=uid) or the usages MEDIA, UNKNOWN and GAME (match=usage), and reads.
 *  4. Every read goes to [LevelHistory] and, while a monitor runs, to the [CaptureMonitor].
 *
 * The volume test and the monitor change STREAM_MUSIC; both restore it in a finally block.
 */
object CaptureProbe {
    const val SAMPLE_RATE = 48_000
    private const val TAG = "capture"
    private const val CHANNELS = 2
    private const val CHUNK_FRAMES = 960 // 20 ms
    private const val DEFAULT_PACKAGE = "com.spotify.tv.android"
    private const val ACTIVITY_WAIT_MS = 6_000L
    private const val WINDOW_MS = 2_000L
    private const val SETTLE_MS = 400L
    private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    private val ids = AtomicInteger()
    private val monitorBusy = AtomicBoolean(false)
    private val volumeBusy = AtomicBoolean(false)

    @Volatile
    private var session: Session? = null

    @Volatile
    private var serviceRef: CaptureService? = null

    @Volatile
    private var consentActivity: WeakReference<Activity>? = null

    @Volatile
    private var monitor: CaptureMonitor? = null

    /** One attempt: consent, service, projection, AudioRecord, capture thread. */
    private class Session(val id: Int, val match: String, val pkg: String, val uid: Int) {
        val startedAt: Long = SystemClock.elapsedRealtime()
        val levels = LevelHistory()
        val frames = AtomicLong()
        val tornDown = AtomicBoolean(false)

        /** Counted down when the state reaches running, failed or stopped. */
        val done = CountDownLatch(1)
        private val timeline = ArrayList<String>()

        @Volatile var state = "awaiting_consent"
        @Volatile var error: String? = null
        @Volatile var activityCreated = false
        @Volatile var stopRequested = false
        @Volatile var resultCode = 0
        @Volatile var resultData: Intent? = null
        @Volatile var projection: MediaProjection? = null
        @Volatile var projectionCallback: MediaProjection.Callback? = null
        @Volatile var record: AudioRecord? = null
        @Volatile var captureThread: Thread? = null
        @Volatile var readErrors = 0
        @Volatile var lastReadError: String? = null
        @Volatile var lastAudioAtNs = 0L
        @Volatile var excludedOwnUid = false

        fun note(text: String) {
            val line = "+" + (SystemClock.elapsedRealtime() - startedAt) + " ms " + text
            synchronized(timeline) { timeline.add(line) }
            AppLog.add(TAG, "#$id $text")
        }

        fun timelineJson(): JSONArray = synchronized(timeline) { JSONArray(ArrayList(timeline)) }
    }

    // ---- /capture/start -------------------------------------------------------------------------

    /**
     * Runs the whole chain and waits up to [waitSeconds] for it to reach running or failed. A
     * consent dialog that nobody answers in that time leaves the session in awaiting_consent (ok,
     * pending); /capture/status shows how it went on.
     */
    fun start(context: Context, pkgParam: String?, matchParam: String?, waitSeconds: Int): JSONObject {
        val app = context.applicationContext
        val match = (matchParam ?: "uid").lowercase(Locale.US)
        if (match != "uid" && match != "usage") throw IllegalArgumentException("match must be uid or usage")
        val pkg = pkgParam?.takeIf { it.isNotBlank() } ?: DEFAULT_PACKAGE
        if (!PACKAGE_PATTERN.matches(pkg)) throw IllegalArgumentException("uid_pkg is not a package name")

        val out = JSONObject()
        out.put("uid_pkg", pkg)
        out.put("match", match)
        val pre = prechecks(app)
        out.put("prechecks", pre)
        val info = packageJson(app, pkg)
        out.put("package", info)
        val uid = info.optInt("uid", -1)

        if (Build.VERSION.SDK_INT < 29) {
            return refuse(out, "AudioPlaybackCapture needs Android 10 (sdk 29), this is sdk ${Build.VERSION.SDK_INT}")
        }
        if (!pre.optBoolean("record_audio_granted")) {
            return refuse(out, "RECORD_AUDIO is not granted. Run: adb shell pm grant ${app.packageName} android.permission.RECORD_AUDIO")
        }
        if (match == "uid" && uid < 0) {
            return refuse(out, "package $pkg is not installed or not visible; see package.candidates, or use match=usage")
        }

        // An earlier session ends first: a projection token can be used once.
        session?.let { teardown(it) }
        waitUntil(2_000) { serviceRef == null }

        val s = Session(ids.incrementAndGet(), match, pkg, uid)
        session = s
        AppState.capture = "awaiting_consent"
        s.note("start: match=$match uid_pkg=$pkg uid=$uid prechecks=$pre")
        try {
            app.startActivity(
                Intent(app, CaptureConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(CaptureConsentActivity.EXTRA_SESSION, s.id),
            )
        } catch (e: Exception) {
            fail(s, "cannot start the consent activity: $e")
        }

        // The activity has to come up within seconds, else the background start was blocked.
        waitUntil(ACTIVITY_WAIT_MS) { s.activityCreated || s.state != "awaiting_consent" }
        if (!s.activityCreated && s.state == "awaiting_consent") {
            fail(
                s,
                "the consent activity did not start within ${ACTIVITY_WAIT_MS / 1000} s, so the system blocked the background " +
                    "activity start. Run: adb shell appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow " +
                    "(or open Whole Flat Audio on the TV and press Capture start)",
            )
        }
        val remaining = waitSeconds * 1000L - (SystemClock.elapsedRealtime() - s.startedAt)
        if (remaining > 0) s.done.await(remaining, TimeUnit.MILLISECONDS)
        return startResult(s, out)
    }

    private fun startResult(s: Session, out: JSONObject): JSONObject {
        val pending = s.state == "awaiting_consent" || s.state == "starting_service" || s.state == "starting_capture"
        out.put("ok", s.state != "failed" && s.state != "stopped")
        out.put("state", s.state)
        out.put("pending", pending)
        out.put("uid", s.uid)
        out.put("elapsed_ms", SystemClock.elapsedRealtime() - s.startedAt)
        out.put("error", s.error ?: JSONObject.NULL)
        out.put(
            "hint",
            when (s.state) {
                "awaiting_consent" -> "waiting for the screen capture consent: if a dialog is on the TV, accept it with the remote, then GET /capture/status"
                "starting_service", "starting_capture" -> "still starting, GET /capture/status in a moment"
                "running" -> "capturing: play something in the app, then GET /capture/status"
                else -> s.error ?: s.state
            },
        )
        out.put("timeline", s.timelineJson())
        return out
    }

    private fun refuse(out: JSONObject, message: String): JSONObject {
        AppLog.error(TAG, message)
        out.put("ok", false)
        out.put("state", session?.state ?: "idle")
        out.put("pending", false)
        out.put("error", message)
        return out
    }

    // ---- callbacks from the activity and the service ----------------------------------------

    /** The consent activity is up. False if its session is gone (the activity then closes itself). */
    fun onConsentActivity(activity: Activity, sessionId: Int): Boolean {
        val s = current(sessionId)
        if (s == null || s.state != "awaiting_consent") {
            AppLog.add(TAG, "consent activity for session $sessionId is stale, closing it")
            return false
        }
        consentActivity = WeakReference(activity)
        s.activityCreated = true
        s.note("consent activity is up, asking for the screen capture permission")
        return true
    }

    fun onConsentActivityGone(activity: Activity) {
        if (consentActivity?.get() === activity) consentActivity = null
    }

    fun onConsentFailed(sessionId: Int, message: String) {
        current(sessionId)?.let { fail(it, message) }
    }

    fun onConsentResult(context: Context, sessionId: Int, resultCode: Int, data: Intent?) {
        val s = current(sessionId) ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail(
                s,
                "screen capture consent not granted (resultCode $resultCode" + (if (data == null) ", no data" else "") +
                    "). Without a dialog: adb shell appops set ${context.packageName} PROJECT_MEDIA allow",
            )
            return
        }
        s.resultCode = resultCode
        s.resultData = data
        enter(s, "starting_service", "consent granted, starting the capture service")
        try {
            context.startForegroundService(
                Intent(context, CaptureService::class.java).putExtra(CaptureService.EXTRA_SESSION, s.id),
            )
        } catch (e: Exception) {
            fail(s, "cannot start the capture service: $e")
        }
    }

    /** CaptureService is in the foreground with type mediaProjection: now the projection can be had. */
    fun onServiceStarted(service: CaptureService, sessionId: Int) {
        val s = current(sessionId)
        if (s == null) {
            AppLog.add(TAG, "capture service for session $sessionId is stale, stopping it")
            serviceRef = service
            service.stopSelf()
            return
        }
        serviceRef = service
        if (s.state != "starting_service" || s.projection != null) {
            // a second onStartCommand for the same session: the consent token is good for one projection
            s.note("capture service started again in state ${s.state}, ignored")
            return
        }
        s.note("capture service is in the foreground (mediaProjection)")
        val data = s.resultData
        if (data == null) {
            fail(s, "no consent data for the projection")
            return
        }
        try {
            val manager = service.getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(s.resultCode, data)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    AppLog.add(TAG, "#${s.id} MediaProjection.onStop")
                    if (!s.stopRequested) fail(s, "the system stopped the MediaProjection")
                }
            }
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
            s.projection = projection
            s.projectionCallback = callback
            s.resultData = null
            s.note("MediaProjection obtained")
            if (s.tornDown.get()) {
                // stop came in while the projection was being set up: nobody else will release it
                projection.unregisterCallback(callback)
                projection.stop()
                s.projection = null
                return
            }
        } catch (e: Exception) {
            fail(s, "getMediaProjection failed: ${why(e)}")
            return
        }
        enter(s, "starting_capture", "building the AudioRecord")
        s.captureThread = thread(name = "wf-capture") { captureLoop(s) }
    }

    /** CaptureService could not go to the foreground (called before it knows its session). */
    fun onServiceFailed(message: String) {
        val s = session ?: return
        if (!s.tornDown.get()) fail(s, message)
    }

    fun onServiceDestroyed(service: CaptureService) {
        if (serviceRef === service) serviceRef = null
        val s = session ?: return
        if (!s.tornDown.get() && s.state == "running") fail(s, "the capture service was destroyed while capturing")
    }

    // ---- the capture thread -------------------------------------------------------------------

    /**
     * match=uid captures the app's uid; match=usage the usages MEDIA, UNKNOWN and GAME of every
     * app, minus this app's own uid ([excludeOwn]) so that a monitor with usage media cannot feed
     * back into the capture.
     */
    private fun buildRecord(s: Session, projection: MediaProjection, excludeOwn: Boolean): AudioRecord {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        if (s.match == "usage") {
            builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            builder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            builder.addMatchingUsage(AudioAttributes.USAGE_GAME)
            if (excludeOwn) builder.excludeUid(Process.myUid())
        } else {
            builder.addMatchingUid(s.uid)
        }
        s.excludedOwnUid = s.match == "usage" && excludeOwn
        val config = builder.build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(if (minBytes > 0) minBytes * 4 else 0, SAMPLE_RATE * 4 / 2) // at least half a second
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    private fun captureLoop(s: Session) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            val projection = s.projection ?: throw IllegalStateException("no MediaProjection")
            val record = try {
                buildRecord(s, projection, excludeOwn = true)
            } catch (e: Exception) {
                if (s.match != "usage") throw e
                // maybe the combination of usage rules and a uid exclusion is what the system refuses
                s.note("AudioRecord with the own uid excluded failed (${why(e)}), trying without")
                buildRecord(s, projection, excludeOwn = false)
            }
            s.record = record
            s.note(
                "AudioRecord built: ${recordStateName(record.state)}, ${record.sampleRate} Hz, ${record.channelCount} ch, " +
                    "buffer ${record.bufferSizeInFrames} frames, match=${s.match}",
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord is ${recordStateName(record.state)} after build")
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord is ${recordingStateName(record.recordingState)} after startRecording")
            }
            enter(s, "running", "capturing")

            val buf = ShortArray(CHUNK_FRAMES * CHANNELS)
            var lastSummary = SystemClock.elapsedRealtime()
            while (!s.stopRequested) {
                val n = record.read(buf, 0, buf.size)
                val tNs = System.nanoTime()
                if (n < 0) {
                    if (s.stopRequested) break
                    s.readErrors++
                    s.lastReadError = readErrorName(n)
                    if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw IllegalStateException("AudioRecord.read: ERROR_DEAD_OBJECT (projection gone?)")
                    }
                    if (s.readErrors > 50) throw IllegalStateException("AudioRecord.read keeps failing: ${s.lastReadError}")
                    Thread.sleep(20)
                    continue
                }
                if (n == 0) {
                    Thread.sleep(5)
                    continue
                }
                val chunk = LevelHistory.measure(buf, n, tNs)
                s.levels.add(chunk)
                s.frames.addAndGet(chunk.frames.toLong())
                if (chunk.peakDb >= LevelHistory.SILENCE_DB) s.lastAudioAtNs = tNs
                monitor?.offer(tNs, buf.copyOf(n))
                val now = SystemClock.elapsedRealtime()
                if (now - lastSummary >= 1000) {
                    lastSummary = now
                    AppState.capture = summary(s)
                }
            }
        } catch (e: Throwable) {
            if (!s.stopRequested) fail(s, "capture failed: ${why(e)}")
        } finally {
            // teardown may have run before the record existed; releasing twice is harmless
            if (s.stopRequested) releaseRecord(s.record)
        }
        AppLog.add(TAG, "#${s.id} capture thread ended")
    }

    private fun releaseRecord(record: AudioRecord?) {
        if (record == null) return
        try {
            record.stop()
        } catch (e: Exception) {
            // not recording
        }
        try {
            record.release()
        } catch (e: Exception) {
            AppLog.error(TAG, "releasing the AudioRecord failed", e)
        }
    }

    // ---- state, failure and tear down -----------------------------------------------------------

    private fun current(sessionId: Int): Session? = session?.takeIf { it.id == sessionId && !it.tornDown.get() }

    private fun running(): Session? = session?.takeIf { it.state == "running" && !it.tornDown.get() }

    private fun enter(s: Session, state: String, text: String) {
        if (s.tornDown.get()) {
            // a stop got in first (e.g. between startRecording and running): a stopped session stays stopped
            s.note("ignored $state after the tear down")
            return
        }
        s.state = state
        s.note(text)
        AppState.capture = summary(s)
        if (state == "running" || state == "failed" || state == "stopped") s.done.countDown()
    }

    private fun fail(s: Session, message: String) {
        if (s.error == null) s.error = message
        AppLog.error(TAG, "#${s.id} $message")
        enter(s, "failed", "failed: $message")
        teardown(s)
    }

    /** Ends everything of [s]: monitor, capture thread, AudioRecord, projection, service, activity. Idempotent. */
    private fun teardown(s: Session) {
        if (!s.tornDown.compareAndSet(false, true)) return
        s.stopRequested = true
        monitor?.stop()
        val record = s.record
        try {
            record?.stop() // lets a blocked read return
        } catch (e: Exception) {
            // not recording
        }
        val t = s.captureThread
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(2000)
            } catch (e: InterruptedException) {
                // shutting down anyway
            }
        }
        releaseRecord(s.record)
        val projection = s.projection
        if (projection != null) {
            try {
                s.projectionCallback?.let { projection.unregisterCallback(it) }
                projection.stop()
            } catch (e: Exception) {
                AppLog.error(TAG, "stopping the MediaProjection failed", e)
            }
        }
        s.projection = null
        s.resultData = null
        val activity = consentActivity?.get()
        consentActivity = null
        if (activity != null) activity.runOnUiThread { activity.finish() }
        val service = serviceRef
        if (service != null) Handler(Looper.getMainLooper()).post { service.stopSelf() }
        if (s.state != "failed") s.state = "stopped"
        s.note("torn down, ${s.frames.get()} frames captured")
        AppState.capture = summary(s)
        s.done.countDown()
    }

    /** Stops the capture when the service goes away. */
    fun shutdown() {
        session?.let { teardown(it) }
    }

    private fun summary(s: Session): String {
        return when (s.state) {
            "running" -> {
                val now = System.nanoTime()
                val w = s.levels.window(now - 1_000_000_000L, now)
                String.format(
                    Locale.US,
                    "running, %d frames, 1 s RMS L %.1f R %.1f dBFS%s",
                    s.frames.get(),
                    w.rmsDbL,
                    w.rmsDbR,
                    if (w.digitalSilence) " (digital silence)" else "",
                )
            }
            "failed" -> "failed: " + (s.error ?: "?")
            else -> s.state
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < end) {
            try {
                Thread.sleep(25)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    // ---- /capture/stop --------------------------------------------------------------------------

    fun stop(context: Context): JSONObject {
        val out = JSONObject()
        out.put("ok", true)
        val s = session
        if (s == null) {
            out.put("stopped", false)
            out.put("state", "idle")
            return out
        }
        val was = s.state
        teardown(s)
        // A volume test or a monitor restores the volume as soon as it sees the stop.
        waitUntil(5_000) { !volumeBusy.get() && !monitorBusy.get() }
        out.put("stopped", true)
        out.put("was", was)
        out.put("state", s.state)
        out.put("frames_captured", s.frames.get())
        out.put("volume_ops_idle", !volumeBusy.get() && !monitorBusy.get())
        out.put("stream_music", streamJson(audioManager(context), AudioManager.STREAM_MUSIC))
        return out
    }

    // ---- /capture/status ------------------------------------------------------------------------

    fun status(context: Context): JSONObject {
        val o = JSONObject()
        o.put("ok", true)
        o.put("probe_mode", PROBE_MODE)
        val s = session
        if (s == null) {
            o.put("running", false)
            o.put("state", "idle")
            o.put("hint", "GET /capture/start?uid_pkg=$DEFAULT_PACKAGE")
            return o
        }
        val nowNs = System.nanoTime()
        val record = s.record
        o.put("running", s.state == "running")
        o.put("state", s.state)
        o.put("match", s.match)
        o.put("uid_pkg", s.pkg)
        o.put("uid", s.uid)
        o.put("excluded_own_uid", s.excludedOwnUid)
        o.put("sample_rate", SAMPLE_RATE)
        o.put("channels", CHANNELS)
        o.put("encoding", "PCM_16BIT")
        o.put("audio_record_state", if (record != null) recordStateName(record.state) else "none")
        o.put("recording_state", if (record != null) recordingStateName(record.recordingState) else "none")
        o.put("frames_captured", s.frames.get())
        o.put("frames_per_s_5s", num(s.levels.framesPerSecond(nowNs, 5_000_000_000L)))

        val w = s.levels.window(nowNs - 1_000_000_000L, nowNs)
        o.put("level_window_s", 1)
        o.put("rms_dbfs_l", num(w.rmsDbL))
        o.put("rms_dbfs_r", num(w.rmsDbR))
        o.put("peak_dbfs_l", num(w.peakDbL))
        o.put("peak_dbfs_r", num(w.peakDbR))
        o.put("digital_silence", if (w.frames > 0) w.digitalSilence else JSONObject.NULL)
        o.put("audio_seen", s.lastAudioAtNs != 0L)
        o.put("seconds_since_audio", if (s.lastAudioAtNs == 0L) JSONObject.NULL else num((nowNs - s.lastAudioAtNs) / 1e9))
        o.put("read_errors", s.readErrors)
        o.put("last_read_error", s.lastReadError ?: JSONObject.NULL)
        o.put("exception", s.error ?: JSONObject.NULL)
        o.put(
            "client_silenced",
            try {
                record?.activeRecordingConfiguration?.isClientSilenced ?: JSONObject.NULL
            } catch (e: Exception) {
                JSONObject.NULL
            },
        )
        o.put("hint", statusHint(s, w))
        val m = monitor
        if (m != null) {
            val mo = JSONObject()
            mo.put("usage", m.usageName)
            mo.put("delay_ms", m.delayMs)
            mo.put("frames_written", m.framesWritten)
            mo.put("error", m.error ?: JSONObject.NULL)
            o.put("monitor", mo)
        }
        o.put("active_players", activePlayers(audioManager(context)))
        o.put("timeline", s.timelineJson())
        return o
    }

    private fun statusHint(s: Session, w: LevelHistory.Window): String {
        return when {
            s.state == "failed" -> s.error ?: "failed"
            s.state != "running" -> "state ${s.state}"
            w.frames == 0L -> "no reads in the last second"
            s.lastAudioAtNs == 0L && w.digitalSilence ->
                "digital silence since the start: nothing is playing from the matched app, or the app opted out of capture " +
                    "(allowAudioPlaybackCapture=false, ALLOW_CAPTURE_BY_NONE) or plays with a usage other than MEDIA, GAME or UNKNOWN. " +
                    "See package.manifest_allow_audio_playback_capture in /capture/start and active_players here."
            w.digitalSilence -> "silent now, audio was seen earlier (paused?)"
            else -> "audio is coming in"
        }
    }

    // ---- /capture/volume-test ---------------------------------------------------------------------

    /**
     * Is the capture taken before or after the STREAM_MUSIC volume? Measures the capture for 2 s,
     * sets STREAM_MUSIC to 0 (flags 0), measures 2 s more and restores the volume (always).
     */
    fun volumeTest(context: Context): JSONObject {
        val s = running() ?: return notRunning()
        val am = audioManager(context)
        val out = JSONObject()
        out.put("ok", true)
        out.put("stream_music", streamJson(am, AudioManager.STREAM_MUSIC))
        out.put("is_volume_fixed", am.isVolumeFixed)
        out.put("output_devices", devicesJson(am))
        if (!volumeBusy.compareAndSet(false, true)) {
            out.put("ok", false)
            out.put("error", "another volume test or a monitor with music_zero is running; wait for it or GET /capture/stop")
            return out
        }
        var original = -1
        var before: LevelHistory.Window? = null
        var after: LevelHistory.Window? = null
        var applied = false
        try {
            original = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            out.put("original_volume", original)
            AppLog.add(TAG, "volume test: STREAM_MUSIC is $original, measuring")
            before = measure(s, WINDOW_MS)
            out.put("before", levelJson(before, WINDOW_MS / 1000.0))
            if (!s.stopRequested) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                applied = now == 0
                out.put("volume_after_set", now)
                AppLog.add(TAG, "volume test: STREAM_MUSIC set to 0, reads back $now")
                sleepWhile(s, SETTLE_MS)
                after = measure(s, WINDOW_MS)
                out.put("music_zero", levelJson(after, WINDOW_MS / 1000.0))
            } else {
                out.put("aborted", true)
            }
        } catch (t: Throwable) {
            out.put("ok", false)
            out.put("error", t.toString())
            AppLog.error(TAG, "volume test failed", t)
        } finally {
            if (original >= 0) {
                val back = restoreMusicVolume(am, original)
                out.put("restored_volume", back)
                out.put("restore_ok", back == original)
            }
            volumeBusy.set(false)
        }
        out.put("verdict", volumeVerdict(original, applied, before, after))
        return out
    }

    private fun volumeVerdict(
        original: Int,
        applied: Boolean,
        before: LevelHistory.Window?,
        after: LevelHistory.Window?,
    ): JSONObject {
        val o = JSONObject()
        var follows: Any = JSONObject.NULL
        val text: String
        if (before != null && after != null && before.frames > 0 && after.frames > 0) {
            o.put("rms_dbfs_before", num(before.rmsDbMean))
            o.put("rms_dbfs_music_zero", num(after.rmsDbMean))
        }
        if (before == null || after == null) {
            text = "inconclusive: the test was cut short"
        } else if (before.frames == 0L || after.frames == 0L) {
            text = "inconclusive: no capture data in a window"
        } else if (original == 0) {
            text = "inconclusive: STREAM_MUSIC was already 0, raise it and repeat"
        } else if (!applied) {
            text = "inconclusive: STREAM_MUSIC did not go to 0 (isVolumeFixed, or the TV owns the volume)"
        } else if (before.digitalSilence) {
            text = "inconclusive: the capture was digital silence before the change (nothing playing, or capture blocked)"
        } else {
            val delta = after.rmsDbMean - before.rmsDbMean
            o.put("delta_db", num(delta))
            if (after.digitalSilence || delta <= -20.0) {
                follows = true
                text = "POST-volume: the capture follows STREAM_MUSIC, it went silent (or ${num(delta)} dB) at volume 0"
            } else if (delta >= -6.0) {
                follows = false
                text = "PRE-volume: the capture level is independent of STREAM_MUSIC (${num(delta)} dB at volume 0), " +
                    "so a copy can be played while the app's own output is muted"
            } else {
                text = "partial: the capture fell by ${num(delta)} dB at volume 0, repeat with steady music"
            }
        }
        o.put("capture_follows_music_volume", follows)
        o.put("verdict", text)
        return o
    }

    private fun restoreMusicVolume(am: AudioManager, original: Int): Int {
        var now = -1
        for (attempt in 1..3) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
            } catch (t: Throwable) {
                AppLog.error(TAG, "restoring STREAM_MUSIC failed", t)
            }
            now = try {
                am.getStreamVolume(AudioManager.STREAM_MUSIC)
            } catch (t: Throwable) {
                -1
            }
            if (now == original) break
            try {
                Thread.sleep(150)
            } catch (e: InterruptedException) {
                break
            }
        }
        AppLog.add(TAG, "STREAM_MUSIC restored to $now (original $original)")
        return now
    }

    // ---- /capture/monitor -------------------------------------------------------------------------

    /**
     * Plays the capture back through an AudioTrack with the given usage, [delayMs] late, for
     * [seconds]; with [musicZero] STREAM_MUSIC is 0 meanwhile (restored afterwards, always). The
     * call returns when the playback ends. Whether the copy is audible is for the ears; the report
     * has the stream mapping and the stream volumes before, during and after.
     */
    fun monitor(context: Context, usageParam: String?, delayParam: Int?, secondsParam: Int?, musicZero: Boolean): JSONObject {
        val usageName = (usageParam ?: "alarm").lowercase(Locale.US)
        val usage = when (usageName) {
            "alarm" -> AudioAttributes.USAGE_ALARM
            "accessibility" -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
            "media" -> AudioAttributes.USAGE_MEDIA
            else -> throw IllegalArgumentException("usage must be alarm, accessibility or media")
        }
        val delayMs = (delayParam ?: 300).coerceIn(0, 10_000)
        val seconds = (secondsParam ?: 20).coerceIn(1, 600)
        val s = running() ?: return notRunning()
        val am = audioManager(context)
        val out = JSONObject()
        if (!monitorBusy.compareAndSet(false, true)) {
            out.put("ok", false)
            out.put("error", "a monitor is already running; GET /capture/stop ends it")
            return out
        }
        if (musicZero && !volumeBusy.compareAndSet(false, true)) {
            monitorBusy.set(false)
            out.put("ok", false)
            out.put("error", "a volume test is running; wait for it or GET /capture/stop")
            return out
        }
        out.put("ok", true)
        out.put("usage", usageName)
        out.put("usage_code", usage)
        out.put("delay_ms", delayMs)
        out.put("prime_ms", CaptureMonitor.PRIME_MS)
        out.put("seconds_requested", seconds)
        out.put("music_zero", musicZero)
        out.put("capture_match", s.match)

        var original = -1
        var volumeStream = AudioManager.STREAM_MUSIC
        var ended = "error"
        var zeroApplied = false
        val startedAt = SystemClock.elapsedRealtime()
        var m: CaptureMonitor? = null
        try {
            original = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val player = CaptureMonitor(usageName, usage, delayMs)
            m = player
            volumeStream = player.attributes.volumeControlStream
            out.put("volume_control_stream", streamName(volumeStream))
            out.put("stream_volumes_before", volumesJson(am))
            if (musicZero) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                zeroApplied = am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                out.put("music_zero_applied", zeroApplied)
                AppLog.add(TAG, "monitor: STREAM_MUSIC set to 0 (was $original), applied=$zeroApplied")
            }
            val failure = player.start()
            if (failure != null) {
                out.put("ok", false)
                out.put("error", failure)
            } else {
                monitor = player
                out.put("audiotrack_stream_type", streamName(player.trackStreamType))
                out.put("audiotrack_buffer_frames", player.bufferFrames)
                val windowFrom = System.nanoTime()
                val deadline = startedAt + seconds * 1000L
                var duringTaken = false
                while (SystemClock.elapsedRealtime() < deadline && !s.stopRequested && player.error == null) {
                    Thread.sleep(50)
                    if (!duringTaken && SystemClock.elapsedRealtime() - startedAt >= 1500) {
                        duringTaken = true
                        out.put("stream_volumes_during", volumesJson(am))
                    }
                }
                if (!duringTaken) out.put("stream_volumes_during", volumesJson(am))
                val windowTo = System.nanoTime()
                out.put("captured", levelJson(s.levels.window(windowFrom, windowTo), (windowTo - windowFrom) / 1e9))
                ended = if (s.stopRequested) "stopped" else if (player.error != null) "error" else "completed"
            }
        } catch (t: Throwable) {
            out.put("ok", false)
            out.put("error", t.toString())
            AppLog.error(TAG, "monitor failed", t)
        } finally {
            m?.stop()
            monitor = null
            if (musicZero) {
                if (original >= 0) {
                    val back = restoreMusicVolume(am, original)
                    out.put("restored_volume", back)
                    out.put("restore_ok", back == original)
                }
                volumeBusy.set(false)
            }
            monitorBusy.set(false)
        }
        out.put("ended", ended)
        out.put("seconds_ran", num((SystemClock.elapsedRealtime() - startedAt) / 1000.0))
        try {
            out.put("stream_volumes_after", volumesJson(am))
        } catch (t: Throwable) {
            out.put("stream_volumes_after", t.toString())
        }
        if (m != null) {
            val track = JSONObject()
            track.put("frames_written", m.framesWritten)
            track.put("underruns", m.underruns)
            track.put("error", m.error ?: JSONObject.NULL)
            out.put("audiotrack", track)
        }
        out.put("note", monitorNote(out, volumeStream, musicZero, zeroApplied))
        return out
    }

    /** What the stream volumes during the run say about hearing the copy while STREAM_MUSIC is 0. */
    private fun monitorNote(out: JSONObject, volumeStream: Int, musicZero: Boolean, zeroApplied: Boolean): String {
        val key = when (volumeStream) {
            AudioManager.STREAM_ALARM -> "alarm"
            AudioManager.STREAM_ACCESSIBILITY -> "accessibility"
            AudioManager.STREAM_MUSIC -> "music"
            else -> null
        }
        val during = out.optJSONObject("stream_volumes_during")?.optJSONObject(key ?: "")
        val volume = during?.optInt("volume", -1) ?: -1
        val captured = out.optJSONObject("captured")
        val silent = captured?.optBoolean("digital_silence", false) ?: false
        return when {
            silent -> "the capture itself was digital silence during the run, so there was nothing to hear (see /capture/status)"
            !musicZero -> "listen for the copy; add music_zero=1 to test it with STREAM_MUSIC at 0"
            !zeroApplied -> "STREAM_MUSIC did not go to 0 (isVolumeFixed?), so the music_zero test says nothing"
            key == "music" -> "usage media is on STREAM_MUSIC: muted along with it, expected"
            volume == 0 -> "${streamName(volumeStream)} went to 0 with STREAM_MUSIC: it is aliased to music on this device (usual on Android TV), the copy is silent"
            volume > 0 -> "${streamName(volumeStream)} stayed at $volume with STREAM_MUSIC at 0: the copy should be audible, confirm by ear"
            else -> "stream volume during the run unknown"
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun notRunning(): JSONObject {
        val o = JSONObject()
        o.put("ok", false)
        o.put("state", session?.state ?: "idle")
        o.put("error", "capture is not running; GET /capture/start first, then check /capture/status")
        return o
    }

    private fun audioManager(context: Context): AudioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)
            ?: throw IllegalStateException("no AudioManager")

    private fun measure(s: Session, ms: Long): LevelHistory.Window {
        val from = System.nanoTime()
        sleepWhile(s, ms)
        return s.levels.window(from, System.nanoTime())
    }

    private fun sleepWhile(s: Session, ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        while (!s.stopRequested && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
    }

    private fun why(t: Throwable): String {
        val cause = t.cause
        return t.toString() + (if (cause != null && cause !== t) " <- $cause" else "")
    }

    /** Two decimals, and JSON-safe (NaN and infinity become null). */
    private fun num(v: Double): Any =
        if (v.isNaN() || v.isInfinite()) JSONObject.NULL else Math.round(v * 100.0) / 100.0

    private fun levelJson(w: LevelHistory.Window, seconds: Double): JSONObject {
        val o = JSONObject()
        o.put("seconds", num(seconds))
        o.put("frames", w.frames)
        o.put("rms_dbfs_l", num(w.rmsDbL))
        o.put("rms_dbfs_r", num(w.rmsDbR))
        o.put("peak_dbfs_l", num(w.peakDbL))
        o.put("peak_dbfs_r", num(w.peakDbR))
        o.put("digital_silence", if (w.frames > 0) w.digitalSilence else JSONObject.NULL)
        return o
    }

    private fun recordStateName(state: Int): String = when (state) {
        AudioRecord.STATE_INITIALIZED -> "INITIALIZED"
        AudioRecord.STATE_UNINITIALIZED -> "UNINITIALIZED"
        else -> "STATE_$state"
    }

    private fun recordingStateName(state: Int): String = when (state) {
        AudioRecord.RECORDSTATE_RECORDING -> "RECORDING"
        AudioRecord.RECORDSTATE_STOPPED -> "STOPPED"
        else -> "RECORDSTATE_$state"
    }

    private fun readErrorName(code: Int): String = when (code) {
        AudioRecord.ERROR -> "ERROR"
        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        else -> "read returned $code"
    }

    fun streamName(type: Int): String = when (type) {
        0 -> "STREAM_VOICE_CALL(0)"
        1 -> "STREAM_SYSTEM(1)"
        2 -> "STREAM_RING(2)"
        3 -> "STREAM_MUSIC(3)"
        4 -> "STREAM_ALARM(4)"
        5 -> "STREAM_NOTIFICATION(5)"
        6 -> "STREAM_BLUETOOTH_SCO(6)"
        7 -> "STREAM_SYSTEM_ENFORCED(7)"
        8 -> "STREAM_DTMF(8)"
        9 -> "STREAM_TTS(9)"
        10 -> "STREAM_ACCESSIBILITY(10)"
        11 -> "STREAM_ASSISTANT(11)"
        Int.MIN_VALUE -> "STREAM_DEFAULT"
        else -> "STREAM($type)"
    }

    private fun usageName(usage: Int): String = when (usage) {
        0 -> "UNKNOWN"
        1 -> "MEDIA"
        2 -> "VOICE_COMMUNICATION"
        3 -> "VOICE_COMMUNICATION_SIGNALLING"
        4 -> "ALARM"
        5 -> "NOTIFICATION"
        6 -> "NOTIFICATION_RINGTONE"
        10 -> "NOTIFICATION_EVENT"
        11 -> "ASSISTANCE_ACCESSIBILITY"
        12 -> "ASSISTANCE_NAVIGATION_GUIDANCE"
        13 -> "ASSISTANCE_SONIFICATION"
        14 -> "GAME"
        16 -> "ASSISTANT"
        else -> "USAGE_$usage"
    }

    private fun capturePolicyName(policy: Int): String = when (policy) {
        AudioAttributes.ALLOW_CAPTURE_BY_ALL -> "ALL"
        AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM -> "SYSTEM"
        AudioAttributes.ALLOW_CAPTURE_BY_NONE -> "NONE"
        else -> "policy $policy"
    }

    /** The AudioDeviceInfo TYPE_* values, by number. */
    private fun deviceTypeName(type: Int): String = when (type) {
        0 -> "UNKNOWN"
        1 -> "BUILTIN_EARPIECE"
        2 -> "BUILTIN_SPEAKER"
        3 -> "WIRED_HEADSET"
        4 -> "WIRED_HEADPHONES"
        5 -> "LINE_ANALOG"
        6 -> "LINE_DIGITAL"
        7 -> "BLUETOOTH_SCO"
        8 -> "BLUETOOTH_A2DP"
        9 -> "HDMI"
        10 -> "HDMI_ARC"
        11 -> "USB_DEVICE"
        12 -> "USB_ACCESSORY"
        13 -> "DOCK"
        14 -> "FM"
        18 -> "TELEPHONY"
        19 -> "AUX_LINE"
        20 -> "IP"
        21 -> "BUS"
        22 -> "USB_HEADSET"
        23 -> "HEARING_AID"
        24 -> "BUILTIN_SPEAKER_SAFE"
        25 -> "REMOTE_SUBMIX"
        26 -> "BLE_HEADSET"
        27 -> "BLE_SPEAKER"
        29 -> "HDMI_EARC"
        30 -> "BLE_BROADCAST"
        31 -> "DOCK_ANALOG"
        else -> "TYPE_$type"
    }

    private fun streamJson(am: AudioManager, stream: Int): JSONObject {
        val o = JSONObject()
        o.put("stream", streamName(stream))
        try {
            o.put("volume", am.getStreamVolume(stream))
            o.put("max", am.getStreamMaxVolume(stream))
            o.put("min", am.getStreamMinVolume(stream))
            o.put("muted", am.isStreamMute(stream))
        } catch (e: Exception) {
            o.put("error", e.toString())
        }
        return o
    }

    private fun volumesJson(am: AudioManager): JSONObject {
        val o = JSONObject()
        o.put("music", streamJson(am, AudioManager.STREAM_MUSIC))
        o.put("alarm", streamJson(am, AudioManager.STREAM_ALARM))
        o.put("accessibility", streamJson(am, AudioManager.STREAM_ACCESSIBILITY))
        return o
    }

    private fun devicesJson(am: AudioManager): JSONArray {
        val arr = JSONArray()
        try {
            for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                val o = JSONObject()
                o.put("id", d.id)
                o.put("type", deviceTypeName(d.type))
                o.put("product_name", d.productName?.toString() ?: JSONObject.NULL)
                o.put("sink", d.isSink)
                if (Build.VERSION.SDK_INT >= 28) o.put("address", d.address)
                arr.put(o)
            }
        } catch (e: Exception) {
            arr.put(JSONObject().put("error", e.toString()))
        }
        return arr
    }

    /** The players that are active now: their usage and capture policy (the uid is hidden from other apps). */
    private fun activePlayers(am: AudioManager): JSONArray {
        val arr = JSONArray()
        try {
            for (p in am.activePlaybackConfigurations) {
                val a = p.audioAttributes
                val o = JSONObject()
                o.put("usage", usageName(a.usage))
                o.put("content_type", a.contentType)
                o.put("flags", "0x" + Integer.toHexString(a.flags))
                o.put("allowed_capture_policy", capturePolicyName(a.allowedCapturePolicy))
                arr.put(o)
            }
        } catch (e: Exception) {
            arr.put(JSONObject().put("error", e.toString()))
        }
        return arr
    }

    // ---- facts about this app and the target app ---------------------------------------------------

    private fun prechecks(app: Context): JSONObject {
        val o = JSONObject()
        o.put("sdk_int", Build.VERSION.SDK_INT)
        o.put("record_audio_granted", app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        o.put(
            "can_draw_overlays",
            try {
                Settings.canDrawOverlays(app)
            } catch (e: Exception) {
                false
            },
        )
        o.put("project_media_appop", appOpMode(app, "android:project_media"))
        o.put("system_alert_window_appop", appOpMode(app, "android:system_alert_window"))
        return o
    }

    private fun appOpMode(app: Context, op: String): String {
        return try {
            val ops = app.getSystemService(AppOpsManager::class.java)
            when (val mode = ops.unsafeCheckOpNoThrow(op, Process.myUid(), app.packageName)) {
                AppOpsManager.MODE_ALLOWED -> "allow"
                AppOpsManager.MODE_IGNORED -> "ignore"
                AppOpsManager.MODE_ERRORED -> "deny"
                AppOpsManager.MODE_DEFAULT -> "default"
                AppOpsManager.MODE_FOREGROUND -> "foreground"
                else -> "mode $mode"
            }
        } catch (e: Exception) {
            "unknown ($e)"
        }
    }

    /** Uid and capture-relevant facts of the app to be captured. Needs QUERY_ALL_PACKAGES (Android 11+). */
    private fun packageJson(app: Context, pkg: String): JSONObject {
        val o = JSONObject()
        o.put("package", pkg)
        try {
            val pm = app.packageManager
            @Suppress("DEPRECATION")
            val ai = pm.getApplicationInfo(pkg, 0)
            o.put("installed", true)
            o.put("uid", ai.uid)
            o.put("target_sdk", ai.targetSdkVersion)
            o.put("enabled", ai.enabled)
            o.put("split_apks", ai.splitSourceDirs?.size ?: 0)
            try {
                @Suppress("DEPRECATION")
                val pi = pm.getPackageInfo(pkg, 0)
                o.put("version_name", pi.versionName ?: JSONObject.NULL)
                o.put("version_code", pi.longVersionCode)
            } catch (e: Exception) {
                // the version is only for the report
            }
            o.put("manifest_allow_audio_playback_capture", manifestAllowCapture(app, pkg, ai))
        } catch (e: PackageManager.NameNotFoundException) {
            o.put("installed", false)
            o.put("candidates", candidates(app, "spotify"))
        } catch (e: Exception) {
            o.put("error", e.toString())
        }
        return o
    }

    private fun candidates(app: Context, needle: String): JSONArray {
        val arr = JSONArray()
        try {
            @Suppress("DEPRECATION")
            val all = app.packageManager.getInstalledApplications(0)
            for (ai in all) {
                if (ai.packageName.contains(needle, ignoreCase = true)) arr.put(ai.packageName + " (uid " + ai.uid + ")")
            }
        } catch (e: Exception) {
            arr.put("error: $e")
        }
        return arr
    }

    /**
     * The android:allowAudioPlaybackCapture attribute of the target's <application>, read from its
     * base manifest (split APKs carry manifests of their own, which are skipped). Absent means
     * true for targetSdk 29 and up and false below. False makes the capture digital silence.
     */
    private fun manifestAllowCapture(app: Context, pkg: String, info: ApplicationInfo): String {
        val whenAbsent = if (info.targetSdkVersion >= 29) {
            "absent, so true (targetSdk ${info.targetSdkVersion} >= 29)"
        } else {
            "absent, so false (targetSdk ${info.targetSdkVersion} < 29)"
        }
        return try {
            val assets = app.packageManager.getResourcesForApplication(pkg).assets
            for (cookie in 0..15) {
                val parser = try {
                    assets.openXmlResourceParser(cookie, "AndroidManifest.xml")
                } catch (e: Exception) {
                    continue
                }
                try {
                    var isBase = false
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG) {
                            if (parser.name == "manifest") {
                                var packageName: String? = null
                                var split: String? = null
                                for (i in 0 until parser.attributeCount) {
                                    when (parser.getAttributeName(i)) {
                                        "package" -> packageName = parser.getAttributeValue(i)
                                        "split" -> split = parser.getAttributeValue(i)
                                    }
                                }
                                if (packageName != pkg || split != null) break // the framework's, or a split's
                                isBase = true
                            } else if (parser.name == "application" && isBase) {
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i) == "allowAudioPlaybackCapture") {
                                        return parser.getAttributeValue(i)
                                    }
                                }
                                return whenAbsent
                            }
                        }
                        event = parser.next()
                    }
                } finally {
                    parser.close()
                }
            }
            "unknown (base manifest not found)"
        } catch (t: Throwable) {
            "unknown ($t)"
        }
    }
}
