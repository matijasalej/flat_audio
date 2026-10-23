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
 * Plain Kotlin; the Kitchen feeds it and does what the outcome says.
 */
class CaptureTracker(
    val controller: SyncController,
    val lag: CaptureLag,
    val lead: CaptureLead,
    private val rate: Int,
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

    /** A new stream was started, planned to sound [plannedLagMs] after the capture. */
    @Synchronized
    fun startStream(plannedLagMs: Double) {
        lag.reset()
        controller.reset()
        relocks = 0
        expectedMs = plannedLagMs
    }

    /**
     * One reading: at [tNanos] the Sonos reported [positionMs]. [map] and [unappliedBudget] are the stream's,
     * [clock] says when each captured frame was captured, [offsetMs] is the kitchen offset in force and [lkMs]
     * the delay from Play to the first sound.
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
    ): Outcome {
        val heard = (positionMs - controller.deltaMs) / 1000.0 * rate
        val frame = map.ringOfStream(heard) ?: return Outcome.Waiting
        val captured = clock.nanosOfFrame(Math.round(frame)) ?: return Outcome.Waiting
        val lagMs = (tNanos - captured) / 1e6

        if (!lag.isLocked) {
            if (!lag.onReading(lagMs, offsetMs)) return Outcome.Waiting
            controller.reset()
            controller.offsetMs = lag.controllerOffsetMs(offsetMs)
            val locked = lag.locked ?: return Outcome.Waiting
            val expected = expectedMs
            val raised = lead.onLocked(locked, expected, lkMs, offsetMs)
            expectedMs = locked
            return Outcome.Locked(locked, expected, raised)
        }

        lag.onReading(lagMs, offsetMs)
        controller.offsetMs = lag.controllerOffsetMs(offsetMs)
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
        val moved = lag.movedMs(RECENT)
        val now = lag.recentMs(RECENT)
        val stepped = !moved.isNaN() && Math.abs(moved) >= MIN_STEP_MS
        if (stepped && now <= MAX_LAG_MS && relocks < MAX_RELOCKS) {
            relocks++
            val from = lag.locked ?: now
            lag.reset()
            controller.reset()
            expectedMs = from
            return Outcome.Relock(from, now)
        }
        return Outcome.Rejoin(if (stepped) "the lag is ${Math.round(now)} ms after $relocks steps ($reason)" else reason)
    }

    companion object {
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
