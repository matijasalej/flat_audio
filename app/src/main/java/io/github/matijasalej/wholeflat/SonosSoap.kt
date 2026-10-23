package io.github.matijasalej.wholeflat

import io.github.matijasalej.wholeflat.sync.SonosXml
import java.net.HttpURLConnection
import java.net.URL

/** One Sonos speaker found on the network. */
class SonosDevice(val ip: String, val udn: String, val room: String, val model: String) {
    override fun toString(): String = "$room ($model) $ip"
}

/**
 * UPnP actions against one Sonos (AVTransport and RenderingControl on port 1400), as plain blocking
 * SOAP calls with short timeouts. A speaker that has gone away costs one connect timeout, not more.
 */
class SonosSoap(val ip: String) {

    class Result(val status: Int, val body: String, val error: String?) {
        val ok: Boolean get() = error == null && status in 200..299
        val upnpError: Int? get() = if (ok) null else SonosXml.errorCode(body)

        fun describe(): String = when {
            error != null -> error
            ok -> "ok"
            else -> "HTTP $status" + (upnpError?.let { " UPnP error $it" } ?: "")
        }
    }

    fun call(service: String, control: String, action: String, args: List<Pair<String, String>>, readTimeoutMs: Int = READ_TIMEOUT_MS): Result {
        val envelope = SonosXml.envelope(service, action, args).toByteArray(Charsets.UTF_8)
        val conn = URL("http://$ip:$PORT$control").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            conn.setFixedLengthStreamingMode(envelope.size)
            conn.outputStream.use { it.write(envelope) }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            Result(status, text, null)
        } catch (e: Exception) {
            Result(-1, "", e.toString())
        } finally {
            conn.disconnect()
        }
    }

    private fun av(action: String, vararg args: Pair<String, String>): Result =
        call(SonosXml.AVTRANSPORT, SonosXml.AVTRANSPORT_CONTROL, action, listOf("InstanceID" to "0") + args.toList())

    private fun rc(action: String, vararg args: Pair<String, String>): Result =
        call(SonosXml.RENDERING, SonosXml.RENDERING_CONTROL, action, listOf("InstanceID" to "0", "Channel" to "Master") + args.toList())

    fun setUri(uri: String, didl: String): Result =
        av("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to didl)

    fun play(): Result = av("Play", "Speed" to "1")

    fun stop(): Result = av("Stop")

    fun transportInfo(): Result = av("GetTransportInfo")

    fun positionInfo(): Result = av("GetPositionInfo")

    fun setMute(mute: Boolean): Result = rc("SetMute", "DesiredMute" to (if (mute) "1" else "0"))

    fun setVolume(volume: Int): Result = rc("SetVolume", "DesiredVolume" to volume.coerceIn(0, 100).toString())

    fun getVolume(): Int? {
        val r = rc("GetVolume")
        return if (r.ok) SonosXml.tag(r.body, "CurrentVolume")?.toIntOrNull() else null
    }

    companion object {
        const val PORT = 1400
        private const val CONNECT_TIMEOUT_MS = 1500
        private const val READ_TIMEOUT_MS = 3000
    }
}
