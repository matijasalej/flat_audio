package io.github.matijasalej.wholeflat.sync

/**
 * Smooths the (frame, time) pairs that `AudioTrack.getTimestamp` reports.
 *
 * The HAL reports positions with a granularity of its period size, so single pairs jitter by a few
 * milliseconds. Over a few seconds the track plays at the nominal rate to well within a millisecond
 * (the clock drift is tens of ppm), so each pair is reduced to its offset `nanos - frame / rate` and
 * the median of the last [window] offsets is the anchor. A reading that is far off the median is an
 * outlier and ignored, unless [stepCount] of them in a row agree that the offset has really moved
 * (a route change, an underrun the caller did not see): then the window starts over.
 */
class AnchorFilter(
    private val window: Int = 9,
    private val rate: Int = TrackTimeline.RATE,
    private val stepNanos: Long = 8_000_000L,
    private val stepCount: Int = 3,
) {
    class Anchor(val frame: Long, val nanos: Long)

    private val offsets = ArrayList<Double>()
    private var stepStreak = 0

    /** Readings that were far off the median and ignored, and times the window started over on a step, since the start (for a look at the clock). */
    @Volatile
    var outliers = 0
        private set

    @Volatile
    var restarts = 0
        private set

    val size: Int get() = offsets.size

    fun reset() {
        offsets.clear()
        stepStreak = 0
    }

    private fun nanosPerFrame(frame: Long): Double = frame.toDouble() * TrackTimeline.NANOS / rate

    private fun median(): Double {
        val sorted = offsets.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** Adds a reading and returns the smoothed anchor at the same frame. */
    fun add(frame: Long, nanos: Long): Anchor {
        val offset = nanos.toDouble() - nanosPerFrame(frame)
        if (offsets.isNotEmpty() && Math.abs(offset - median()) > stepNanos) {
            stepStreak++
            if (stepStreak < stepCount) {
                // an outlier: answer with what the window says
                outliers++
                return Anchor(frame, (median() + nanosPerFrame(frame)).toLong())
            }
            restarts++
            offsets.clear()
        }
        stepStreak = 0
        offsets.add(offset)
        while (offsets.size > window) offsets.removeAt(0)
        return Anchor(frame, (median() + nanosPerFrame(frame)).toLong())
    }
}
