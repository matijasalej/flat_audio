package io.github.matijasalej.wholeflat.sync

/**
 * The format of the stream to the Sonos (the setting `kitchen_format`): FLAC with frames of a chosen block size, or
 * WAV. The URL of a stream ends in the extension, and the speaker is told the MIME type in the item it is given.
 */
enum class StreamFormat(val key: String, val ext: String, val mime: String) {
    FLAC("flac", "flac", "audio/flac"),
    WAV("wav", "wav", "audio/wav"),
    ;

    companion object {
        /** The format of a setting value ("flac" or "wav", any case); null for anything else. */
        fun of(key: String?): StreamFormat? = values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }

        /** The format of the extension of a stream path (`/k/<token>.flac`); null if it is none. */
        fun ofPath(path: String): StreamFormat? = values().firstOrNull { path.endsWith("." + it.ext) }

        /** The block sizes of FLAC that the setting `kitchen_flac_block` allows. */
        val FLAC_BLOCKS = intArrayOf(4096, 1152, 576)

        /** The size of a chunk of a WAV stream (frames): 10 ms at 44.1 kHz, the spacing of the trims. */
        const val WAV_CHUNK_FRAMES = 441
    }
}
