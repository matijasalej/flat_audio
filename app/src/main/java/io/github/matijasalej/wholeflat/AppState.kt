package io.github.matijasalej.wholeflat

import android.os.SystemClock
import android.util.Log
import io.github.matijasalej.wholeflat.capture.CaptureSource
import io.github.matijasalej.wholeflat.capture.MediaWatch
import io.github.matijasalej.wholeflat.sync.KeyMessage
import io.github.matijasalej.wholeflat.sync.LyricsRepository
import io.github.matijasalej.wholeflat.sync.NowPlaying
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Name the device shows up under in the Spotify app. */
const val DEVICE_NAME = "Whole Flat"

/** In-memory log (about 2000 lines) behind the debug page, `/log` and the status screen. */
object AppLog {
    private const val MAX_LINES = 2000
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(tag: String, message: String) {
        Log.i("WholeFlat", "[$tag] $message")
        synchronized(lines) {
            lines.addLast(timeFormat.format(Date()) + " [" + tag + "] " + message)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
    }

    fun error(tag: String, message: String, t: Throwable? = null) {
        val suffix = if (t != null) " ($t)" else ""
        Log.e("WholeFlat", "[$tag] $message$suffix", t)
        add(tag, "ERROR $message$suffix")
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

    fun tail(count: Int): List<String> = synchronized(lines) {
        val all = lines.toList()
        if (all.size <= count) all else all.subList(all.size - count, all.size)
    }
}

/** What the status screen, `/status.json` and the debug page show. Written from several threads. */
object AppState {
    val startedAt: Long = SystemClock.elapsedRealtime()

    /** stopped, starting, running, error */
    @Volatile var service: String = "stopped"
    @Volatile var nativeVersion: String = "-"
    @Volatile var deviceName: String = DEVICE_NAME

    /**
     * Where the music that feeds the speakers comes from (see source/AudioSource.kt): "librespot" (Spotify Connect,
     * "Whole Flat"), "capture" (what the Cast receiver or the Spotify TV app plays on this box) or "idle".
     * librespot wins when both play.
     */
    @Volatile var source: String = "idle"

    /** Since when the service keeps the box awake for the music (elapsedRealtime), 0 while it does not. */
    @Volatile var boxAwakeSinceMs: Long = 0L

    /** The capture source's own state: stopped, idle (running, nothing plays), playing, paused; the track of its media session. */
    @Volatile var capturePlayback: String = "stopped"
    @Volatile var captureTitle: String? = null
    @Volatile var captureArtist: String? = null

    /** idle (waiting for a phone to pick the device), connected, lost */
    @Volatile var session: String = "idle"
    @Volatile var sessionUser: String? = null

    /** stopped, loading, playing, paused */
    @Volatile var playback: String = "stopped"
    @Volatile var title: String? = null
    @Volatile var artist: String? = null
    @Volatile var trackUri: String? = null
    @Volatile var durationMs: Long = 0
    @Volatile var positionMs: Long = 0
    @Volatile var positionAt: Long = 0

    /** Spotify volume 0..100, -1 until the first report. */
    @Volatile var volumePercent: Int = -1

    /** The same volume as librespot has it, 0..65535, -1 until the first report. */
    @Volatile var volumeRaw: Int = -1

    /** The rooms and the TV, for their status; set by the service. */
    @Volatile var livingRoom: LivingRoom? = null
    @Volatile var kitchen: Kitchen? = null
    @Volatile var tv: TvControl? = null
    @Volatile var capture: CaptureSource? = null
    @Volatile var tvRenderer: TvRenderer? = null
    @Volatile var synced: SyncedController? = null

    /** The living room as the capture replayed on HDMI while the Spotify app is muted (Synced on HDMI, `/living/replay`). */
    @Volatile var livingReplay: LivingReplay? = null

    /** Every switch of the TV to its player, every end of such a stream, the TV's power and the box's sleeps, on disk (`/tv/journal`). */
    @Volatile var tvJournal: io.github.matijasalej.wholeflat.sync.TvJournal? = null

    /** The lyrics of the track that plays, and its cover: the lyrics picture of the TV and the phone page read them. */
    @Volatile var lyrics: LyricsRepository? = null
    @Volatile var covers: CoverCache? = null

    /**
     * The track that plays now with its position: Whole Flat's own Spotify Connect playback when that is the source, else what the media
     * session of the captured player says. Null while there is none.
     */
    fun nowPlaying(): NowPlaying? {
        if (source == "librespot") {
            val t = title ?: return null
            val at = if (positionAt > 0L) NowPlaying.toNanoTime(positionAt, SystemClock.elapsedRealtime(), System.nanoTime()) else System.nanoTime()
            return NowPlaying("librespot", playback == "playing", t, artist, null, durationMs, trackUri, null, positionMs, at, 1.0)
        }
        return MediaWatch.nowPlaying
    }

    /**
     * ok, key_refused (Spotify refuses the audio key of this account: the engine holds the track and tries
     * again with a back-off) or key_gave_up (it kept refusing for 30 minutes: press play on the phone).
     */
    @Volatile var spotifyState: String = "ok"

    /** Refusals in the current episode, and since the app started. */
    @Volatile var keyCount: Int = 0
    @Volatile var keyTotal: Long = 0

    /** The engine's phase: ok, waiting, retrying, user_paused, gave_up. */
    @Volatile var keyPhase: String = "ok"

    /** [SystemClock.elapsedRealtime] of the next try, 0 if none is scheduled. */
    @Volatile var keyRetryAt: Long = 0

    @Volatile var network: String = "unknown"
    @Volatile var lastError: String? = null
    @Volatile var lastEventAt: Long = 0
    @Volatile var eventCount: Long = 0
    @Volatile var nativeRestarts: Int = 0

    fun setPosition(ms: Long) {
        positionMs = ms
        positionAt = SystemClock.elapsedRealtime()
    }

    fun currentPositionMs(): Long {
        val base = positionMs
        return if (playback == "playing") {
            base + (SystemClock.elapsedRealtime() - positionAt)
        } else {
            base
        }
    }

    /** Seconds until the next try of a refused track (rounded up), -1 if none is scheduled. */
    fun retryInSeconds(): Long {
        val at = keyRetryAt
        if (at == 0L || spotifyState == "ok") return -1
        val left = at - SystemClock.elapsedRealtime()
        return if (left <= 0) 0 else (left + 999) / 1000
    }

    /** From the fields of a key_* event of the engine. */
    fun updateKey(fields: JSONObject) {
        val phase = fields.optString("phase", "ok")
        keyPhase = phase
        keyCount = fields.optInt("count", 0)
        keyTotal = fields.optLong("total", keyTotal)
        val retryIn = fields.optLong("retry_in_s", -1)
        keyRetryAt = if (retryIn >= 0 && phase == "waiting") SystemClock.elapsedRealtime() + retryIn * 1000 else 0
        spotifyState = when (phase) {
            "ok" -> "ok"
            "gave_up" -> "key_gave_up"
            else -> "key_refused"
        }
    }

    fun clearKey() {
        spotifyState = "ok"
        keyPhase = "ok"
        keyCount = 0
        keyRetryAt = 0
    }

    /** The explanation for the screen when Spotify refuses playback, null when all is well. */
    fun keyMessage(): String? = KeyMessage.text(spotifyState, keyPhase, retryInSeconds(), keyCount)

    /** What the screen should say in its warning banner (Spotify refusing playback, low storage), null if nothing. */
    fun banner(): String? {
        val lines = ArrayList<String>()
        keyMessage()?.let { lines.add(it) }
        val storage = Storage.info()
        if (storage.low) {
            lines.add(
                "Storage is low: ${storage.freeMb} MB free on /data. Clear the cache of other apps " +
                    "(Spotify's fills this box), or updates of this app will not install",
            )
        }
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }

    private fun ageSeconds(at: Long): Long =
        if (at == 0L) -1 else (SystemClock.elapsedRealtime() - at) / 1000

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("service", service)
        o.put("device_name", deviceName)
        o.put("source", source)
        val awake = JSONObject()
        awake.put("held", boxAwakeSinceMs != 0L)
        awake.put("since_s", if (boxAwakeSinceMs != 0L) ageSeconds(boxAwakeSinceMs) else JSONObject.NULL)
        o.put("box_awake", awake)
        o.put("app_version", BuildInfo.versionName)
        o.put("native", nativeVersion)
        o.put("session", session)
        o.put("session_user", sessionUser ?: JSONObject.NULL)
        o.put("spotify_state", spotifyState)
        o.put("retry_in_s", retryInSeconds())
        o.put("count", keyCount)
        val key = JSONObject()
        key.put("phase", keyPhase)
        key.put("refusals", keyCount)
        key.put("refusals_total", keyTotal)
        key.put("retry_in_s", retryInSeconds())
        key.put("message", keyMessage() ?: JSONObject.NULL)
        o.put("spotify_key", key)
        o.put("storage", Storage.toJson())
        o.put("playback", playback)
        o.put("title", title ?: JSONObject.NULL)
        o.put("artist", artist ?: JSONObject.NULL)
        o.put("track_uri", trackUri ?: JSONObject.NULL)
        o.put("duration_ms", durationMs)
        o.put("position_ms", currentPositionMs())
        o.put("volume_percent", volumePercent)
        o.put("volume_raw", volumeRaw)
        o.put("living_room", livingRoom?.statusJson() ?: JSONObject.NULL)
        o.put("kitchen", kitchen?.statusJson() ?: JSONObject.NULL)
        o.put("tv", tv?.statusJson() ?: JSONObject.NULL)
        o.put("synced", synced?.statusJson() ?: JSONObject.NULL)
        o.put("living_replay", livingReplay?.statusJson() ?: JSONObject.NULL)
        val captureJson = capture?.statusJson()
        if (captureJson != null) {
            captureJson.put("playback", capturePlayback)
            captureJson.put("title", captureTitle ?: JSONObject.NULL)
            captureJson.put("artist", captureArtist ?: JSONObject.NULL)
        }
        o.put("capture", captureJson ?: JSONObject.NULL)
        o.put("settings", Settings.toJson())
        o.put("network", network)
        o.put("last_error", lastError ?: JSONObject.NULL)
        o.put("events", eventCount)
        o.put("last_event_age_s", ageSeconds(lastEventAt))
        o.put("native_restarts", nativeRestarts)
        o.put("uptime_s", (SystemClock.elapsedRealtime() - startedAt) / 1000)
        return o
    }

    fun toText(): String {
        val sb = StringBuilder()
        sb.append("Service   : ").append(service).append("  (").append(deviceName).append(")\n")
        sb.append("Spotify   : ").append(session)
        sessionUser?.let { sb.append("  as ").append(it) }
        sb.append("\n")
        val captured = source == "capture"
        sb.append("Source    : ").append(source)
        if (captured) sb.append("  (what the Cast receiver plays)")
        sb.append("\n")
        capture?.let { sb.append("Capture   : ").append(it.summaryText()).append("\n") }
        val shownTitle = if (captured) captureTitle else title
        val shownArtist = if (captured) captureArtist else artist
        sb.append("Playback  : ").append(if (captured) capturePlayback else playback)
        if (!shownTitle.isNullOrEmpty()) {
            sb.append("  ").append(shownTitle)
            if (!shownArtist.isNullOrEmpty()) sb.append(" - ").append(shownArtist)
        }
        sb.append("\n")
        if (playback != "stopped" && !captured) {
            sb.append("Position  : ").append(formatMs(currentPositionMs()))
            if (durationMs > 0) sb.append(" / ").append(formatMs(durationMs))
            sb.append("\n")
        }
        sb.append("Volume    : ").append(if (volumePercent >= 0) "$volumePercent %" else "-").append("\n")
        livingRoom?.let { lr ->
            sb.append("Living    : ").append(lr.state)
            if (lr.dataFlowing) sb.append("  ").append(lr.epochKind)
            sb.append("  underruns ").append(lr.underruns)
            sb.append("  stalls ").append(lr.stalls)
            sb.append("  recoveries ").append(lr.recoveries).append("\n")
        }
        kitchen?.let { k ->
            val v = Settings.values
            sb.append("Kitchen   : ").append(k.state.label)
            if (!v.kitchenEnabled) sb.append("  (switched off)")
            sb.append("  offset ").append(v.kitchenOffsetMs).append(" ms")
            sb.append(k.lagText())
            sb.append("  rejoins ").append(k.rejoins)
            k.lastError?.let { sb.append("\n            last problem: ").append(it) }
            sb.append("\n")
        }
        sb.append("Network   : ").append(network).append("\n")
        sb.append("Storage   : ").append(Storage.text()).append("\n")
        sb.append("Events    : ").append(eventCount)
        val age = ageSeconds(lastEventAt)
        if (age >= 0) sb.append("  (last ").append(age).append(" s ago)")
        sb.append("\n")
        sb.append("Native    : ").append(nativeVersion)
        if (nativeRestarts > 0) sb.append("  restarts ").append(nativeRestarts)
        sb.append("\n")
        lastError?.let { sb.append("Last error: ").append(it).append("\n") }
        sb.append("Debug page: http://").append(Net.primaryIp() ?: "<tv-ip>").append(":").append(DebugServer.PORT)
        return sb.toString()
    }

    private fun formatMs(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }
}
