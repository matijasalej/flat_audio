package io.github.matijasalej.wholeflat

/**
 * The Rust core (librespot, built by cargo-ndk into libwholeflat_native.so).
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

    /** Next engine event as one JSON object (fields "t" and "ts" always present), null on timeout. */
    @JvmStatic
    external fun nextEvent(timeoutMs: Int): String?

    /** Signals the engine to shut down; returns at once. */
    @JvmStatic
    external fun stop()

    @JvmStatic
    external fun version(): String

    /** Fills [meta] (see [RingMeta]) with the ring's state; -1 when no engine runs. */
    @JvmStatic
    external fun ringState(meta: LongArray): Int

    /**
     * The living room's read: up to [maxFrames] frames into [buf] (interleaved stereo). Returns the
     * frames read, 0 on timeout, [CLOSED], [PAUSED] (nothing is playing) or [START_PENDING] (a start
     * event waits for [lrAck]). [heardHint] is the ring frame being heard about now, or -1.
     */
    @JvmStatic
    external fun lrRead(buf: ShortArray, maxFrames: Int, timeoutMs: Int, heardHint: Long, meta: LongArray): Int

    /** The start event of [epoch] has been scheduled; frames are handed out from now on. */
    @JvmStatic
    external fun lrAck(epoch: Long)

    /** The living room lost its output: start again from [heardHint] (a start event). */
    @JvmStatic
    external fun lrRestart(heardHint: Long)

    /**
     * The kitchen's read at the absolute ring index [cursor] for a session of [epoch] (the frames stay
     * in the ring). Returns the frames read, 0 on timeout, [CLOSED], [LAPPED] or [EPOCH_CHANGED].
     */
    @JvmStatic
    external fun kRead(cursor: Long, epoch: Long, buf: ShortArray, maxFrames: Int, timeoutMs: Int, meta: LongArray): Int

    /** A source on the Kotlin side (see source/AudioSource.kt): playback begins or resumes. */
    @JvmStatic
    external fun sourceStart()

    /** A source on the Kotlin side: playback pauses. */
    @JvmStatic
    external fun sourcePause()

    /** A source on the Kotlin side: the audio jumps (a seek or a skip). */
    @JvmStatic
    external fun sourceFlush()

    /**
     * A source on the Kotlin side writes [frames] stereo frames of [sampleRate] Hz from [buf]. Returns
     * the frames taken, -1 if the engine is closed or not running, -2 if the ring does not take that rate.
     */
    @JvmStatic
    external fun sourceWrite(buf: ShortArray, frames: Int, sampleRate: Int): Int

    /** Starts the calibration click track as a start event through the ring. */
    @JvmStatic
    external fun calibStart(seconds: Int): Boolean

    @JvmStatic
    external fun calibStop()

    const val CLOSED = -1
    const val PAUSED = -2
    const val START_PENDING = -3
    const val LAPPED = -2
    const val EPOCH_CHANGED = -3
}

/** The ring state the JNI calls fill in (layout: see native/src/lib.rs). */
class RingMeta {
    val a = LongArray(12)

    val epoch get() = a[0]
    val epochStart get() = a[1]
    val kind get() = a[2].toInt()
    val startPending get() = a[3] and 1L != 0L
    val sinkRunning get() = a[3] and 2L != 0L
    val synthetic get() = a[3] and 4L != 0L
    val closed get() = a[3] and 8L != 0L
    val playing get() = a[3] and 16L != 0L
    val writeIdx get() = a[4]
    val lrPos get() = a[5]
    val validStart get() = a[6]
    val flushes get() = a[7]
    val resumes get() = a[8]
    val pauses get() = a[9]
    val restarts get() = a[10]
    val firstFrame get() = a[11]

    fun kindName(): String = when (kind) {
        1 -> "flush"
        2 -> "resume"
        3 -> "calibration"
        4 -> "restart"
        else -> "none"
    }
}
