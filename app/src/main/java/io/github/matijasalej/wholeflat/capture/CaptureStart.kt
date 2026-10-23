package io.github.matijasalej.wholeflat.capture

/**
 * Where in the captured audio the stream to the Sonos begins.
 *
 * The speaker starts as soon as it can: the stream begins a little in the past, so that the speaker has
 * something to start with and the beginning of what just started playing is not lost. The distance is the
 * "lead" (audio sent ahead at the start), counted back from the newest frame at the moment Play is sent. The
 * kitchen offset is not part of it: a persistent negative offset would make every start later (the Sonos
 * starts when it has about 270 ms buffered, and a smaller lead only makes that take longer). This is only
 * the plan: what the speaker really does with it is measured afterwards (see [CaptureLag]).
 */
object CaptureStart {
    /** Frame [startFrame] is the first of the stream; the plan expects the kitchen to be [plannedLagMs] behind. */
    class Plan(val startFrame: Long, val aheadFrames: Long, val plannedLagMs: Double, val limited: Boolean)

    /** The stream never starts closer than this to the newest captured frame (ms). */
    const val MIN_LEAD_MS = 10

    /**
     * How far the clock of the reads may be ahead of the frames that were written (ms): the time since the last
     * read. A capture that stalled has a clock that runs on without frames; the stream then does not start
     * further ahead than this.
     */
    const val MAX_RUN_ON_MS = 50

    /**
     * @param nowFrame the frame that is being captured when Play is sent
     * @param writeIndex the number of frames captured so far
     * @param validStart the oldest frame the buffer still holds
     * @param lkMs the delay from Play to the first sound of the speaker
     */
    fun plan(
        nowFrame: Long,
        writeIndex: Long,
        validStart: Long,
        rate: Int,
        leadMs: Int,
        lkMs: Double,
    ): Plan {
        val perMs = rate / 1000.0
        val minAhead = Math.round(MIN_LEAD_MS * perMs)
        val wantAhead = Math.round(leadMs * perMs)
        var limited = false
        // the newest frame at Play, as far as the clock of the reads can be believed
        var now = nowFrame
        val latest = writeIndex + Math.round(MAX_RUN_ON_MS * perMs)
        if (now > latest) {
            now = latest
            limited = true
        }
        var start = now - maxOf(wantAhead, minAhead)
        if (wantAhead < minAhead) limited = true
        // never before what the buffer still holds (a margin keeps the reader from being lapped at once)
        val oldest = if (validStart <= 0L) 0L else validStart + Math.round(250 * perMs)
        if (start < oldest) {
            start = oldest
            limited = true
        }
        if (start < 0L) start = 0L
        val ahead = maxOf(0L, now - start)
        return Plan(start, ahead, lkMs + ahead / perMs, limited)
    }
}
