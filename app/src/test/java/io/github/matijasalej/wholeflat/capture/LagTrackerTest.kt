package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LagTrackerTest {
    @Test
    fun theLagIsLockedAtTheMedianOfTheFirstReadings() {
        val t = LagTracker(samples = 5)
        assertNull(t.add(400.0))
        assertNull(t.add(420.0))
        assertNull(t.add(900.0)) // an outlier
        assertNull(t.add(410.0))
        assertEquals(415.0, t.add(415.0)!!, 0.0)
        assertEquals(415.0, t.locked!!, 0.0)
        // later readings do not move it
        assertNull(t.add(600.0))
        assertEquals(415.0, t.locked!!, 0.0)
    }

    @Test
    fun theRecentMedianFollowsTheLastReadings() {
        val t = LagTracker(samples = 3)
        assertTrue(t.recent().isNaN())
        t.add(100.0)
        t.add(200.0)
        t.add(300.0)
        assertEquals(200.0, t.recent(), 0.0)
        t.add(1_000.0)
        t.add(1_000.0)
        assertEquals(1_000.0, t.recent(), 0.0)
        assertEquals("locked at the first three", 200.0, t.locked!!, 0.0)
    }

    @Test
    fun resetStartsMeasuringAgain() {
        val t = LagTracker(samples = 2)
        t.add(1.0)
        t.add(3.0)
        assertEquals(2.0, t.locked!!, 0.0)
        t.reset()
        assertNull(t.locked)
        assertEquals(0, t.count)
        assertTrue(t.recent().isNaN())
        t.add(10.0)
        assertEquals(10.0, t.add(10.0)!!, 0.0)
    }
}
