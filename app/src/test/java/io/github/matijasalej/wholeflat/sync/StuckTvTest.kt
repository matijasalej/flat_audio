package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckTvTest {
    @Test
    fun oneStartThatEndsInTransitioningIsNotHungTwoInARowAre() {
        val s = StuckTv()
        assertFalse(s.onNoGet(1_000))
        assertFalse(s.stuck)
        assertTrue(s.onNoGet(60_000))
        assertTrue(s.stuck)
        assertEquals(60_000L, s.stuckSinceMs)
        // it is told once
        assertFalse(s.onNoGet(120_000))
    }

    @Test
    fun anotherFailureOrASuccessBetweenStartsTheCountAgain() {
        val s = StuckTv()
        s.onNoGet(1_000)
        s.onOtherFailure()
        assertFalse(s.onNoGet(60_000))
        s.onPlaying()
        assertFalse(s.onNoGet(120_000))
        assertFalse(s.stuck)
        assertTrue(s.onNoGet(180_000))
    }

    @Test
    fun aTransportThatStaysTransitioningAfterAStopIsHungAtOnce() {
        val s = StuckTv()
        assertTrue(s.onHungBeforeSet(5_000))
        assertTrue(s.stuck)
        assertFalse(s.onHungBeforeSet(6_000))
    }

    @Test
    fun onlyTheUserClearsItAndTheNextStartIsOnProbation() {
        val s = StuckTv()
        s.onNoGet(0)
        s.onNoGet(1)
        assertTrue(s.stuck)
        s.clear()
        assertFalse(s.stuck)
        assertTrue(s.probation)
        // one more start that hangs is enough
        assertTrue(s.onNoGet(100_000))
        assertTrue(s.stuck)
        assertFalse(s.probation)
        // cleared again, and this time it plays: the probation is over, two are needed again
        s.clear()
        s.onPlaying()
        assertFalse(s.probation)
        assertFalse(s.onNoGet(200_000))
        assertTrue(s.onNoGet(260_000))
    }

    @Test
    fun clearingAPlayerThatWasNotHungStartsNoProbation() {
        val s = StuckTv()
        s.onNoGet(0)
        s.clear()
        assertFalse(s.probation)
        assertEquals(0, s.noGetsInARow)
    }

    @Test
    fun aFailureThatCameWhileHungDoesNotClearTheCount() {
        val s = StuckTv()
        s.onNoGet(0)
        s.onNoGet(1)
        s.onOtherFailure()
        assertTrue(s.stuck)
        assertEquals(2, s.noGetsInARow)
    }

    @Test
    fun aHungPlayerIsRestoredAfterARestart() {
        val s = StuckTv()
        s.restore(42L)
        assertTrue(s.stuck)
        assertEquals(42L, s.stuckSinceMs)
        assertTrue(StuckTv.ADVICE.contains("unplug the TV for a minute"))
    }
}
