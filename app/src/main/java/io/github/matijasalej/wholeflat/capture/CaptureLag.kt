package io.github.matijasalej.wholeflat.capture

/**
 * The lag of the kitchen behind the captured player, and the lag the controller holds it at.
 *
 * Nothing in the kitchen can sound before the audio has been captured, and the Sonos wants a buffer of its
 * own, so the lag is whatever the speaker settled at after Play: it is measured, not planned. The first
 * readings of a session only measure; the median of them is locked as the lag of the session, and from
 * then on the controller keeps it constant against the drift of the speaker's clock (see SyncController).
 *
 * The kitchen offset of the settings still works: after the lock a change of it moves the target lag by the
 * same amount (later when positive), but the target never goes below [minLagMs].
 */
class CaptureLag(samples: Int = 11, private val minLagMs: Double = MIN_LAG_MS) {
    private val tracker = LagTracker(samples)

    /**
     * The lag with the offset that was set at the lock taken out. The controller treats the capture time of a
     * frame plus this as the moment the "other room" plays it, and its offset on top of that.
     */
    @Volatile
    var baseMs = Double.NaN
        private set

    /** The lag the session settled at (ms), null while it is still being measured. */
    val locked: Double? get() = tracker.locked

    val isLocked: Boolean get() = tracker.locked != null

    /** Readings taken so far (up to the number needed to lock). */
    val readings: Int get() = tracker.count

    @Synchronized
    fun reset() {
        tracker.reset()
        baseMs = Double.NaN
    }

    /** One reading of the lag (ms). True if this one locked it. [offsetMs] is the kitchen offset in force. */
    @Synchronized
    fun onReading(lagMs: Double, offsetMs: Int): Boolean {
        val m = tracker.add(lagMs) ?: return false
        baseMs = m - offsetMs
        return true
    }

    /** The offset the controller works with for the user's [offsetMs]: not so early that the target goes below the minimum. */
    fun controllerOffsetMs(offsetMs: Int): Double {
        val base = baseMs
        return if (base.isNaN()) offsetMs.toDouble() else maxOf(offsetMs.toDouble(), minLagMs - base)
    }

    /** The lag the controller holds (ms); NaN before the lock. */
    fun targetMs(offsetMs: Int): Double {
        val base = baseMs
        return if (base.isNaN()) Double.NaN else base + controllerOffsetMs(offsetMs)
    }

    /** What to add to the capture time of a frame to get the time the controller compares the kitchen with. */
    fun baseNanos(): Long = if (baseMs.isNaN()) 0L else (baseMs * 1_000_000.0).toLong()

    /** The median of the last readings (ms), NaN before the first. Never negative: the kitchen cannot be earlier than the capture. */
    fun recentMs(): Double {
        val v = tracker.recent()
        return if (v.isNaN()) v else maxOf(0.0, v)
    }

    companion object {
        /** The target lag is never smaller than this (ms). */
        const val MIN_LAG_MS = 100.0
    }
}
