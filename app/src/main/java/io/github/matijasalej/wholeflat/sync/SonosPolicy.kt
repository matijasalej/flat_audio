package io.github.matijasalej.wholeflat.sync

/**
 * Who has the Sonos. The kitchen only ever uses a speaker that is free: one that plays something
 * else (Bluetooth, a line-in, an app) is left alone and the kitchen waits until it is free again.
 *
 * Only a foreign source that is playing (PLAYING, TRANSITIONING) is busy. One that is paused or stopped
 * is free: a paused Spotify Connect session, say, keeps holding the transport (it ignores a UPnP Stop and
 * stays PAUSED_PLAYBACK), and a speaker that nobody listens to must not keep the kitchen out for ever.
 * Such a paused session is taken over before the stream is set (see [needsTakeover], [queueUri]).
 */
object SonosPolicy {
    enum class Availability {
        /**
         * The transport has one of our streams (or had: a leftover of an earlier run of the app). Whether it is
         * ours to use again depends on who paused it, which only the kitchen knows.
         */
        OURS,

        /** Stopped, paused or nothing loaded, and not ours: we may take it. */
        FREE,

        /** Something else plays on it. */
        BUSY,
    }

    const val STREAM_PATH = "/k/"

    fun isOurUri(uri: String): Boolean = uri.contains(STREAM_PATH) && StreamFormat.values().any { uri.contains("." + it.ext) }

    fun classify(transportState: String, trackUri: String): Availability {
        if (isOurUri(trackUri)) return Availability.OURS
        return when (transportState) {
            "PLAYING", "TRANSITIONING" -> Availability.BUSY
            else -> Availability.FREE
        }
    }

    /**
     * A foreign source that is paused holds the transport. A Spotify Connect session (`x-sonos-vli:...`)
     * ignores a UPnP Stop and stays PAUSED_PLAYBACK, so before our stream is set the speaker is switched to
     * its own (empty) queue first.
     */
    fun needsTakeover(transportState: String, trackUri: String): Boolean =
        transportState == "PAUSED_PLAYBACK" && trackUri.isNotEmpty() && !isOurUri(trackUri)

    /**
     * Whether a playback state of the speaker's websocket (`PLAYBACK_STATE_BUFFERING`) or a transport state of UPnP (`TRANSITIONING`)
     * says that it is waiting for audio: it ran dry, or is starting again after it did.
     */
    fun isStall(state: String): Boolean = state.contains("BUFFERING", ignoreCase = true) || state.contains("TRANSITIONING", ignoreCase = true)

    private val RINCON = Regex("RINCON_[0-9A-Fa-f]+")

    /** The URI that switches a speaker to its own queue, from the UDN of its description (`uuid:RINCON_...`); null if it has none. */
    fun queueUri(udn: String): String? {
        val id = RINCON.find(udn)?.value ?: return null
        return "x-rincon-queue:$id#0"
    }

    /** The token of our stream in a track URI (`.../k/<token>.flac` or `.wav`), or null. */
    fun tokenOf(uri: String): String? {
        val start = uri.indexOf(STREAM_PATH)
        if (start < 0) return null
        val rest = uri.substring(start + STREAM_PATH.length)
        val end = StreamFormat.values().map { rest.indexOf("." + it.ext) }.filter { it > 0 }.minOrNull() ?: return null
        return rest.substring(0, end)
    }
}
