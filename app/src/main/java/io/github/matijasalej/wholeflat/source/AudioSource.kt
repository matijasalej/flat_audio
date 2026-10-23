package io.github.matijasalej.wholeflat.source

/**
 * Where the music comes from, as a small interface.
 *
 * There is one source today: librespot (Spotify Connect), which lives inside the native core. It feeds the
 * ring from there and reports through the JSON events that `PlaybackService` turns into the status. A second
 * source, the capture of another app's audio for instance, lives on this side and plugs in here:
 *
 * ```
 * AudioSource  --PCM-->  PcmSink (SourceBridge)  --JNI-->  the ring  -->  living room + kitchen
 *      |
 *      +--start / pause / resume / flush, track, volume, position-->  SourceListener (SourceBridge)
 *                                                                        |--> ring control (start, pause, flush)
 *                                                                        '--> SourceStatus (screen, media session, volumes)
 * ```
 *
 * The sync pipeline behind the ring (start events, the clock of the living room, the kitchen) does not know
 * which source feeds it. The ring plays 44.1 kHz; a source with another rate converts before it writes.
 */

/** What a source says about the track it plays. */
data class TrackInfo(
    val uri: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
)

/** What a source reports to the app. May be called from any thread. */
interface SourceListener {
    /** Playback begins; the first audio follows. */
    fun onStart()

    fun onPause()

    /** Playback goes on after a pause. */
    fun onResume()

    /** The audio jumps (a seek, a skip): whatever was written and not played yet is void. */
    fun onFlush(positionMs: Long = -1)

    fun onTrack(info: TrackInfo)

    /** The source's own volume, 0.0 to 1.0. */
    fun onVolume(volume: Double)

    fun onPosition(positionMs: Long)
}

/** The ring as a source sees it. */
interface PcmSink {
    /** The sample rates [write] takes. */
    val supportedRates: IntArray

    /**
     * Writes [frames] interleaved stereo 16 bit frames. Blocks while the ring is full (that is what keeps a
     * source in step with playback). False if the engine is closed or the rate is not supported.
     */
    fun write(pcm: ShortArray, frames: Int, sampleRate: Int): Boolean
}

/** A source of music. */
interface AudioSource {
    val name: String

    /** Starts delivering audio to [sink] and telling [listener] what happens. */
    fun start(sink: PcmSink, listener: SourceListener)

    fun stop()

    /** The volume (0.0 to 1.0) the app asks for; a source that cannot set it ignores this. */
    fun setVolume(volume: Double) {}
}

/** Where a source's reports end up in the app: the status screen, the media session, the volumes. */
interface SourceStatus {
    fun trackChanged(info: TrackInfo)

    /** [state] is "playing", "paused" or "stopped"; [positionMs] is -1 when the source did not say. */
    fun playbackChanged(state: String, positionMs: Long)

    fun positionChanged(positionMs: Long)

    fun volumeChanged(volume: Double)
}

/** The control side of the ring (JNI in the app, a recording fake in the tests). */
interface RingControl {
    fun start()

    fun pause()

    fun flush()

    /** Returns [frames] when all went in, -1 if closed, -2 if the rate is not supported. */
    fun write(pcm: ShortArray, frames: Int, sampleRate: Int): Int
}

/**
 * The app's side of the interface: it is the listener and the sink a source gets. A report of the source
 * goes to the ring (start, pause, flush) and to the status; audio goes to the ring.
 */
class SourceBridge(private val ring: RingControl, private val status: SourceStatus) : SourceListener, PcmSink {
    override val supportedRates: IntArray = intArrayOf(SUPPORTED_RATE)

    override fun write(pcm: ShortArray, frames: Int, sampleRate: Int): Boolean =
        ring.write(pcm, frames, sampleRate) == frames

    override fun onStart() {
        ring.start()
        status.playbackChanged("playing", 0)
    }

    override fun onPause() {
        ring.pause()
        status.playbackChanged("paused", -1)
    }

    override fun onResume() {
        ring.start()
        status.playbackChanged("playing", -1)
    }

    override fun onFlush(positionMs: Long) {
        ring.flush()
        if (positionMs >= 0) status.positionChanged(positionMs)
    }

    override fun onTrack(info: TrackInfo) = status.trackChanged(info)

    override fun onVolume(volume: Double) = status.volumeChanged(volume.coerceIn(0.0, 1.0))

    override fun onPosition(positionMs: Long) = status.positionChanged(positionMs)

    companion object {
        /** The ring plays 44.1 kHz. */
        const val SUPPORTED_RATE = 44_100
    }
}
