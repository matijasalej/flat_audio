package io.github.matijasalej.wholeflat.sync

/**
 * How soon the kitchen may try to start again after a start failed.
 *
 * A start that fails (the Sonos does not take the stream, or drops it again at once) and is tried again
 * at once makes the speaker start and cut over and over, which is worse than silence in the kitchen. The
 * first failed start is tried again soon (the usual cooldown of a re-join); after the second the waits grow:
 * 10 s, 30 s, 60 s, 120 s and then 5 minutes at most. A stream that went on well for a while ([onHealthy])
 * starts it all over.
 *
 * Plain Kotlin; the caller passes the time (any monotonic clock, in ms).
 */
class StartBackoff(private val waitsMs: LongArray = DEFAULT_WAITS_MS) {
    /** Failed starts in a row. */
    @Volatile
    var failures = 0
        private set

    private var blockedUntilMs = Long.MIN_VALUE

    /** A start failed at [nowMs]. Returns how long the next start has to wait (0: none). */
    @Synchronized
    fun onFailure(nowMs: Long): Long {
        failures++
        val wait = waitsMs[minOf(failures - 1, waitsMs.size - 1)]
        blockedUntilMs = if (wait > 0) nowMs + wait else Long.MIN_VALUE
        return wait
    }

    /** A stream went on well: the failures are forgotten. */
    @Synchronized
    fun onHealthy() {
        failures = 0
        blockedUntilMs = Long.MIN_VALUE
    }

    @Synchronized
    fun allowed(nowMs: Long): Boolean = nowMs >= blockedUntilMs

    /** How long a start has to wait still (ms), 0 if it may begin. */
    @Synchronized
    fun remainingMs(nowMs: Long): Long = if (nowMs >= blockedUntilMs) 0L else blockedUntilMs - nowMs

    companion object {
        /** After the 1st, 2nd, 3rd ... failed start in a row; the last value goes on for ever. */
        val DEFAULT_WAITS_MS = longArrayOf(0L, 10_000L, 30_000L, 60_000L, 120_000L, 300_000L)
    }
}
