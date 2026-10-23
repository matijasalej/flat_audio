package io.github.matijasalej.wholeflat.capture

/**
 * How much audio the stream to the Sonos should be sent ahead at its start. The setting `capture_lead_ms` is
 * the wish (a small number is a small lag), but what a Sonos needs from a live stream is not known in
 * advance: if it settled clearly later than planned (it waited for more audio before it played, or it
 * stalled and filled its buffer again), the next stream is planned for that lag, so that it starts as it
 * will go on and does not have to be corrected or restarted.
 *
 * It only ever raises the lead (up to [maxMs]); a change of the setting, or a new run of the app, starts
 * over. A difference smaller than [marginMs] is noise and teaches nothing, which also keeps a small
 * systematic overshoot from adding up from stream to stream.
 */
class CaptureLead(private val maxMs: Int = 3_000, private val marginMs: Double = 100.0) {
    /** The lead the Sonos has been seen to need (ms), 0 if it has not been seen to need more than asked. */
    @Volatile
    var learnedMs = 0
        private set

    /** The lead to plan with, given the [settingMs] of the settings. */
    fun effectiveMs(settingMs: Int): Int = maxOf(settingMs, learnedMs)

    /**
     * The lag settled at [lockedMs] where [expectedMs] was expected (the plan for a new stream, the old lag
     * after a step). With a clearly later lag, the lead needed is what that lag is made of: the Play-to-sound
     * delay [lkMs], the kitchen offset and the rest. True if the lead was raised.
     */
    @Synchronized
    fun onLocked(lockedMs: Double, expectedMs: Double, lkMs: Double, offsetMs: Int): Boolean {
        if (expectedMs.isNaN() || lockedMs - expectedMs <= marginMs) return false
        val needed = Math.round(lockedMs - lkMs - offsetMs).toInt().coerceIn(0, maxMs)
        if (needed <= learnedMs) return false
        learnedMs = needed
        return true
    }

    @Synchronized
    fun reset() {
        learnedMs = 0
    }
}
