package io.github.matijasalej.wholeflat.sync

/**
 * Keeps the delay between the capture of a frame and its write to a renderer at the wanted value, with the frame trims.
 *
 * The TV gives no position to follow (unlike the Sonos), so the stream to it is held against the capture clock alone: frame f is to
 * be written at capture(f) + hold. The stream is paced in real time from the moment the TV connects, and the capture clock and the
 * pacing clock drift apart a little (tens of ppm); a change of the setting moves the wanted hold. The difference is removed the way
 * the Sonos's is, by dropping or repeating single frames, at most one per trim spacing (4.5 ms of correction per second for a
 * captured stream), so that nothing is heard. The renderer's own delay (the setting `tv_latency_ms`) is behind the write and not
 * measured here.
 *
 * Plain Kotlin; the caller measures the error and passes the frames still on their way.
 */
class HoldController(
    private val rate: Int,
    private val deadbandMs: Double = DEADBAND_MS,
    private val gain: Double = GAIN,
    private val maxPendingFrames: Int = MAX_PENDING_FRAMES,
) {
    /**
     * The trim to ask for: [errorMs] is how much later than wanted the frame about to be written was captured before (positive: the
     * stream is late, frames are dropped; negative: it is early, frames are repeated), [pendingFrames] the trims asked for and not
     * applied yet (they will remove part of the error by themselves). Positive result: drop that many frames, negative: repeat.
     */
    fun trim(errorMs: Double, pendingFrames: Int): Int {
        val remaining = errorMs - pendingFrames * 1000.0 / rate
        if (Math.abs(remaining) <= deadbandMs) return 0
        val frames = Math.round(remaining * gain * rate / 1000.0).toInt()
        return frames.coerceIn(-maxPendingFrames - pendingFrames, maxPendingFrames - pendingFrames)
    }

    companion object {
        /** Errors up to this are left alone (ms): the capture clock is good to a few ms. */
        const val DEADBAND_MS = 4.0

        /** A trim removes this part of the error; the rest is left for the next look. */
        const val GAIN = 0.5

        /** Most frames that may be on their way at any time (54 ms). */
        const val MAX_PENDING_FRAMES = 2_400
    }
}
