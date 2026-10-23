package io.github.matijasalej.wholeflat

import android.os.SystemClock
import io.github.matijasalej.wholeflat.sync.SonosXml
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Finds Sonos speakers by SSDP: an M-SEARCH for ZonePlayers on demand, and a listener for the NOTIFY
 * messages a speaker multicasts when it comes back (alive) or goes away (byebye), so that the kitchen
 * joins within a second of the speaker returning instead of at the next poll. The service holds the
 * multicast lock for the lifetime of the app.
 */
class SonosFinder {

    interface Listener {
        fun onAlive(ip: String)
        fun onByebye(ip: String)
    }

    @Volatile
    var listener: Listener? = null

    /** off, listening, or why it could not */
    @Volatile
    var notifyStatus = "off"
        private set

    @Volatile
    private var notifySocket: MulticastSocket? = null
    private var notifyThread: Thread? = null

    @Volatile
    private var stopping = false

    fun startNotifyListener() {
        if (notifyThread != null) return
        stopping = false
        notifyThread = Thread({ notifyLoop() }, "wf-ssdp-notify").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        stopping = true
        try {
            notifySocket?.close()
        } catch (e: Exception) {
            // closed already
        }
        notifySocket = null
        notifyThread = null
        notifyStatus = "off"
    }

    @Suppress("DEPRECATION")
    private fun notifyLoop() {
        val socket: MulticastSocket = try {
            val s = MulticastSocket(null as SocketAddress?)
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(SSDP_PORT))
                s.joinGroup(InetAddress.getByName(SSDP_ADDRESS))
                s.soTimeout = 1000
            } catch (e: Exception) {
                s.close()
                throw e
            }
            s
        } catch (e: Exception) {
            notifyStatus = "unavailable: $e"
            AppLog.add(TAG, "no SSDP NOTIFY listener ($e), the kitchen is found by polling instead")
            return
        }
        notifySocket = socket
        notifyStatus = "listening"
        AppLog.add(TAG, "listening for SSDP NOTIFY on port $SSDP_PORT")
        val buffer = ByteArray(2048)
        val lastSeen = HashMap<String, Long>()
        while (!stopping) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!text.startsWith("NOTIFY")) continue
                val nt = headerValue(text, "NT") ?: ""
                val usn = headerValue(text, "USN") ?: ""
                if (!nt.contains("ZonePlayer", ignoreCase = true) && !usn.contains("RINCON", ignoreCase = true)) continue
                val ip = packet.address?.hostAddress ?: continue
                val nts = headerValue(text, "NTS") ?: continue
                val key = ip + nts
                val now = SystemClock.elapsedRealtime()
                // a speaker sends a dozen NOTIFYs per announcement: one is enough
                if (now - (lastSeen[key] ?: 0L) < 2000L) continue
                lastSeen[key] = now
                when {
                    nts.contains("alive") -> listener?.onAlive(ip)
                    nts.contains("byebye") -> listener?.onByebye(ip)
                }
            } catch (e: SocketTimeoutException) {
                // nothing in this second
            } catch (e: Exception) {
                if (!stopping) AppLog.error(TAG, "SSDP NOTIFY listener failed", e)
                break
            }
        }
        try {
            socket.close()
        } catch (e: Exception) {
            // closed already
        }
    }

    /** M-SEARCH for ZonePlayers, then the description of each answer. Blocks for [listenMs]. */
    fun search(listenMs: Int = 2500): List<SonosDevice> {
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
                        if (!found.containsKey(ip)) {
                            found[ip] = headerValue(text, "LOCATION") ?: "http://$ip:1400/xml/device_description.xml"
                        }
                    } catch (e: SocketTimeoutException) {
                        // no answer in this slice
                    }
                }
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            AppLog.error(TAG, "SSDP search failed", e)
        }
        return found.keys.map { describe(it) }
    }

    /** The room name, model and unique name of the speaker at [ip]. */
    fun describe(ip: String): SonosDevice {
        var xml: String? = null
        try {
            xml = httpGet("http://$ip:1400/xml/device_description.xml", 2500)
        } catch (e: Exception) {
            AppLog.add(TAG, "no description from $ip: $e")
        }
        val room = xml?.let { SonosXml.tag(it, "roomName") } ?: ""
        val model = xml?.let { SonosXml.tag(it, "modelName") } ?: ""
        val udn = xml?.let { SonosXml.tag(it, "UDN") } ?: ""
        return SonosDevice(ip, udn, room, model)
    }

    private fun httpGet(url: String, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
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

    companion object {
        private const val TAG = "ssdp"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"
    }
}
