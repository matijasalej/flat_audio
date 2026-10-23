package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceClockTest {
    private val s = 1_000_000_000L

    @Test
    fun beforeAnySoundTheCountIsFromTheStart() {
        val c = SilenceClock(-70.0, 100 * s)
        assertEquals(5 * s, c.silentForNanos(105 * s))
        assertFalse(c.heardSound)
    }

    @Test
    fun anyChunkAboveTheThresholdStartsTheCountAgainWhateverTheActivityState() {
        val c = SilenceClock(-70.0, 0)
        c.onChunk(10 * s, -30.0)
        assertEquals(20 * s, c.silentForNanos(30 * s))
        c.onChunk(31 * s, -69.0) // faint but audible
        assertEquals(1 * s, c.silentForNanos(32 * s))
        c.onChunk(33 * s, -90.0) // silence does not move it
        assertEquals(2 * s, c.silentForNanos(33 * s))
        assertTrue(c.heardSound)
    }

    @Test
    fun theCountOutlivesAMadeAgainCaptureSoMusicThatJustPlayedIsNotThirtyMinutesOfSilence() {
        // music until 22:49:23; the capture is made again then (the detector forgets everything with it) and hears nothing for 2 min 41 s;
        // the count goes on from the last sound, it is not "never"
        val c = SilenceClock(-70.0, 0)
        val t = 1_000 * s
        for (i in 0 until 100) c.onChunk(t + i * s / 10, -25.0)
        val lastSound = t + 99 * s / 10
        assertEquals(161 * s, c.silentForNanos(lastSound + 161 * s))
        assertTrue(c.silentForNanos(lastSound + 161 * s) < 30 * 60 * s)
        // and sound again sets it right
        c.onChunk(lastSound + 161 * s, -20.0)
        assertEquals(0L, c.silentForNanos(lastSound + 161 * s))
    }

    @Test
    fun anOlderChunkDoesNotMoveItBack() {
        val c = SilenceClock(-70.0, 0)
        c.onChunk(50 * s, -20.0)
        c.onChunk(40 * s, -20.0)
        assertEquals(10 * s, c.silentForNanos(60 * s))
    }
}
