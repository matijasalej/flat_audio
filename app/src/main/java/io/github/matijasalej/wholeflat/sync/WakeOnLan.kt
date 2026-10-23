package io.github.matijasalej.wholeflat.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wake-on-LAN for the TV: JointSpace refuses `powerstate On` while the TV is in standby (seen on the Philips), but its network chip
 * (wifi0, `wake-on-lan: Enabled` in `/6/network/devices`) wakes it on the magic packet. Plain Kotlin.
 */
object WakeOnLan {
    /** The six bytes of "AA:BB:CC:DD:EE:FF" (colons or dashes, any case); null if the text is not a MAC address. */
    fun parseMac(text: String): ByteArray? {
        val parts = text.trim().split(':', '-')
        if (parts.size != 6) return null
        val out = ByteArray(6)
        for ((i, p) in parts.withIndex()) {
            if (p.length != 2) return null
            out[i] = (p.toIntOrNull(16) ?: return null).toByte()
        }
        return out
    }

    /** The magic packet: six bytes of 0xFF and the MAC address sixteen times (102 bytes). */
    fun packet(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "a MAC address has six bytes" }
        val p = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) p[i] = 0xFF.toByte()
        for (r in 0 until 16) System.arraycopy(mac, 0, p, 6 + r * 6, 6)
        return p
    }

    /**
     * The MAC address of the interface that can wake the TV in the answer of `GET /6/network/devices` (a list of interfaces with `id`,
     * `mac` and `wake-on-lan`): the first with Wake-on-LAN enabled, else the first that has an address. Null if there is none.
     */
    fun macFromDevices(json: String): String? {
        val list: JSONArray = try {
            val t = json.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("devices") ?: return null
        } catch (e: Exception) {
            return null
        }
        var first: String? = null
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val mac = o.optString("mac", "").trim()
            if (parseMac(mac) == null) continue
            val norm = mac.uppercase().replace('-', ':')
            if (first == null) first = norm
            if (o.optString("wake-on-lan", "").equals("Enabled", ignoreCase = true)) return norm
        }
        return first
    }
}
