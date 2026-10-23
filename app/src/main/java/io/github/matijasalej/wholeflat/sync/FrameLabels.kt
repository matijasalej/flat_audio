package io.github.matijasalej.wholeflat.sync

/**
 * Pictures that were put on the encoder's surface, in order: the moment (System.nanoTime) each was queued, the capture time it shows and
 * its PTS. The encoder hands a picture back with the timestamp that it was queued with, which is a little later than the moment the
 * drawing call returned when the GPU does the drawing (and a hair earlier with the software canvas), so a picture is found by the newest
 * label that is not later than its timestamp. If the encoder's timestamps are not on this clock at all, the offset is learnt from the
 * first picture (the first one out is the first one in). Thread safe. Plain Kotlin.
 */
class FrameLabels {
    private val times = LongArray(64)
    private val captures = LongArray(64)
    private val pts = LongArray(64)
    private var n = 0

    /** Added to the encoder's timestamps to bring them to the clock of the labels; learnt if the first picture does not fit. */
    @Volatile
    var epochOffsetNanos = 0L
        private set

    @Volatile
    var learnedOffset = false
        private set
    private var outputs = 0L

    @Synchronized
    fun add(t: Long, capture: Long, p: Long) {
        val i = n % times.size
        times[i] = t
        captures[i] = capture
        pts[i] = p
        n++
    }

    /** The label for a picture that the encoder timestamped [encoderNanos]: its capture time and PTS; null if none is near. */
    @Synchronized
    fun find(encoderNanos: Long): Pair<Long, Long>? {
        outputs++
        var t = encoderNanos + epochOffsetNanos
        var best = pick(t)
        if (best < 0 && outputs == 1L && n > 0) {
            // the first picture out matches nothing: the clocks differ; the oldest label that is still there is the first picture
            var oldest = 0
            for (i in 0 until minOf(n, times.size)) if (times[i] < times[oldest]) oldest = i
            epochOffsetNanos = times[oldest] - encoderNanos
            learnedOffset = true
            t = encoderNanos + epochOffsetNanos
            best = pick(t)
        }
        return if (best >= 0) Pair(captures[best], pts[best]) else null
    }

    private fun pick(t: Long): Int {
        var best = -1
        for (i in 0 until minOf(n, times.size)) {
            if (times[i] <= t + SLACK_NANOS && t - times[i] <= WINDOW_NANOS && (best < 0 || times[i] > times[best])) best = i
        }
        return best
    }

    private companion object {
        const val SLACK_NANOS = 2_000_000L
        const val WINDOW_NANOS = 80_000_000L
    }
}
