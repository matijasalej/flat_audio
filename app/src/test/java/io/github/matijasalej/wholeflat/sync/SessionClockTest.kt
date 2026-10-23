package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionClockTest {
    @Test
    fun heardFrameAdvancesWithTime() {
        val clock = SessionClock(startNanos = 2_000_000_000L)
        assertEquals(0.0, clock.heardFrame(2_000_000_000L), 1e-6)
        assertEquals(44_100.0, clock.heardFrame(3_000_000_000L), 1e-6)
        assertEquals(-4_410.0, clock.heardFrame(1_900_000_000L), 1e-6)
        assertEquals(3_000_000_000L, clock.timeOfFrame(44_100.0))
    }

    @Test
    fun theFirstReadingsReplaceThePrediction() {
        val clock = SessionClock(startNanos = 2_000_000_000L)
        // the Sonos plays frame 44100 at 3.100 s: it started at 2.100 s, not 2.000 s
        clock.refine(3_100_000_000L, 44_100.0)
        assertEquals(2_100_000_000L, clock.startNanos)
    }

    @Test
    fun laterReadingsOnlySmoothTheNoise() {
        val clock = SessionClock(startNanos = 2_000_000_000L)
        clock.refine(3_000_000_000L, 44_100.0)
        clock.refine(4_000_000_000L, 88_200.0)
        assertEquals(2_000_000_000L, clock.startNanos)
        // a reading 10 ms off moves it by a fraction only
        clock.refine(5_010_000_000L, 132_300.0)
        assertTrue(clock.startNanos in 2_000_000_001L..2_005_000_000L)
        // a big jump (a new stream, a stall) is followed at once
        clock.refine(6_500_000_000L, 176_400.0)
        assertEquals(2_500_000_000L, clock.startNanos)
    }
}
