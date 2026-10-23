package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {
    private val s = 1_000_000_000L

    private fun np(playing: Boolean = true, position: Long = 10_000, at: Long = 100 * s, speed: Double = 1.0, duration: Long = 200_000) =
        NowPlaying("p", playing, "Song", "Band", "Album", duration, "id", null, position, at, speed)

    @Test
    fun thePositionCarriesOnWhileItPlaysAndStandsStillWhenPaused() {
        assertEquals(10_000L, np().positionAt(100 * s))
        assertEquals(13_500L, np().positionAt(103 * s + s / 2))
        assertEquals(10_000L, np(playing = false).positionAt(150 * s))
        // before the moment it was valid: a little earlier in the track
        assertEquals(9_000L, np().positionAt(99 * s))
    }

    @Test
    fun theSpeedCounts() {
        assertEquals(20_000L, np(speed = 2.0).positionAt(105 * s))
        assertEquals(12_500L, np(speed = 0.5).positionAt(105 * s))
    }

    @Test
    fun theUnknownPositionIsMinusOneAndTheKnownOneStaysInsideTheTrack() {
        assertEquals(-1L, np(position = -1).positionAt(100 * s))
        assertEquals(0L, np().positionAt(0))
        assertEquals(200_000L, np().positionAt(10_000 * s))
        // without a length there is no upper bound
        assertTrue(np(duration = 0).positionAt(10_000 * s) > 200_000L)
    }

    @Test
    fun theKeyIsTheMediaIdElseTitleArtistAndLength() {
        assertEquals("id", np().key)
        val noId = NowPlaying("p", true, "Song", "Band", null, 201_400, null, null, 0, 0, 1.0)
        assertEquals("Song|Band|201", noId.key)
        val blankId = NowPlaying("p", true, "Song", "Band", null, 201_400, " ", null, 0, 0, 1.0)
        assertEquals("Song|Band|201", blankId.key)
    }

    @Test
    fun aTrackNeedsATitle() {
        assertTrue(np().hasTrack)
        assertFalse(NowPlaying("p", true, null, "Band", null, 0, null, null, 0, 0, 1.0).hasTrack)
        assertFalse(NowPlaying("p", true, "  ", "Band", null, 0, null, null, 0, 0, 1.0).hasTrack)
    }

    @Test
    fun theElapsedRealtimeClockOfASessionBecomesANanoTime() {
        // the position was valid 2.5 s ago on the elapsed-realtime clock: it is 2.5 s before nanoNow
        assertEquals(97_500_000_000L, NowPlaying.toNanoTime(lastUpdateElapsedMs = 7_500, elapsedNowMs = 10_000, nanoNow = 100 * s))
    }
}
