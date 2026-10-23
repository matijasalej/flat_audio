package io.github.matijasalej.wholeflat

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import io.github.matijasalej.wholeflat.sync.VideoPolicy
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * The Philips TV behind the amplifier, through JointSpace (HTTP on port 1925, no login). The music
 * needs the TV to be on (its optical output feeds the amplifier), but not its picture: when music
 * starts after a silence, the TV is woken if it is in standby (best effort) and its screen is
 * switched off, unless a video app is in front.
 *
 * - `GET /6/powerstate` says "On" or "Standby"; `POST /6/powerstate {"powerstate":"On"}` wakes it.
 * - `POST /6/screenstate {"screenstate":"Off"}` turns the picture off; the audio goes on.
 *
 * What is in front is read from the usage events (`PACKAGE_USAGE_STATS`, granted once with
 * `adb shell appops set io.github.matijasalej.wholeflat GET_USAGE_STATS allow`). Without that grant the
 * screen is left alone, because switching off the picture under a film would be worse than not doing it.
 */
class TvControl(private val context: Context) {

    private val exec = Executors.newSingleThreadExecutor(
        ThreadFactory { r ->
            val t = Thread(r, "wf-tv")
            t.isDaemon = true
            t
        },
    )
    private val prefs = context.applicationContext.getSharedPreferences("wholeflat_tv", Context.MODE_PRIVATE)

    @Volatile
    private var playing = false

    @Volatile
    private var stoppedAtMs = 0L

    @Volatile
    private var detectedIp: String? = prefs.getString(PREF_IP, null)
    private var lastDetectMs = 0L

    // ---- status ------------------------------------------------------------------------------

    @Volatile
    private var lastAction = ""

    @Volatile
    private var lastActionAtMs = 0L

    @Volatile
    private var lastPower = ""

    @Volatile
    private var lastForeground: String? = null

    @Volatile
    private var lastVideo: Boolean? = null

    @Volatile
    private var screenOffSent = 0

    @Volatile
    private var wakesSent = 0

    @Volatile
    private var lastError: String? = null

    fun stop() {
        exec.shutdown()
    }

    /** A start event: the music starts or restarts. Called on the audio thread, returns at once. */
    fun onStartEvent(kind: Int) {
        val wasPlaying = playing
        playing = true
        if (kind == KIND_CALIBRATION) return // the test signal leaves the TV alone
        val silentForMs = if (stoppedAtMs == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - stoppedAtMs
        if (wasPlaying || silentForMs < SILENCE_MS) return
        post("start sequence") { sequence() }
    }

    fun onStopped() {
        if (playing) stoppedAtMs = SystemClock.elapsedRealtime()
        playing = false
    }

    /** Runs the TV sequence now (debug page). */
    fun runNow() {
        post("manual sequence") { sequence() }
    }

    fun detectNow() {
        post("detect") {
            detectedIp = null
            lastDetectMs = 0L
            effectiveIp()
        }
    }

    private fun post(name: String, block: () -> Unit) {
        try {
            exec.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    AppLog.error(TAG, "$name failed", t)
                    lastError = "$name: $t"
                }
            }
        } catch (e: Exception) {
            // shut down
        }
    }

    // ---- the sequence --------------------------------------------------------------------------

    private fun sequence() {
        val v = Settings.values
        if (!v.tvWake && !v.tvScreenOff) return
        val ip = effectiveIp()
        if (ip == null) {
            note("no TV found (set its address on the settings screen)")
            return
        }
        var power = getPowerState(ip)
        lastPower = power ?: "unreachable"
        if (power == null) {
            note("the TV at $ip does not answer")
            return
        }
        if (v.tvWake && power != "On") {
            wakesSent++
            val ok = postJson(ip, "powerstate", "{\"powerstate\":\"On\"}")
            note("TV power state was $power, asked it to wake: ${if (ok) "accepted" else "refused"}")
            var waited = 0
            while (waited < WAKE_WAIT_S) {
                sleepQuietly(1000)
                waited++
                power = getPowerState(ip)
                lastPower = power ?: "unreachable"
                if (power == "On") break
            }
            if (power != "On") {
                note("the TV did not come on within $WAKE_WAIT_S s")
                return
            }
            // the picture needs a moment before it takes commands
            sleepQuietly(1500)
        }
        if (v.tvScreenOff) {
            when (isVideoInFront()) {
                true -> note("a video app is in front (${lastForeground}), the screen stays on")
                null -> note("usage access is not granted, the screen stays on (see the README)")
                false -> {
                    screenOffSent++
                    var ok = postJson(ip, "screenstate", "{\"screenstate\":\"Off\"}")
                    if (!ok) {
                        sleepQuietly(1500)
                        ok = postJson(ip, "screenstate", "{\"screenstate\":\"Off\"}")
                    }
                    note("screen off: ${if (ok) "accepted" else "refused"}")
                }
            }
        }
    }

    private fun note(text: String) {
        lastAction = text
        lastActionAtMs = SystemClock.elapsedRealtime()
        AppLog.add(TAG, text)
    }

    // ---- usage access ----------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    fun hasUsageAccess(): Boolean {
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /** True: a video app is in front. False: not. Null: unknown (no usage access). */
    private fun isVideoInFront(): Boolean? {
        if (!hasUsageAccess()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = ArrayList<VideoPolicy.Event>()
        val result = usm.queryEvents(now - LOOKBACK_MS, now) ?: return null
        val e = UsageEvents.Event()
        while (result.hasNextEvent()) {
            result.getNextEvent(e)
            events.add(VideoPolicy.Event(e.eventType, e.packageName ?: "", e.timeStamp))
        }
        val front = VideoPolicy.foreground(events)
        lastForeground = front
        val video = VideoPolicy.isVideo(front, context.packageName, homePackages())
        lastVideo = video
        return video
    }

    private fun homePackages(): Set<String> {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val infos = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            infos.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    // ---- JointSpace ----------------------------------------------------------------------------

    /** The address to use: the one typed on the settings screen, else the one found by SSDP. */
    private fun effectiveIp(): String? {
        val manual = Settings.values.tvIp
        if (manual.isNotEmpty()) return manual
        detectedIp?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now - lastDetectMs < DETECT_EVERY_MS) return null
        lastDetectMs = now
        val found = detect()
        if (found != null) {
            detectedIp = found
            prefs.edit().putString(PREF_IP, found).apply()
            note("found the TV at $found")
        } else {
            note("no TV answered on port 1925")
        }
        return found
    }

    /** Looks for a JointSpace TV: SSDP root devices, each asked for /6/system. */
    private fun detect(): String? {
        val candidates = LinkedHashSet<String>()
        val own = Net.primaryIp()
        try {
            val socket = MulticastSocket()
            try {
                socket.soTimeout = 400
                socket.timeToLive = 4
                val search = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: upnp:rootdevice\r\n" +
                        "USER-AGENT: Android/WholeFlat UPnP/1.1\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                val group = InetAddress.getByName("239.255.255.250")
                val deadline = SystemClock.elapsedRealtime() + 2500
                var nextSend = 0L
                val buffer = ByteArray(2048)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextSend) {
                        socket.send(DatagramPacket(search, search.size, group, 1900))
                        nextSend = now + 1000
                    }
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val ip = packet.address?.hostAddress
                        if (ip != null && ip != own) candidates.add(ip)
                    } catch (e: SocketTimeoutException) {
                        // keep listening
                    }
                }
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            AppLog.error(TAG, "SSDP search for the TV failed", e)
        }
        if (candidates.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(minOf(8, candidates.size))
        try {
            val futures = candidates.map { ip -> pool.submit(Callable<String?> { if (isJointSpace(ip)) ip else null }) }
            for (f in futures) {
                val ip = try {
                    f.get(4, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    null
                }
                if (ip != null) return ip
            }
        } finally {
            pool.shutdownNow()
        }
        return null
    }

    private fun isJointSpace(ip: String): Boolean {
        val text = try {
            httpText("GET", "http://$ip:$PORT/6/system", null, 800) ?: return false
        } catch (e: Exception) {
            return false
        }
        return try {
            val o = JSONObject(text)
            o.has("api_version") || o.has("nettvversion") || o.has("featuring")
        } catch (e: Exception) {
            false
        }
    }

    private fun getPowerState(ip: String): String? {
        val text = httpText("GET", "http://$ip:$PORT/6/powerstate", null, 1500) ?: return null
        return try {
            JSONObject(text).optString("powerstate").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun postJson(ip: String, path: String, body: String): Boolean =
        httpText("POST", "http://$ip:$PORT/6/$path", body, 2500) != null

    /** The body of the answer, or null if the TV did not answer with a success. */
    private fun httpText(method: String, url: String, body: String?, timeoutMs: Int): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                null
            } else {
                conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            }
        } catch (e: Exception) {
            lastError = "$method $url: $e"
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // shutting down
        }
    }

    fun statusJson(): JSONObject {
        val o = JSONObject()
        val v = Settings.values
        val ip = if (v.tvIp.isNotEmpty()) v.tvIp else detectedIp
        o.put("ip", ip ?: JSONObject.NULL)
        o.put("ip_source", if (v.tvIp.isNotEmpty()) "setting" else if (detectedIp != null) "detected" else "none")
        o.put("power", lastPower)
        o.put("usage_access", hasUsageAccess())
        o.put("foreground", lastForeground ?: JSONObject.NULL)
        o.put("video_in_front", lastVideo ?: JSONObject.NULL)
        o.put("wakes_sent", wakesSent)
        o.put("screen_off_sent", screenOffSent)
        o.put("last_action", lastAction)
        o.put("last_action_age_s", if (lastActionAtMs == 0L) -1L else (SystemClock.elapsedRealtime() - lastActionAtMs) / 1000)
        o.put("last_error", lastError ?: JSONObject.NULL)
        return o
    }

    companion object {
        private const val TAG = "tv"
        private const val PORT = 1925
        private const val PREF_IP = "detected_ip"
        private const val KIND_CALIBRATION = 3

        /** A start after at least this much silence runs the sequence. */
        private const val SILENCE_MS = 60_000L
        private const val WAKE_WAIT_S = 12
        private const val LOOKBACK_MS = 12L * 3600_000L
        private const val DETECT_EVERY_MS = 10L * 60_000L
    }
}
