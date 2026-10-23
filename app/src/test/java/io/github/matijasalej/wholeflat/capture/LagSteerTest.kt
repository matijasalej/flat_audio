package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LagSteerTest {
    private val t0 = 1_000_000_000_000L

    /** Reading number [i], 200 ms apart. */
    private fun at(i: Int): Long = t0 + i * 200_000_000L

    /** A steer with a target that is locked at [lockedMs]. */
    private fun steered(targetMs: Double, lockedMs: Double): LagSteer {
        val steer = LagSteer()
        steer.setTarget(targetMs)
        steer.onLock(lockedMs)
        return steer
    }

    /** [n] readings from number [from] on in which the lag follows the setpoint exactly; the floor events there were. */
    private fun follow(steer: LagSteer, from: Int, n: Int): List<LagSteer.FloorEvent> {
        val events = ArrayList<LagSteer.FloorEvent>()
        for (i in from until from + n) steer.onReading(at(i), steer.setpointMs)?.let { events.add(it) }
        return events
    }

    @Test
    fun withoutATargetNothingIsSteered() {
        val steer = LagSteer()
        assertFalse(steer.active)
        assertEquals(LagSteer.State.OFF, steer.state())
        assertNull(steer.onReading(at(0), 440.0))
        assertTrue(steer.setpointMs.isNaN())
        assertTrue(steer.goalMs.isNaN())
        assertNull(steer.onUnderrun(at(1), "buffering"))
        assertFalse(steer.descending())
    }

    @Test
    fun withATargetTheLagIsMeasuredUntilItIsLocked() {
        val steer = LagSteer()
        steer.setTarget(300.0)
        assertTrue(steer.active)
        assertEquals(LagSteer.State.MEASURING, steer.state())
        assertNull(steer.onReading(at(0), 440.0))
        assertTrue(steer.setpointMs.isNaN())
        assertNull(steer.onUnderrun(at(1), "buffering"))
    }

    @Test
    fun theSetpointStartsAtTheLockAndFallsAtFourMillisecondsPerSecond() {
        val steer = steered(300.0, 440.0)
        assertEquals(440.0, steer.setpointMs, 0.0)
        assertEquals(LagSteer.State.DESCENDING, steer.state())
        assertTrue(steer.descending())
        follow(steer, 0, 51) // the first reading sets the time base, then 10 s
        assertEquals(400.0, steer.setpointMs, 0.01)
        follow(steer, 51, 50)
        assertEquals(360.0, steer.setpointMs, 0.01)
        assertTrue(steer.floorMs.isNaN())
    }

    @Test
    fun theRampIsAtMostFiveMillisecondsPerSecondAtAnyRateOfReadings() {
        // readings every 20 ms or every second: the setpoint falls with the time, not with the number of readings
        val fast = steered(300.0, 440.0)
        for (i in 0..500) fast.onReading(t0 + i * 20_000_000L, fast.setpointMs) // 10 s
        assertEquals(400.0, fast.setpointMs, 0.5)
        val slow = steered(300.0, 440.0)
        for (i in 0..10) slow.onReading(t0 + i * 1_000_000_000L, slow.setpointMs)
        assertEquals(400.0, slow.setpointMs, 0.5)
        // a long gap between two readings (a stall of the websocket) moves it by one second's worth, not by the gap
        val gap = steered(300.0, 440.0)
        gap.onReading(t0, 440.0)
        gap.onReading(t0 + 60_000_000_000L, 440.0)
        assertEquals(440.0 - 4.0, gap.setpointMs, 0.01)
        assertTrue(LagSteer.RAMP_MS_PER_S <= 5.0)
    }

    @Test
    fun theSetpointStopsAtTheTargetAndThenItIsHolding() {
        val steer = steered(400.0, 440.0)
        follow(steer, 0, 61) // 40 ms take 10 s
        assertEquals(400.0, steer.setpointMs, 0.0)
        assertEquals(LagSteer.State.HOLDING, steer.state())
        assertFalse(steer.descending())
        follow(steer, 60, 100)
        assertEquals(400.0, steer.setpointMs, 0.0)
    }

    @Test
    fun itIsDescendingUntilTheLagIsNearTheGoalNotOnlyTheSetpoint() {
        val steer = steered(400.0, 440.0)
        // the setpoint gets to the target, the lag is 25 ms behind it
        for (i in 0 until 62) steer.onReading(at(i), steer.setpointMs + 25.0)
        assertEquals(400.0, steer.setpointMs, 0.0)
        assertEquals(LagSteer.State.DESCENDING, steer.state())
        // the lag comes down to it (a step down is no trouble: a speaker that runs dry has a bigger lag after)
        for (i in 62 until 72) steer.onReading(at(i), 403.0)
        assertEquals(LagSteer.State.HOLDING, steer.state())
        assertTrue(steer.floorMs.isNaN())
    }

    @Test
    fun aTargetAboveTheLockRisesAtTheSameSpeed() {
        val steer = steered(500.0, 440.0)
        assertEquals(LagSteer.State.ASCENDING, steer.state())
        assertFalse(steer.descending())
        follow(steer, 0, 26) // 5 s
        assertEquals(460.0, steer.setpointMs, 0.01)
        follow(steer, 26, 200)
        assertEquals(500.0, steer.setpointMs, 0.0)
        assertEquals(LagSteer.State.HOLDING, steer.state())
    }

    @Test
    fun theGoalIsNeverBelowTheLeastLagThereIs() {
        val steer = steered(50.0, 140.0)
        assertEquals(CaptureLag.MIN_LAG_MS, steer.goalMs, 0.0)
        follow(steer, 0, 100)
        assertEquals(CaptureLag.MIN_LAG_MS, steer.setpointMs, 0.0)
    }

    @Test
    fun theSetpointWaitsForALagThatDoesNotFollow() {
        // the lag stays at 440 (nothing is trimmed): the setpoint goes no further than 30 ms ahead of it
        val steer = steered(300.0, 440.0)
        for (i in 0 until 200) steer.onReading(at(i), 440.0)
        assertEquals(410.0, steer.setpointMs, 0.01)
        // and it goes on when the lag comes
        for (i in 200 until 220) steer.onReading(at(i), 395.0)
        assertTrue(steer.setpointMs < 400.0)
    }

    @Test
    fun anUnderrunAtALagThatWasBroughtDownRaisesTheFloorTwentyAboveIt() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100) // 20 s: the setpoint is at 360
        val lagBefore = steer.setpointMs
        val event = steer.onUnderrun(at(101), "PLAYBACK_STATE_BUFFERING")
        assertNotNull(event)
        // the lag from before (the median of the readings from 1.5 s to 0.5 s ago, a few ms above the last one on a way down), plus 20
        assertTrue("floor ${event!!.floorMs} for a lag of $lagBefore", event.floorMs in (lagBefore + 20.0)..(lagBefore + 28.0))
        assertEquals(event.floorMs - 20.0, event.lagMs, 0.001)
        assertEquals("PLAYBACK_STATE_BUFFERING", event.why)
        assertEquals(event.floorMs, steer.floorMs, 0.0)
        assertEquals(1, steer.floorEvents)
        // the goal is now the floor, not the target
        assertEquals(event.floorMs, steer.goalMs, 0.0)
    }

    @Test
    fun anUnderrunBeforeTheLagWasBroughtDownIsNotTheSteers() {
        val steer = steered(250.0, 440.0)
        // the lock itself: nothing was steered yet
        assertNull(steer.onUnderrun(at(0), "buffering"))
        follow(steer, 0, 5) // 1 s: the setpoint is 4 ms down, within the margin
        assertNull(steer.onUnderrun(at(6), "buffering"))
        assertTrue(steer.floorMs.isNaN())
        // and a target above the lock is no steering down
        val up = steered(500.0, 440.0)
        follow(up, 0, 50)
        assertNull(up.onUnderrun(at(51), "buffering"))
    }

    @Test
    fun theFloorOnlyGoesUp() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val first = steer.onUnderrun(at(101), "buffering")!!.floorMs
        // a second event at a lower lag than the floor moves nothing
        assertNull(steer.onUnderrun(at(101) + 10_000_000_000L, "buffering again"))
        assertEquals(first, steer.floorMs, 0.0)
        assertEquals(1, steer.floorEvents)
        // one at a higher lag raises it
        val lag = first + 60.0
        for (i in 160 until 200) steer.onReading(at(i), lag)
        val second = steer.onUnderrun(at(200), "once more")
        assertNotNull(second)
        assertTrue(steer.floorMs > first)
        assertEquals(2, steer.floorEvents)
    }

    @Test
    fun severalSignalsOfOneStallRaiseTheFloorOnce() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val floor = steer.onUnderrun(at(101), "buffering")!!.floorMs
        // the readings that follow show a lag 400 ms bigger; the speaker also reports an error within the same few seconds
        for (i in 102 until 110) steer.onReading(at(i), steer.setpointMs + 400.0)
        assertNull(steer.onUnderrun(at(110), "transport TRANSITIONING"))
        assertEquals(floor, steer.floorMs, 0.0)
        assertEquals(1, steer.floorEvents)
    }

    @Test
    fun aJumpOfTheLagThatStaysRaisesTheFloorWithTheLagFromBeforeTheJump() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val before = steer.setpointMs
        var event: LagSteer.FloorEvent? = null
        // the speaker said nothing, but the lag is 150 ms bigger from one reading to the next and stays there
        for (i in 100 until 103) event = steer.onReading(at(i), before + 150.0) ?: event
        assertNotNull("three readings are a jump", event)
        assertTrue("floor ${event!!.floorMs} for a lag of $before", event.floorMs in (before + 20.0)..(before + 28.0))
        assertTrue(event.why.contains("jumped"))
    }

    @Test
    fun aLagThatStepsDownIsNoTroubleAMoreSpeakerThatRunsDryHasABiggerLagAfter() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val before = steer.setpointMs
        for (i in 100 until 140) assertNull("reading $i", steer.onReading(at(i), before - 60.0))
        assertTrue(steer.floorMs.isNaN())
    }

    @Test
    fun oneOrTwoStrayReadingsAreNoJumpAndNoScatter() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val spots = intArrayOf(100, 101)
        for (i in 100 until 140) {
            val lag = if (i in spots) steer.setpointMs + 60.0 else steer.setpointMs
            assertNull(steer.onReading(at(i), lag))
        }
        assertTrue(steer.floorMs.isNaN())
    }

    @Test
    fun readingsThatScatterRaiseTheFloor() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val before = steer.setpointMs
        var event: LagSteer.FloorEvent? = null
        // every other reading is 40 ms off
        for (i in 100 until 112) {
            val lag = if (i % 2 == 0) before else before + 40.0
            event = steer.onReading(at(i), lag) ?: event
        }
        assertNotNull(event)
        assertTrue(event!!.why.contains("median"))
        assertTrue("floor ${event.floorMs} for a lag of $before", event.floorMs in (before + 20.0)..(before + 28.0))
    }

    @Test
    fun theNoiseOfTheReadingsIsNotTrouble() {
        val steer = steered(250.0, 440.0)
        val rnd = java.util.Random(11)
        for (i in 0 until 600) {
            // noise of 4 ms (more than the readings have) on a lag that follows the setpoint
            assertNull("reading $i", steer.onReading(at(i), steer.setpointMs + rnd.nextGaussian() * 4.0))
        }
        assertTrue(steer.floorMs.isNaN())
    }

    @Test
    fun theFloorIsTheGoalAndTheSetpointRisesToItWhenItIsAboveTheLag() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val floor = steer.onUnderrun(at(101), "buffering")!!.floorMs
        // the lag was below it, so the setpoint goes up to it
        assertTrue(steer.setpointMs < floor)
        follow(steer, 101, 100)
        assertEquals(floor, steer.setpointMs, 0.0)
        assertTrue(floor > 250.0)
    }

    @Test
    fun aTargetBelowTheFloorIsNotReached() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val floor = steer.onUnderrun(at(101), "buffering")!!.floorMs
        // the stall: the stream is measured again, 400 ms later in the stream
        steer.startLock()
        steer.onLock(floor + 400.0)
        follow(steer, 110, 800) // 160 s
        assertEquals(floor, steer.setpointMs, 0.0)
        assertTrue(steer.setpointMs > 250.0)
    }

    @Test
    fun aNewStreamKeepsTheFloorAndTheTargetButNotTheSetpoint() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val floor = steer.onUnderrun(at(101), "buffering")!!.floorMs
        steer.startStream()
        assertTrue(steer.setpointMs.isNaN())
        assertEquals(floor, steer.floorMs, 0.0)
        assertEquals(250.0, steer.targetMs, 0.0)
        assertEquals(LagSteer.State.MEASURING, steer.state())
        // the new stream is steered down to the floor and no further
        steer.onLock(520.0)
        follow(steer, 0, 1000)
        assertEquals(floor, steer.setpointMs, 0.0)
    }

    @Test
    fun theFloorCanBeCleared() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        steer.onUnderrun(at(101), "buffering")
        assertTrue(steer.clearFloor())
        assertTrue(steer.floorMs.isNaN())
        assertFalse(steer.clearFloor())
        assertEquals(250.0, steer.goalMs, 0.0)
    }

    @Test
    fun switchingTheTargetOffEndsTheSteeringAndSaysSo() {
        val steer = steered(300.0, 440.0)
        follow(steer, 0, 20)
        assertFalse(steer.setTarget(310.0))
        assertEquals(310.0, steer.targetMs, 0.0)
        assertTrue(steer.setTarget(Double.NaN))
        assertTrue(steer.setpointMs.isNaN())
        assertEquals(LagSteer.State.OFF, steer.state())
        // off again: nothing to end
        assertFalse(steer.setTarget(Double.NaN))
    }

    @Test
    fun aChangedTargetIsFollowedFromWhereTheSetpointIs() {
        val steer = steered(300.0, 440.0)
        follow(steer, 0, 50)
        val here = steer.setpointMs
        assertEquals(400.8, here, 0.01)
        steer.setTarget(380.0)
        follow(steer, 50, 20)
        assertEquals(here - 16.0, steer.setpointMs, 0.01)
        follow(steer, 70, 6)
        assertEquals(380.0, steer.setpointMs, 0.0)
        // and up again
        steer.setTarget(420.0)
        follow(steer, 76, 25)
        assertEquals(380.0 + 25 * 0.8, steer.setpointMs, 0.01)
    }
}
