package io.github.matijasalej.wholeflat.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerTest {
    private val rate = Signals.RATE

    /** Every snapshot the analyzer publishes while [pcm] is pushed. */
    private fun run(pcm: ShortArray, a: Analyzer = Analyzer(rate).also { it.reset(0) }): List<Analysis> {
        val out = ArrayList<Analysis>()
        var last: Analysis? = null
        Signals.feed(pcm) { buf, n ->
            a.push(buf, n)
            if (a.snapshot !== last) {
                last = a.snapshot
                out.add(a.snapshot)
            }
        }
        return out
    }

    @Test
    fun aBandLocksWithinSixSeconds() {
        val snaps = run(Signals.band(120.0, 12.0))
        val first = snaps.first { it.locked }
        assertTrue("locked at ${first.songSeconds(rate)} s", first.songSeconds(rate) <= 6.0)
        assertEquals(120.0, snaps.last().bpm, 2.4)
    }

    @Test
    fun theGridOfALockedSnapshotPredictsTheNextBeatsWithinTwentyMilliseconds() {
        for (bpm in doubleArrayOf(96.0, 120.0, 128.0)) {
            val seconds = 16.0
            val beats = Signals.beats(bpm, seconds)
            val snaps = run(Signals.band(bpm, seconds))
            val s = snaps.first { it.songSeconds(rate) >= 10.0 }
            assertTrue(s.locked)
            // the beats of the next two seconds, from the grid as it was at 10 s
            val upcoming = beats.filter { it > s.frame && it < s.frame + 2 * rate }
            for (b in upcoming) {
                val k = Math.round((b - s.beatFrame) / s.periodFrames)
                val predicted = s.beatFrame + k * s.periodFrames
                assertEquals("beat at $bpm BPM", 0.0, (predicted - b) * 1000.0 / rate, 20.0)
            }
        }
    }

    @Test
    fun aNewTempoIsFollowed() {
        val snaps = run(Signals.concat(Signals.band(100.0, 12.0), Signals.band(128.0, 12.0, seed = 2)))
        val end = snaps.last()
        assertTrue(end.locked)
        assertEquals(128.0, end.bpm, 128 * 0.02)
    }

    @Test
    fun noiseNeverLocks() {
        assertFalse(run(Signals.noise(12.0)).any { it.locked })
    }

    @Test
    fun aRememberedTempoCentresThePriorOnTheRightOctave() {
        // fast clicks are ambiguous between 180 and 90; the song's remembered 180 settles it
        val a = Analyzer(rate).also { it.reset(0, priorBpm = 180.0) }
        val snaps = run(Signals.clicks(Signals.beats(180.0, 12.0), 12.0), a)
        assertEquals(180.0, snaps.last().bpm, 180 * 0.02)
    }

    @Test
    fun theFramesOfASnapshotCountFromTheStartGivenByReset() {
        val a = Analyzer(rate).also { it.reset(1_000_000L) }
        val snaps = run(Signals.band(120.0, 3.0), a)
        assertEquals(1_000_000L + 3 * rate - (3 * rate) % 441, snaps.last().frame)
        assertEquals(1_000_000L, snaps.last().firstFrame)
    }
}
