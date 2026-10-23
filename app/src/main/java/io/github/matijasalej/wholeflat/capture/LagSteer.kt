package io.github.matijasalej.wholeflat.capture

/**
 * Brings the lag of a captured stream to a target, slowly, and learns how low the speaker lets it go.
 *
 * The lag is how long after the capture the kitchen sounds. It is whatever the speaker settled at after Play (see [CaptureLag]),
 * and with a target (the setting `kitchen_target_ms`, or the living room's latency) the steer moves it there afterwards: a
 * **setpoint** starts at the locked lag and moves toward the target at [RAMP_MS_PER_S], and the controller (see SyncController)
 * holds the lag at the setpoint with the frame trims. Downward, a trim drops a frame of the stream, so the speaker's buffer
 * shrinks by that much; upward, a trim repeats one. At most 3 ms of lag per second are moved, a speed change of 0.3 % that
 * cannot be heard (the trims could do 4.5). The setpoint never runs more than [MAX_AHEAD_MS] ahead of the lag that is measured,
 * so a controller that cannot follow (nothing to trim) does not build up an error.
 *
 * **The floor.** The lag is never aimed below the **configured floor** (the setting `kitchen_floor_ms`, 316 ms on the device: the
 * lowest lag that was heard clean) and not below the **learned floor**. A speaker has a lowest lag below which it runs out of
 * audio, and what is steered down finds it by hitting it: the speaker reports buffering or an error, the lag readings jump up (it
 * stopped and filled its buffer again) or scatter, or the position it reports advances slower than real time (it stutters, and the
 * controller, which keeps the lag where it is told by dropping frames, hides every pause it makes: this can be heard without any of
 * the others showing). The steer then sets the learned floor to [FLOOR_STEP_MS] above the lag where it
 * happened, and the caller starts the stream again: a speaker that has been driven that low stays degraded, and raising the
 * setpoint does not cure it, only a fresh start does. The learned floor is kept (the caller stores it for the rest of the day)
 * until [clearFloor]. Trouble at a lag that the steer did not bring down, say a hiccup of the network at the lag the speaker chose
 * itself, is not the steer's doing and moves nothing.
 *
 * Plain Kotlin; the tracker feeds it the lag readings and the Kitchen the speaker's own signals.
 */
class LagSteer(
    private val rampMsPerS: Double = RAMP_MS_PER_S,
    private val minMs: Double = CaptureLag.MIN_LAG_MS,
) {
    enum class State(val label: String) {
        /** No target: the lag is held where it locked. */
        OFF("off"),

        /** A target, but the lag is not locked yet. */
        MEASURING("measuring"),
        DESCENDING("descending"),
        ASCENDING("ascending"),
        HOLDING("holding"),
    }

    /** The learned floor was raised to [floorMs] because trouble came at a lag of [lagMs]. */
    class FloorEvent(val floorMs: Double, val lagMs: Double, val why: String)

    private class Reading(val nanos: Long, val lagMs: Double, val positionMs: Long)

    /** The lag that is wanted (ms after the capture), NaN: no target. */
    @Volatile
    var targetMs = Double.NaN
        private set

    /** The floor of the settings (ms), NaN: none. */
    @Volatile
    var configuredFloorMs = Double.NaN
        private set

    /** The lowest lag the speaker has shown it allows, plus the step (ms); NaN until trouble came. */
    @Volatile
    var learnedFloorMs = Double.NaN
        private set

    /** The lag the controller holds now on its way to the goal (ms); NaN without a target or before the lock. */
    @Volatile
    var setpointMs = Double.NaN
        private set

    /** Times the learned floor was raised in this run. */
    @Volatile
    var floorEvents = 0
        private set

    /** The last deficit of the position against real time (ms per second, median of five 1 s windows); NaN before enough readings. */
    @Volatile
    var rateDeficitMs = Double.NaN
        private set

    /** The setpoint of the lock: trouble counts for the steer only when the setpoint is below it. */
    private var lockedMs = Double.NaN
    private val history = ArrayDeque<Reading>()
    private var lastNanos = 0L
    private var quietUntilNanos = 0L

    val active: Boolean get() = !targetMs.isNaN()

    val hasSetpoint: Boolean get() = !setpointMs.isNaN()

    /** The floor in force: the higher of the configured and the learned one (ms); NaN if there is neither. */
    val floorMs: Double
        get() {
            val a = configuredFloorMs
            val b = learnedFloorMs
            return when {
                a.isNaN() -> b
                b.isNaN() -> a
                else -> maxOf(a, b)
            }
        }

    /** Where the setpoint is going: the target, but not below the floor and not below the least lag there is (ms); NaN without a target. */
    val goalMs: Double
        get() {
            val target = targetMs
            if (target.isNaN()) return Double.NaN
            val floor = floorMs
            return maxOf(target, if (floor.isNaN()) 0.0 else floor, minMs)
        }

    /**
     * The target is now [wantedMs] (NaN: none). True if a target that was being steered to has ended: the lag has to be
     * measured again and held where it is.
     */
    @Synchronized
    fun setTarget(wantedMs: Double): Boolean {
        val wasSteering = active && hasSetpoint
        targetMs = wantedMs
        if (!active && wasSteering) {
            forgetStream()
            return true
        }
        return false
    }

    /** The floor of the settings is [ms] (NaN: none). */
    fun setConfiguredFloor(ms: Double) {
        configuredFloorMs = ms
    }

    /** A new stream (a re-join, a new session): the lag is measured again; the target and the floors stay. */
    @Synchronized
    fun startStream() = forgetStream()

    /** The lag is measured again within the stream (it stepped); the target and the floors stay. */
    @Synchronized
    fun startLock() = forgetStream()

    private fun forgetStream() {
        setpointMs = Double.NaN
        lockedMs = Double.NaN
        history.clear()
        lastNanos = 0L
        quietUntilNanos = 0L
        rateDeficitMs = Double.NaN
    }

    /** The lag is held at [heldMs] now (just locked, or the target was switched on): the setpoint starts there. */
    @Synchronized
    fun onLock(heldMs: Double) {
        forgetStream()
        setpointMs = heldMs
        lockedMs = heldMs
    }

    /** The learned floor was kept from earlier today: used from the start. One that no speaker's limit can be (see [FLOOR_LEARN_MAX_MS]) is not. */
    fun restoreLearnedFloor(ms: Double) {
        if (ms.isNaN() || ms > FLOOR_LEARN_MAX_MS + FLOOR_STEP_MS) return
        learnedFloorMs = ms
    }

    /** The learned floor is forgotten (another floor setting, another format, another speaker). True if there was one. */
    @Synchronized
    fun clearFloor(): Boolean {
        val had = !learnedFloorMs.isNaN()
        learnedFloorMs = Double.NaN
        return had
    }

    /**
     * One reading at [tNanos]: the lag (ms after the capture) and the position the speaker reported ([positionMs]). Moves the
     * setpoint, and looks at the readings themselves. A lag that jumps, readings that scatter or a position that falls behind real
     * time are the speaker running out of audio: the learned floor is raised and the event returned (the stream is to be restarted).
     */
    @Synchronized
    fun onReading(tNanos: Long, lagMs: Double, positionMs: Long = -1L): FloorEvent? {
        if (!active || !hasSetpoint) return null
        history.addLast(Reading(tNanos, lagMs, positionMs))
        while (history.size > MAX_HISTORY || (history.size > 1 && tNanos - history.first().nanos > HISTORY_NANOS)) history.removeFirst()
        rateDeficitMs = positionDeficit()
        var event: FloorEvent? = null
        if (steered() && tNanos >= quietUntilNanos) {
            val trouble = troubleInReadings()
            if (trouble != null) event = raiseFloor(tNanos, trouble)
        }
        move(tNanos)
        return event
    }

    /** The speaker said it ran out of audio (buffering, an error) at [tNanos]. The new floor, or null if this changes nothing. */
    @Synchronized
    fun onUnderrun(tNanos: Long, why: String): FloorEvent? {
        if (!active || !hasSetpoint || !steered() || tNanos < quietUntilNanos) return null
        return raiseFloor(tNanos, why)
    }

    /** Whether the lag is below where it was when the steering began: then the steer, and nobody else, has brought it there. */
    private fun steered(): Boolean = !lockedMs.isNaN() && setpointMs < lockedMs - STEER_MARGIN_MS

    private fun raiseFloor(tNanos: Long, why: String): FloorEvent? {
        // the lag where it happened, or the setpoint that was held if that was higher (the lag trails the setpoint on a way down)
        val where = maxOf(lagBefore(tNanos), setpointMs)
        quietUntilNanos = tNanos + DEBOUNCE_NANOS
        // a speaker does not run dry at a lag of seconds: trouble there (a network hiccup, the readings of a deep buffer) is no floor, and a
        // floor learned from it would keep the next streams, at any lag, up there
        if (where > FLOOR_LEARN_MAX_MS) return null
        val wanted = maxOf(where + FLOOR_STEP_MS, minMs)
        val current = floorMs
        if (!current.isNaN() && wanted <= current) return null // the floor is above that lag already
        learnedFloorMs = wanted
        floorEvents++
        return FloorEvent(wanted, where, why)
    }

    /**
     * The lag from before the trouble: the median of the readings from 1.5 s to 0.5 s ago (the newest ones may be the trouble already);
     * if there are fewer than three, the last few from the 6 s before that; if there are none, the setpoint.
     */
    private fun lagBefore(tNanos: Long): Double {
        val window = history.filter { it.nanos in (tNanos - WHERE_FROM_NANOS)..(tNanos - WHERE_TO_NANOS) }.map { it.lagMs }
        if (window.size >= 3) return median(window)
        val older = history.filter { it.nanos in (tNanos - WHERE_OLDEST_NANOS)..(tNanos - WHERE_TO_NANOS) }.map { it.lagMs }
        if (older.isNotEmpty()) return median(older.takeLast(RECENT))
        return setpointMs
    }

    /**
     * How far the reported position falls behind real time (ms per second): the median of the slopes between all pairs of the last ten
     * readings (a Theil-Sen estimate over about 1.8 s), each slope being (time between them) minus (position between them) per second of
     * time. The median outvotes a stray reading (a slow round trip moves one time stamp by up to its half) where a fit through all of
     * them would be pulled, and a real dropout, a step of the position, moves it as soon as it is in a good third of the pairs. The
     * readings are good to about 3.4 ms, which makes this estimate good to about 2 ms per second. NaN with fewer than eight readings
     * or less than 1.2 s of them.
     */
    private fun positionDeficit(): Double {
        val recent = history.toList().takeLast(RATE_READINGS).filter { it.positionMs >= 0 }
        if (recent.size < RATE_MIN_READINGS) return Double.NaN
        if (recent.last().nanos - recent.first().nanos < RATE_MIN_SPAN_NANOS) return Double.NaN
        val slopes = ArrayList<Double>()
        for (i in recent.indices) {
            for (j in i + 1 until recent.size) {
                val dt = (recent[j].nanos - recent[i].nanos) / 1e6
                if (dt < RATE_MIN_PAIR_MS) continue // two readings this close say nothing about a rate
                val dpos = (recent[j].positionMs - recent[i].positionMs).toDouble()
                slopes.add((dt - dpos) * 1000.0 / dt)
            }
        }
        return if (slopes.size >= RATE_MIN_PAIRS) median(slopes) else Double.NaN
    }

    /**
     * Trouble in the readings: a jump of the lag that stays (three readings in a row far above the ones before), readings that scatter
     * (several of the last few far above their median; one or two stray readings of a slow round trip are not), or a position that
     * advances slower than real time. Null if all is well. Only a lag that rises is the speaker running dry: it stops, and what comes
     * after is later in the stream.
     */
    private fun troubleInReadings(): String? {
        val all = history.map { it.lagMs }
        val n = all.size
        if (n >= JUMP_REFERENCE + JUMP_READINGS) {
            val reference = median(all.subList(n - JUMP_REFERENCE - JUMP_READINGS, n - JUMP_READINGS))
            val last = all.subList(n - JUMP_READINGS, n)
            if (last.all { it - reference > JUMP_MS }) {
                return "the lag jumped from ${Math.round(reference)} to ${Math.round(last.last())} ms"
            }
        }
        if (n >= ERRATIC_READINGS) {
            val recent = all.subList(n - ERRATIC_READINGS, n)
            val middle = median(recent)
            val off = recent.count { it - middle > ERRATIC_MS }
            // one of the last two must be among them: readings from before a step down are above the median of the new level, and are no trouble
            val now = recent.takeLast(2).any { it - middle > ERRATIC_MS }
            if (off >= ERRATIC_COUNT && now) return "$off of the last $ERRATIC_READINGS lag readings are more than ${Math.round(ERRATIC_MS)} ms above their median"
        }
        val deficit = rateDeficitMs
        if (!deficit.isNaN() && deficit > RATE_DEFICIT_MS) {
            return "the position advances ${Math.round(deficit)} ms per second slower than real time"
        }
        return null
    }

    private fun move(tNanos: Long) {
        val goal = goalMs
        // the first reading after a lock only sets the time base
        val dt = if (lastNanos == 0L) 0.0 else ((tNanos - lastNanos) / 1e9).coerceIn(0.0, MAX_DT_S)
        lastNanos = tNanos
        val previous = setpointMs
        val recent = recentLag()
        var next = previous
        if (goal < previous) {
            next = maxOf(goal, previous - rampMsPerS * dt)
            // not further ahead of the lag that is measured than the controller can catch up with
            if (!recent.isNaN()) next = maxOf(next, minOf(previous, recent - MAX_AHEAD_MS))
        } else if (goal > previous) {
            next = minOf(goal, previous + rampMsPerS * dt)
            if (!recent.isNaN()) next = minOf(next, maxOf(previous, recent + MAX_AHEAD_MS))
        }
        setpointMs = next
    }

    private fun recentLag(): Double {
        if (history.isEmpty()) return Double.NaN
        return median(history.toList().takeLast(RECENT).map { it.lagMs })
    }

    /** What the steer is doing now. */
    @Synchronized
    fun state(): State {
        if (!active) return State.OFF
        if (!hasSetpoint) return State.MEASURING
        val goal = goalMs
        val sp = setpointMs
        if (sp > goal + 0.5) return State.DESCENDING
        if (sp < goal - 0.5) return State.ASCENDING
        // the setpoint is there; the lag is still on its way until it is within the tolerance of the controller
        val lag = recentLag()
        if (!lag.isNaN()) {
            if (lag > goal + SETTLE_MS) return State.DESCENDING
            if (lag < goal - SETTLE_MS) return State.ASCENDING
        }
        return State.HOLDING
    }

    /** True while the lag is still being brought down. */
    fun descending(): Boolean = state() == State.DESCENDING

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    companion object {
        /** The lag moves at most this much per second (ms): a speed change of 0.3 %, well under the 0.45 % a trim every 220 frames can make. */
        const val RAMP_MS_PER_S = 3.0

        /** The setpoint is never further than this from the lag that is measured (ms). */
        const val MAX_AHEAD_MS = 30.0

        /** The learned floor is this far above the lag at which the speaker ran out of audio (ms). */
        const val FLOOR_STEP_MS = 15.0

        /** A floor is only learned from trouble at a lag up to this (ms): the speaker's own limit is a few hundred ms. */
        const val FLOOR_LEARN_MAX_MS = 1000.0

        /** The setpoint must be this far below where the steering began for trouble to be the steer's (ms). */
        const val STEER_MARGIN_MS = 5.0

        /** A lag this far from the readings before it, in three readings in a row, is a jump (ms; the readings are good to about 4). */
        const val JUMP_MS = 25.0
        private const val JUMP_READINGS = 3
        private const val JUMP_REFERENCE = 6

        /** [ERRATIC_COUNT] of the last [ERRATIC_READINGS] readings this far above their median are erratic (ms; a ramp adds up to 3, the noise 10). */
        const val ERRATIC_MS = 20.0
        private const val ERRATIC_READINGS = 8
        private const val ERRATIC_COUNT = 3

        /**
         * The position falls behind real time by more than this (ms per second, see [positionDeficit]): the speaker stutters. The
         * estimate is good to about 2 ms per second, so 12 is six sigma; a stutter of 40 ms lost per second, or a dropout of 20 ms or
         * more, is well above it.
         */
        const val RATE_DEFICIT_MS = 12.0
        private const val RATE_READINGS = 10
        private const val RATE_MIN_READINGS = 8
        private const val RATE_MIN_SPAN_NANOS = 1_200_000_000L
        private const val RATE_MIN_PAIR_MS = 100.0
        private const val RATE_MIN_PAIRS = 10

        /** Trouble within this time of the last is the same event (several signals of one stall). */
        private const val DEBOUNCE_NANOS = 5_000_000_000L
        private const val WHERE_FROM_NANOS = 1_500_000_000L
        private const val WHERE_TO_NANOS = 500_000_000L
        private const val WHERE_OLDEST_NANOS = 6_000_000_000L
        private const val HISTORY_NANOS = 10_000_000_000L
        private const val MAX_HISTORY = 80
        private const val RECENT = 5

        /** The lag is on its way until it is this near the goal (ms): the tolerance of the controller. */
        private const val SETTLE_MS = 8.0
        private const val MAX_DT_S = 1.0
    }
}
