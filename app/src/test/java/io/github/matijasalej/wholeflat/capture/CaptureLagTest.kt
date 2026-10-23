package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLagTest {
    private fun lockedAt(lag: CaptureLag, ms: Double, offset: Int = 0) {
        var locked = false
        for (i in 1..11) locked = lag.onReading(ms, offset)
        assertTrue(locked)
    }

    @Test
    fun theFirstReadingsOnlyMeasure() {
        val lag = CaptureLag()
        for (i in 1..10) assertFalse(lag.onReading(430.0, 0))
        assertFalse(lag.isLocked)
        assertTrue(lag.baseMs.isNaN())
        assertTrue(lag.targetMs(0).isNaN())
        assertTrue(lag.onReading(430.0, 0))
        assertTrue(lag.isLocked)
        assertEquals(430.0, lag.locked!!, 0.0)
    }

    @Test
    fun theLockedLagIsTheMedianAndAnOutlierDoesNotMatter() {
        val lag = CaptureLag()
        val readings = listOf(440.0, 436.0, 900.0, 441.0, 439.0, 438.0, 437.0, 1200.0, 440.0, 442.0, 439.0)
        var locked = false
        for (r in readings) locked = lag.onReading(r, 0)
        assertTrue(locked)
        assertEquals(440.0, lag.locked!!, 1.5)
    }

    @Test
    fun theBaseTakesTheOffsetOfTheLockOut() {
        val lag = CaptureLag()
        lockedAt(lag, 500.0, offset = 60)
        assertEquals(440.0, lag.baseMs, 0.0)
        // with the same offset the target is the lag it settled at
        assertEquals(500.0, lag.targetMs(60), 0.0)
        // a later change of the offset moves the target by the change
        assertEquals(540.0, lag.targetMs(100), 0.0)
        assertEquals(440.0, lag.targetMs(0), 0.0)
        assertEquals(440_000_000L, lag.baseNanos())
    }

    @Test
    fun theTargetNeverGoesBelowTheMinimum() {
        val lag = CaptureLag()
        lockedAt(lag, 150.0, offset = 0)
        assertEquals(100.0, lag.targetMs(-300), 0.0)
        assertEquals(-50.0, lag.controllerOffsetMs(-300), 0.0)
        assertEquals(150.0, lag.targetMs(0), 0.0)
        assertEquals(170.0, lag.targetMs(20), 0.0)
    }

    @Test
    fun beforeTheLockTheOffsetIsPassedOn() {
        val lag = CaptureLag()
        assertEquals(25.0, lag.controllerOffsetMs(25), 0.0)
        assertEquals(0L, lag.baseNanos())
    }

    @Test
    fun theReportedLagIsTheRecentMedianAndNeverNegative() {
        val lag = CaptureLag(samples = 3)
        assertTrue(lag.recentMs().isNaN())
        lag.onReading(-30.0, 0)
        lag.onReading(-20.0, 0)
        lag.onReading(-10.0, 0)
        assertEquals(0.0, lag.recentMs(), 0.0)
        lag.onReading(300.0, 0)
        lag.onReading(310.0, 0)
        assertEquals(300.0, lag.recentMs(), 0.0)
    }

    @Test
    fun aStepOfTheLagShowsInTheLastReadingsButAnOffsetChangeDoesNot() {
        val lag = CaptureLag()
        assertTrue("not locked yet", lag.movedMs(5).isNaN())
        lockedAt(lag, 440.0)
        assertEquals(0.0, lag.movedMs(5), 0.0)
        // the offset changes: the readings stay where they were
        lag.onReading(440.0, 80)
        assertEquals(0.0, lag.movedMs(5), 0.0)
        // the lag steps up by 700 ms: five readings are enough to see it
        repeat(5) { lag.onReading(1_140.0, 0) }
        assertEquals(700.0, lag.movedMs(5), 0.0)
        assertEquals(1_140.0, lag.recentMs(5), 0.0)
    }

    @Test
    fun resetStartsMeasuringAgain() {
        val lag = CaptureLag()
        lockedAt(lag, 480.0)
        lag.reset()
        assertFalse(lag.isLocked)
        assertTrue(lag.baseMs.isNaN())
        assertEquals(0, lag.readings)
    }
}
