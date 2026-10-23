package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LagSteerTest {
    private val t0 = 1_000_000_000_000L

    /** The movement of the setpoint in one reading (readings are 200 ms apart). */
    private val step = LagSteer.RAMP_MS_PER_S * 0.2

    /** Reading number [i], 200 ms apart. */
    private fun at(i: Int): Long = t0 + i * 200_000_000L

    /** The position a speaker reports at reading [i] when it plays in real time. */
    private fun pos(i: Int): Long = 5_000L + i * 200L

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
        for (i in from until from + n) steer.onReading(at(i), steer.setpointMs, pos(i))?.let { events.add(it) }
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
    fun theSetpointStartsAtTheLockAndFallsAtThreeMillisecondsPerSecond() {
        assertEquals(3.0, LagSteer.RAMP_MS_PER_S, 0.0)
        val steer = steered(300.0, 440.0)
        assertEquals(440.0, steer.setpointMs, 0.0)
        assertEquals(LagSteer.State.DESCENDING, steer.state())
        assertTrue(steer.descending())
        follow(steer, 0, 51) // the first reading sets the time base, then 10 s
        assertEquals(410.0, steer.setpointMs, 0.01)
        follow(steer, 51, 50)
        assertEquals(380.0, steer.setpointMs, 0.01)
        assertTrue(steer.learnedFloorMs.isNaN())
    }

    @Test
    fun theRampIsLessThanAHalfPercentAtAnyRateOfReadings() {
        // readings every 20 ms or every second: the setpoint falls with the time, not with the number of readings
        val fast = steered(300.0, 440.0)
        for (i in 0..500) fast.onReading(t0 + i * 20_000_000L, fast.setpointMs) // 10 s
        assertEquals(410.0, fast.setpointMs, 0.5)
        val slow = steered(300.0, 440.0)
        for (i in 0..10) slow.onReading(t0 + i * 1_000_000_000L, slow.setpointMs)
        assertEquals(410.0, slow.setpointMs, 0.5)
        // a long gap between two readings (a stall of the websocket) moves it by one second's worth, not by the gap
        val gap = steered(300.0, 440.0)
        gap.onReading(t0, 440.0)
        gap.onReading(t0 + 60_000_000_000L, 440.0)
        assertEquals(440.0 - 3.0, gap.setpointMs, 0.01)
        // 3 ms per second is 0.3 %, and the trims of a captured stream (every 220 frames) can do 0.45 %
        assertTrue(LagSteer.RAMP_MS_PER_S / 1000.0 <= 0.0045)
    }

    @Test
    fun theSetpointStopsAtTheTargetAndThenItIsHolding() {
        val steer = steered(400.0, 440.0)
        follow(steer, 0, 70) // 40 ms take 13 s
        assertEquals(400.0, steer.setpointMs, 0.0)
        assertEquals(LagSteer.State.HOLDING, steer.state())
        assertFalse(steer.descending())
        follow(steer, 70, 100)
        assertEquals(400.0, steer.setpointMs, 0.0)
    }

    @Test
    fun itIsDescendingUntilTheLagIsNearTheGoalNotOnlyTheSetpoint() {
        val steer = steered(400.0, 440.0)
        // the setpoint gets to the target, the lag is 25 ms behind it
        for (i in 0 until 72) steer.onReading(at(i), steer.setpointMs + 25.0, pos(i))
        assertEquals(400.0, steer.setpointMs, 0.0)
        assertEquals(LagSteer.State.DESCENDING, steer.state())
        // the lag comes down to it (a step down is no trouble: a speaker that runs dry has a bigger lag after)
        for (i in 72 until 82) steer.onReading(at(i), 403.0, pos(i))
        assertEquals(LagSteer.State.HOLDING, steer.state())
        assertTrue(steer.learnedFloorMs.isNaN())
    }

    @Test
    fun aTargetAboveTheLockRisesAtTheSameSpeed() {
        val steer = steered(500.0, 440.0)
        assertEquals(LagSteer.State.ASCENDING, steer.state())
        assertFalse(steer.descending())
        follow(steer, 0, 26) // 5 s
        assertEquals(455.0, steer.setpointMs, 0.01)
        follow(steer, 26, 300)
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
    fun theGoalIsNeverBelowTheConfiguredFloor() {
        val steer = LagSteer()
        steer.setTarget(300.0)
        steer.setConfiguredFloor(316.0)
        assertEquals(316.0, steer.floorMs, 0.0)
        assertEquals(316.0, steer.goalMs, 0.0)
        steer.onLock(362.0)
        follow(steer, 0, 200)
        assertEquals(316.0, steer.setpointMs, 0.0)
        assertEquals(LagSteer.State.HOLDING, steer.state())
        // a target above the floor is the goal
        steer.setTarget(322.0)
        assertEquals(322.0, steer.goalMs, 0.0)
        // the floor setting can change
        steer.setConfiguredFloor(Double.NaN)
        steer.setTarget(250.0)
        assertEquals(250.0, steer.goalMs, 0.0)
    }

    @Test
    fun theSetpointWaitsForALagThatDoesNotFollow() {
        // the lag stays at 440 (nothing is trimmed): the setpoint goes no further than 30 ms ahead of it
        val steer = steered(300.0, 440.0)
        for (i in 0 until 200) steer.onReading(at(i), 440.0, pos(i))
        assertEquals(410.0, steer.setpointMs, 0.01)
        // and it goes on when the lag comes
        for (i in 200 until 240) steer.onReading(at(i), 395.0, pos(i))
        assertTrue(steer.setpointMs < 400.0)
        assertTrue(steer.learnedFloorMs.isNaN())
    }

    @Test
    fun anUnderrunAtALagThatWasBroughtDownSetsTheFloorFifteenAboveIt() {
        assertEquals(15.0, LagSteer.FLOOR_STEP_MS, 0.0)
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100) // 20 s: the setpoint is at 381
        val lagBefore = steer.setpointMs
        val event = steer.onUnderrun(at(101), "PLAYBACK_STATE_BUFFERING")
        assertNotNull(event)
        // the lag from before (the median of the readings from 1.5 s to 0.5 s ago, a few ms above the last one on a way down), plus 15
        assertTrue("floor ${event!!.floorMs} for a lag of $lagBefore", event.floorMs in (lagBefore + 15.0)..(lagBefore + 21.0))
        assertEquals(event.floorMs - 15.0, event.lagMs, 0.001)
        assertEquals("PLAYBACK_STATE_BUFFERING", event.why)
        assertEquals(event.floorMs, steer.learnedFloorMs, 0.0)
        assertEquals(event.floorMs, steer.floorMs, 0.0)
        assertEquals(1, steer.floorEvents)
        // the goal is now the floor, not the target
        assertEquals(event.floorMs, steer.goalMs, 0.0)
    }

    @Test
    fun theFloorIsFifteenAboveTheSetpointThatWasHeldIfThatIsHigherThanTheLag() {
        // after a floor was set the setpoint rises to it while the lag trails below: what was held is the setpoint, not the lag
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val first = steer.onUnderrun(at(101), "buffering")!!.floorMs
        val lag = steer.setpointMs
        for (i in 102 until 140) steer.onReading(at(i), lag, pos(i))
        assertEquals(first, steer.setpointMs, 0.01)
        val second = steer.onUnderrun(at(140), "again")
        assertNotNull(second)
        assertEquals(first + 15.0, second!!.floorMs, 0.01)
    }

    @Test
    fun anUnderrunBeforeTheLagWasBroughtDownIsNotTheSteers() {
        val steer = steered(250.0, 440.0)
        // the lock itself: nothing was steered yet
        assertNull(steer.onUnderrun(at(0), "buffering"))
        follow(steer, 0, 5) // 1 s: the setpoint is 2.4 ms down, within the margin
        assertNull(steer.onUnderrun(at(6), "buffering"))
        assertTrue(steer.learnedFloorMs.isNaN())
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
        assertEquals(first, steer.learnedFloorMs, 0.0)
        assertEquals(1, steer.floorEvents)
        // one at a higher lag raises it
        val lag = first + 60.0
        for (i in 160 until 200) steer.onReading(at(i), lag, pos(i))
        val second = steer.onUnderrun(at(200), "once more")
        assertNotNull(second)
        assertTrue(steer.learnedFloorMs > first)
        assertEquals(2, steer.floorEvents)
    }

    @Test
    fun severalSignalsOfOneStallRaiseTheFloorOnce() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val floor = steer.onUnderrun(at(101), "buffering")!!.floorMs
        // the readings that follow show a lag 400 ms bigger; the speaker also reports an error within the same few seconds
        for (i in 102 until 110) steer.onReading(at(i), steer.setpointMs + 400.0, pos(i))
        assertNull(steer.onUnderrun(at(110), "transport TRANSITIONING"))
        assertEquals(floor, steer.learnedFloorMs, 0.0)
        assertEquals(1, steer.floorEvents)
    }

    @Test
    fun aJumpOfTheLagThatStaysSetsTheFloorWithTheLagFromBeforeTheJump() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val before = steer.setpointMs
        var event: LagSteer.FloorEvent? = null
        // the speaker said nothing, but the lag is 150 ms bigger from one reading to the next and stays there
        for (i in 100 until 103) event = steer.onReading(at(i), before + 150.0, pos(i)) ?: event
        assertNotNull("three readings are a jump", event)
        assertTrue("floor ${event!!.floorMs} for a lag of $before", event.floorMs in (before + 15.0)..(before + 22.0))
        assertTrue(event.why.contains("jumped"))
    }

    @Test
    fun aLagThatStepsDownIsNoTroubleASpeakerThatRunsDryHasABiggerLagAfter() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val before = steer.setpointMs
        for (i in 100 until 140) assertNull("reading $i", steer.onReading(at(i), before - 60.0, pos(i)))
        assertTrue(steer.learnedFloorMs.isNaN())
    }

    @Test
    fun oneOrTwoStrayReadingsAreNoJumpAndNoScatter() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val spots = intArrayOf(100, 101)
        for (i in 100 until 140) {
            val lag = if (i in spots) steer.setpointMs + 60.0 else steer.setpointMs
            assertNull(steer.onReading(at(i), lag, pos(i)))
        }
        assertTrue(steer.learnedFloorMs.isNaN())
    }

    @Test
    fun readingsThatScatterSetTheFloor() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val before = steer.setpointMs
        var event: LagSteer.FloorEvent? = null
        // every other reading is 40 ms off
        for (i in 100 until 112) {
            val lag = if (i % 2 == 0) before else before + 40.0
            event = steer.onReading(at(i), lag, pos(i)) ?: event
        }
        assertNotNull(event)
        assertTrue(event!!.why.contains("median"))
        assertTrue("floor ${event.floorMs} for a lag of $before", event.floorMs in (before + 15.0)..(before + 22.0))
    }

    @Test
    fun theNoiseOfTheReadingsIsNotTrouble() {
        for (seed in 1L..5L) {
            val steer = steered(250.0, 440.0)
            val rnd = java.util.Random(seed)
            for (i in 0 until 1000) {
                // noise of 4 ms (more than the readings have) on the lag and on the position
                val event = steer.onReading(at(i), steer.setpointMs + rnd.nextGaussian() * 4.0, pos(i) + Math.round(rnd.nextGaussian() * 4.0))
                assertNull("seed $seed reading $i: ${event?.why}", event)
            }
            assertTrue(steer.learnedFloorMs.isNaN())
        }
    }

    // ---- the position that falls behind real time -------------------------------------------------------------

    @Test
    fun aPositionThatAdvancesInRealTimeShowsNoDeficit() {
        val steer = steered(250.0, 440.0)
        assertTrue(steer.rateDeficitMs.isNaN())
        follow(steer, 0, 5)
        assertTrue("not enough readings yet", steer.rateDeficitMs.isNaN())
        follow(steer, 5, 50)
        assertEquals(0.0, steer.rateDeficitMs, 0.5)
    }

    @Test
    fun aPositionThatFallsBehindRealTimeSetsTheFloorWithinAboutASecond() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        val before = steer.setpointMs
        var position = pos(100)
        var found: Int? = null
        var event: LagSteer.FloorEvent? = null
        // the speaker advances 190 ms in each 200 ms (5 % slow); the lag readings stay where the controller holds them
        for (k in 0 until 30) {
            position += 190
            val e = steer.onReading(at(100 + k), steer.setpointMs, position)
            if (e != null && found == null) {
                found = k
                event = e
            }
        }
        assertNotNull("the stutter was found", found)
        assertTrue("found after $found readings", found!! in 3..8)
        assertTrue(event!!.why.contains("slower than real time"))
        assertTrue("floor ${event.floorMs} for a lag of $before", event.floorMs >= before + 15.0 - 2.0)
        assertTrue(steer.rateDeficitMs > LagSteer.RATE_DEFICIT_MS || steer.rateDeficitMs.isNaN().not())
    }

    @Test
    fun aDropoutOfSixtyMillisecondsIsFound() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        var position = pos(100)
        var found: Int? = null
        for (k in 0 until 30) {
            // the position freezes for 60 ms once, at reading 5 of this run, and then goes on in real time
            position += if (k == 5) 140L else 200L
            val e = steer.onReading(at(100 + k), steer.setpointMs, position)
            if (e != null && found == null) found = k
        }
        assertNotNull("a dropout of 60 ms was found", found)
        assertTrue("found $found readings in, the dropout was at 5", found!! in 6..12)
    }

    @Test
    fun aStrayTimeStampOrTwoAreOutvoted() {
        val steer = steered(250.0, 440.0)
        follow(steer, 0, 100)
        for (k in 0 until 60) {
            // one time stamp in ten is 40 ms late (a slow round trip), the position is right
            val late = if (k % 10 == 4) 40_000_000L else 0L
            val e = steer.onReading(at(100 + k) + late, steer.setpointMs, pos(100 + k))
            assertNull("reading $k: ${e?.why}", e)
        }
        assertTrue(steer.learnedFloorMs.isNaN())
    }

    @Test
    fun withoutPositionsThereIsNoRateAndNoSign() {
        val steer = steered(250.0, 440.0)
        for (i in 0 until 100) assertNull(steer.onReading(at(i), steer.setpointMs))
        assertTrue(steer.rateDeficitMs.isNaN())
    }

    @Test
    fun aPositionThatFallsBehindIsOnlyTheSteersAffairWhenItBroughtTheLagDown() {
        // the lag is where the speaker put it (a target above it): a stutter there is not the steer's doing
        val steer = steered(500.0, 440.0)
        follow(steer, 0, 20)
        var position = pos(20)
        for (k in 0 until 40) {
            position += 150
            assertNull(steer.onReading(at(20 + k), steer.setpointMs, position))
        }
        assertTrue(steer.learnedFloorMs.isNaN())
    }

    // ---- the floors --------------------------------------------------------------------------------------------

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
        // the stream starts again, 400 ms later in the stream
        steer.startStream()
        steer.onLock(floor + 400.0)
        follow(steer, 110, 1000) // 200 s
        assertEquals(floor, steer.setpointMs, 0.0)
        assertTrue(steer.setpointMs > 250.0)
    }

    @Test
    fun aNewStreamKeepsTheFloorsAndTheTargetButNotTheSetpoint() {
        val steer = steered(250.0, 440.0)
        steer.setConfiguredFloor(200.0)
        follow(steer, 0, 100)
        val floor = steer.onUnderrun(at(101), "buffering")!!.floorMs
        steer.startStream()
        assertTrue(steer.setpointMs.isNaN())
        assertEquals(floor, steer.learnedFloorMs, 0.0)
        assertEquals(200.0, steer.configuredFloorMs, 0.0)
        assertEquals(250.0, steer.targetMs, 0.0)
        assertEquals(LagSteer.State.MEASURING, steer.state())
        // the new stream is steered down to the floor and no further
        steer.onLock(520.0)
        follow(steer, 0, 1200)
        assertEquals(floor, steer.setpointMs, 0.0)
    }

    @Test
    fun theLearnedFloorCanBeClearedAndTheConfiguredOneStays() {
        val steer = steered(250.0, 440.0)
        steer.setConfiguredFloor(316.0)
        follow(steer, 0, 100)
        steer.onUnderrun(at(101), "buffering")
        assertTrue(steer.learnedFloorMs > 316.0 || steer.floorEvents == 0)
        assertTrue(steer.clearFloor())
        assertTrue(steer.learnedFloorMs.isNaN())
        assertFalse(steer.clearFloor())
        assertEquals(316.0, steer.floorMs, 0.0)
        assertEquals(316.0, steer.goalMs, 0.0)
    }

    @Test
    fun aFloorThatWasKeptFromEarlierTodayIsUsedFromTheStart() {
        val steer = LagSteer()
        steer.setTarget(322.0)
        steer.setConfiguredFloor(316.0)
        steer.restoreLearnedFloor(337.0)
        assertEquals(337.0, steer.floorMs, 0.0)
        assertEquals(337.0, steer.goalMs, 0.0)
        steer.onLock(362.0)
        follow(steer, 0, 100)
        assertEquals(337.0, steer.setpointMs, 0.0)
    }

    @Test
    fun anUnderrunAtTheConfiguredFloorRaisesTheLearnedFloorAboveIt() {
        val steer = steered(250.0, 362.0)
        steer.setConfiguredFloor(316.0)
        follow(steer, 0, 100) // down to the configured floor and held there
        assertEquals(316.0, steer.setpointMs, 0.0)
        val event = steer.onUnderrun(at(101), "buffering")
        assertNotNull(event)
        assertTrue("floor ${event!!.floorMs}", event.floorMs >= 316.0 + 15.0)
        assertEquals(event.floorMs, steer.floorMs, 0.0)
        assertEquals(316.0, steer.configuredFloorMs, 0.0)
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
        assertEquals(440.0 - 49 * step, here, 0.01)
        steer.setTarget(380.0)
        follow(steer, 50, 20)
        assertEquals(here - 20 * step, steer.setpointMs, 0.01)
        follow(steer, 70, 40)
        assertEquals(380.0, steer.setpointMs, 0.0)
        // and up again
        steer.setTarget(420.0)
        follow(steer, 110, 25)
        assertEquals(380.0 + 25 * step, steer.setpointMs, 0.01)
    }
}
