package io.github.matijasalej.wholeflat.sync

/**
 * What the writer does with a video access unit that reaches it **after the moment it was due** (the encoder was slow): the audio, the
 * clock and the padding of the transport stream go out on the slot clock whatever the video does, and a picture is only let into the
 * stream while it can still reach the TV in time. The stream runs a PCR lead (700 ms) behind the presentation time of what it carries, so a
 * picture that is sent `late` after its due time arrives with that much less than the lead before it is to be shown, and it takes the time
 * it needs to send (a key picture of 100 KB is 90 ms of a 9 Mbit/s stream). A picture whose last packet would leave later than [maxEndNanos]
 * after its due time is **dropped**: a TV that is given a picture after its time has been seen to freeze and to take the audio with it,
 * which moves its sound against the other room for good, and a picture that is missing costs a freeze of the picture only.
 *
 * Every later picture refers to the one that was dropped, so they are dropped too until the next key picture that is in time; the caller
 * is told ([waitingForKey]) to ask the encoder for one. Plain Kotlin, thread safe.
 */
class LateVideoGate(
    /** The rate of the transport stream (bit/s): what a picture needs to send. */
    private val muxRate: Int,
    /** The most that the end of a picture may be after its due time (ns). */
    private val maxEndNanos: Long = DEFAULT_MAX_END_NANOS,
) {
    enum class Verdict {
        /** In time: into the stream. */
        SEND,

        /** Too late: it is not sent, and what follows it is not either until a key picture. */
        TOO_LATE,

        /** In time itself, but it follows a picture that was dropped and would be decoded without it. */
        BROKEN,
    }

    /** True from a drop until a key picture got through: the encoder is to be asked for one. */
    @Volatile
    var waitingForKey = false
        private set

    /** Pictures not sent, in all; the ones that were too late themselves; the ones that followed a dropped one. */
    @Volatile
    var dropped = 0L
        private set

    @Volatile
    var droppedLate = 0L
        private set

    @Volatile
    var droppedBroken = 0L
        private set

    /** Times the stream went into the state of waiting for a key picture. */
    @Volatile
    var episodes = 0L
        private set

    /** Pictures that were sent although they were more than [LATE_NOTED_MS] late. */
    @Volatile
    var sentLate = 0L
        private set

    /** The latest end of a picture after its due time that was let through (ms), since the start. */
    @Volatile
    var worstSentEndMs = 0.0
        private set

    /** The time (ns) a picture of [bytes] needs to send behind [queuedVideoBytes] of video that waits already. */
    fun sendNanos(bytes: Int, queuedVideoBytes: Long): Long = ((queuedVideoBytes + bytes) * 8.0e9 / (muxRate * VIDEO_SHARE)).toLong()

    /**
     * The verdict on a picture of [bytes] bytes that is [lateNanos] after its due time, with [queuedVideoBytes] of video waiting in the
     * muxer; [key]: it is a key picture.
     */
    @Synchronized
    fun judge(key: Boolean, lateNanos: Long, bytes: Int, queuedVideoBytes: Long): Verdict {
        val end = maxOf(0L, lateNanos) + sendNanos(bytes, queuedVideoBytes)
        val inTime = end <= maxEndNanos
        if (waitingForKey && !key) {
            dropped++
            droppedBroken++
            return Verdict.BROKEN
        }
        if (!inTime) {
            if (!waitingForKey) {
                waitingForKey = true
                episodes++
            }
            dropped++
            droppedLate++
            return Verdict.TOO_LATE
        }
        waitingForKey = false
        if (lateNanos > LATE_NOTED_MS * 1_000_000L) sentLate++
        val endMs = end / 1e6
        if (endMs > worstSentEndMs) worstSentEndMs = endMs
        return Verdict.SEND
    }

    companion object {
        /** The PCR lead (700 ms) less 150 ms that the TV is left to take the picture in. */
        const val DEFAULT_MAX_END_NANOS = 550_000_000L

        /** The part of the stream that video can have: the audio, the clock and the tables take the rest. */
        const val VIDEO_SHARE = 0.9

        /** A picture sent later than this after its due time is counted. */
        const val LATE_NOTED_MS = 150L
    }
}
