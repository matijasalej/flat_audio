package io.github.matijasalej.wholeflat.light

/**
 * The safety rule of the lights, kept whatever the music does: two flashes start at least [MIN_GAP_MS] apart (ITU-R BT.1702-3's simple
 * rule, under WCAG 2.3.1's three a second), and a saturated red ([isSaturatedRed]) never flashes deeper than [RED_DEPTH]. The planner asks
 * before every pulse. Plain Kotlin, one thread.
 */
class FlashLimiter(private val minGapFrames: Long) {
    private var last = Long.MIN_VALUE / 4

    /** Pulses let through and held back, for the status. */
    var allowed = 0L
        private set
    var held = 0L
        private set

    /** May a flash start at frame [onset]? If so it is counted as the last one. */
    fun allow(onset: Long): Boolean {
        if (onset - last < minGapFrames) {
            held++
            return false
        }
        last = onset
        allowed++
        return true
    }

    fun reset() {
        last = Long.MIN_VALUE / 4
    }

    companion object {
        const val MIN_GAP_MS = 360
        const val RED_SHARE = 0.8
        const val RED_DEPTH = 0.10

        /** The limiter for capture frames at [rate]. */
        fun forRate(rate: Int): FlashLimiter = FlashLimiter(rate.toLong() * MIN_GAP_MS / 1000)

        /** A colour that is mostly red and not dark. */
        fun isSaturatedRed(c: Int): Boolean = Colors.peak(c) >= 60 && Colors.redShare(c) >= RED_SHARE
    }
}
