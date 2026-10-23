package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionProbeTest {
    @Test
    fun theTimesOfAnItemAreReadInMilliseconds() {
        assertEquals(0L, PositionProbe.parseTime("0:00:00"))
        assertEquals(3_723_000L, PositionProbe.parseTime("1:02:03"))
        assertEquals(42_500L, PositionProbe.parseTime("0:00:42.5"))
        assertEquals(42_123L, PositionProbe.parseTime("00:00:42.123"))
        assertNull(PositionProbe.parseTime("NOT_IMPLEMENTED"))
        assertNull(PositionProbe.parseTime(""))
        assertNull(PositionProbe.parseTime(null))
    }

    @Test
    fun aPlayerThatKeepsTimeIsNoPause() {
        val p = PositionProbe { 100.0 }
        p.add(0, "0:00:10.000", null, "0", "0")
        val r = p.add(6_000, "0:00:16.010", null, "0", "0")
        assertEquals(6_000L, r.dWallMs)
        assertEquals(6_010L, r.dRelMs)
        assertEquals(-10L, r.driftMs)
        assertEquals(0L, r.pauseMs)
        assertEquals(true, r.advancing)
        assertFalse(p.coarse)
    }

    @Test
    fun aPlayerThatStoodStillForHalfASecondIsAPauseWhenTheCounterIsFine() {
        val p = PositionProbe { 100.0 }
        p.add(0, "0:00:10.000", null, null, null)
        p.add(5_000, "0:00:15.000", null, null, null)
        val r = p.add(10_000, "0:00:19.480", null, null, null)
        assertEquals(520L, r.driftMs)
        assertEquals(520L, r.pauseMs)
    }

    @Test
    fun aCounterOfWholeSecondsOnlyShowsAPauseOfMoreThanTwoAndAHalf() {
        val p = PositionProbe { 100.0 }
        p.add(0, "0:00:10", null, null, null)
        assertEquals(0L, p.add(5_000, "0:00:14", null, null, null).pauseMs) // 1 s: a second's rounding
        assertTrue(p.coarse)
        assertEquals(0L, p.add(10_000, "0:00:19", null, null, null).pauseMs)
        assertEquals(3_000L, p.add(18_000, "0:00:24", null, null, null).pauseMs)
    }

    @Test
    fun aPositionThatDoesNotMoveIsReportedAsNotAdvancing() {
        val p = PositionProbe { 100.0 }
        p.add(0, "0:00:00", "NOT_IMPLEMENTED", "0", "2147483647")
        val r = p.add(6_000, "0:00:00", "NOT_IMPLEMENTED", "0", "2147483647")
        assertEquals(false, r.advancing)
        assertEquals(6_000L, r.driftMs)
    }

    @Test
    fun aNewItemStartsTheReadingsAgain() {
        val p = PositionProbe { 100.0 }
        p.add(0, "0:05:00", null, null, null)
        val r = p.add(5_000, "0:00:02", null, null, null) // the position went back: a new stream
        assertNull(r.dWallMs)
        assertEquals(0L, r.pauseMs)
        assertEquals(true, p.add(10_000, "0:00:07", null, null, null).advancing)
    }

    @Test
    fun theLimitFollowsTheSettingWhenTheCounterIsFine() {
        var limit = 100.0
        val p = PositionProbe { limit }
        p.add(0, "0:00:10.000", null, null, null)
        limit = 800.0
        assertEquals(0L, p.add(5_000, "0:00:14.500", null, null, null).pauseMs) // 500 ms under 800
    }

    @Test
    fun aTvThatDoesNotImplementThePositionNeverShowsAStandstill() {
        // the Philips: RelTime always 0:00:00, AbsTime NOT_IMPLEMENTED, the counts 2147483647
        val p = PositionProbe { 100.0 }
        var pauses = 0L
        for (i in 0 until 10) pauses += p.add(i * 7_000L, "00:00:00", "NOT_IMPLEMENTED", "2147483647", "2147483647").pauseMs
        assertEquals(0L, pauses)
        assertEquals(false, p.supported)
        // it stays so, also for a new stream of the same TV
        p.reset()
        assertEquals(false, p.supported)
        assertEquals(0L, p.add(100_000, "00:00:00", "NOT_IMPLEMENTED", "2147483647", "2147483647").pauseMs)
    }

    @Test
    fun aTvThatGivesAPositionIsSupportedAndAStandstillBeforeAnyAdvanceIsNoPause() {
        val p = PositionProbe { 100.0 }
        // the first two readings are 0:00:00 (the player is only starting), then it runs: supported, never unsupported
        assertEquals(0L, p.add(0, "0:00:00", null, null, null).pauseMs)
        assertEquals(0L, p.add(6_000, "0:00:00", null, null, null).pauseMs)
        assertEquals(null, p.supported)
        assertEquals(0L, p.add(12_000, "0:00:12", null, null, null).pauseMs)
        assertEquals(true, p.supported)
        assertEquals(0L, p.add(18_000, "0:00:18", null, null, null).pauseMs)
        // and now a standstill is a pause
        assertEquals(true, p.add(28_000, "0:00:21", null, null, null).pauseMs > 0)
    }
}
