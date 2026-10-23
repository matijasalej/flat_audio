package io.github.matijasalej.wholeflat.sync

/**
 * How long the speaker takes from Play to its first sound, by stream format: what a start is planned with (when to unmute, when the
 * living room has to sound, how far behind the capture a stream is expected to land), and what the readings of the streams that
 * run teach, smoothed over the starts. The Sonos was measured at 0.29 s for FLAC (it wants a few frames before it plays) and at
 * 0.19 s for WAV, which has no frames.
 */
class PlayDelays(
    flacNanos: Long = DEFAULT_FLAC_NANOS,
    wavNanos: Long = DEFAULT_WAV_NANOS,
    private val minNanos: Long = MIN_NANOS,
    private val maxNanos: Long = MAX_NANOS,
    private val weight: Double = WEIGHT,
) {
    private val delays = HashMap<StreamFormat, Long>()

    init {
        delays[StreamFormat.FLAC] = flacNanos
        delays[StreamFormat.WAV] = wavNanos
    }

    /** The delay for streams of [format] (ns). */
    @Synchronized
    fun nanos(format: StreamFormat): Long = delays.getValue(format)

    /** The delay for streams of [format] (ms). */
    fun ms(format: StreamFormat): Double = nanos(format) / 1e6

    /**
     * A stream of [format] was heard to start [observedNanos] after Play. A value within the bounds is mixed into the delay of that
     * format; the new delay (ns) is returned, or null if the observation was not used.
     */
    @Synchronized
    fun learn(format: StreamFormat, observedNanos: Long): Long? {
        if (observedNanos < minNanos || observedNanos > maxNanos) return null
        val before = delays.getValue(format)
        val now = (before * (1.0 - weight) + observedNanos * weight).toLong()
        delays[format] = now
        return now
    }

    companion object {
        /** Measured 0.288 s +- 14 ms for FLAC with blocks of 4096. */
        const val DEFAULT_FLAC_NANOS = 290_000_000L

        /** Measured about 0.19 s for WAV. */
        const val DEFAULT_WAV_NANOS = 195_000_000L
        const val MIN_NANOS = 100_000_000L
        const val MAX_NANOS = 800_000_000L

        /** The part of a new observation that goes into the delay. */
        const val WEIGHT = 0.4
    }
}
