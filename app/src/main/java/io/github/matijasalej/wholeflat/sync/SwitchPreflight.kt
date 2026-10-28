package io.github.matijasalej.wholeflat.sync

/**
 * What a start does with the TV's transport before it sets the item, read by read (GetTransportInfo, one a second at most). Plain Kotlin.
 *  - NO_MEDIA_PRESENT or STOPPED: the item is set at once, with no Stop (a Stop right behind another, after a release or a restart, is what
 *    the TV answers with 705 for seconds).
 *  - PLAYING, PAUSED_PLAYBACK and the like: one Stop, then the transport is given [afterStopMs] to say it stopped, then the item is set.
 *  - TRANSITIONING: it may be on its way somewhere, so it is given [busyWaitMs]; then one Stop; if it still says TRANSITIONING [afterStopMs]
 *    after that, its player is hung, and nothing is set (the item and Play only bring 705s and the jump to the tuner).
 *  - No answer: the item is set (the ready gate has seen the player answer; SetAVTransportURI has its own tries).
 */
class SwitchPreflight(private val busyWaitMs: Long = BUSY_WAIT_MS, private val afterStopMs: Long = AFTER_STOP_MS) {
    enum class Step { SET, STOP, WAIT, HUNG }

    private var busySinceMs = -1L
    private var stoppedAtMs = -1L

    /** The transport as it was first read, for the journal. */
    var first: String? = null
        private set

    /** Whether a Stop was asked for. */
    val stopped: Boolean get() = stoppedAtMs >= 0

    /** The next step, given the transport [transport] read at [nowMs] ("" if the TV did not answer). */
    fun next(nowMs: Long, transport: String): Step {
        if (first == null) first = transport
        return when (transport) {
            "NO_MEDIA_PRESENT", "STOPPED", "" -> Step.SET
            "TRANSITIONING" -> {
                if (busySinceMs < 0) busySinceMs = nowMs
                when {
                    stoppedAtMs < 0 && nowMs - busySinceMs < busyWaitMs -> Step.WAIT
                    stoppedAtMs < 0 -> stop(nowMs)
                    nowMs - stoppedAtMs < afterStopMs -> Step.WAIT
                    else -> Step.HUNG
                }
            }
            else -> when {
                stoppedAtMs < 0 -> stop(nowMs)
                nowMs - stoppedAtMs < afterStopMs -> Step.WAIT
                else -> Step.SET
            }
        }
    }

    private fun stop(nowMs: Long): Step {
        stoppedAtMs = nowMs
        return Step.STOP
    }

    companion object {
        const val BUSY_WAIT_MS = 5_000L
        const val AFTER_STOP_MS = 3_000L
    }
}
