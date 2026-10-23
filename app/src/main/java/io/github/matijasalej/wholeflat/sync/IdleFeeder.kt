package io.github.matijasalej.wholeflat.sync

/**
 * How much silence to write to an idle AudioTrack.
 *
 * A streaming AudioTrack only starts when its buffer holds the start threshold, which on some devices (the
 * Chromecast with Google TV among them) is the whole buffer, and after an underrun it only goes on when that
 * many frames are queued again. A writer that keeps just a small lead queued (the idle feeding wants to
 * stay close to real time, so that a start event can be placed early) then waits for a track that waits
 * for the writer: the position stays at 0 for ever. That was the first bug of the first device run.
 *
 * So the track is filled up while its position has not moved yet and whenever it stops moving; only a
 * track that runs gets the small steady lead. Plain Kotlin, the caller passes the time in.
 */
class IdleFeeder(
    /** The steady queue of a running track: about 100 ms. */
    private val leadFrames: Int,
    /** What the track's buffer holds at most. */
    private val capacityFrames: Int,
    /** A position that has not moved for this long counts as stuck. */
    private val stuckNanos: Long = 120_000_000L,
) {
    private var lastHead = 0L
    private var lastMoveNanos = 0L
    private var moved = false

    /** True once the playback position has moved since [reset]. */
    val started: Boolean get() = moved

    /** A new or flushed track: its position is 0 and has not moved yet. */
    fun reset(nowNanos: Long) {
        lastHead = 0L
        lastMoveNanos = nowNanos
        moved = false
    }

    /**
     * Silence frames to write now (never more than fit into the buffer; 0 when there is nothing to do).
     * [written] is the number of frames written since the track was created or flushed, [head] its
     * playback position in frames.
     */
    fun framesToWrite(written: Long, head: Long, nowNanos: Long): Int {
        if (head != lastHead) {
            lastHead = head
            lastMoveNanos = nowNanos
            moved = true
        }
        val queued = (written - head).coerceAtLeast(0L)
        val room = (capacityFrames - queued).coerceAtLeast(0L)
        val filling = !moved || nowNanos - lastMoveNanos >= stuckNanos
        val want = if (filling) capacityFrames - queued else leadFrames - queued
        return want.coerceIn(0L, room).toInt()
    }
}

/** What the watchdog makes of a track whose position does not move. */
object StallPolicy {
    enum class Verdict {
        /** Not stuck for long enough yet. */
        OK,

        /** Stuck, but the buffer is not full: the writer is starving the track, the track is not broken. */
        STARVING,

        /** Stuck with a full buffer: the track (or the sink behind it) is broken. */
        STALLED,
    }

    /**
     * @param advancedOnce the position has moved since the track was built
     * @param stuckNanos how long it has not moved
     * @param queuedFrames written and not yet played
     * @param capacityFrames what the buffer holds at most
     * @param slackFrames a buffer this close to full counts as full
     */
    fun judge(
        advancedOnce: Boolean,
        stuckNanos: Long,
        queuedFrames: Long,
        capacityFrames: Int,
        stallNanos: Long,
        startupNanos: Long,
        slackFrames: Int = 1_024,
    ): Verdict {
        val allowed = if (advancedOnce) stallNanos else startupNanos
        if (stuckNanos <= allowed) return Verdict.OK
        return if (queuedFrames < capacityFrames - slackFrames) Verdict.STARVING else Verdict.STALLED
    }
}
