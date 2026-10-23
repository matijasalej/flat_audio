package io.github.matijasalej.wholeflat

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import io.github.matijasalej.wholeflat.sync.CalibrationClicks
import io.github.matijasalej.wholeflat.sync.ClickTrack
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A small hand-rolled HTTP server on 0.0.0.0:8080 (one thread per connection): the status page and
 * JSON endpoints, the settings, the calibration test signal, and the stream the Sonos pulls.
 *
 * Routes:
 *   GET  /                      HTML status, refreshes every 2 s
 *   GET  /status.json           everything: service, Spotify, living room, kitchen, TV, settings
 *   GET  /log                   the in-memory log, plain text
 *   GET  /sys                   device facts (model, ABIs, SDK, IPs, uptime)
 *   GET  /settings              the settings as JSON (also `?key=value` to change some)
 *   POST /settings              JSON body with some of the keys; answers with all settings
 *   GET  /calibrate/start       ?seconds=N (default 30): the click track through the whole pipeline
 *   GET  /calibrate/stop        ends the click track
 *   GET  /kitchen               the kitchen's status
 *   GET  /kitchen/rejoin        restarts the Sonos stream (only the kitchen)
 *   GET  /kitchen/search        looks for Sonos speakers again
 *   GET  /kitchen/select        ?ip=...: use this speaker
 *   GET  /kitchen/readings      the last 10 minutes of the Sonos's readings as CSV (position, lag of each reading and its median, error, target)
 *   GET  /kitchen/last-sessions the last 10 sessions: every connection of the Sonos to the stream (method, start and end,
 *                               bytes, blocks, who closed it and why) and the transport state and status seen meanwhile
 *   GET  /capture               the capture of the Cast receiver: state, grants, level, activity, media session
 *   GET  /capture/start         an attempt now, also without the adb grants (the consent dialog then shows on the TV)
 *   GET  /capture/restart       ends the capture in progress; it starts again by itself (`?buffer=1` makes the AudioRecord buffer the smallest)
 *   GET  /tv                    the TV's status
 *   GET  /tv/run                runs the wake and screen-off sequence now
 *   GET  /tv/detect             looks for the TV again
 *   GET  /synced                Synced mode: the mode manager, the TV's DLNA player, the screen, the Ambilight
 *   GET  /tv/dlna               the TV's DLNA player alone
 *   GET  /tv/last-sessions      the last 10 streams to the TV: every connection with its statistics
 *   GET  /tv/grab               has the TV play the capture now (the mode manager may give it back; set mode=lyrics to test alone)
 *   GET  /tv/release            gives the TV back (it returns to HDMI)
 *   GET  /remote                the phone page: the three displays of the TV (Listening, Ambilight, Screen + Ambilight) and the kitchen volume;
 *                               /remote/state.json its state, /remote/action?display=screen&volume=%2B the presses, /remote/manifest.webmanifest
 *                               and the icons for the home screen
 *   GET  /tune                  the fine tuning of the debug page: ?tv_extra=%2B|-, ?fine=%2B|- (the kitchen against the TV), ?lyrics=%2B|-, ?display=...
 *   GET  /tv/reanchor           restarts the TV's stream now (with its warm-up); `?at=boundary` waits for the next song boundary
 *   GET  /tv/preview.jpg        what the picture of the TV's stream shows now (a small JPEG)
 *   GET  /tv/frames             the last ten seconds of the lyrics video as CSV: every picture drawn (draw ms, how late the loop woke, the position in the song) and
 *                               every picture the encoder gave (bytes, key, ms from the picture to its bytes)
 *   GET  /tv/clicks             ?seconds=N (default 30): the click track in both streams instead of the music, for calibrating the rooms:
 *                               the TV's stream 2 kHz (4 kHz every 10th click), the kitchen's 3 kHz (6 kHz); `/tv/clicks/stop` ends it
 *   GET  /k/<token>.flac        the kitchen stream (GET and HEAD), pulled by the Sonos
 */
object DebugServer {
    const val PORT = 8080
    private const val TAG = "http"
    private const val MAX_BODY = 64 * 1024
    private val IP_PATTERN = Regex("^[0-9A-Za-z.\\-]{1,100}$")

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var appContext: Context? = null

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
        val remote: String,
    )

    @Synchronized
    fun ensureStarted(context: Context) {
        appContext = context.applicationContext
        val existing = server
        if (existing != null && !existing.isClosed) return
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT))
            server = socket
            thread(name = "http-accept", isDaemon = true) { acceptLoop(socket) }
            AppLog.add(TAG, "listening on 0.0.0.0:$PORT")
        } catch (e: IOException) {
            AppLog.error(TAG, "cannot listen on port $PORT", e)
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (e: IOException) {
            // closing anyway
        }
        server = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                thread(name = "http-conn", isDaemon = true) { handle(client) }
            } catch (e: IOException) {
                if (socket.isClosed) break
                AppLog.error(TAG, "accept failed", e)
                Thread.sleep(200)
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val out = BufferedOutputStream(client.getOutputStream(), 16 * 1024)
            val request = readRequest(input, client.remoteSocketAddress.toString()) ?: return
            route(request, client, out)
        } catch (e: Exception) {
            // the client went away, or sent garbage; nothing to answer
        } finally {
            try {
                client.close()
            } catch (e: IOException) {
                // already closed
            }
        }
    }

    private fun readRequest(input: InputStream, remote: String): Request? {
        val buffer = ByteArrayOutputStream()
        val terminator = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (buffer.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            matched = if (b == terminator[matched].toInt()) matched + 1 else if (b == 13) 1 else 0
            if (matched == terminator.size) break
        }
        if (matched != terminator.size) return null
        val lines = buffer.toString("ISO-8859-1").split("\r\n")
        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = HashMap<String, String>()
        if (target.contains('?')) {
            for (pair in target.substringAfter('?').split('&')) {
                if (pair.isEmpty()) continue
                val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                query[key] = value
            }
        }
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                headers[lines[i].substring(0, colon).trim().lowercase()] = lines[i].substring(colon + 1).trim()
            }
        }
        var body = ""
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length in 1..MAX_BODY) {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n < 0) return null
                read += n
            }
            body = String(bytes, Charsets.UTF_8)
        }
        return Request(parts[0].uppercase(), path, query, headers, body, remote)
    }

    private fun route(req: Request, client: Socket, out: OutputStream) {
        val head = req.method == "HEAD"
        val kitchenToken = KitchenStreams.tokenOf(req.path)
        if (kitchenToken != null) {
            if (StreamRouter.isEmpty || (req.method != "GET" && !head)) {
                sendText(out, 404, "no streams\n", head)
                return
            }
            KitchenStreams.serve(StreamRouter, kitchenToken, req.method, req.remote, req.headers["user-agent"] ?: "", SocketConn(client, out), req.headers["range"] ?: "")
            return
        }
        if (req.method != "GET" && !head && !(req.method == "POST" && req.path == "/settings")) {
            sendText(out, 405, "method not allowed\n", head)
            return
        }
        when (req.path) {
            "/" -> sendBytes(out, 200, "text/html; charset=utf-8", indexHtml().toByteArray(Charsets.UTF_8), head)
            "/status.json" -> sendJson(out, 200, AppState.toJson(), head)
            "/log" -> sendBytes(out, 200, "text/plain; charset=utf-8", AppLog.dump().toByteArray(Charsets.UTF_8), head)
            "/sys" -> sendJson(out, 200, sysJson(), head)
            "/settings" -> settingsRoute(req, out, head)
            "/calibrate/start" -> {
                val seconds = (req.query["seconds"]?.toIntOrNull() ?: 30).coerceIn(1, 600)
                AppLog.add(TAG, "${req.remote} calibrate/start seconds=$seconds")
                sendJson(out, 200, calibrateStart(seconds), head)
            }
            "/calibrate/stop" -> {
                AppLog.add(TAG, "${req.remote} calibrate/stop")
                val o = JSONObject()
                try {
                    NativeBridge.calibStop()
                    o.put("ok", true)
                } catch (t: Throwable) {
                    o.put("ok", false)
                    o.put("error", t.toString())
                }
                sendJson(out, 200, o, head)
            }
            "/kitchen" -> sendJson(out, 200, AppState.kitchen?.statusJson() ?: JSONObject().put("state", "not running"), head)
            "/kitchen/rejoin" -> {
                AppState.kitchen?.rejoinNow()
                sendJson(out, 200, JSONObject().put("ok", AppState.kitchen != null), head)
            }
            "/kitchen/last-sessions" -> sendJson(out, 200, AppState.kitchen?.lastSessionsJson() ?: JSONArray(), head)
            "/kitchen/readings" -> sendText(out, 200, AppState.kitchen?.readingsCsv() ?: "kitchen not running\n", head)
            "/kitchen/search" -> {
                AppState.kitchen?.searchNow()
                sendJson(out, 200, JSONObject().put("ok", AppState.kitchen != null), head)
            }
            "/kitchen/select" -> {
                val ip = req.query["ip"]
                if (ip == null || !IP_PATTERN.matches(ip)) return sendText(out, 400, "missing or invalid ip=\n", head)
                AppState.kitchen?.select(ip)
                sendJson(out, 200, JSONObject().put("ok", AppState.kitchen != null).put("ip", ip), head)
            }
            "/capture" -> sendJson(out, 200, AppState.capture?.statusJson() ?: JSONObject().put("state", "not running"), head)
            "/capture/start" -> {
                AppLog.add(TAG, "${req.remote} capture/start")
                AppState.capture?.startNow()
                sendJson(out, 200, JSONObject().put("ok", AppState.capture != null), head)
            }
            "/capture/restart" -> {
                AppLog.add(TAG, "${req.remote} capture/restart")
                // ?buffer=1|2|4|8: the AudioRecord buffer as a multiple of the smallest the device allows, for the latency measurements
                req.query["buffer"]?.toIntOrNull()?.let { AppState.capture?.setBufferFactor(it) }
                AppState.capture?.restart("asked for on the debug page")
                sendJson(out, 200, JSONObject().put("ok", AppState.capture != null), head)
            }
            "/tv" -> sendJson(out, 200, AppState.tv?.statusJson() ?: JSONObject().put("state", "not running"), head)
            "/tv/run" -> {
                AppState.tv?.runNow()
                sendJson(out, 200, JSONObject().put("ok", AppState.tv != null), head)
            }
            "/synced" -> sendJson(out, 200, AppState.synced?.statusJson() ?: JSONObject().put("state", "not running"), head)
            "/tv/dlna" -> sendJson(out, 200, AppState.tvRenderer?.statusJson() ?: JSONObject().put("state", "not running"), head)
            "/tv/last-sessions" -> sendJson(out, 200, AppState.tvRenderer?.lastSessionsJson() ?: JSONArray(), head)
            "/tv/frames" -> sendText(out, 200, "kind,wall_ms,time_us,a,b,c\n" + (AppState.tvRenderer?.framesCsv() ?: ""), head)
            "/tv/grab" -> {
                AppLog.add(TAG, "${req.remote} tv/grab")
                AppState.tvRenderer?.grab("asked for on the debug page")
                sendJson(out, 200, JSONObject().put("ok", AppState.tvRenderer != null), head)
            }
            "/tv/release" -> {
                AppLog.add(TAG, "${req.remote} tv/release")
                AppState.tvRenderer?.release("asked for on the debug page")
                sendJson(out, 200, JSONObject().put("ok", AppState.tvRenderer != null), head)
            }
            "/remote" -> asset("index.html", "text/html; charset=utf-8", out, head)
            "/remote/state.json" -> sendJson(out, 200, RemoteApi.stateJson(), head)
            "/remote/action" -> {
                AppLog.add(TAG, "${req.remote} remote/action ${req.query}")
                sendJson(out, 200, RemoteApi.action(req.query), head)
            }
            "/remote/manifest.webmanifest" -> asset("manifest.webmanifest", "application/manifest+json", out, head)
            "/remote/icon-192.png" -> asset("icon-192.png", "image/png", out, head)
            "/remote/icon-512.png" -> asset("icon-512.png", "image/png", out, head)
            "/remote/apple-touch-icon.png", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png" -> asset("apple-touch-icon.png", "image/png", out, head)
            "/tune" -> {
                AppLog.add(TAG, "${req.remote} tune ${req.query}")
                if (req.query.isNotEmpty()) RemoteApi.action(req.query)
                sendJson(out, 200, RemoteApi.tuningJson(), head)
            }
            "/tv/reanchor" -> {
                AppLog.add(TAG, "${req.remote} tv/reanchor ${req.query}")
                if (req.query["at"] == "boundary") {
                    AppState.synced?.requestReanchor()
                } else {
                    AppState.tvRenderer?.reanchor("asked for on the debug page")
                }
                sendJson(out, 200, JSONObject().put("ok", AppState.tvRenderer != null).put("at", req.query["at"] ?: "now"), head)
            }
            "/tv/preview.jpg" -> {
                val jpeg = AppState.tvRenderer?.previewJpeg()
                if (jpeg == null) sendText(out, 404, "no lyrics video is running\n", head) else sendBytes(out, 200, "image/jpeg", jpeg, head)
            }
            "/tv/clicks" -> {
                val seconds = (req.query["seconds"]?.toIntOrNull() ?: 30).coerceIn(1, 600)
                AppLog.add(TAG, "${req.remote} tv/clicks seconds=$seconds")
                CalibrationClicks.start(seconds)
                sendJson(out, 200, clicksJson(seconds), head)
            }
            "/tv/clicks/stop" -> {
                AppLog.add(TAG, "${req.remote} tv/clicks/stop")
                CalibrationClicks.stop()
                sendJson(out, 200, JSONObject().put("ok", true), head)
            }
            "/tv/detect" -> {
                AppState.tv?.detectNow()
                sendJson(out, 200, JSONObject().put("ok", AppState.tv != null), head)
            }
            else -> sendText(out, 404, "not found\n", head)
        }
    }

    // ---- settings and calibration ----------------------------------------------------------------

    private fun settingsRoute(req: Request, out: OutputStream, head: Boolean) {
        val update = JSONObject()
        if (req.method == "POST") {
            if (req.body.isNotBlank()) {
                try {
                    val parsed = JSONObject(req.body)
                    for (key in parsed.keys()) update.put(key, parsed.get(key))
                } catch (e: JSONException) {
                    sendJson(out, 400, JSONObject().put("ok", false).put("error", "the body is not a JSON object: ${e.message}"), head)
                    return
                }
            }
        } else {
            // a GET may carry changes in the query, for a quick tweak from a browser
            for ((key, value) in req.query) update.put(key, value)
        }
        if (update.length() > 0) {
            AppLog.add(TAG, "${req.remote} ${req.method} /settings $update")
            Settings.applyJson(update)
        }
        sendJson(out, 200, Settings.toJson(), head)
    }

    private fun clicksJson(seconds: Int): JSONObject {
        val o = JSONObject()
        val renderer = AppState.tvRenderer
        o.put("ok", true)
        o.put("seconds", seconds)
        o.put("clicks", "one per second at capture frame k*44100, 5 ms Hann-windowed, 0.5 full scale, in place of the music")
        o.put("tv", "${ClickTrack.TV_HZ} Hz, ${ClickTrack.TV_HZ * 2} Hz at k%10==0")
        o.put("kitchen", "${ClickTrack.KITCHEN_HZ} Hz, ${ClickTrack.KITCHEN_HZ * 2} Hz at k%10==0")
        o.put("tv_state", renderer?.state?.label ?: "none")
        o.put("tv_live", renderer?.live ?: false)
        o.put("kitchen_state", AppState.kitchen?.state?.label ?: "none")
        o.put("kitchen_offset_ms", Settings.values.kitchenOffsetMs)
        o.put("tv_latency_ms", renderer?.tvLatencyMs() ?: JSONObject.NULL)
        val notes = JSONArray()
        if (renderer == null || !renderer.live) notes.put("the TV does not play the music yet (Synced mode, /tv/grab, and the warm-up must be over): its clicks start with the music")
        if (AppState.kitchen?.state?.label != "playing") notes.put("the kitchen streams only while something plays in the captured player: start a track on the phone (its sound is replaced by the clicks)")
        o.put("notes", notes)
        return o
    }

    private fun calibrateStart(seconds: Int): JSONObject {
        val o = JSONObject()
        if (NativeBridge.loadError != null) {
            o.put("ok", false)
            o.put("error", "the native library is not loaded: ${NativeBridge.loadError}")
            return o
        }
        val ok = try {
            NativeBridge.calibStart(seconds)
        } catch (t: Throwable) {
            o.put("error", t.toString())
            false
        }
        o.put("ok", ok)
        if (ok) {
            o.put("seconds", seconds)
            o.put("clicks", "one per second at frame k*44100 from the start, 5 ms Hann-windowed, 0.5 full scale")
            o.put("living_room", "channel L: 2 kHz, 4 kHz at k%10==0")
            o.put("kitchen", "channel R: 3 kHz, 6 kHz at k%10==0")
            o.put("kitchen_state", AppState.kitchen?.state?.label ?: "none")
            o.put("kitchen_offset_ms", Settings.values.kitchenOffsetMs)
        } else if (!o.has("error")) {
            o.put("error", "the audio engine is not running")
        }
        return o
    }

    // ---- responses -----------------------------------------------------------------------------

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "Status"
    }

    private fun sendBytes(out: OutputStream, code: Int, contentType: String, body: ByteArray, headOnly: Boolean) {
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    /** A file of `assets/remote/` (the phone page, its manifest and icons). */
    private fun asset(name: String, contentType: String, out: OutputStream, head: Boolean) {
        val bytes = try {
            appContext?.assets?.open("remote/$name")?.use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
        if (bytes == null) sendText(out, 404, "not found\n", head) else sendBytes(out, 200, contentType, bytes, head)
    }

    private fun sendText(out: OutputStream, code: Int, text: String, headOnly: Boolean) {
        sendBytes(out, code, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8), headOnly)
    }

    private fun sendJson(out: OutputStream, code: Int, json: Any, headOnly: Boolean) {
        val text = when (json) {
            is JSONObject -> json.toString(2)
            is JSONArray -> json.toString(2)
            else -> json.toString()
        }
        sendBytes(out, code, "application/json; charset=utf-8", (text + "\n").toByteArray(Charsets.UTF_8), headOnly)
    }

    // ---- pages ---------------------------------------------------------------------------------

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun renderRows(sb: StringBuilder, o: JSONObject, indent: Int) {
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = o.opt(key)
            sb.append("<tr><td style=\"padding-left:").append(indent * 14).append("px\">").append(escapeHtml(key)).append("</td><td>")
            if (value is JSONObject) {
                sb.append("</td></tr>")
                renderRows(sb, value, indent + 1)
            } else {
                sb.append(escapeHtml(value?.toString() ?: "")).append("</td></tr>")
            }
        }
    }

    private fun indexHtml(): String {
        val status = AppState.toJson()
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta http-equiv=\"refresh\" content=\"2\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Whole Flat Audio</title><style>")
        sb.append("body{font:14px/1.45 system-ui,sans-serif;margin:16px;background:#0f1720;color:#e6edf3}")
        sb.append("h1{font-size:20px;margin:0 0 12px}table{border-collapse:collapse}")
        sb.append("td{padding:1px 14px 1px 0;vertical-align:top}td:first-child{color:#8b98a5}")
        sb.append("pre{background:#0b1118;padding:10px;overflow:auto;font-size:12px;line-height:1.35}")
        sb.append("a{color:#6aa6ff;margin-right:14px}")
        sb.append(".tune{background:#16202a;border-radius:8px;padding:10px 12px;margin:0 0 14px;line-height:2.1}")
        sb.append(".tune b{color:#8b98a5;font-weight:500;margin-right:6px}.tune button{font:inherit;background:#24303c;color:#e6edf3;border:0;border-radius:6px;padding:2px 12px;margin-right:6px}")
        sb.append(".tune .on{background:#1b6ef3}.tune .gap{margin-left:18px}</style></head><body>")
        sb.append("<h1>Whole Flat Audio ").append(escapeHtml(BuildInfo.versionName)).append("</h1>")
        tuningPanel(sb)
        sb.append("<table>")
        renderRows(sb, status, 0)
        sb.append("</table><p>")
        for (link in listOf("/status.json", "/settings", "/kitchen", "/kitchen/last-sessions", "/capture", "/tv", "/log", "/sys", "/calibrate/start?seconds=20", "/calibrate/stop", "/kitchen/rejoin", "/capture/restart", "/tv/run", "/synced", "/tv/dlna", "/tv/last-sessions", "/tv/preview.jpg")) {
            sb.append("<a href=\"").append(link).append("\">").append(link).append("</a>")
        }
        sb.append("</p><h2 style=\"font-size:15px\">Log (last 120 lines)</h2><pre>")
        sb.append(escapeHtml(AppLog.tail(120).joinToString("\n")))
        sb.append("</pre></body></html>")
        return sb.toString()
    }

    /**
     * The fine tuning of Synced mode, which is not on the phone page: what the TV shows, the extra that the TV adds to the backlog of its
     * connection (the calibration of the two rooms), the fine sync of the kitchen against the TV, the lyrics against the sound, and two tools.
     */
    private fun tuningPanel(sb: StringBuilder) {
        val t = RemoteApi.tuningJson()
        fun step(name: String, key: String, value: Any?, unit: String, small: String, big: String? = null) {
            sb.append("<span class=\"gap\"><b>").append(name).append("</b>").append(escapeHtml(value?.toString() ?: "?")).append(" ").append(unit).append(" ")
            sb.append("<button onclick=\"t('$key=-')\">&minus;$small</button><button onclick=\"t('$key=%2B')\">+$small</button>")
            if (big != null) sb.append("<button onclick=\"t('$key=0')\">0</button>")
            sb.append("</span>")
        }
        sb.append("<div class=\"tune\"><b>TV display</b>")
        for ((key, label) in listOf("listen" to "Listening", "ambilight" to "Ambilight", "screen" to "Screen + Ambilight")) {
            sb.append("<button").append(if (t.optString("display") == key) " class=\"on\"" else "").append(" onclick=\"t('display=$key')\">").append(label).append("</button>")
        }
        sb.append("<br>")
        step("TV extra", "tv_extra", t.opt("tv_extra_ms"), "ms", "10")
        step("Fine sync (kitchen against the TV)", "fine", t.opt("kitchen_offset_ms"), "ms", "10", "0")
        step("Lyrics", "lyrics", t.opt("lyrics_offset_ms"), "ms", "100", "0")
        sb.append("<br><span class=\"gap\">TV latency ").append(escapeHtml(t.opt("tv_latency_ms")?.toString() ?: "?"))
        sb.append(" ms, common delay ").append(escapeHtml(t.opt("tv_delay_ms")?.toString() ?: "?")).append(" ms</span>")
        sb.append("<button class=\"gap\" onclick=\"g('/tv/reanchor')\">Re-anchor the TV</button>")
        sb.append("<button onclick=\"g('/tv/clicks?seconds=30')\">Clicks in both rooms, 30 s</button>")
        sb.append("<button onclick=\"g('/tv/clicks/stop')\">Stop clicks</button>")
        sb.append("<a class=\"gap\" href=\"/remote\">phone page</a></div>")
        sb.append("<script>function t(q){fetch('/tune?'+q).then(function(){location.reload()})}function g(u){fetch(u).then(function(){location.reload()})}</script>")
    }

    private fun sysJson(): JSONObject {
        val o = JSONObject()
        o.put("model", Build.MODEL)
        o.put("device", Build.DEVICE)
        o.put("product", Build.PRODUCT)
        o.put("manufacturer", Build.MANUFACTURER)
        o.put("android_release", Build.VERSION.RELEASE)
        o.put("sdk_int", Build.VERSION.SDK_INT)
        o.put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        o.put("process_is_64_bit", Process.is64Bit())
        o.put("ip", JSONArray(Net.localIps()))
        o.put("uptime_ms", SystemClock.elapsedRealtime())
        o.put("app_version", BuildInfo.versionName)
        o.put("native", AppState.nativeVersion)
        val runtime = Runtime.getRuntime()
        o.put("heap_used_mb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
        o.put("heap_max_mb", runtime.maxMemory() / (1024 * 1024))
        return o
    }
}
