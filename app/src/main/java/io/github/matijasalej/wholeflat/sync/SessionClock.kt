package io.github.matijasalej.wholeflat.sync

/**
 * When the Sonos plays which frame of a stream.
 *
 * `startNanos` is the moment its stream frame 0 is heard. Before the first reading of the Sonos's
 * position it is the prediction (the Play request plus the measured start delay); every reading
 * moves it. The Sonos plays 22 ppm slower than this device's clock, which the readings absorb.
 */
class SessionClock(startNanos: Long, private val rate: Int = TrackTimeline.RATE) {
    @Volatile
    var startNanos: Long = startNanos
        private set

    private var refined = 0

    /** The stream position being heard at `nowNanos`, in frames. */
    fun heardFrame(nowNanos: Long): Double = (nowNanos - startNanos).toDouble() * rate / TrackTimeline.NANOS

    /** When stream position `s` (frames) is heard. */
    fun timeOfFrame(s: Double): Long = startNanos + (s * TrackTimeline.NANOS / rate).toLong()

    /** A reading: at `tNanos` the Sonos plays stream position `heardFrame`. */
    @Synchronized
    fun refine(tNanos: Long, heardFrame: Double) {
        val measured = tNanos - (heardFrame * TrackTimeline.NANOS / rate).toLong()
        val delta = measured - startNanos
        // the first readings replace the prediction, later ones only smooth the noise
        val weight = if (refined < FIRST_READINGS || Math.abs(delta) > BIG_STEP_NANOS) 1.0 else SMOOTHING
        startNanos += (delta * weight).toLong()
        refined++
    }

    companion object {
        private const val FIRST_READINGS = 2
        private const val BIG_STEP_NANOS = 200_000_000L
        private const val SMOOTHING = 0.15
    }
}
