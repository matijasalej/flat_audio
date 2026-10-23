package io.github.matijasalej.wholeflat.sync

/**
 * The warm-up of a stream to the TV, which hides how the TV starts. The TV does not take a stream the way a speaker does: it opens
 * several connections in its first few seconds (a probe, two that it reads for a while and drops, and the one it stays on), and what it
 * plays hitches while it settles. Until it has settled the stream carries a splash picture and silence; then the content changes to the
 * music at the next IDR picture (and the other room starts at that moment). Only a grab does this, never a change of song.
 *
 * The TV has settled when it plays, has one connection open, and has not asked for another for [settleNanos]. If it never does, the
 * warm-up ends after [maxNanos] all the same (a TV that keeps reconnecting is no reason for a silent flat). Plain Kotlin.
 */
class Warmup(val settleNanos: Long = 2_500_000_000L, val maxNanos: Long = 25_000_000_000L) {
    var live = false
        private set

    /** Why the warm-up ended ("settled", "timed out"), for the log. */
    var reason = ""
        private set

    var startNanos = 0L
        private set

    var endNanos = 0L
        private set

    /** GET requests of the TV in this warm-up. */
    var gets = 0
        private set

    private var lastGetNanos = 0L

    /** A stream begins (Play was sent) at [nowNanos]. */
    fun start(nowNanos: Long) {
        live = false
        reason = ""
        startNanos = nowNanos
        endNanos = 0L
        gets = 0
        lastGetNanos = 0L
    }

    /** The TV asked for the stream (a GET). */
    fun onGet(nowNanos: Long) {
        gets++
        lastGetNanos = nowNanos
    }

    /** How long since the TV last asked for the stream (ms); -1 if it has not. */
    fun sinceLastGetMs(nowNanos: Long): Long = if (gets == 0) -1L else (nowNanos - lastGetNanos) / 1_000_000L

    /** How long the warm-up has lasted (ms): until it ended, or until [nowNanos]. */
    fun durationMs(nowNanos: Long): Long = ((if (live) endNanos else nowNanos) - startNanos) / 1_000_000L

    /** True once, at the moment the warm-up is over. */
    fun poll(nowNanos: Long, openConnections: Int, tvPlaying: Boolean): Boolean {
        if (live) return false
        if (tvPlaying && gets > 0 && openConnections == 1 && nowNanos - lastGetNanos >= settleNanos) {
            finish(nowNanos, "settled: one connection for ${(nowNanos - lastGetNanos) / 1_000_000L} ms without another GET")
            return true
        }
        if (nowNanos - startNanos >= maxNanos) {
            finish(
                nowNanos,
                "timed out after ${(nowNanos - startNanos) / 1_000_000L} ms ($openConnections connections open, " +
                    "${if (tvPlaying) "the TV plays" else "the TV does not play"}, $gets GETs)",
            )
            return true
        }
        return false
    }

    private fun finish(nowNanos: Long, why: String) {
        live = true
        endNanos = nowNanos
        reason = why
    }
}
