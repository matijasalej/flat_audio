package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.sync.AnchorFilter

/**
 * When each captured frame was captured: ties the frame numbers of a generation to the System.nanoTime
 * clock. The capture reports pairs (frame, time) now and then, from `AudioRecord.getTimestamp` or, where
 * the device gives none, from the moments its reads return; they are smoothed like the living room's audio
 * timestamps (the median of the offsets of the last few).
 */
class CaptureClock(private val rate: Int) {
    private val filter = AnchorFilter(window = 9, rate = rate)
    private var anchorFrame = 0L
    private var anchorNanos = 0L

    @Volatile
    var hasAnchor = false
        private set

    @Synchronized
    fun reset() {
        filter.reset()
        hasAnchor = false
    }

    /** Frame number [frame] was captured at [nanos]. */
    @Synchronized
    fun add(frame: Long, nanos: Long) {
        val a = filter.add(frame, nanos)
        anchorFrame = a.frame
        anchorNanos = a.nanos
        hasAnchor = true
    }

    /** When frame [frame] was captured; null before the first anchor. */
    @Synchronized
    fun nanosOfFrame(frame: Long): Long? =
        if (!hasAnchor) null else anchorNanos + (frame - anchorFrame) * NANOS / rate

    /** The frame captured at [nanos] (rounded down); null before the first anchor. */
    @Synchronized
    fun frameAtNanos(nanos: Long): Long? =
        if (!hasAnchor) null else anchorFrame + Math.floorDiv((nanos - anchorNanos) * rate, NANOS)

    /** The frame captured at [nanos] as a fraction (frame numbers are whole samples; this is for clocks that are to be smooth); null before the first anchor. */
    @Synchronized
    fun frameAtNanosExact(nanos: Long): Double? =
        if (!hasAnchor) null else anchorFrame + (nanos - anchorNanos).toDouble() * rate / NANOS

    private companion object {
        const val NANOS = 1_000_000_000L
    }
}
