package io.github.matijasalej.wholeflat

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

/**
 * Spike 1b: this device talks to a Sonos over UPnP. Discovery by SSDP, control by the AVTransport
 * SOAP service on port 1400; the Sonos then pulls the test signal from [DebugServer].
 */
object Sonos {
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"
    private const val TAG = "sonos"

    /** The Sonos used by the buttons: the first one found, or the last one addressed. */
    @Volatile
    var lastIp: String? = null

    class SoapResult(val status: Int, val body: String, val error: String?) {
        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("status", status)
            o.put("body", body)
            if (error != null) o.put("error", error)
            return o
        }
    }

    /** SSDP M-SEARCH for ZonePlayers, then the device description of each answer. */
    fun discover(context: Context, listenMs: Int = 3000): JSONArray {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("wholeflat-ssdp")
        lock.setReferenceCounted(false)
        lock.acquire()
        val found = LinkedHashMap<String, String>() // ip -> LOCATION
        try {
            val socket = MulticastSocket()
            try {
                socket.soTimeout = 400
                socket.timeToLive = 4
                val search = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $SEARCH_TARGET\r\n" +
                        "USER-AGENT: Android/WholeFlat UPnP/1.1\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                val group = InetAddress.getByName(SSDP_ADDRESS)
                val deadline = SystemClock.elapsedRealtime() + listenMs
                var nextSend = 0L
                val buffer = ByteArray(2048)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextSend) {
                        socket.send(DatagramPacket(search, search.size, group, SSDP_PORT))
                        nextSend = now + 1000
                    }
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val st = headerValue(text, "ST")
                        if (st != null && !st.contains("ZonePlayer", ignoreCase = true)) continue
                        val ip = packet.address?.hostAddress ?: continue
                        val location = headerValue(text, "LOCATION") ?: "http://$ip:1400/xml/device_description.xml"
                        if (!found.containsKey(ip)) {
                            found[ip] = location
                            AppLog.add(TAG, "SSDP answer from $ip")
                        }
                    } catch (e: SocketTimeoutException) {
                        // no answer in this slice, keep listening until the deadline
                    }
                }
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            AppLog.error(TAG, "SSDP discovery failed", e)
        } finally {
            if (lock.isHeld) lock.release()
        }

        val result = JSONArray()
        for ((ip, location) in found) {
            result.put(describe(ip, location))
        }
        if (lastIp == null && found.isNotEmpty()) lastIp = found.keys.first()
        AppLog.add(TAG, "discover: ${found.size} device(s)")
        return result
    }

    private fun describe(ip: String, location: String): JSONObject {
        val o = JSONObject()
        o.put("ip", ip)
        o.put("location", location)
        var xml: String? = null
        var failure: String? = null
        for (url in listOf("http://$ip:1400/xml/device_description.xml", location)) {
            try {
                xml = httpGet(url, 3000)
                failure = null
                break
            } catch (e: Exception) {
                failure = e.toString()
            }
        }
        if (xml != null) {
            o.put("modelName", tag(xml, "modelName") ?: JSONObject.NULL)
            o.put("roomName", tag(xml, "roomName") ?: JSONObject.NULL)
        } else {
            o.put("modelName", JSONObject.NULL)
            o.put("roomName", JSONObject.NULL)
            o.put("error", failure ?: "no description")
        }
        return o
    }

    private fun httpGet(url: String, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code from $url")
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun headerValue(response: String, name: String): String? {
        for (line in response.split("\r\n", "\n")) {
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals(name, ignoreCase = true)) {
                return line.substring(colon + 1).trim()
            }
        }
        return null
    }

    private fun tag(xml: String, name: String): String? {
        val match = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        return xmlUnescape(match.groupValues[1].trim())
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun xmlUnescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** One AVTransport:1 action against the Sonos at [ip]. */
    fun soap(ip: String, action: String, arguments: String): SoapResult {
        val envelope = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                arguments +
                "</u:$action></s:Body></s:Envelope>"
            ).toByteArray(Charsets.UTF_8)
        val conn = URL("http://$ip:1400/MediaRenderer/AVTransport/Control").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            conn.setFixedLengthStreamingMode(envelope.size)
            conn.outputStream.use { it.write(envelope) }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            AppLog.add(TAG, "$action -> HTTP $status")
            SoapResult(status, text, null)
        } catch (e: Exception) {
            AppLog.error(TAG, "$action failed", e)
            SoapResult(-1, "", e.toString())
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Points the Sonos at the test signal served by this device and starts it.
     * [fmt] is "wav" or "flac"; [scheme] is "http" or "x-rincon-mp3radio" (the radio scheme makes
     * the Sonos treat the URL as an internet radio stream); [prebufferSec] is how much audio the
     * server sends ahead of real time when the Sonos connects.
     */
    fun play(context: Context, ip: String, fmt: String, scheme: String, prebufferSec: Double): JSONObject {
        val out = JSONObject()
        out.put("ip", ip)
        DebugServer.ensureStarted(context)
        val host = Net.localAddressFor(ip)
        if (host == null) {
            out.put("error", "this device has no LAN address")
            return out
        }
        lastIp = ip
        val path = if (fmt == "flac") "/test/tone.flac" else "/test/tone.wav"
        val query = "?prebuffer=" + String.format(Locale.US, "%.2f", prebufferSec)
        val authority = "$host:${DebugServer.PORT}"
        val uri = if (scheme == "x-rincon-mp3radio") {
            "x-rincon-mp3radio://$authority$path$query"
        } else {
            "http://$authority$path$query"
        }
        val didl = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
            """xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" """ +
            """xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
            """<item id="R:0/0/0" parentID="R:0/0" restricted="true">""" +
            """<dc:title>Whole Flat test tone ($fmt)</dc:title>""" +
            """<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>""" +
            """<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">SA_RINCON65031_</desc>""" +
            """</item></DIDL-Lite>"""
        out.put("uri", uri)
        AppLog.add(TAG, "play $fmt via $scheme on $ip: $uri")
        val set = soap(
            ip,
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${xmlEscape(uri)}</CurrentURI>" +
                "<CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>",
        )
        out.put("SetAVTransportURI", set.toJson())
        if (set.status in 200..299) {
            val play = soap(ip, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            out.put("Play", play.toJson())
        } else {
            out.put("Play", JSONObject.NULL)
        }
        return out
    }

    fun stop(ip: String): JSONObject {
        lastIp = ip
        val out = JSONObject()
        out.put("ip", ip)
        out.put("Stop", soap(ip, "Stop", "<InstanceID>0</InstanceID>").toJson())
        return out
    }

    fun position(ip: String): JSONObject {
        val out = JSONObject()
        out.put("ip", ip)
        out.put("GetTransportInfo", soap(ip, "GetTransportInfo", "<InstanceID>0</InstanceID>").toJson())
        out.put("GetPositionInfo", soap(ip, "GetPositionInfo", "<InstanceID>0</InstanceID>").toJson())
        return out
    }

    /** The Sonos for the buttons: the last one used, else the first one SSDP finds. */
    fun anyIp(context: Context): String? {
        lastIp?.let { return it }
        discover(context)
        return lastIp
    }
}
