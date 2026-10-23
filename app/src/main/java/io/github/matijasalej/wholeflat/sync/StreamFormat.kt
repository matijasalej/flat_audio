package io.github.matijasalej.wholeflat.sync

/**
 * The format of a stream the app serves: FLAC with frames of a chosen block size, or WAV, for the Sonos; raw big-endian 16 bit PCM
 * ([L16]) for the TV's DLNA renderer, which does not take FLAC. The URL of a stream ends in the extension, and the renderer is told the
 * MIME type in the item it is given. The setting `kitchen_format` is one of the Sonos formats or [AUTO], which picks per source (see
 * [resolve]); the TV's format is not a setting of the Sonos.
 *
 * @param forSonos whether the Sonos can be given this format (and the setting `kitchen_format` may name it)
 */
enum class StreamFormat(val key: String, val ext: String, val mime: String, val forSonos: Boolean = true) {
    FLAC("flac", "flac", "audio/flac"),
    WAV("wav", "wav", "audio/wav"),

    /** What the Philips TV takes: audio/L16 at 44.1 kHz stereo, big-endian, with no header and no length (see [L16]). */
    L16("l16", "l16", "audio/L16;rate=44100;channels=2", forSonos = false),
    ;

    companion object {
        /**
         * The value of `kitchen_format` that leaves the choice to the app: WAV for a captured stream (it starts
         * sooner on the Sonos and needs no block of audio to exist before it can be sent), FLAC for Spotify's.
         */
        const val AUTO = "auto"

        /** The Sonos format of a setting value ("flac" or "wav", any case); null for anything else, "auto" and the TV's format included. */
        fun of(key: String?): StreamFormat? = values().firstOrNull { it.forSonos && it.key.equals(key?.trim(), ignoreCase = true) }

        /** The value of the setting `kitchen_format` that [text] means: "auto", "flac" or "wav" (any case); null for anything else. */
        fun settingOf(text: String?): String? {
            val t = text?.trim()?.lowercase() ?: return null
            return if (t == AUTO || values().any { it.forSonos && it.key == t }) t else null
        }

        /** The format a stream of the capture mode ([capture]) or of Spotify gets under the setting [setting]. */
        fun resolve(setting: String?, capture: Boolean): StreamFormat = of(setting) ?: if (capture) WAV else FLAC

        /** The format of the extension of a stream path (`/k/<token>.flac`); null if it is none. */
        fun ofPath(path: String): StreamFormat? = values().firstOrNull { path.endsWith("." + it.ext) }

        /** The block sizes of FLAC that the setting `kitchen_flac_block` allows. */
        val FLAC_BLOCKS = intArrayOf(4096, 1152, 576)

        /** The size of a chunk of a WAV or L16 stream (frames): 10 ms at 44.1 kHz, the spacing of the trims. */
        const val WAV_CHUNK_FRAMES = 441
    }
}
