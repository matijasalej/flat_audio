package io.github.matijasalej.wholeflat.sync

/**
 * When the living room plays what.
 *
 * The living room is the clock master. Everything it plays goes through one `AudioTrack`, whose frames
 * are numbered from the start of the track ("track frames"). The ring numbers the decoded audio
 * ("ring frames"). A **run** says that ring frames `ringStart, ringStart + 1, ...` were written at
 * track frames `trackStart, trackStart + 1, ...`; a new run starts after silence (a start event) or
 * after an underrun, so a run is always contiguous.
 *
 * An **anchor** ties the track frames to the clock: `AudioTrack.getTimestamp` says that track frame
 * `trackFrame` was presented (left the audio HAL) at `nanos` on the `System.nanoTime()` clock. What
 * the room downstream of the HAL adds (HDMI, the TV, the amplifier) is not known here and not needed:
 * it is part of the kitchen offset that the calibration finds.
 *
 * Plain Kotlin, no Android classes, so that the JVM tests can run it.
 */
class TrackTimeline(private val rate: Int = RATE) {
    class Run(val ringStart: Long, val trackStart: Long) {
        var frames: Long = 0
    }

    private val runs = ArrayList<Run>()
    private var anchorFrame = 0L
    private var anchorNanos = 0L

    @Volatile
    var hasAnchor = false
        private set

    /** When the anchor was last set (`System.nanoTime()` clock), 0 if never. */
    @Volatile
    var anchorSetNanos = 0L
        private set

    @Synchronized
    fun setAnchor(trackFrame: Long, nanos: Long, setAtNanos: Long = nanos) {
        anchorFrame = trackFrame
        anchorNanos = nanos
        anchorSetNanos = setAtNanos
        hasAnchor = true
    }

    /** The track was flushed or recreated: its frame numbers start again, the old anchor is wrong. */
    @Synchronized
    fun clearAnchor() {
        hasAnchor = false
        anchorSetNanos = 0L
    }

    @Synchronized
    fun halNanosOfTrack(trackFrame: Long): Long =
        anchorNanos + (trackFrame - anchorFrame) * NANOS / rate

    /** The track frame that is presented at `nanos` (rounded down). */
    @Synchronized
    fun trackOfHalNanos(nanos: Long): Long =
        anchorFrame + Math.floorDiv((nanos - anchorNanos) * rate, NANOS)

    /** The same, rounded to the nearest frame. */
    @Synchronized
    fun trackNearestHalNanos(nanos: Long): Long =
        anchorFrame + Math.floorDiv((nanos - anchorNanos) * rate + NANOS / 2, NANOS)

    /** A new contiguous run: ring frame `ringStart` is written at track frame `trackStart`. */
    @Synchronized
    fun startRun(ringStart: Long, trackStart: Long) {
        runs.add(Run(ringStart, trackStart))
        // old runs are of no use once they are further back than the ring's history
        while (runs.size > MAX_RUNS) runs.removeAt(0)
    }

    /** The last run grew by `frames` written frames. */
    @Synchronized
    fun extend(frames: Long) {
        if (runs.isNotEmpty()) runs[runs.size - 1].frames += frames
    }

    @Synchronized
    fun clear() {
        runs.clear()
    }

    @Synchronized
    fun runCount(): Int = runs.size

    /**
     * The track frame at which ring frame `f` was or will be written: exact inside a run, a
     * prediction (the run goes on without a gap) beyond the end of the last run, null before the first
     * run or in a gap between two runs.
     */
    @Synchronized
    fun trackOfRing(f: Long): Long? {
        for (i in runs.indices.reversed()) {
            val run = runs[i]
            if (f < run.ringStart) continue
            val last = i == runs.size - 1
            return if (last || f < run.ringStart + run.frames) run.trackStart + (f - run.ringStart) else null
        }
        return null
    }

    /** The ring frame that was or will be written at track frame `t`; null in silence before or between runs. */
    @Synchronized
    fun ringOfTrack(t: Long): Long? {
        for (i in runs.indices.reversed()) {
            val run = runs[i]
            if (t < run.trackStart) continue
            val last = i == runs.size - 1
            return if (last || t < run.trackStart + run.frames) run.ringStart + (t - run.trackStart) else null
        }
        return null
    }

    /** When the HAL presents ring frame `f` (see [trackOfRing]); null if unknown. */
    @Synchronized
    fun halNanosOfRing(f: Long): Long? {
        if (!hasAnchor) return null
        val t = trackOfRing(f) ?: return null
        return halNanosOfTrack(t)
    }

    /** The ring frame the HAL presents at `nanos`; null in silence, in a gap or without an anchor. */
    @Synchronized
    fun ringAtHalNanos(nanos: Long): Long? {
        if (!hasAnchor) return null
        return ringOfTrack(trackOfHalNanos(nanos))
    }

    companion object {
        const val RATE = 44_100
        const val NANOS = 1_000_000_000L
        private const val MAX_RUNS = 16
    }
}
