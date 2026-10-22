package io.github.matijasalej.wholeflat

/**
 * The Rust core (librespot, built by cargo-ndk into libwholeflat_native.so) behind five calls.
 *
 * The JNI symbol names are derived from the package and the object name; if either changes, the
 * `Java_io_github_matijasalej_wholeflat_NativeBridge_*` functions in native/src/lib.rs must follow.
 */
object NativeBridge {
    /** Set when the library could not be loaded; every call below would throw then. */
    @Volatile
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("wholeflat_native")
        } catch (t: Throwable) {
            loadError = t.toString()
        }
    }

    /**
     * Starts the Spotify Connect engine on its own native thread and returns at once. [filesDir]
     * keeps credentials and volume, [cacheDir] the audio cache and TMPDIR. False if it could not
     * be started.
     */
    @JvmStatic
    external fun start(filesDir: String, cacheDir: String, deviceName: String): Boolean

    /**
     * Blocks up to [timeoutMs] for decoded audio and copies it into [buf] (interleaved stereo,
     * 44.1 kHz, 16 bit, always whole frames). Returns the number of shorts written, 0 on timeout,
     * -1 once the engine is stopped.
     */
    @JvmStatic
    external fun readPcm(buf: ShortArray, timeoutMs: Int): Int

    /** Next engine event as one JSON object (fields "t" and "ts" always present), null on timeout. */
    @JvmStatic
    external fun nextEvent(timeoutMs: Int): String?

    /** Signals the engine to shut down; returns at once. */
    @JvmStatic
    external fun stop()

    @JvmStatic
    external fun version(): String
}
