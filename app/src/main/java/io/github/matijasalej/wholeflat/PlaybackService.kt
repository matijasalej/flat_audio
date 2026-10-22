package io.github.matijasalej.wholeflat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Foreground service (type mediaPlayback) that runs the whole receiver:
 *
 * - the wake, Wi-Fi and multicast locks (libmdns needs the multicast lock before the native start),
 * - the native Spotify Connect engine (librespot, see NativeBridge),
 * - the audio thread: NativeBridge.readPcm -> AudioTrack,
 * - the event thread: NativeBridge.nextEvent -> status model, log, MediaSession, notification,
 * - the debug HTTP server.
 *
 * START_STICKY, and BootReceiver starts it at boot.
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "service"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 44_100
        private const val BUFFER_MS = 200
        private const val IDLE_PAUSE_MS = 500L
        private const val NETWORK_WAIT_MS = 60_000L
        private const val RESTART_WINDOW_MS = 10 * 60_000L
        private const val MAX_RESTARTS_PER_WINDOW = 5
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var running = false

    /** Bumped whenever the audio and event threads have to end (stop or native restart). */
    @Volatile
    private var epoch = 0

    private var bootThread: Thread? = null
    private var audioThread: Thread? = null
    private var eventThread: Thread? = null
    private val restartTimes = ArrayList<Long>()
    private var lastNotificationText = ""

    override fun onCreate() {
        super.onCreate()
        BuildInfo.init(this)
        AppLog.add(
            TAG,
            "onCreate, app ${BuildInfo.versionName}, Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), " +
                "abis ${Build.SUPPORTED_ABIS.joinToString()}",
        )
        createChannel()
        // startForegroundService() demands this within a few seconds, so before any real work.
        try {
            startInForeground("Starting")
        } catch (e: Exception) {
            AppLog.error(TAG, "startForeground refused", e)
            AppState.service = "error"
            AppState.lastError = "startForeground: $e"
            stopSelf()
            return
        }
        AppState.service = "starting"
        acquireLocks()
        createMediaSession()
        registerNetworkCallback()
        DebugServer.ensureStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            bootThread = thread(name = "wf-boot") { bootstrap() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.add(TAG, "onDestroy")
        running = false
        epoch++
        try {
            NativeBridge.stop()
        } catch (t: Throwable) {
            AppLog.error(TAG, "NativeBridge.stop failed", t)
        }
        for (t in listOf(bootThread, audioThread, eventThread)) {
            try {
                t?.join(1500)
            } catch (e: InterruptedException) {
                // shutting down anyway
            }
        }
        unregisterNetworkCallback()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {
            AppLog.error(TAG, "releasing the media session failed", e)
        }
        mediaSession = null
        for (lock in listOf<Any?>(multicastLock, wifiLock, wakeLock)) {
            try {
                when (lock) {
                    is WifiManager.MulticastLock -> if (lock.isHeld) lock.release()
                    is WifiManager.WifiLock -> if (lock.isHeld) lock.release()
                    is PowerManager.WakeLock -> if (lock.isHeld) lock.release()
                }
            } catch (e: Exception) {
                AppLog.error(TAG, "releasing a lock failed", e)
            }
        }
        DebugServer.stop()
        AppState.service = "stopped"
        AppState.session = "idle"
        AppState.sessionUser = null
        AppState.playback = "stopped"
        AppState.audio = "idle"
        super.onDestroy()
    }

    // ---- start up -----------------------------------------------------------------------------

    private fun bootstrap() {
        try {
            waitForNetwork()
            if (!running) return
            try {
                Os.setenv("TMPDIR", cacheDir.absolutePath, true)
            } catch (e: Exception) {
                AppLog.error(TAG, "cannot set TMPDIR", e)
            }
            startNative()
        } catch (t: Throwable) {
            AppLog.error(TAG, "start failed", t)
            AppState.service = "error"
            AppState.lastError = "start: $t"
        }
    }

    private fun startNative(): Boolean {
        val loadError = NativeBridge.loadError
        if (loadError != null) {
            AppState.service = "error"
            AppState.lastError = "native library: $loadError"
            AppLog.error(TAG, "native library did not load: $loadError")
            updateNotification()
            return false
        }
        val started = try {
            NativeBridge.start(filesDir.absolutePath, cacheDir.absolutePath, DEVICE_NAME)
        } catch (t: Throwable) {
            AppLog.error(TAG, "NativeBridge.start threw", t)
            false
        }
        if (!started) {
            AppState.service = "error"
            AppState.lastError = "NativeBridge.start returned false"
            AppLog.error(TAG, "the native engine did not start")
            updateNotification()
            return false
        }
        AppState.nativeVersion = try {
            NativeBridge.version()
        } catch (t: Throwable) {
            "?"
        }
        AppState.service = "running"
        AppLog.add(TAG, "native engine started: ${AppState.nativeVersion}")
        val myEpoch = epoch
        audioThread = thread(name = "wf-audio") { audioLoop(myEpoch) }
        eventThread = thread(name = "wf-events") { eventLoop(myEpoch) }
        updateNotification()
        return true
    }

    /**
     * Restarts the engine after it ended on its own (a panic, a dead player thread). Runs on its
     * own thread because it has to wait for the audio and event threads to leave.
     */
    private fun scheduleNativeRestart(reason: String) {
        thread(name = "wf-restart") {
            val now = SystemClock.elapsedRealtime()
            restartTimes.removeAll { now - it > RESTART_WINDOW_MS }
            if (restartTimes.size >= MAX_RESTARTS_PER_WINDOW) {
                AppLog.error(TAG, "native engine failed $MAX_RESTARTS_PER_WINDOW times in 10 minutes, giving up ($reason)")
                AppState.service = "error"
                AppState.lastError = "native engine keeps failing: $reason"
                updateNotification()
                return@thread
            }
            restartTimes.add(now)
            AppState.nativeRestarts++
            AppLog.add(TAG, "restarting the native engine in 3 s ($reason)")
            epoch++
            try {
                NativeBridge.stop()
            } catch (t: Throwable) {
                AppLog.error(TAG, "NativeBridge.stop failed", t)
            }
            for (t in listOf(audioThread, eventThread)) {
                try {
                    if (t !== Thread.currentThread()) t?.join(2000)
                } catch (e: InterruptedException) {
                    return@thread
                }
            }
            try {
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                return@thread
            }
            if (running) startNative()
        }
    }

    private fun waitForNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + NETWORK_WAIT_MS
        while (running && SystemClock.elapsedRealtime() < deadline) {
            if (networkUp(cm)) {
                AppLog.add(TAG, "network is up (${Net.primaryIp()})")
                return
            }
            Thread.sleep(500)
        }
        AppLog.add(TAG, "no network after ${NETWORK_WAIT_MS / 1000} s, starting anyway; discovery keeps retrying")
    }

    private fun networkUp(cm: ConnectivityManager?): Boolean {
        if (cm == null) return Net.primaryIp() != null
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && Net.primaryIp() != null
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    AppState.network = "up"
                    AppLog.add(TAG, "network available (${Net.primaryIp()})")
                }

                override fun onLost(network: Network) {
                    AppState.network = "down"
                    AppLog.add(TAG, "network lost")
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            AppLog.error(TAG, "cannot watch the network", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // not registered any more
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi != null) {
                // libmdns (Spotify Connect discovery) receives multicast; Android drops it without this.
                val multicast = wifi.createMulticastLock("wholeflat-mdns")
                multicast.setReferenceCounted(false)
                multicast.acquire()
                multicastLock = multicast

                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                val wifiLock = wifi.createWifiLock(mode, "wholeflat-wifi")
                wifiLock.setReferenceCounted(false)
                wifiLock.acquire()
                this.wifiLock = wifiLock
            } else {
                AppLog.add(TAG, "no WifiManager, running without Wi-Fi and multicast locks")
            }
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wholeflat:playback")
            wake.setReferenceCounted(false)
            wake.acquire()
            wakeLock = wake
            AppLog.add(TAG, "locks held: multicast=${multicastLock != null} wifi=${wifiLock != null} wake=${wakeLock != null}")
        } catch (e: Exception) {
            AppLog.error(TAG, "acquiring locks failed", e)
        }
    }

    // ---- audio ---------------------------------------------------------------------------------

    private fun createTrack(): AudioTrack? {
        return try {
            val minBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val wantBytes = SAMPLE_RATE * 4 * BUFFER_MS / 1000 // 4 bytes per stereo frame
            val bytes = maxOf(minBytes, wantBytes) / 4 * 4
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                AppLog.error(TAG, "AudioTrack did not initialise")
                null
            } else {
                AppLog.add(TAG, "AudioTrack open: $bytes bytes buffer (min $minBytes), session ${track.audioSessionId}")
                track
            }
        } catch (e: Exception) {
            AppLog.error(TAG, "cannot create the AudioTrack", e)
            null
        }
    }

    private fun pauseTrack(track: AudioTrack?) {
        try {
            track?.pause()
            track?.flush()
        } catch (e: IllegalStateException) {
            AppLog.error(TAG, "AudioTrack.pause failed", e)
        }
    }

    private fun audioLoop(myEpoch: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(4096) // 2048 stereo frames, about 46 ms
        var track: AudioTrack? = null
        var playing = false
        var lastData = SystemClock.elapsedRealtime()
        var lastStats = lastData
        try {
            while (running && epoch == myEpoch) {
                val n = NativeBridge.readPcm(buffer, 100)
                val now = SystemClock.elapsedRealtime()
                if (n < 0) break
                if (n == 0) {
                    // No data: after about half a second the stream is paused or between tracks.
                    if (playing && now - lastData >= IDLE_PAUSE_MS) {
                        pauseTrack(track)
                        playing = false
                        AppState.audio = "paused"
                    }
                    continue
                }
                lastData = now
                var current = track
                if (current == null) {
                    current = createTrack()
                    if (current == null) {
                        Thread.sleep(1000)
                        continue
                    }
                    track = current
                }
                if (!playing) {
                    current.play()
                    playing = true
                    AppState.audio = "playing"
                }
                var offset = 0
                while (offset < n && running) {
                    val written = current.write(buffer, offset, n - offset)
                    if (written < 0) {
                        AppLog.error(TAG, "AudioTrack.write returned $written, reopening the track")
                        current.release()
                        track = null
                        playing = false
                        break
                    }
                    if (written == 0) Thread.sleep(5)
                    offset += written
                }
                AppState.framesWritten += n / 2
                if (now - lastStats >= 1000) {
                    lastStats = now
                    track?.let { AppState.underruns = it.underrunCount }
                }
            }
        } catch (t: Throwable) {
            AppLog.error(TAG, "audio thread died", t)
        } finally {
            try {
                track?.pause()
                track?.flush()
                track?.release()
            } catch (e: Exception) {
                // released already
            }
            AppState.audio = "idle"
            AppLog.add(TAG, "audio thread ended")
        }
    }

    // ---- events --------------------------------------------------------------------------------

    private fun eventLoop(myEpoch: Int) {
        try {
            while (running && epoch == myEpoch) {
                val started = SystemClock.elapsedRealtime()
                val line = NativeBridge.nextEvent(1000)
                if (line == null) {
                    // A null that came back at once means the engine is gone; do not spin.
                    if (SystemClock.elapsedRealtime() - started < 20) Thread.sleep(250)
                    continue
                }
                handleEvent(line)
            }
        } catch (t: Throwable) {
            AppLog.error(TAG, "event thread died", t)
        } finally {
            AppLog.add(TAG, "event thread ended")
        }
    }

    private fun handleEvent(line: String) {
        AppState.eventCount++
        AppState.lastEventAt = SystemClock.elapsedRealtime()
        AppLog.add("native", if (line.length > 400) line.substring(0, 400) + "..." else line)
        val o = try {
            JSONObject(line)
        } catch (e: Exception) {
            return
        }
        when (o.optString("t")) {
            "discovery_started" -> if (AppState.session != "connected") AppState.session = "idle"
            "credentials_received" -> AppState.session = "pairing"
            "session_connected" -> {
                AppState.session = "connected"
                AppState.sessionUser = o.optString("user").ifEmpty { null }
                AppState.lastError = null
            }
            "session_lost" -> {
                AppState.session = "lost"
                AppState.playback = "stopped"
                publishSession(PlaybackState.STATE_STOPPED)
            }
            "loading" -> {
                AppState.playback = "loading"
                AppState.trackUri = o.optString("track_id").ifEmpty { null }
                AppState.setPosition(o.optLong("position_ms"))
                publishSession(PlaybackState.STATE_BUFFERING)
            }
            "playing" -> {
                AppState.playback = "playing"
                AppState.trackUri = o.optString("track_id").ifEmpty { null }
                AppState.setPosition(o.optLong("position_ms"))
                publishSession(PlaybackState.STATE_PLAYING)
            }
            "paused" -> {
                AppState.playback = "paused"
                AppState.trackUri = o.optString("track_id").ifEmpty { null }
                AppState.setPosition(o.optLong("position_ms"))
                publishSession(PlaybackState.STATE_PAUSED)
            }
            "stopped" -> {
                AppState.playback = "stopped"
                AppState.setPosition(0)
                publishSession(PlaybackState.STATE_STOPPED)
            }
            "seeked", "position_correction", "position_changed" -> {
                AppState.setPosition(o.optLong("position_ms"))
                publishSession(
                    if (AppState.playback == "playing") PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                )
            }
            "unavailable" -> AppState.lastError = "track unavailable: ${o.optString("track_id")}"
            "track_changed" -> {
                AppState.title = o.optString("title").ifEmpty { null }
                AppState.artist = o.optString("artists").ifEmpty { null }
                AppState.durationMs = o.optLong("duration_ms")
                AppState.trackUri = o.optString("track_id").ifEmpty { null }
                publishMetadata()
            }
            "volume_changed" -> AppState.volumePercent = o.optInt("percent", -1)
            "error" -> AppState.lastError = o.optString("where") + ": " + o.optString("message")
            "engine_exit" -> {
                if (o.optBoolean("unexpected") && running) {
                    AppState.session = "idle"
                    AppState.lastError = "engine: " + o.optString("reason")
                    scheduleNativeRestart(o.optString("reason"))
                }
            }
        }
        updateNotification()
    }

    // ---- media session and notification ---------------------------------------------------------

    private fun createMediaSession() {
        try {
            val session = MediaSession(this, "WholeFlat")
            session.setCallback(object : MediaSession.Callback() {})
            session.isActive = true
            mediaSession = session
            publishSession(PlaybackState.STATE_NONE)
        } catch (e: Exception) {
            AppLog.error(TAG, "cannot create the media session", e)
        }
    }

    private fun publishSession(state: Int) {
        val session = mediaSession ?: return
        try {
            val speed = if (state == PlaybackState.STATE_PLAYING) 1.0f else 0.0f
            val playbackState = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(state, AppState.positionMs, speed, SystemClock.elapsedRealtime())
                .build()
            session.setPlaybackState(playbackState)
        } catch (e: Exception) {
            AppLog.error(TAG, "media session state failed", e)
        }
    }

    private fun publishMetadata() {
        val session = mediaSession ?: return
        try {
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, AppState.title ?: DEVICE_NAME)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, AppState.artist ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, AppState.durationMs)
                .build()
            session.setMetadata(metadata)
        } catch (e: Exception) {
            AppLog.error(TAG, "media session metadata failed", e)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback service", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notificationText(): String {
        val sb = StringBuilder(DEVICE_NAME)
        sb.append(": ")
        when (AppState.service) {
            "running" -> {
                sb.append(
                    when (AppState.session) {
                        "connected" -> "connected"
                        "lost" -> "reconnecting"
                        "pairing" -> "pairing"
                        else -> "waiting for Spotify"
                    },
                )
                if (AppState.playback == "playing" || AppState.playback == "paused") {
                    AppState.title?.let { sb.append(" | ").append(it) }
                }
            }
            "error" -> sb.append("error, see the debug page")
            else -> sb.append(AppState.service)
        }
        return sb.toString()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Whole Flat Audio")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        lastNotificationText = text
    }

    private fun updateNotification() {
        val text = notificationText()
        if (text == lastNotificationText) return
        lastNotificationText = text
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            // POST_NOTIFICATIONS may be denied; the service runs regardless
        }
    }
}
