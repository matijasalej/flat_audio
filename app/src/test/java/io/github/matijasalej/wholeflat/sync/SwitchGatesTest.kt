package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchGatesTest {
    // ---- the power clock -------------------------------------------------------------------------------

    @Test
    fun theFirstReadingOfOnStartsTheClock() {
        val c = PowerClock()
        assertFalse(c.read(1_000L, "On"))
        assertEquals(1_000L, c.onSinceMs)
        // more readings of On do not move it
        c.read(50_000L, "On")
        assertEquals(1_000L, c.onSinceMs)
    }

    @Test
    fun aReadingThatFailsForAMomentDoesNotMoveTheClock() {
        val c = PowerClock(offMinMs = 60_000L)
        c.read(1_000L, "On")
        assertTrue(c.read(100_000L, null))
        assertTrue(c.read(130_000L, "On"))
        assertEquals(1_000L, c.onSinceMs)
    }

    @Test
    fun aTvThatWasOffForAMinuteHasComeOnNow() {
        val c = PowerClock(offMinMs = 60_000L)
        c.read(1_000L, "On")
        c.read(100_000L, "Standby")
        c.read(170_000L, null)
        c.read(200_000L, "On")
        assertEquals(200_000L, c.onSinceMs)
    }

    @Test
    fun afterTheBoxWokeTheNextOnStartsTheClock() {
        val c = PowerClock()
        c.read(1_000L, "On")
        c.forget()
        assertEquals(0L, c.onSinceMs)
        c.read(500_000L, "On")
        assertEquals(500_000L, c.onSinceMs)
    }

    // ---- the gates -------------------------------------------------------------------------------------

    @Test
    fun aTvThatCameOnAMomentAgoWaitsTheRestOfTheBootWait() {
        val w = SwitchGates.wait(nowMs = 100_000L, onSinceMs = 70_000L, bootWaitMs = 180_000L, nowWallMs = 0L, lastCloseWallMs = 0L, closeWaitMs = 300_000L)
        assertEquals(150_000L, w.ms)
        assertTrue(w.why!!, w.why!!.contains("came on 30 s ago"))
    }

    @Test
    fun aPlayerClosedAMomentAgoWaitsTheRestOfTheCloseWait() {
        val w = SwitchGates.wait(nowMs = 1_000_000L, onSinceMs = 1L, bootWaitMs = 180_000L, nowWallMs = 5_070_000L, lastCloseWallMs = 5_000_000L, closeWaitMs = 300_000L)
        assertEquals(230_000L, w.ms)
        assertTrue(w.why!!, w.why!!.contains("closed 70 s ago"))
    }

    @Test
    fun theLongerOfTheTwoWaitsCountsAndNoneWhenBothAreOver() {
        val both = SwitchGates.wait(nowMs = 60_000L, onSinceMs = 30_000L, bootWaitMs = 180_000L, nowWallMs = 1_000_000L, lastCloseWallMs = 990_000L, closeWaitMs = 300_000L)
        assertEquals(290_000L, both.ms)
        assertEquals(150_000L, both.bootLeftMs)
        val none = SwitchGates.wait(nowMs = 600_000L, onSinceMs = 1L, bootWaitMs = 180_000L, nowWallMs = 2_000_000L, lastCloseWallMs = 1_000_000L, closeWaitMs = 300_000L)
        assertEquals(0L, none.ms)
        assertNull(none.why)
    }

    @Test
    fun zeroSwitchesAWaitOffAndAnUnknownStartOrCloseIsNoWait() {
        assertEquals(0L, SwitchGates.wait(10L, 5L, 0L, 10L, 5L, 0L).ms)
        assertEquals(0L, SwitchGates.wait(10L, 0L, 180_000L, 10L, 0L, 300_000L).ms)
        // a wall clock that went back does not make the wait longer than the wait itself
        assertEquals(300_000L, SwitchGates.wait(10L, 0L, 0L, 1_000L, 5_000L, 300_000L).ms)
    }
}
