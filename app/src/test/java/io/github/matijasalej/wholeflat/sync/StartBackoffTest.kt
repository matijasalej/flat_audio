package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartBackoffTest {
    @Test
    fun theFirstFailureIsTriedAgainAtOnce() {
        val b = StartBackoff()
        assertTrue(b.allowed(0))
        assertEquals(0L, b.onFailure(1_000))
        assertEquals(1, b.failures)
        assertTrue("no wait after the first", b.allowed(1_000))
        assertEquals(0L, b.remainingMs(1_000))
    }

    @Test
    fun afterTheSecondTheWaitsGrowToFiveMinutes() {
        val b = StartBackoff()
        var now = 0L
        val waits = ArrayList<Long>()
        repeat(9) {
            waits.add(b.onFailure(now))
            now += 1_000
        }
        assertEquals(
            listOf(0L, 10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L, 300_000L, 300_000L),
            waits,
        )
    }

    @Test
    fun aStartIsHeldBackUntilTheWaitIsOver() {
        val b = StartBackoff()
        b.onFailure(0)
        b.onFailure(5_000) // the second: 10 s from now
        assertFalse(b.allowed(5_001))
        assertFalse(b.allowed(14_999))
        assertEquals(5_000L, b.remainingMs(10_000))
        assertTrue(b.allowed(15_000))
        assertEquals(0L, b.remainingMs(15_000))
    }

    @Test
    fun aHealthyStreamStartsItAllOver() {
        val b = StartBackoff()
        b.onFailure(0)
        b.onFailure(0)
        b.onFailure(0)
        assertFalse(b.allowed(1))
        b.onHealthy()
        assertEquals(0, b.failures)
        assertTrue(b.allowed(1))
        // and the next failure is the first again
        assertEquals(0L, b.onFailure(100))
        assertEquals(10_000L, b.onFailure(200))
    }

    @Test
    fun aCustomScheduleIsUsed() {
        val b = StartBackoff(longArrayOf(0, 5))
        assertEquals(0L, b.onFailure(0))
        assertEquals(5L, b.onFailure(0))
        assertEquals(5L, b.onFailure(0))
    }
}
