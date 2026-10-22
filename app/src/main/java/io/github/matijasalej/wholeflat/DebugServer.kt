package io.github.matijasalej.wholeflat

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A small hand-rolled HTTP server on 0.0.0.0:8080 (one thread per connection): the debug page and
 * JSON endpoints, the Sonos test routes, and the streaming test signal that the Sonos pulls.
 *
 * Routes:
 *   GET /                  HTML status, refreshes every 2 s
 *   GET /status.json       the status model
 *   GET /log               the in-memory log, plain text
 *   GET /sys               device facts (model, ABIs, SDK, IPs, uptime)
 *   GET /net/udp319        can this app bind UDP 319 and 320 (PTP, AirPlay 2) without root?
 *   GET /sonos/discover    SSDP search for Sonos speakers
 *   GET /sonos/play        ?ip=..&fmt=wav|flac&scheme=http|x-rincon-mp3radio&prebuffer=S
 *   GET /sonos/stop        ?ip=..
 *   GET /sonos/position    ?ip=..   raw GetTransportInfo + GetPositionInfo
 *   GET /test/tone.wav     the test signal as an endless WAV
 *   GET /test/tone.flac    the test signal as a chunked FLAC stream
 */
object DebugServer {
    const val PORT = 8080
    private const val TAG = "http"
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
        return Request(parts[0].uppercase(), path, query, headers, remote)
    }

    private fun route(req: Request, client: Socket, out: OutputStream) {
        val head = req.method == "HEAD"
        if (req.method != "GET" && !head) {
            sendText(out, 405, "method not allowed\n", head)
            return
        }
        val context = appContext
        when (req.path) {
            "/" -> sendBytes(out, 200, "text/html; charset=utf-8", indexHtml().toByteArray(Charsets.UTF_8), head)
            "/status.json" -> sendJson(out, 200, AppState.toJson(), head)
            "/log" -> sendBytes(out, 200, "text/plain; charset=utf-8", AppLog.dump().toByteArray(Charsets.UTF_8), head)
            "/sys" -> sendJson(out, 200, sysJson(), head)
            "/net/udp319" -> sendJson(out, 200, udpProbe(), head)
            "/sonos/discover" -> {
                if (context == null) return sendText(out, 500, "no context\n", head)
                AppLog.add(TAG, "${req.remote} GET /sonos/discover")
                sendJson(out, 200, Sonos.discover(context), head)
            }
            "/sonos/play" -> {
                if (context == null) return sendText(out, 500, "no context\n", head)
                val ip = sonosIp(req) ?: return sendText(out, 400, "missing or invalid ip=\n", head)
                val fmt = req.query["fmt"] ?: "wav"
                val scheme = req.query["scheme"] ?: "http"
                val prebuffer = (req.query["prebuffer"]?.toDoubleOrNull() ?: 2.0).coerceIn(0.0, 30.0)
                if (fmt != "wav" && fmt != "flac") return sendText(out, 400, "fmt must be wav or flac\n", head)
                if (scheme != "http" && scheme != "x-rincon-mp3radio") {
                    return sendText(out, 400, "scheme must be http or x-rincon-mp3radio\n", head)
                }
                AppLog.add(TAG, "${req.remote} GET /sonos/play ip=$ip fmt=$fmt scheme=$scheme prebuffer=$prebuffer")
                sendJson(out, 200, Sonos.play(context, ip, fmt, scheme, prebuffer), head)
            }
            "/sonos/stop" -> {
                val ip = sonosIp(req) ?: return sendText(out, 400, "missing or invalid ip=\n", head)
                AppLog.add(TAG, "${req.remote} GET /sonos/stop ip=$ip")
                sendJson(out, 200, Sonos.stop(ip), head)
            }
            "/sonos/position" -> {
                val ip = sonosIp(req) ?: return sendText(out, 400, "missing or invalid ip=\n", head)
                sendJson(out, 200, Sonos.position(ip), head)
            }
            "/test/tone.wav" -> serveTone(req, client, out, flac = false)
            "/test/tone.flac" -> serveTone(req, client, out, flac = true)
            else -> sendText(out, 404, "not found\n", head)
        }
    }

    private fun sonosIp(req: Request): String? {
        val ip = req.query["ip"] ?: Sonos.lastIp
        return if (ip != null && IP_PATTERN.matches(ip)) ip else null
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

    private fun indexHtml(): String {
        val status = AppState.toJson()
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta http-equiv=\"refresh\" content=\"2\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Whole Flat Audio</title><style>")
        sb.append("body{font:14px/1.45 system-ui,sans-serif;margin:16px;background:#0f1720;color:#e6edf3}")
        sb.append("h1{font-size:20px;margin:0 0 12px}table{border-collapse:collapse}")
        sb.append("td{padding:2px 14px 2px 0;vertical-align:top}td:first-child{color:#8b98a5}")
        sb.append("pre{background:#0b1118;padding:10px;overflow:auto;font-size:12px;line-height:1.35}")
        sb.append("a{color:#6aa6ff;margin-right:14px}</style></head><body>")
        sb.append("<h1>Whole Flat Audio ").append(escapeHtml(BuildInfo.versionName)).append("</h1><table>")
        status.keys().forEach { key ->
            sb.append("<tr><td>").append(escapeHtml(key)).append("</td><td>")
            sb.append(escapeHtml(status.opt(key)?.toString() ?: "")).append("</td></tr>")
        }
        sb.append("</table><p>")
        for (link in listOf("/status.json", "/log", "/sys", "/net/udp319", "/sonos/discover")) {
            sb.append("<a href=\"").append(link).append("\">").append(link).append("</a>")
        }
        sb.append("</p><h2 style=\"font-size:15px\">Log (last 80 lines)</h2><pre>")
        sb.append(escapeHtml(AppLog.tail(80).joinToString("\n")))
        sb.append("</pre></body></html>")
        return sb.toString()
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
        o.put("supported_32_bit_abis", JSONArray(Build.SUPPORTED_32_BIT_ABIS.toList()))
        o.put("supported_64_bit_abis", JSONArray(Build.SUPPORTED_64_BIT_ABIS.toList()))
        o.put("process_is_64_bit", Process.is64Bit())
        o.put("ip", JSONArray(Net.localIps()))
        o.put("uptime_ms", SystemClock.elapsedRealtime())
        o.put("app_version", BuildInfo.versionName)
        o.put("native", AppState.nativeVersion)
        return o
    }

    /** Can an unprivileged app bind the PTP ports? Answers whether AirPlay 2 timing is possible without root. */
    private fun udpProbe(): JSONObject {
        val result = JSONObject()
        for (port in intArrayOf(319, 320)) {
            val entry = JSONObject()
            try {
                val socket = DatagramSocket(null as SocketAddress?)
                try {
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(port))
                    entry.put("ok", true)
                    entry.put("local", socket.localSocketAddress.toString())
                } finally {
                    socket.close()
                }
            } catch (e: Exception) {
                entry.put("ok", false)
                entry.put("error", e.toString())
            }
            result.put(port.toString(), entry)
        }
        return result
    }

    // ---- the test signal ----------------------------------------------------------------------

    private fun writeChunk(out: OutputStream, data: ByteArray) {
        out.write((Integer.toHexString(data.size) + "\r\n").toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(byteArrayOf(13, 10))
    }

    /**
     * Streams the test signal: first the `prebuffer` seconds as fast as the connection takes them,
     * then paced in real time. Every connection is logged with its headers, byte count and duration.
     */
    private fun serveTone(req: Request, client: Socket, out: OutputStream, flac: Boolean) {
        val prebuffer = (req.query["prebuffer"]?.toDoubleOrNull() ?: 2.0).coerceIn(0.0, 30.0)
        val kind = if (flac) "flac" else "wav"
        val head = StringBuilder("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ").append(if (flac) "audio/flac" else "audio/wav").append("\r\n")
        if (flac) {
            head.append("Transfer-Encoding: chunked\r\n")
        } else {
            head.append("Content-Length: ").append(Wav.TOTAL_BYTES).append("\r\n")
        }
        head.append("Accept-Ranges: none\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
        val interesting = listOf("user-agent", "range", "icy-metadata", "connection", "x-sonos-ignore-me")
        val seen = interesting.mapNotNull { name -> req.headers[name]?.let { "$name: $it" } }
        AppLog.add(
            "stream",
            "${req.remote} ${req.method} ${req.path} prebuffer=${prebuffer}s" +
                (if (seen.isEmpty()) "" else " [" + seen.joinToString("; ") + "]"),
        )
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (req.method == "HEAD") {
            out.flush()
            return
        }
        client.soTimeout = 0

        val generator = ToneGenerator()
        val chunkFrames = if (flac) Flac.BLOCK else 882 // 20 ms
        val pcm = ShortArray(chunkFrames * ToneGenerator.CHANNELS)
        val wavBytes = ByteArray(chunkFrames * ToneGenerator.CHANNELS * 2)
        val preFrames = (prebuffer * ToneGenerator.RATE).toLong()
        val startNs = System.nanoTime()
        var sentFrames = 0L
        var bytes = 0L
        var frameNumber = 0L
        var lastReport = startNs
        var ended = "client closed"
        try {
            if (flac) {
                val header = Flac.streamHeader()
                writeChunk(out, header)
                bytes += header.size
            } else {
                val header = Wav.header()
                out.write(header)
                bytes += header.size
            }
            out.flush()
            while (true) {
                // A chunk is due when real time has caught up with its start, minus the prebuffer.
                val dueNs = startNs + maxOf(0L, sentFrames - preFrames) * 1_000_000_000L / ToneGenerator.RATE
                val waitNs = dueNs - System.nanoTime()
                if (waitNs > 0) Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                generator.fill(pcm, chunkFrames)
                if (flac) {
                    val frame = Flac.frame(frameNumber++, pcm)
                    writeChunk(out, frame)
                    bytes += frame.size
                } else {
                    Wav.pcmBytes(pcm, chunkFrames, wavBytes)
                    out.write(wavBytes, 0, wavBytes.size)
                    bytes += wavBytes.size
                }
                out.flush()
                sentFrames += chunkFrames
                if (!flac && bytes >= Wav.TOTAL_BYTES) {
                    ended = "end of the declared length"
                    break
                }
                val now = System.nanoTime()
                if (now - lastReport >= 10_000_000_000L) {
                    lastReport = now
                    val elapsed = (now - startNs) / 1_000_000L
                    val audioMs = sentFrames * 1000L / ToneGenerator.RATE
                    AppLog.add("stream", "${req.remote} $kind: $bytes bytes, ${elapsed / 1000.0}s elapsed, ${audioMs / 1000.0}s of audio sent")
                }
            }
        } catch (e: IOException) {
            ended = e.toString()
        } catch (e: InterruptedException) {
            ended = "interrupted"
        } finally {
            val elapsed = (System.nanoTime() - startNs) / 1_000_000L
            AppLog.add("stream", "${req.remote} $kind closed after $bytes bytes, $elapsed ms ($ended)")
        }
    }
}
