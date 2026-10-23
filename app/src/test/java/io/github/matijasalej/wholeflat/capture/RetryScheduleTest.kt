package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryScheduleTest {
    @Test
    fun theWaitsGrowAndStayAtAMinute() {
        val r = RetrySchedule()
        val waits = (1..8).map { r.next() }
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 60_000L, 60_000L, 60_000L), waits)
        assertEquals(8, r.attempts)
    }

    @Test
    fun resetStartsOver() {
        val r = RetrySchedule()
        r.next()
        r.next()
        r.reset()
        assertEquals(0, r.attempts)
        assertEquals(2_000L, r.next())
    }

    @Test
    fun aCustomScheduleIsUsed() {
        val r = RetrySchedule(longArrayOf(1, 2))
        assertEquals(listOf(1L, 2L, 2L), (1..3).map { r.next() })
    }
}
