package io.github.matijasalej.wholeflat.sync

/**
 * Decides when to send Play to the Sonos and where to put the first frame of an epoch in the living
 * room's track, so that both rooms start together.
 *
 * The kitchen's first sound is at `tCmd + lk` (`lk` is the measured delay from the Play request to the
 * sound, about 0.29 s). The kitchen should be `offset` later than the living room, so the living
 * room's first frame is presented at `tCmd + lk - offset`. The living room cannot start earlier than
 * the earliest frame it can still write (its buffer is already queued), so Play is held back until the
 * living room can make it: `tCmd` is the later of "now" and the moment that makes this possible.
 */
object StartPlanner {
    const val DEFAULT_MARGIN_NANOS = 10_000_000L

    class Plan(
        /** When to send Play. */
        val tCmdNanos: Long,
        /** When the kitchen's first frame should be heard. */
        val kitchenSoundNanos: Long,
        /** Track frame of the living room's first frame of the epoch. */
        val firstTrackFrame: Long,
        /** When the HAL presents that frame. */
        val livingHalNanos: Long,
        /** True if Play had to wait for the living room. */
        val delayed: Boolean,
    )

    fun planTogether(
        nowNanos: Long,
        earliestTrackFrame: Long,
        timeline: TrackTimeline,
        lkNanos: Long,
        offsetNanos: Long,
        marginNanos: Long = DEFAULT_MARGIN_NANOS,
    ): Plan {
        val earliestHal = timeline.halNanosOfTrack(earliestTrackFrame)
        val feasible = earliestHal + marginNanos - lkNanos + offsetNanos
        val tCmd = maxOf(nowNanos, feasible)
        val kitchenSound = tCmd + lkNanos
        val wanted = timeline.trackNearestHalNanos(kitchenSound - offsetNanos)
        val first = maxOf(earliestTrackFrame, wanted)
        return Plan(tCmd, kitchenSound, first, timeline.halNanosOfTrack(first), feasible > nowNanos)
    }

    /** No kitchen: the living room starts as soon as it can. */
    fun planImmediate(earliestTrackFrame: Long, timeline: TrackTimeline): Plan {
        val hal = timeline.halNanosOfTrack(earliestTrackFrame)
        return Plan(hal, hal, earliestTrackFrame, hal, false)
    }

    /**
     * Hot-join: the kitchen joins while the living room plays. Returns the ring frame the Sonos stream
     * has to start at, so that its first frame is heard `offset` after the living room plays it.
     * `tCmd` is when Play is sent.
     */
    fun hotJoinFrame(timeline: TrackTimeline, tCmdNanos: Long, lkNanos: Long, offsetNanos: Long): Long? {
        val livingHal = tCmdNanos + lkNanos - offsetNanos
        return timeline.ringAtHalNanos(livingHal)
    }
}
