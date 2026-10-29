package io.github.matijasalej.wholeflat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.widget.Toast
import io.github.matijasalej.wholeflat.capture.CaptureFeed
import io.github.matijasalej.wholeflat.capture.CaptureListener
import io.github.matijasalej.wholeflat.capture.CaptureSource
import io.github.matijasalej.wholeflat.capture.MediaWatch
import io.github.matijasalej.wholeflat.capture.SourceArbiter
import io.github.matijasalej.wholeflat.source.NativeRingControl
import io.github.matijasalej.wholeflat.source.SourceBridge
import io.github.matijasalej.wholeflat.source.SourceStatus
import io.github.matijasalej.wholeflat.source.TrackInfo
import io.github.matijasalej.wholeflat.sync.SettingsModel
import io.github.matijasalej.wholeflat.sync.LyricsRepository
import io.github.matijasalej.wholeflat.sync.VolumeCurve
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Foreground service (type mediaPlayback) that runs the whole receiver:
 *
 * - the wake, Wi-Fi and multicast locks (libmdns needs the multicast lock before the native start),
 * - the native Spotify Connect engine (librespot, see NativeBridge),
 * - the living room: the AudioTrack pull thread (see LivingRoom),
 * - the event thread: NativeBridge.nextEvent -> status model, log, MediaSession, notification,
 * - the debug HTTP server,
 * - the capture source (the audio of the Cast receiver for the kitchen, see capture/CaptureSource.kt).
 *
 * START_STICKY, and BootReceiver starts it at boot.
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "service"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val NETWORK_WAIT_MS = 60_000L
        private const val RESTART_WINDOW_MS = 10 * 60_000L
        private const val MAX_RESTARTS_PER_WINDOW = 5
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Held while the music plays, so that the box does not go to sleep under it (see [holdAwake]). */
    private var musicLock: PowerManager.WakeLock? = null

    /** The box's screen going off (its sleep, with the CEC Standby that switches the TV off) and on, for the journal. */
    private var screenWatch: BroadcastReceiver? = null

    private fun registerScreenWatch(journal: io.github.matijasalej.wholeflat.sync.TvJournal) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val on = intent.action == Intent.ACTION_SCREEN_ON
                journal.add("box", org.json.JSONObject().put("screen", if (on) "on" else "off").put("source", AppState.source))
                if (on) tv.forgetPower()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, filter)
            screenWatch = r
        } catch (e: Exception) {
            AppLog.error(TAG, "the screen of the box cannot be watched", e)
        }
    }
    private var mediaSession: MediaSession? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var running = false

    /** Bumped whenever the event thread has to end (stop or native restart). */
    @Volatile
    private var epoch = 0

    private lateinit var livingRoom: LivingRoom
    private lateinit var kitchen: Kitchen
    private lateinit var tv: TvControl
    private lateinit var tvRenderer: TvRenderer
    private lateinit var synced: SyncedController
    private lateinit var captureFeed: CaptureFeed
    private lateinit var captureSource: CaptureSource
    private lateinit var livingReplay: LivingReplay
    private var bootThread: Thread? = null
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
        Settings.init(this)
        Storage.init(this)
        livingRoom = LivingRoom(this)
        kitchen = Kitchen(this, livingRoom)
        tv = TvControl(this)
        livingRoom.coordinator = Conductor(kitchen, tv)
        kitchen.onJoined = { toast("Kitchen connected") }
        captureFeed = CaptureFeed()
        captureSource = CaptureSource(this, captureFeed)
        // Synced on HDMI: while the Spotify app's own sound is muted, the living room is the capture played on HDMI (see LivingReplay)
        livingReplay = LivingReplay(this, captureFeed) { captureSource.state == "running" }
        kitchen.captureFeed = captureFeed
        tvRenderer = TvRenderer(this, tv)
        tvRenderer.captureFeed = captureFeed
        tvRenderer.captureAlive = { captureSource.state == "running" }
        tvRenderer.sonosTargetMs = { kitchen.lyricsTargetMs() }
        tvRenderer.track = {
            val n = MediaWatch.nowPlaying
            TvRenderer.Track(
                n?.title?.takeIf { it.isNotBlank() } ?: AppState.captureTitle?.takeIf { it.isNotBlank() } ?: DEVICE_NAME,
                n?.artist ?: AppState.captureArtist, n?.album, n?.artUri,
            )
        }
        val lyricsRepository = LyricsRepository(
            HttpLyricsFetcher("WholeFlatAudio/${BuildInfo.versionName} (https://github.com/matijasalej/flat_audio)"),
            log = { AppLog.add("lyrics", it) },
        )
        val coverCache = CoverCache()
        AppState.lyrics = lyricsRepository
        AppState.covers = coverCache
        tvRenderer.scenes = LyricsScenes(
            { AppState.nowPlaying() }, { MediaWatch.art }, coverCache, lyricsRepository, { Settings.values.lyricsOffsetMs },
            { Pair(AppState.captureTitle?.takeIf { it.isNotBlank() } ?: DEVICE_NAME, AppState.captureArtist) },
            { Settings.values.tvScroll == SettingsModel.TV_SCROLL_INSTANT },
        )
        synced = SyncedController(this, kitchen, tvRenderer, tv)
        synced.livingReplay = livingReplay
        kitchen.onSource = { source ->
            // the Sonos follows the box's music volume while the capture is the source
            captureSource.setVolumeActive(source == SourceArbiter.Source.CAPTURE)
            holdAwake(source != SourceArbiter.Source.IDLE)
            updateNotification()
            if (::synced.isInitialized) synced.tickSoon()
        }
        AppState.livingRoom = livingRoom
        AppState.kitchen = kitchen
        AppState.tv = tv
        AppState.capture = captureSource
        AppState.tvRenderer = tvRenderer
        AppState.synced = synced
        AppState.livingReplay = livingReplay
        livingReplay.start()
        // the record of the TV's switches, on disk: what came before a player that hung
        val journal = io.github.matijasalej.wholeflat.sync.TvJournal(java.io.File(filesDir, "tv_journal.jsonl"))
        AppState.tvJournal = journal
        tvRenderer.journal = journal
        tv.onPower = { from, to -> journal.add("tv_power", org.json.JSONObject().put("from", from).put("to", to)) }
        registerScreenWatch(journal)
        // the box's own HDMI output is not fed while the TV plays our stream in its DLNA player or sleeps (and nothing of Whole Flat's own plays)
        livingRoom.idleSuspended = {
            AppState.source != "librespot" && AppState.playback != "playing" && AppState.playback != "loading" &&
                (tvRenderer.state == TvRenderer.State.STARTING || tvRenderer.state == TvRenderer.State.PLAYING || tv.lastKnownPower() == "Standby")
        }
        Settings.addListener(settingsListener)
        applyVolume()
        acquireLocks()
        createMediaSession()
        registerNetworkCallback()
        livingRoom.start()
        kitchen.start()
        tv.start()
        tvRenderer.start()
        synced.start()
        DebugServer.ensureStarted(this)
        captureSource.start(captureFeed, CaptureEvents())
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
        for (t in listOf(bootThread, eventThread)) {
            try {
                t?.join(1500)
            } catch (e: InterruptedException) {
                // shutting down anyway
            }
        }
        unregisterNetworkCallback()
        try {
            if (::captureSource.isInitialized) captureSource.stop()
        } catch (t: Throwable) {
            AppLog.error(TAG, "stopping the capture failed", t)
        }
        try {
            // the TV is given back first: the lights and the screen are put as they were, the player is stopped
            if (::synced.isInitialized) synced.stop()
            if (::tvRenderer.isInitialized) tvRenderer.stop()
        } catch (t: Throwable) {
            AppLog.error(TAG, "giving the TV back failed", t)
        }
        try {
            if (::kitchen.isInitialized) kitchen.stop()
            if (::tv.isInitialized) tv.stop()
        } catch (t: Throwable) {
            AppLog.error(TAG, "stopping the kitchen failed", t)
        }
        try {
            if (::livingReplay.isInitialized) livingReplay.stop()
            if (::livingRoom.isInitialized) livingRoom.stop()
        } catch (t: Throwable) {
            AppLog.error(TAG, "stopping the living room failed", t)
        }
        AppState.livingReplay = null
        AppState.livingRoom = null
        AppState.kitchen = null
        AppState.tv = null
        AppState.capture = null
        AppState.tvRenderer = null
        AppState.synced = null
        AppState.tvJournal = null
        screenWatch?.let { r ->
            try {
                unregisterReceiver(r)
            } catch (e: Exception) {
                // not registered
            }
        }
        screenWatch = null
        AppState.source = "idle"
        Settings.removeListener(settingsListener)
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {
            AppLog.error(TAG, "releasing the media session failed", e)
        }
        mediaSession = null
        AppState.boxAwakeSinceMs = 0L
        for (lock in listOf<Any?>(multicastLock, wifiLock, wakeLock, musicLock)) {
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
            removeOldAudioCache()
            startNative()
        } catch (t: Throwable) {
            AppLog.error(TAG, "start failed", t)
            AppState.service = "error"
            AppState.lastError = "start: $t"
        }
    }

    /** The first builds kept up to 256 MB of audio in the cache; there is no audio cache any more. */
    private fun removeOldAudioCache() {
        try {
            val old = File(cacheDir, "audio")
            if (old.exists()) {
                val bytes = Storage.sizeOf(old)
                old.deleteRecursively()
                AppLog.add(TAG, "removed the old audio cache (${bytes / (1024 * 1024)} MB)")
            }
        } catch (e: Exception) {
            AppLog.error(TAG, "cannot remove the old audio cache", e)
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
        livingRoom.engineReset()
        kitchen.engineReset()
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
            for (t in listOf(eventThread)) {
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
            // made now, held only while the music plays (holdAwake)
            val music = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "wholeflat:music")
            music.setReferenceCounted(false)
            musicLock = music
            AppLog.add(TAG, "locks held: multicast=${multicastLock != null} wifi=${wifiLock != null} wake=${wakeLock != null}")
        } catch (e: Exception) {
            AppLog.error(TAG, "acquiring locks failed", e)
        }
    }

    /**
     * Keeps the box awake while the music plays ([playing]: the source is not idle; the capture becomes idle a minute after a pause).
     * The box goes to sleep when its screen timeout runs out (10 min on this box, and with the screensaver off there is no screensaver
     * first), a Cast session does not keep it awake, and its sleep sends CEC Standby to the TV: the TV and the music went off ten minutes
     * after the cast began. The release starts the timeout again (ON_AFTER_RELEASE), so the box and the TV still go off that long after
     * the music. A TV switched off with its own remote still takes the box with it: the box's sleep from CEC does not wait for the lock.
     */
    private fun holdAwake(playing: Boolean) {
        val lock = musicLock ?: return
        try {
            if (playing && !lock.isHeld) {
                lock.acquire()
                AppState.boxAwakeSinceMs = SystemClock.elapsedRealtime()
                AppLog.add(TAG, "the box is kept awake while the music plays")
            } else if (!playing && lock.isHeld) {
                lock.release()
                AppState.boxAwakeSinceMs = 0L
                AppLog.add(TAG, "the music stopped: the box may sleep again when its screen timeout runs out")
            }
        } catch (e: Exception) {
            AppLog.error(TAG, "keeping the box awake failed", e)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun toast(text: String) {
        mainHandler.post {
            try {
                Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                AppLog.error(TAG, "cannot show a toast", e)
            }
        }
    }

    // ---- a second source ----------------------------------------------------------------------

    /**
     * The plug for a source other than librespot (see source/AudioSource.kt): a source gets this as its
     * sink and its listener, and what it reports lands in the same status that the engine's events fill.
     * Nothing uses it yet.
     */
    val sourceBridge: SourceBridge by lazy { SourceBridge(NativeRingControl, SourceReports()) }

    private inner class SourceReports : SourceStatus {
        override fun trackChanged(info: TrackInfo) {
            AppState.title = info.title
            AppState.artist = info.artist
            AppState.trackUri = info.uri
            AppState.durationMs = info.durationMs
            publishMetadata()
            updateNotification()
        }

        override fun playbackChanged(state: String, positionMs: Long) {
            AppState.playback = state
            if (positionMs >= 0) AppState.setPosition(positionMs)
            publishSession(
                when (state) {
                    "playing" -> PlaybackState.STATE_PLAYING
                    "paused" -> PlaybackState.STATE_PAUSED
                    else -> PlaybackState.STATE_STOPPED
                },
            )
            updateNotification()
        }

        override fun positionChanged(positionMs: Long) {
            AppState.setPosition(positionMs)
        }

        override fun volumeChanged(volume: Double) {
            val raw = Math.round(volume * 65535).toInt()
            AppState.volumeRaw = raw
            AppState.volumePercent = Math.round(volume * 100).toInt()
            applyVolume()
            if (::kitchen.isInitialized) kitchen.onVolume(raw)
        }
    }

    /**
     * What the capture source reports: the playing and idle states go to the status, the sound and the
     * silence to the kitchen, which decides with librespot's state which of the two feeds the speaker.
     */
    private inner class CaptureEvents : CaptureListener {
        override fun onCandidate() = kitchen.captureCandidate()

        override fun onCandidateCancelled() = kitchen.captureCandidateCancelled()

        override fun onIdle() {
            AppState.capturePlayback = "idle"
            kitchen.captureIdle()
            updateNotification()
        }

        override fun onCaptureRunning(generation: Long) {
            AppState.capturePlayback = "idle"
        }

        override fun onCaptureLost(reason: String) {
            AppState.capturePlayback = "stopped"
            kitchen.captureLost(reason)
            updateNotification()
        }

        override fun onStart() {
            AppState.capturePlayback = "playing"
            kitchen.captureStarted()
            updateNotification()
        }

        override fun onPause() {
            AppState.capturePlayback = "paused"
            updateNotification()
        }

        override fun onResume() {
            AppState.capturePlayback = "playing"
            kitchen.captureStarted()
            updateNotification()
        }

        override fun onFlush(positionMs: Long) {}

        override fun onTrack(info: TrackInfo) {
            AppState.captureTitle = info.title
            AppState.captureArtist = info.artist
        }

        /** The box's music volume, as a Connect volume (0.0 to 1.0); the Sonos follows it. */
        override fun onVolume(volume: Double) {
            kitchen.onCaptureVolume(Math.round(volume * 65535).toInt())
        }

        override fun onPosition(positionMs: Long) {}
    }

    // ---- settings and volume -------------------------------------------------------------------

    private val settingsListener: (Set<String>) -> Unit = { changed ->
        if (changed.contains("living_fixed_volume")) applyVolume()
        if (changed.contains("capture_enabled") && ::captureSource.isInitialized) captureSource.wake()
        if (changed.contains("capture_volume_mirror") && ::captureSource.isInitialized) captureSource.refreshVolume()
    }

    /** The living room plays at full level, unless "living room fixed volume" is off. */
    private fun applyVolume() {
        if (!::livingRoom.isInitialized) return
        val raw = AppState.volumeRaw
        val gain = if (Settings.values.livingFixedVolume || raw < 0) {
            1.0f
        } else {
            VolumeCurve.logGain(raw / 65535.0).toFloat()
        }
        livingRoom.gain = gain
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
            // Spotify refuses the audio key; the engine holds the track and tries again (see keyguard.rs)
            "key_refused", "key_user" -> {
                AppState.updateKey(o)
                AppLog.add("spotify", AppState.keyMessage() ?: "audio key events")
            }
            "key_retry" -> {
                AppState.updateKey(o)
                AppLog.add("spotify", "trying the track again (refusal ${AppState.keyCount})")
            }
            "key_gave_up" -> {
                AppState.updateKey(o)
                AppLog.add("spotify", AppState.keyMessage() ?: "gave up")
            }
            "key_recovered" -> {
                AppLog.add("spotify", "the audio key was granted again after ${o.optInt("refusals")} refusals")
                AppState.keyTotal = o.optLong("total", AppState.keyTotal)
                AppState.clearKey()
            }
            "key_reset" -> {
                AppState.keyTotal = o.optLong("total", AppState.keyTotal)
                AppState.clearKey()
            }
            "audio_key" -> AppLog.add("spotify", "audio key request: ${o.optString("outcome")}")
            "track_changed" -> {
                AppState.title = o.optString("title").ifEmpty { null }
                AppState.artist = o.optString("artists").ifEmpty { null }
                AppState.durationMs = o.optLong("duration_ms")
                AppState.trackUri = o.optString("track_id").ifEmpty { null }
                publishMetadata()
            }
            "volume_changed" -> {
                AppState.volumePercent = o.optInt("percent", -1)
                AppState.volumeRaw = o.optInt("volume", -1)
                applyVolume()
                if (AppState.volumeRaw >= 0) kitchen.onVolume(AppState.volumeRaw)
            }
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
                if (AppState.source == "capture") {
                    sb.append(" | capture ").append(AppState.capturePlayback)
                    AppState.captureTitle?.let { sb.append(" | ").append(it) }
                } else if (AppState.playback == "playing" || AppState.playback == "paused") {
                    AppState.title?.let { sb.append(" | ").append(it) }
                }
                if (AppState.spotifyState != "ok") sb.append(" | Spotify refuses playback")
                if (::kitchen.isInitialized && Settings.values.kitchenEnabled) {
                    sb.append(" | kitchen ").append(kitchen.state.label)
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
