package io.github.matijasalej.wholeflat.sync

/**
 * Since when the TV has been on, from its power as JointSpace tells it ("On", "Standby", or no answer). A TV that was not on for at least
 * [offMinMs] and is on again has come on now (its player starts with it); a reading that fails for a moment does not move the clock. After
 * [forget] (the box woke: it is switching the TV on over CEC, and the readings before may be hours old) the next reading of On starts it.
 * The first reading of all, On, starts it too: what came before is not known. Plain Kotlin.
 */
class PowerClock(private val offMinMs: Long = OFF_MIN_MS) {
    /** When the TV came on, as far as the readings tell (the caller's clock, ms; kept through a short gap); 0 before any reading of On. */
    @Volatile
    var onSinceMs = 0L
        private set

    private var on = false
    private var known = false
    private var notOnSinceMs = -1L

    /** The power read at [nowMs] ("On", "Standby", null for no answer); true if it is a change between on and not on. */
    fun read(nowMs: Long, power: String?): Boolean {
        val isOn = power == "On"
        if (known && isOn == on) return false
        val first = !known
        known = true
        on = isOn
        if (!isOn) {
            notOnSinceMs = nowMs
            return !first
        }
        val wasOffLong = first || notOnSinceMs < 0 || nowMs - notOnSinceMs >= offMinMs || onSinceMs == 0L
        if (wasOffLong) onSinceMs = nowMs
        return !first
    }

    /** The box woke: the next reading of On is the TV coming on. */
    fun forget() {
        known = false
        onSinceMs = 0L
        notOnSinceMs = -1L
    }

    companion object {
        /** Shorter than this without an answer is a reading that failed, not a TV that was off. */
        const val OFF_MIN_MS = 60_000L
    }
}
