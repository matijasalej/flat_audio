package io.github.matijasalej.wholeflat

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The stream server of the debug server asks this for the session behind a `/k/<token>` URL: the kitchen's (the Sonos pulls its
 * streams) and the TV's (its DLNA renderer pulls the live stream of Synced mode) are made by different parts of the app, and a stream
 * that is lost is reported to the part that made it.
 */
object StreamRouter : StreamHost {
    private val hosts = CopyOnWriteArrayList<StreamHost>()

    fun add(host: StreamHost) {
        if (!hosts.contains(host)) hosts.add(host)
    }

    fun remove(host: StreamHost) {
        hosts.remove(host)
    }

    /** True when nobody makes streams (the service is not running). */
    val isEmpty: Boolean get() = hosts.isEmpty()

    override fun sessionFor(token: String): KitchenSession? {
        for (h in hosts) {
            val s = h.sessionFor(token)
            if (s != null) return s
        }
        return null
    }

    override fun streamLost(s: KitchenSession, reason: String) {
        for (h in hosts) {
            if (h.sessionFor(s.token) === s) {
                h.streamLost(s, reason)
                return
            }
        }
    }
}
