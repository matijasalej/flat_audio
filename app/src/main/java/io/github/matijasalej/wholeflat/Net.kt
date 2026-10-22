package io.github.matijasalej.wholeflat

import android.content.Context
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object BuildInfo {
    @Volatile
    var versionName: String = "?"
        private set

    fun init(context: Context) {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            versionName = info.versionName ?: "?"
        } catch (e: Exception) {
            // keep the placeholder
        }
    }
}

object Net {
    /** "wlan0: 192.168.1.20" for every IPv4 address of an interface that is up (loopback excluded). */
    fun localIps(): List<String> {
        val out = ArrayList<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        out.add(iface.name + ": " + addr.hostAddress)
                    }
                }
            }
        } catch (e: Exception) {
            // fall through with what we have
        }
        return out
    }

    /** The LAN address of this device, preferring Ethernet and Wi-Fi interfaces. */
    fun primaryIp(): String? {
        val ips = localIps()
        val preferred = ips.firstOrNull { it.startsWith("eth") || it.startsWith("wlan") } ?: ips.firstOrNull()
        return preferred?.substringAfter(": ")
    }

    /** The address of this device on the interface that routes to [remoteIp] (UDP connect sends nothing). */
    fun localAddressFor(remoteIp: String): String? {
        return try {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(remoteIp), 1400)
                val host = socket.localAddress?.hostAddress
                if (host == null || host == "0.0.0.0" || host == "::") primaryIp() else host
            }
        } catch (e: Exception) {
            primaryIp()
        }
    }
}
