package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTimelineTest {
    /** Track frame 1000 is presented at 5.000 s. */
    private fun timeline() = TrackTimeline().apply { setAnchor(1_000, 5_000_000_000L) }

    @Test
    fun frameAndTimeConvertBothWays() {
        val t = timeline()
        assertEquals(6_000_000_000L, t.halNanosOfTrack(1_000 + 44_100))
        assertEquals(1_000L + 44_100, t.trackOfHalNanos(6_000_000_000L))
        // 22 us is less than a frame (22.68 us): rounded down it is the frame before, rounded to the nearest the next
        assertEquals(1_000L, t.trackOfHalNanos(5_000_000_000L + 22_000))
        assertEquals(1_001L, t.trackNearestHalNanos(5_000_000_000L + 22_000))
    }

    @Test
    fun aRunMapsRingFramesToTrackFrames() {
        val t = timeline()
        t.startRun(ringStart = 500, trackStart = 2_000)
        t.extend(100)
        assertEquals(2_000L, t.trackOfRing(500))
        assertEquals(2_050L, t.trackOfRing(550))
        assertNull("before the run", t.trackOfRing(499))
        assertEquals("beyond the written end of the last run: predicted", 2_150L, t.trackOfRing(650))
        assertEquals(550L, t.ringOfTrack(2_050))
        assertNull("silence before the run", t.ringOfTrack(1_999))
    }

    @Test
    fun theSilenceBetweenTwoRunsHasNoRingFrames() {
        val t = timeline()
        t.startRun(0, 1_000)
        t.extend(100)
        t.startRun(100, 1_500) // 400 frames of silence in between
        t.extend(50)
        assertEquals(1_099L, t.trackOfRing(99))
        assertEquals(1_500L, t.trackOfRing(100))
        assertNull(t.ringOfTrack(1_200))
        assertEquals(99L, t.ringOfTrack(1_099))
        assertEquals(100L, t.ringOfTrack(1_500))
    }

    @Test
    fun aRewoundRunWinsForTheFramesItPlaysAgain() {
        val t = timeline()
        t.startRun(0, 1_000)
        t.extend(300) // ring 0..299 at track 1000..1299
        t.startRun(250, 5_000) // a pause rewound the reader to 250
        t.extend(10)
        assertEquals("played again: the newest run", 5_010L, t.trackOfRing(260))
        assertEquals("only played once: the old run", 1_100L, t.trackOfRing(100))
        assertEquals(5_030L, t.trackOfRing(280))
    }

    @Test
    fun ringFramesAndTheirPresentationTimeAreInverse() {
        val t = timeline()
        t.startRun(100, 1_000)
        t.extend(100_000)
        val f = 100L + 44_100
        val at = t.halNanosOfRing(f)!!
        assertEquals(6_000_000_000L, at)
        assertEquals(f, t.ringAtHalNanos(at))
        assertNull("before the first run it is silence", t.ringAtHalNanos(t.halNanosOfTrack(500)))
    }

    @Test
    fun withoutAnAnchorNothingIsKnown() {
        val t = TrackTimeline()
        t.startRun(0, 0)
        assertFalse(t.hasAnchor)
        assertNull(t.halNanosOfRing(0))
        assertNull(t.ringAtHalNanos(0))
    }

    @Test
    fun onlyTheLatestRunsAreKept() {
        val t = timeline()
        for (i in 0 until 100) {
            t.startRun(i * 10L, i * 100L)
            t.extend(10)
        }
        assertTrue(t.runCount() <= 16)
        assertEquals(9_900L + 5, t.trackOfRing(990 + 5))
    }
}
