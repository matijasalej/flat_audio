package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayDelaysTest {
    @Test
    fun eachFormatStartsFromItsMeasuredDelay() {
        val d = PlayDelays()
        assertEquals(290.0, d.ms(StreamFormat.FLAC), 0.0)
        assertEquals(195.0, d.ms(StreamFormat.WAV), 0.0)
        assertEquals(290_000_000L, d.nanos(StreamFormat.FLAC))
    }

    @Test
    fun anObservationIsMixedInForItsOwnFormatOnly() {
        val d = PlayDelays()
        val now = d.learn(StreamFormat.WAV, 185_000_000L)
        // 60 % of the old delay and 40 % of the new
        assertEquals(191_000_000L, now)
        assertEquals(191.0, d.ms(StreamFormat.WAV), 0.001)
        assertEquals(290.0, d.ms(StreamFormat.FLAC), 0.0)
    }

    @Test
    fun repeatedObservationsConvergeOnWhatTheSpeakerDoes() {
        val d = PlayDelays()
        repeat(12) { d.learn(StreamFormat.WAV, 240_000_000L) }
        assertEquals(240.0, d.ms(StreamFormat.WAV), 1.0)
    }

    @Test
    fun anImplausibleObservationIsNotUsed() {
        val d = PlayDelays()
        assertNull(d.learn(StreamFormat.WAV, 50_000_000L))
        assertNull(d.learn(StreamFormat.WAV, 2_000_000_000L))
        assertEquals(195.0, d.ms(StreamFormat.WAV), 0.0)
        assertTrue(d.learn(StreamFormat.FLAC, 300_000_000L) != null)
    }
}
