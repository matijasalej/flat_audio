package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.sync.KitchenMap
import io.github.matijasalej.wholeflat.sync.StreamView
import io.github.matijasalej.wholeflat.sync.SyncController

/**
 * What the readings of the Sonos mean for a captured stream.
 *
 * A reading says which stream frame the Sonos is playing, hence which captured frame, hence how long ago
 * that frame was captured: the lag of the kitchen. The first readings of a stream only measure it
 * ([CaptureLag] locks the median); after that the [SyncController] holds the locked lag against the drift
 * of the speaker's clock with frame trims. There is no second room that the kitchen must stay in step with,
 * so when the lag itself moves by a step (the speaker stalled and filled its buffer again, or skipped) the
 * answer is not to restart the stream but to measure the lag again ([Outcome.Relock]); a restart is for a
 * lag that has gone too far, for too many steps, and for the controller's own reason that is not a step
 * (the kitchen offset was changed by more than it can trim).
 *
 * With a target lag (the `targetMs` of a reading) the lag is not held where it locked but brought to the target, slowly, by the
 * [LagSteer]: its setpoint moves toward the target and the controller holds the lag at the setpoint. The steer also learns the
 * floor, the lowest lag the speaker allows (see [LagSteer]); a step of the lag then is measured against the setpoint.
 *
 * Plain Kotlin; the Kitchen feeds it and does what the outcome says.
 */
class CaptureTracker(
    val controller: SyncController,
    val lag: CaptureLag,
    val lead: CaptureLead,
    private val rate: Int,
    val steer: LagSteer = LagSteer(),
) {
    sealed class Outcome {
        /** No usable reading (no mapping yet), or still measuring. */
        object Waiting : Outcome()

        /** The lag is locked: the speaker sounds [lagMs] after the capture where [expectedMs] was expected. */
        class Locked(val lagMs: Double, val expectedMs: Double, val leadRaised: Boolean) : Outcome()

        object Hold : Outcome()

        class Trim(val frames: Int) : Outcome()

        /** The lag moved from [fromMs] to about [toMs]: it is measured again instead of restarting the stream. */
        class Relock(val fromMs: Double, val toMs: Double) : Outcome()

        class Rejoin(val reason: String) : Outcome()
    }

    /** Steps of the lag that were taken in as a new lag during this stream. */
    @Volatile
    var relocks = 0
        private set

    /** The lag the next lock is compared with: the plan for a new stream, the old lag after a step. */
    @Volatile
    var expectedMs = Double.NaN
        private set

    /** Called (on the thread of the reading) when trouble of the speaker raised the floor of the lag. */
    @Volatile
    var onFloor: ((LagSteer.FloorEvent) -> Unit)? = null

    /** A new stream was started, planned to sound [plannedLagMs] after the capture. */
    @Synchronized
    fun startStream(plannedLagMs: Double) {
        lag.reset()
        controller.reset()
        steer.startStream()
        relocks = 0
        expectedMs = plannedLagMs
    }

    /**
     * The offset that the controller works with: with the steer at work the setpoint (counted from the base of the lag), else the
     * kitchen [offsetMs] of the settings, which moves the lag that was locked.
     */
    fun controllerOffsetMs(offsetMs: Int): Double {
        val setpoint = steer.setpointMs
        val base = lag.baseMs
        return if (steer.active && !setpoint.isNaN() && !base.isNaN()) setpoint - base else lag.controllerOffsetMs(offsetMs)
    }

    /** The speaker said at [tNanos] that it ran out of audio ([why]); if the lag was brought down to that, the floor is raised. */
    @Synchronized
    fun onSpeakerTrouble(tNanos: Long, why: String): LagSteer.FloorEvent? {
        val event = steer.onUnderrun(tNanos, why)
        if (event != null) onFloor?.invoke(event)
        return event
    }

    /**
     * One reading: at [tNanos] the Sonos reported [positionMs]. [map] and [unappliedBudget] are the stream's,
     * [clock] says when each captured frame was captured, [offsetMs] is the kitchen offset in force and [lkMs]
     * the delay from Play to the first sound. [targetMs] is the lag to bring the kitchen to (NaN: hold what it locked).
     */
    @Synchronized
    fun onReading(
        tNanos: Long,
        positionMs: Long,
        map: KitchenMap,
        unappliedBudget: () -> Int,
        clock: CaptureClock,
        offsetMs: Int,
        lkMs: Double,
        targetMs: Double = Double.NaN,
    ): Outcome {
        val heard = (positionMs - controller.deltaMs) / 1000.0 * rate
        val frame = map.ringOfStream(heard) ?: return Outcome.Waiting
        val captured = clock.nanosOfFrame(Math.round(frame)) ?: return Outcome.Waiting
        val lagMs = (tNanos - captured) / 1e6
        val ended = steer.setTarget(targetMs)

        if (!lag.isLocked) {
            if (!lag.onReading(lagMs, offsetMs)) return Outcome.Waiting
            controller.reset()
            val locked = lag.locked ?: return Outcome.Waiting
            if (steer.active) steer.onLock(locked)
            controller.offsetMs = controllerOffsetMs(offsetMs)
            val expected = expectedMs
            // with a target the lag is brought where it is wanted; what a speaker needs at the start is then no reason to start further ahead
            val raised = !steer.active && lead.onLocked(locked, expected, lkMs, offsetMs)
            expectedMs = locked
            return Outcome.Locked(locked, expected, raised)
        }

        if (ended) {
            // the target was switched off while the lag was brought to it: what it is now is measured and held
            val now = lag.recentMs(RECENT)
            lag.reset()
            controller.reset()
            expectedMs = now
            return Outcome.Waiting
        }
        // the target was switched on, or changed to one that the lag is brought to: it starts from what is held now
        if (steer.active && !steer.hasSetpoint) steer.onLock(lag.targetMs(offsetMs))

        lag.onReading(lagMs, offsetMs)
        val floorEvent = steer.onReading(tNanos, lagMs)
        if (floorEvent != null) onFloor?.invoke(floorEvent)
        controller.offsetMs = controllerOffsetMs(offsetMs)
        val base = lag.baseNanos()
        val view = StreamView(map, unappliedBudget) { f -> clock.nanosOfFrame(f)?.plus(base) }
        return when (val d = controller.onReading(tNanos, positionMs, view)) {
            is SyncController.Decision.Trim -> Outcome.Trim(d.frames)
            is SyncController.Decision.Hold -> Outcome.Hold
            is SyncController.Decision.Rejoin -> decide(d.reason)
        }
    }

    /** The controller wants a restart: is it the lag that moved (measure again), or something a restart is for? */
    private fun decide(reason: String): Outcome {
        val now = lag.recentMs(RECENT)
        // a step is a lag that is far from where it was held: from the setpoint when the lag is steered, else from where it locked
        val steering = steer.active && steer.hasSetpoint
        val held = if (steering) steer.setpointMs else (lag.locked ?: now)
        val moved = if (steering) (if (now.isNaN()) Double.NaN else now - held) else lag.movedMs(RECENT)
        val stepped = !moved.isNaN() && Math.abs(moved) >= MIN_STEP_MS
        if (stepped && now <= MAX_LAG_MS && relocks < MAX_RELOCKS) {
            relocks++
            val from = held
            lag.reset()
            controller.reset()
            steer.startLock()
            expectedMs = from
            return Outcome.Relock(from, now)
        }
        return Outcome.Rejoin(if (stepped) "the lag is ${Math.round(now)} ms after $relocks steps ($reason)" else reason)
    }

    companion object {
        /**
         * The controller of a captured stream (the kitchen's and the tests' alike): a little more tolerant than the living room's,
         * because the reads that the lag is measured against are 10 ms apart and only hold it where it is told to; it gives up
         * (a re-join, or measuring the lag again) at 60 ms for five readings.
         */
        fun controllerParams(rate: Int) = SyncController.Params(
            rejoinThresholdMs = 60.0,
            rejoinReadings = 5,
            deadbandMs = 5.0,
            maxPendingFrames = 2_400,
            rate = rate,
        )

        /** The readings that say what the lag is now (1 s), as opposed to the whole window. */
        const val RECENT = 5

        /** A lag that moved by this much since the lock is a step of the speaker, not a change of the offset. */
        const val MIN_STEP_MS = 40.0

        /** Beyond this the kitchen is too far behind to be worth keeping: restart it near the capture. */
        const val MAX_LAG_MS = 5_000.0

        /** Steps in one stream; more is a speaker that cannot keep up. */
        const val MAX_RELOCKS = 6
    }
}
