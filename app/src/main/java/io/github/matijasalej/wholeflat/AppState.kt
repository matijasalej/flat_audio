package io.github.matijasalej.wholeflat

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Name the device shows up under in the Spotify app. */
const val DEVICE_NAME = "Whole Flat"

/**
 * capture-probe build: the native Spotify engine is never started (NativeBridge is not even
 * touched), so no Spotify Connect device is announced and no audio keys are requested. The service
 * still holds the locks and the foreground notification and runs the debug server, which carries
 * the capture routes (see CaptureProbe). Set to false to get the normal receiver back.
 */
const val PROBE_MODE = true

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

    /** idle, playing, paused (no data) */
    @Volatile var audio: String = "idle"
    @Volatile var framesWritten: Long = 0
    @Volatile var underruns: Int = 0

    @Volatile var network: String = "unknown"
    @Volatile var lastError: String? = null
    @Volatile var lastEventAt: Long = 0
    @Volatile var eventCount: Long = 0
    @Volatile var nativeRestarts: Int = 0

    /** Last result of a Sonos action from the buttons. */
    @Volatile var lastSonos: String = ""

    /** One line about the capture probe: idle, awaiting_consent, running with levels, failed. */
    @Volatile var capture: String = "idle"

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

    private fun ageSeconds(at: Long): Long =
        if (at == 0L) -1 else (SystemClock.elapsedRealtime() - at) / 1000

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("service", service)
        o.put("device_name", deviceName)
        o.put("app_version", BuildInfo.versionName)
        o.put("native", nativeVersion)
        o.put("session", session)
        o.put("session_user", sessionUser ?: JSONObject.NULL)
        o.put("playback", playback)
        o.put("title", title ?: JSONObject.NULL)
        o.put("artist", artist ?: JSONObject.NULL)
        o.put("track_uri", trackUri ?: JSONObject.NULL)
        o.put("duration_ms", durationMs)
        o.put("position_ms", currentPositionMs())
        o.put("volume_percent", volumePercent)
        o.put("audio", audio)
        o.put("frames_written", framesWritten)
        o.put("underruns", underruns)
        o.put("network", network)
        o.put("last_error", lastError ?: JSONObject.NULL)
        o.put("events", eventCount)
        o.put("last_event_age_s", ageSeconds(lastEventAt))
        o.put("native_restarts", nativeRestarts)
        o.put("uptime_s", (SystemClock.elapsedRealtime() - startedAt) / 1000)
        o.put("last_sonos", lastSonos)
        o.put("probe_mode", PROBE_MODE)
        o.put("capture", capture)
        return o
    }

    fun toText(): String {
        val sb = StringBuilder()
        sb.append("Service   : ").append(service).append("  (").append(deviceName).append(")\n")
        sb.append("Spotify   : ").append(session)
        sessionUser?.let { sb.append("  as ").append(it) }
        sb.append("\n")
        sb.append("Playback  : ").append(playback)
        if (!title.isNullOrEmpty()) {
            sb.append("  ").append(title)
            if (!artist.isNullOrEmpty()) sb.append(" - ").append(artist)
        }
        sb.append("\n")
        if (playback != "stopped") {
            sb.append("Position  : ").append(formatMs(currentPositionMs()))
            if (durationMs > 0) sb.append(" / ").append(formatMs(durationMs))
            sb.append("\n")
        }
        sb.append("Volume    : ").append(if (volumePercent >= 0) "$volumePercent %" else "-").append("\n")
        sb.append("Audio     : ").append(audio).append("  frames ").append(framesWritten)
        sb.append("  underruns ").append(underruns).append("\n")
        sb.append("Network   : ").append(network).append("\n")
        sb.append("Events    : ").append(eventCount)
        val age = ageSeconds(lastEventAt)
        if (age >= 0) sb.append("  (last ").append(age).append(" s ago)")
        sb.append("\n")
        sb.append("Native    : ").append(nativeVersion)
        if (nativeRestarts > 0) sb.append("  restarts ").append(nativeRestarts)
        sb.append("\n")
        lastError?.let { sb.append("Last error: ").append(it).append("\n") }
        if (lastSonos.isNotEmpty()) sb.append("Sonos     : ").append(lastSonos).append("\n")
        if (PROBE_MODE) sb.append("Capture   : ").append(capture).append("\n")
        sb.append("Debug page: http://").append(Net.primaryIp() ?: "<tv-ip>").append(":").append(DebugServer.PORT)
        return sb.toString()
    }

    private fun formatMs(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }
}
