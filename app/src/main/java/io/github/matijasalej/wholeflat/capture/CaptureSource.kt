package io.github.matijasalej.wholeflat.capture

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import io.github.matijasalej.wholeflat.AppLog
import io.github.matijasalej.wholeflat.Settings
import io.github.matijasalej.wholeflat.source.AudioSource
import io.github.matijasalej.wholeflat.source.PcmSink
import io.github.matijasalej.wholeflat.source.SourceListener
import io.github.matijasalej.wholeflat.source.TrackInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The second source of music: the audio that the Cast receiver (or the Spotify TV app) plays on this box,
 * taken with Android's AudioPlaybackCapture and handed to the kitchen. It is the way around a Spotify that
 * refuses librespot: the phone casts to this box ("Den TV") as to any Chromecast, the box plays it to the
 * living room by itself, and the kitchen follows from the copy. The living room is not touched.
 *
 * What it does, all by itself and without a screen:
 *  1. waits for the grants that only adb can give (see [blocker]);
 *  2. gets the screen capture consent from a translucent activity ([CaptureConsentActivity], answered at
 *     once when PROJECT_MEDIA is allowed), puts [CaptureService] in the foreground with the type
 *     mediaProjection (Android 14 wants that order) and only then gets the MediaProjection;
 *  3. captures the uids of the two player apps as 48 kHz stereo into the [CaptureFeed] and watches the level:
 *     the [ActivityDetector] turns it into start, pause, resume and idle for the [CaptureListener];
 *  4. when the projection is lost, or anything fails, tries again with a back-off of 2, 5, 15, 30 and then 60 s.
 *
 * The source is behind the same [AudioSource] interface as any other, but its audio does not go through the
 * ring of the Spotify engine: a capture cannot be slowed down, so the feed has a buffer of its own that the
 * kitchen's stream reads from.
 */
class CaptureSource(context: Context, private val feed: CaptureFeed) : AudioSource {
    override val name = "capture"

    private val app: Context = context.applicationContext
    private val detector = ActivityDetector()
    private val retry = RetrySchedule()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attemptIds = AtomicInteger()
    private val wakeLock = Object()

    @Volatile
    private var wakeRequested = false

    @Volatile
    private var forceAttempt = false

    @Volatile
    private var running = false

    @Volatile
    private var listener: SourceListener? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var attempt: Attempt? = null

    /** The Sonos volume follows the box's music volume while capture mode is the source. */
    val volume = CaptureVolume(app) { raw -> listener?.onVolume(raw / 65_535.0) }

    // ---- status ----------------------------------------------------------------------------------

    /** off, needs_grants, awaiting_consent, starting_service, starting_capture, running or retry. */
    @Volatile
    var state = "off"
        private set

    /** Why the capture is not running, for the screen. */
    @Volatile
    var problem: String? = null
        private set

    @Volatile
    var attempts = 0
        private set

    @Volatile
    private var retryAtMs = 0L

    @Volatile
    private var lastError: String? = null

    /** Package name to uid of the player apps that are installed. */
    @Volatile
    var uids: Map<String, Int> = emptyMap()
        private set

    @Volatile
    private var mediaPlaying = false

    /** When the media sessions began to say "playing" (elapsedRealtime), 0 if they do not. */
    @Volatile
    private var mediaPlayingSince = 0L

    @Volatile
    var title: String? = null
        private set

    @Volatile
    var artist: String? = null
        private set

    private val timeline = ArrayList<String>()

    /** One try: consent, service, projection, AudioRecord, capture loop. */
    private class Attempt(val id: Int) {
        val startedAt: Long = SystemClock.elapsedRealtime()
        val tornDown = AtomicBoolean(false)

        /** Counted down when the consent activity is up, when its result is in, when the service is in the foreground. */
        val consentUp = CountDownLatch(1)
        val consent = CountDownLatch(1)
        val service = CountDownLatch(1)

        @Volatile var error: String? = null
        @Volatile var stopRequested = false
        @Volatile var resultCode = 0
        @Volatile var resultData: Intent? = null
        @Volatile var activity: Activity? = null
        @Volatile var serviceRef: CaptureService? = null
        @Volatile var projection: MediaProjection? = null
        @Volatile var callback: MediaProjection.Callback? = null
        @Volatile var record: AudioRecord? = null
        @Volatile var runningSince = 0L
        @Volatile var frames = 0L
    }

    // ---- AudioSource -----------------------------------------------------------------------------

    override fun start(sink: PcmSink, listener: SourceListener) {
        require(sink === feed) { "the capture source writes into its own feed" }
        synchronized(this) {
            if (running) return
            running = true
            this.listener = listener
        }
        instance = this
        MediaWatch.listener = { info -> onMedia(info) }
        volume.start()
        thread = Thread({ supervise() }, "wf-capture").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun stop() {
        synchronized(this) {
            if (!running) return
            running = false
        }
        if (instance === this) instance = null
        MediaWatch.listener = null
        wake()
        attempt?.let { abort(it, "the app is stopping") }
        volume.stop()
        try {
            thread?.join(4_000)
        } catch (e: InterruptedException) {
            // shutting down anyway
        }
        thread = null
    }

    /** Looks at the settings and the grants again now (a setting changed, an adb grant was given). */
    fun wake() {
        synchronized(wakeLock) {
            wakeRequested = true
            wakeLock.notifyAll()
        }
    }

    /** An attempt now, even without the grants that make it silent (the consent dialog then shows on the TV). */
    fun startNow() {
        forceAttempt = true
        retry.reset()
        wake()
    }

    /** Ends the capture in progress; the supervisor tries again at once. */
    fun restart(reason: String) {
        retry.reset()
        attempt?.let { abort(it, reason) }
        wake()
    }

    /** The Sonos follows the music volume of the box only while capture mode is the source. */
    fun setVolumeActive(on: Boolean) = volume.setActive(on)

    // ---- the supervisor --------------------------------------------------------------------------

    private fun waitFor(ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        synchronized(wakeLock) {
            while (running && !wakeRequested) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) break
                try {
                    wakeLock.wait(left)
                } catch (e: InterruptedException) {
                    break
                }
            }
            wakeRequested = false
        }
    }

    private fun supervise() {
        AppLog.add(TAG, "capture supervisor started")
        while (running) {
            try {
                if (!Settings.values.captureEnabled) {
                    setState("off", null)
                    waitFor(2_000)
                    continue
                }
                val blocked = blocker(forceAttempt)
                if (blocked != null) {
                    setState("needs_grants", blocked)
                    // adb grants take effect without a restart: look again now and then
                    waitFor(GRANT_RECHECK_MS)
                    continue
                }
                forceAttempt = false
                runAttempt()
                if (!running) break
                if (!Settings.values.captureEnabled) continue
                val delay = retry.next()
                retryAtMs = SystemClock.elapsedRealtime() + delay
                setState("retry", lastError)
                AppLog.add(TAG, "trying again in ${delay / 1000} s (attempt ${retry.attempts})")
                waitFor(delay)
            } catch (t: Throwable) {
                AppLog.error(TAG, "the capture supervisor failed", t)
                lastError = t.toString()
                waitFor(5_000)
            }
        }
        setState("off", null)
        AppLog.add(TAG, "capture supervisor ended")
    }

    /**
     * What stops the capture from starting by itself; null if nothing does. With [forced] (the button on the
     * screen, /capture/start) the consent dialog on the TV is accepted, so the app op is not asked for.
     */
    private fun blocker(forced: Boolean): String? {
        if (Build.VERSION.SDK_INT < 29) return "the capture needs Android 10 or newer"
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "RECORD_AUDIO is not granted. Run: adb shell pm grant ${app.packageName} android.permission.RECORD_AUDIO"
        }
        uids = resolveUids()
        if (uids.isEmpty()) return "neither the Cast receiver nor the Spotify TV app is installed on this box"
        val projectMedia = appOp("android:project_media")
        if (!forced && projectMedia != "allow" && projectMedia != "unknown") {
            return "the screen capture would ask on the TV. Run: adb shell appops set ${app.packageName} PROJECT_MEDIA allow"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun resolveUids(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (pkg in PACKAGES) {
            try {
                out[pkg] = app.packageManager.getApplicationInfo(pkg, 0).uid
            } catch (e: PackageManager.NameNotFoundException) {
                // this player is not installed here
            } catch (e: Exception) {
                AppLog.error(TAG, "cannot look up $pkg", e)
            }
        }
        return out
    }

    private fun appOp(op: String): String {
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
            "unknown"
        }
    }

    private fun canDrawOverlays(): Boolean {
        return try {
            android.provider.Settings.canDrawOverlays(app)
        } catch (e: Exception) {
            false
        }
    }

    private fun setState(newState: String, why: String?) {
        problem = why
        if (state == newState) return
        state = newState
        AppLog.add(TAG, "capture: $newState" + (if (why != null) " ($why)" else ""))
    }

    private fun note(a: Attempt, text: String) {
        val line = "+" + (SystemClock.elapsedRealtime() - a.startedAt) + " ms " + text
        synchronized(timeline) {
            timeline.add(line)
            while (timeline.size > MAX_TIMELINE) timeline.removeAt(0)
        }
        AppLog.add(TAG, "#${a.id} $text")
    }

    // ---- one attempt -----------------------------------------------------------------------------

    private fun runAttempt() {
        val a = Attempt(attemptIds.incrementAndGet())
        attempt = a
        attempts++
        synchronized(timeline) { timeline.clear() }
        var wasRunning = false
        try {
            setState("awaiting_consent", null)
            note(a, "asking for the screen capture consent (uids ${uids.values.joinToString()})")
            try {
                app.startActivity(
                    Intent(app, CaptureConsentActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(CaptureConsentActivity.EXTRA_ATTEMPT, a.id),
                )
            } catch (e: Exception) {
                abort(a, "cannot start the consent activity: $e")
            }
            await(
                a, a.consentUp, ACTIVITY_WAIT_MS,
                "the consent activity did not start within ${ACTIVITY_WAIT_MS / 1000} s, so Android blocked the start from the " +
                    "background. Run: adb shell appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow",
            )
            await(
                a, a.consent, CONSENT_WAIT_MS,
                "the screen capture consent was not answered within ${CONSENT_WAIT_MS / 1000} s. Run: " +
                    "adb shell appops set ${app.packageName} PROJECT_MEDIA allow",
            )
            setState("starting_service", null)
            await(a, a.service, SERVICE_WAIT_MS, "the capture service did not come up in the foreground within ${SERVICE_WAIT_MS / 1000} s")
            if (a.stopRequested) return

            val projection = makeProjection(a)
            setState("starting_capture", null)
            val record = buildRecord(projection)
            a.record = record
            note(a, "AudioRecord built: ${record.sampleRate} Hz, ${record.channelCount} ch, buffer ${record.bufferSizeInFrames} frames")
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord is not initialized after the build (state ${record.state})")
            }
            feed.open()
            feed.newGeneration(a.id.toLong())
            detector.reset()
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord is not recording after startRecording (state ${record.recordingState})")
            }
            a.runningSince = SystemClock.elapsedRealtime()
            wasRunning = true
            setState("running", null)
            lastError = null
            note(a, "capturing")
            (listener as? CaptureListener)?.onCaptureRunning(a.id.toLong())
            captureLoop(a, record)
        } catch (t: Throwable) {
            if (!a.stopRequested || a.error == null) abort(a, "capture failed: ${why(t)}")
        } finally {
            teardown(a)
            val reason = a.error ?: "stopped"
            if (a.error != null) {
                lastError = a.error
                AppLog.error(TAG, "#${a.id} ${a.error}")
            }
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            } catch (e: Exception) {
                // the priority is a nicety
            }
            if (wasRunning) {
                val ranMs = SystemClock.elapsedRealtime() - a.runningSince
                if (ranMs >= GOOD_RUN_MS) retry.reset()
                note(a, "ended after ${ranMs / 1000} s, ${a.frames} frames: $reason")
                (listener as? CaptureListener)?.onCaptureLost(reason)
            }
            feed.close()
            detector.reset()
        }
    }

    private fun await(a: Attempt, latch: CountDownLatch, ms: Long, message: String) {
        if (a.stopRequested) return
        if (!latch.await(ms, TimeUnit.MILLISECONDS)) abort(a, message)
    }

    /** Ends the attempt from any thread: the capture loop leaves, blocked waits return, a blocked read returns. */
    private fun abort(a: Attempt, reason: String) {
        if (a.error == null) a.error = reason
        a.stopRequested = true
        a.consentUp.countDown()
        a.consent.countDown()
        a.service.countDown()
        try {
            a.record?.stop() // lets a blocked read return
        } catch (e: Exception) {
            // not recording
        }
    }

    private fun makeProjection(a: Attempt): MediaProjection {
        val data = a.resultData ?: throw IllegalStateException("no consent data for the projection")
        val manager = app.getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(a.resultCode, data)
        a.resultData = null // the consent is good for one projection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                AppLog.add(TAG, "#${a.id} MediaProjection.onStop")
                if (!a.stopRequested) abort(a, "the system stopped the MediaProjection")
            }
        }
        projection.registerCallback(callback, mainHandler)
        a.projection = projection
        a.callback = callback
        note(a, "MediaProjection obtained")
        return projection
    }

    private fun buildRecord(projection: MediaProjection): AudioRecord {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        for (uid in uids.values) builder.addMatchingUid(uid)
        val config = builder.build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(feed.rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(feed.rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(if (minBytes > 0) minBytes * 4 else 0, feed.rate * 4 / 2) // at least half a second
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    private fun captureLoop(a: Attempt, record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(CHUNK_FRAMES * 2)
        val ts = AudioTimestamp()
        var lastTimestampAt = 0L
        var lastLogMs = SystemClock.elapsedRealtime()
        var readErrors = 0
        while (!a.stopRequested) {
            val n = record.read(buf, 0, buf.size)
            val tNs = System.nanoTime()
            if (n < 0) {
                if (a.stopRequested) break
                readErrors++
                if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                    abort(a, "AudioRecord.read: ERROR_DEAD_OBJECT (the projection is gone?)")
                    break
                }
                if (readErrors > 50) {
                    abort(a, "AudioRecord.read keeps failing (error $n)")
                    break
                }
                Thread.sleep(20)
                continue
            }
            if (n == 0) {
                Thread.sleep(5)
                continue
            }
            readErrors = 0
            val frames = n / 2
            val peak = feed.writeAt(buf, frames, tNs)
            a.frames += frames
            dispatch(detector.onChunk(tNs, peak))
            if (tNs - lastTimestampAt >= TIMESTAMP_EVERY_NANOS) {
                lastTimestampAt = tNs
                readTimestamp(record, ts, tNs)
            }
            if (!Settings.values.captureEnabled) {
                abort(a, "the capture was switched off")
                break
            }
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastLogMs >= LOG_EVERY_MS) {
                lastLogMs = nowMs
                AppLog.add(
                    TAG,
                    "capturing: level ${fmt(feed.levelDb())} dBFS, peak ${fmt(feed.peakDb)}, ${detector.state.name.lowercase()}, " +
                        "clock ${feed.clockSource}, longest gap ${feed.maxGapMs} ms",
                )
            }
        }
    }

    /** AudioRecord.getTimestamp: when each frame was captured, much better than the moments the reads returned. */
    private fun readTimestamp(record: AudioRecord, ts: AudioTimestamp, tNs: Long) {
        try {
            if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return
            if (ts.nanoTime <= 0L || ts.framePosition < 0L) return
            // one from the future, or from long ago, is not trusted
            if (ts.nanoTime > tNs + 50_000_000L || tNs - ts.nanoTime > 2_000_000_000L) return
            feed.onTimestamp(ts.framePosition, ts.nanoTime)
        } catch (e: Exception) {
            // the read-based clock does it
        }
    }

    /** Releases everything of [a], in the order that keeps the system happy. Safe to call twice. */
    private fun teardown(a: Attempt) {
        if (!a.tornDown.compareAndSet(false, true)) return
        a.stopRequested = true
        val record = a.record
        try {
            record?.stop()
        } catch (e: Exception) {
            // not recording
        }
        try {
            record?.release()
        } catch (e: Exception) {
            AppLog.error(TAG, "releasing the AudioRecord failed", e)
        }
        a.record = null
        val projection = a.projection
        if (projection != null) {
            try {
                a.callback?.let { projection.unregisterCallback(it) }
                projection.stop()
            } catch (e: Exception) {
                AppLog.error(TAG, "stopping the MediaProjection failed", e)
            }
        }
        a.projection = null
        a.resultData = null
        val activity = a.activity
        a.activity = null
        if (activity != null) activity.runOnUiThread { activity.finish() }
        val service = a.serviceRef
        a.serviceRef = null
        if (service != null) mainHandler.post { service.stopSelf() }
        if (attempt === a) attempt = null
    }

    private fun why(t: Throwable): String {
        val cause = t.cause
        return t.toString() + (if (cause != null && cause !== t) " <- $cause" else "")
    }

    // ---- events of the detector and of the media sessions ----------------------------------------

    private fun dispatch(events: List<ActivityDetector.Event>) {
        if (events.isEmpty()) return
        val l = listener ?: return
        val cl = l as? CaptureListener
        for (e in events) {
            AppLog.add(TAG, "activity: ${e.name.lowercase()} (level ${fmt(feed.levelDb())} dBFS)")
            when (e) {
                ActivityDetector.Event.CANDIDATE -> cl?.onCandidate()
                ActivityDetector.Event.CANDIDATE_CANCELLED -> cl?.onCandidateCancelled()
                ActivityDetector.Event.START -> l.onStart()
                ActivityDetector.Event.PAUSE -> l.onPause()
                ActivityDetector.Event.RESUME -> l.onResume()
                ActivityDetector.Event.IDLE -> cl?.onIdle()
            }
        }
    }

    private fun onMedia(info: MediaWatch.Info) {
        if (info.playing && !mediaPlaying) mediaPlayingSince = SystemClock.elapsedRealtime()
        if (!info.playing) mediaPlayingSince = 0L
        mediaPlaying = info.playing
        title = info.title
        artist = info.artist
        val l = listener ?: return
        if (info.title != null || info.artist != null) {
            l.onTrack(TrackInfo(title = info.title, artist = info.artist))
        }
        if (state == "running") dispatch(detector.hint(info.playing, System.nanoTime()))
    }

    // ---- callbacks of the activity and the service (main thread) ----------------------------------

    private fun current(id: Int): Attempt? = attempt?.takeIf { it.id == id && !it.tornDown.get() && !it.stopRequested }

    /** The consent activity is up. False if its attempt is gone (the activity then closes itself). */
    fun onConsentActivity(activity: Activity, attemptId: Int): Boolean {
        val a = current(attemptId)
        if (a == null) {
            AppLog.add(TAG, "the consent activity of attempt $attemptId is stale, closing it")
            return false
        }
        a.activity = activity
        note(a, "the consent activity is up")
        a.consentUp.countDown()
        return true
    }

    fun onConsentActivityGone(activity: Activity) {
        attempt?.let { if (it.activity === activity) it.activity = null }
    }

    fun onConsentFailed(attemptId: Int, message: String) {
        current(attemptId)?.let { abort(it, message) }
    }

    fun onConsentResult(attemptId: Int, resultCode: Int, data: Intent?) {
        val a = current(attemptId) ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            abort(
                a,
                "the screen capture consent was not granted (result $resultCode" + (if (data == null) ", no data" else "") +
                    "). Without a dialog: adb shell appops set ${app.packageName} PROJECT_MEDIA allow",
            )
            return
        }
        a.resultCode = resultCode
        a.resultData = data
        note(a, "consent granted, starting the capture service")
        try {
            app.startForegroundService(Intent(app, CaptureService::class.java).putExtra(CaptureService.EXTRA_ATTEMPT, a.id))
            a.consent.countDown()
        } catch (e: Exception) {
            abort(a, "cannot start the capture service: $e")
        }
    }

    /** The capture service is in the foreground with its type: now the projection can be had. */
    fun onServiceStarted(service: CaptureService, attemptId: Int) {
        val a = current(attemptId)
        if (a == null) {
            AppLog.add(TAG, "the capture service of attempt $attemptId is stale, stopping it")
            service.stopSelf()
            return
        }
        if (a.serviceRef != null) return // a second start command: the consent is good for one projection
        a.serviceRef = service
        note(a, "the capture service is in the foreground")
        a.service.countDown()
    }

    /** The service could not go to the foreground (it does not know its attempt yet). */
    fun onServiceFailed(message: String) {
        attempt?.let { if (!it.tornDown.get()) abort(it, message) }
    }

    fun onServiceDestroyed(service: CaptureService) {
        val a = attempt ?: return
        if (a.serviceRef === service && !a.tornDown.get() && !a.stopRequested) {
            abort(a, "the capture service was destroyed while capturing")
        }
    }

    // ---- status ----------------------------------------------------------------------------------

    private fun num(v: Double): Any = if (v.isNaN() || v.isInfinite()) JSONObject.NULL else Math.round(v * 10.0) / 10.0

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

    /** The silent time since the last sound, in seconds; -1 before the first. */
    private fun silentSeconds(): Double {
        val ns = detector.silentForNanos(System.nanoTime())
        return if (ns < 0) -1.0 else ns / 1e9
    }

    /**
     * A player whose media session says it plays while the captured audio stays silent: the app may keep
     * its audio from being captured (a newer Spotify TV app, say). Null when all is as it should be.
     */
    fun warning(): String? {
        if (state != "running") return null
        val since = mediaPlayingSince
        if (!mediaPlaying || since == 0L || SystemClock.elapsedRealtime() - since < WARN_AFTER_MS) return null
        val quietFor = silentSeconds()
        if (detector.state == ActivityDetector.State.ACTIVE || (quietFor in 0.0..WARN_QUIET_SECONDS)) return null
        val who = MediaWatch.lastInfo?.packageName?.takeIf { it.isNotEmpty() } ?: "a player"
        return "The media session of $who says it plays, but the captured audio is silent. The app may not allow its audio " +
            "to be captured (an older version of the Spotify TV app may be needed), or it plays through another path."
    }

    /** One line for the screen. */
    fun summaryText(): String {
        val sb = StringBuilder()
        when (state) {
            "running" -> {
                sb.append("running, ").append(detector.state.name.lowercase())
                sb.append(", level ").append(fmt(feed.levelDb())).append(" dBFS")
            }
            "retry" -> {
                val s = Math.max(0L, (retryAtMs - SystemClock.elapsedRealtime() + 999) / 1000)
                sb.append("not running, trying again in ").append(s).append(" s")
            }
            "needs_grants" -> sb.append("waiting for the adb grants")
            "off" -> sb.append(if (Settings.values.captureEnabled) "starting" else "switched off")
            else -> sb.append(state.replace('_', ' '))
        }
        problem?.let { sb.append("\n            ").append(it) }
        warning()?.let { sb.append("\n            ").append(it) }
        return sb.toString()
    }

    fun statusJson(): JSONObject {
        val o = JSONObject()
        o.put("enabled", Settings.values.captureEnabled)
        o.put("state", state)
        o.put("problem", problem ?: JSONObject.NULL)
        o.put("warning", warning() ?: JSONObject.NULL)
        o.put("last_error", lastError ?: JSONObject.NULL)
        o.put("attempts", attempts)
        o.put("retry_in_s", if (state == "retry") Math.max(0L, (retryAtMs - SystemClock.elapsedRealtime() + 999) / 1000) else -1)
        o.put("activity", detector.state.name.lowercase())
        o.put("level_dbfs", num(feed.levelDb()))
        o.put("peak_dbfs", num(feed.peakDb))
        o.put("silent_s", num(silentSeconds()))
        o.put("rate", feed.rate)
        o.put("frames", feed.buffer.writeIndex)
        o.put("generation", feed.generation)
        o.put("clock", feed.clockSource)
        o.put("longest_gap_ms", feed.maxGapMs)
        // true when Android silences the recording (a background restriction): the capture then reads zeros
        o.put(
            "client_silenced",
            try {
                attempt?.record?.activeRecordingConfiguration?.isClientSilenced ?: JSONObject.NULL
            } catch (e: Exception) {
                JSONObject.NULL
            },
        )
        val read = feed.lastChunkNanos
        o.put("last_chunk_age_ms", if (read == 0L) -1L else (System.nanoTime() - read) / 1_000_000L)
        val packages = JSONObject()
        for ((pkg, uid) in uids) packages.put(pkg, uid)
        o.put("packages", packages)
        val grants = JSONObject()
        grants.put("record_audio", app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        grants.put("project_media_appop", appOp("android:project_media"))
        grants.put("system_alert_window_appop", appOp("android:system_alert_window"))
        grants.put("can_draw_overlays", canDrawOverlays())
        o.put("grants", grants)
        val media = MediaWatch.statusJson()
        media.put("sessions_playing", mediaPlaying)
        o.put("media_session", media)
        o.put("volume", volume.statusJson())
        val lines = JSONArray()
        synchronized(timeline) { for (line in timeline) lines.put(line) }
        o.put("timeline", lines)
        return o
    }

    companion object {
        private const val TAG = "capture"

        /** The Cast receiver of the Chromecast (it plays what a phone casts) and the Spotify TV app. */
        val PACKAGES = listOf("com.google.android.apps.mediashell", "com.spotify.tv.android")

        /** 20 ms per read. */
        private const val CHUNK_FRAMES = 960
        private const val ACTIVITY_WAIT_MS = 8_000L
        private const val CONSENT_WAIT_MS = 15_000L
        private const val SERVICE_WAIT_MS = 8_000L
        private const val GRANT_RECHECK_MS = 10_000L
        private const val TIMESTAMP_EVERY_NANOS = 250_000_000L
        private const val LOG_EVERY_MS = 30_000L
        private const val GOOD_RUN_MS = 30_000L
        private const val WARN_AFTER_MS = 15_000L
        private const val WARN_QUIET_SECONDS = 10.0
        private const val MAX_TIMELINE = 40

        /** The running source, for the activity and the service. */
        @Volatile
        var instance: CaptureSource? = null
    }
}
