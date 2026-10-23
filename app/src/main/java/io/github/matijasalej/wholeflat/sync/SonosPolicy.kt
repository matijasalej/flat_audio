package io.github.matijasalej.wholeflat.sync

/**
 * Who has the Sonos. The kitchen only ever uses a speaker that is free: one that plays something
 * else (Bluetooth, a line-in, an app, a paused queue of someone's) is left alone and the kitchen
 * waits until it is free again.
 */
object SonosPolicy {
    enum class Availability {
        /** The transport plays one of our streams (or did: a leftover of an earlier run of the app). */
        OURS,

        /** Stopped, nothing loaded or nothing playing: we may take it. */
        FREE,

        /** Something else uses it. */
        BUSY,
    }

    const val STREAM_PATH = "/k/"

    fun isOurUri(uri: String): Boolean = uri.contains(STREAM_PATH) && uri.contains(".flac")

    fun classify(transportState: String, trackUri: String): Availability {
        if (isOurUri(trackUri)) return Availability.OURS
        return when (transportState) {
            "STOPPED", "NO_MEDIA_PRESENT" -> Availability.FREE
            else -> Availability.BUSY
        }
    }

    /** The token of our stream in a track URI (`.../k/<token>.flac`), or null. */
    fun tokenOf(uri: String): String? {
        val start = uri.indexOf(STREAM_PATH)
        if (start < 0) return null
        val rest = uri.substring(start + STREAM_PATH.length)
        val end = rest.indexOf(".flac")
        return if (end <= 0) null else rest.substring(0, end)
    }
}
