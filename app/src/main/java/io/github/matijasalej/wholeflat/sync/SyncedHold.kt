package io.github.matijasalej.wholeflat.sync

import kotlin.math.abs

/**
 * What the kitchen's stream does in Synced mode once it has started: it is **set once and then left alone**.
 *
 * The two rooms agree on a delay D after the capture (the TV's from the backlog of its connection, see [SyncedTiming]); the kitchen is
 * put at D plus the kitchen offset when its stream starts, and corrected once more when it has landed (the speaker's start varies by
 * 100 ms or so with the lead). After that no trimming goes on: the Sonos and the TV run on clocks that agree to about 20 ppm, and a
 * controller that follows the readings to the millisecond only follows their noise (the lag estimate of a stream that holds 2 s of
 * lead moved by 10 to 25 ms from one second to the next, and the continuous trims it caused moved the real kitchen against the TV).
 *
 *  - **Placing.** The first time a stream is seen at its lag, and again when the target moves (a new backlog of the TV after a
 *    reconnect, a changed kitchen offset), the error is trimmed away in one go (the trims reach the speaker at the pace the stream
 *    builder allows, 0.45 %). An error beyond [restartMs] is not trimmed but gets a fresh start (the lead then follows what was measured).
 *  - **Holding.** Only an error beyond [farMs] that has lasted [farForNanos] without a break is corrected, and at most once in
 *    [everyNanos]. Every correction is reported to the caller, which logs it.
 *
 * The error is the lag of the kitchen less the target less the trims on their way (positive: the kitchen is late). Plain Kotlin.
 */
class SyncedHold(
    private val rate: Int,
    private val placeMinMs: Double = PLACE_MIN_MS,
    private val farMs: Double = FAR_MS,
    private val farForNanos: Long = FAR_FOR_NANOS,
    private val everyNanos: Long = EVERY_NANOS,
    private val restartMs: Double = RESTART_MS,
) {
    sealed class Decision {
        object Hold : Decision()

        /** Drop this many frames from the stream (negative: repeat), for the reason in [why]. */
        class Trim(val frames: Int, val why: String) : Decision()

        /** Start the stream again (with a lead that follows what was measured). */
        class Restart(val why: String) : Decision()
    }

    /** The target the error is counted from (ms after the capture); NaN before the first reading. */
    @Volatile
    var targetMs = Double.NaN
        private set

    /** The last error (ms, positive: the kitchen is late), NaN before the first reading. */
    @Volatile
    var errorMs = Double.NaN
        private set

    /** Corrections made in this stream, the placing included. */
    @Volatile
    var corrections = 0
        private set

    /** Whether the stream has been placed at its target. */
    @Volatile
    var placed = false
        private set

    /** For how long the error has been beyond the band without a break (ms; 0 if it is inside). */
    @Volatile
    var farForMs = 0L
        private set

    /** Whether an error beyond [restartMs] may be answered with a fresh start (the caller says no when it did so just before). */
    @Volatile
    var restartAllowed = true

    private var farSince = 0L
    private var isFar = false
    private var lastCorrectionNanos = 0L
    private var haveCorrection = false

    /** A new stream. */
    @Synchronized
    fun reset() {
        targetMs = Double.NaN
        errorMs = Double.NaN
        corrections = 0
        placed = false
        farForMs = 0L
        farSince = 0L
        isFar = false
        haveCorrection = false
    }

    /** One reading of the lag at [tNanos]: the [errorMs] against [targetMs] (NaN: nothing to say yet). */
    @Synchronized
    fun onReading(tNanos: Long, errorMs: Double, targetMs: Double): Decision {
        if (errorMs.isNaN() || targetMs.isNaN()) return Decision.Hold
        this.errorMs = errorMs
        val before = this.targetMs
        this.targetMs = targetMs
        if (!before.isNaN() && abs(targetMs - before) >= TARGET_STEP_MS) {
            // a new target: the kitchen is set once more
            placed = false
            isFar = false
            farForMs = 0L
        }
        if (!placed) {
            placed = true
            isFar = false
            farForMs = 0L
            return correct(tNanos, errorMs, "placing the kitchen at ${fmt(targetMs)} ms", placeMinMs)
        }
        if (abs(errorMs) <= farMs) {
            isFar = false
            farForMs = 0L
            return Decision.Hold
        }
        if (!isFar) {
            isFar = true
            farSince = tNanos
        }
        val farFor = tNanos - farSince
        farForMs = farFor / 1_000_000L
        if (farFor < farForNanos) return Decision.Hold
        if (haveCorrection && tNanos - lastCorrectionNanos < everyNanos) return Decision.Hold
        return correct(tNanos, errorMs, "${fmt(errorMs)} ms off for ${farFor / 1_000_000_000L} s (more than ${farMs.toInt()} ms)", 0.0)
    }

    private fun correct(tNanos: Long, errorMs: Double, why: String, minMs: Double): Decision {
        if (abs(errorMs) < minMs) return Decision.Hold
        val frames = Math.round(errorMs * rate / 1000.0).toInt()
        if (frames == 0) return Decision.Hold
        haveCorrection = true
        lastCorrectionNanos = tNanos
        isFar = false
        farForMs = 0L
        corrections++
        if (abs(errorMs) > restartMs && restartAllowed) return Decision.Restart("$why: the kitchen is ${fmt(errorMs)} ms from it, more than a trim is for")
        val limit = Math.round(MAX_TRIM_MS * rate / 1000.0).toInt()
        return Decision.Trim(frames.coerceIn(-limit, limit), why)
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

    companion object {
        /** The placing leaves an error smaller than this alone (ms): the readings are good to about that. */
        const val PLACE_MIN_MS = 8.0

        /** The band inside which the kitchen is left alone (ms). */
        const val FAR_MS = 40.0

        /** How long the error has to be beyond the band, without a break, before it is corrected. */
        const val FAR_FOR_NANOS = 30_000_000_000L

        /** The least time between two corrections. */
        const val EVERY_NANOS = 60_000_000_000L

        /** An error beyond this is a fresh start rather than a trim (ms). */
        const val RESTART_MS = 250.0

        /** The most a single correction trims (ms), when a fresh start is not allowed. */
        const val MAX_TRIM_MS = 600.0

        /** A target that moves by this much is a new target (ms). */
        const val TARGET_STEP_MS = 3.0
    }
}
