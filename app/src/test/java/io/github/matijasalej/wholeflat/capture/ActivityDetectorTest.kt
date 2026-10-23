package io.github.matijasalej.wholeflat.capture

import io.github.matijasalej.wholeflat.capture.ActivityDetector.Event
import io.github.matijasalej.wholeflat.capture.ActivityDetector.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDetectorTest {
    private val ms = 1_000_000L
    private val loud = -14.0
    private val quiet = -120.0

    /** Feeds 20 ms chunks of one level from [fromMs] for [durationMs]; returns the events with their times in ms. */
    private fun run(d: ActivityDetector, fromMs: Long, durationMs: Long, peak: Double): List<Pair<Long, Event>> {
        val out = ArrayList<Pair<Long, Event>>()
        var t = fromMs
        while (t < fromMs + durationMs) {
            for (e in d.onChunk(t * ms, peak)) out.add(Pair(t, e))
            t += 20
        }
        return out
    }

    private fun events(list: List<Pair<Long, Event>>) = list.map { it.second }

    @Test
    fun silenceAloneNeverDoesAnything() {
        val d = ActivityDetector()
        assertTrue(run(d, 0, 120_000, quiet).isEmpty())
        assertEquals(State.IDLE, d.state)
    }

    @Test
    fun soundForTwoHundredMillisecondsIsAStart() {
        val d = ActivityDetector()
        val e = run(d, 1_000, 1_000, loud)
        assertEquals(listOf(Event.CANDIDATE, Event.START), events(e))
        assertEquals("the candidate is announced at the first sound", 1_000L, e[0].first)
        assertEquals("the start 200 ms later", 1_200L, e[1].first)
        assertEquals(State.ACTIVE, d.state)
    }

    @Test
    fun aBlipShorterThanTheStartTimeIsCancelled() {
        val d = ActivityDetector()
        val e = run(d, 0, 100, loud) + run(d, 100, 1_000, quiet)
        assertEquals(listOf(Event.CANDIDATE, Event.CANDIDATE_CANCELLED), events(e))
        assertEquals(State.IDLE, d.state)
        // and a new run starts from scratch
        val again = run(d, 2_000, 400, loud)
        assertEquals(listOf(Event.CANDIDATE, Event.START), events(again))
        assertEquals(2_200L, again[1].first)
    }

    @Test
    fun aShortGapInsideTheSoundDoesNotBreakTheRun() {
        val d = ActivityDetector()
        val e = run(d, 0, 100, loud) + run(d, 100, 60, quiet) + run(d, 160, 400, loud)
        assertEquals(listOf(Event.CANDIDATE, Event.START), events(e))
        assertEquals("200 ms after the first sound, the gap counts as part of the run", 200L, e[1].first)
    }

    @Test
    fun twoSecondsOfSilenceIsAPauseAndNotBefore() {
        val d = ActivityDetector()
        run(d, 0, 1_000, loud)
        val lastSound = 980L
        val e = run(d, 1_000, 3_000, quiet)
        assertEquals(listOf(Event.PAUSE), events(e))
        assertEquals(lastSound + 2_000, e[0].first)
        assertEquals(State.PAUSED, d.state)
    }

    @Test
    fun soundAfterAPauseIsAResumeOnceItHasLastedTwoHundredMilliseconds() {
        val d = ActivityDetector()
        run(d, 0, 1_000, loud)
        run(d, 1_000, 3_000, quiet) // pause
        val short = run(d, 4_000, 100, loud) + run(d, 4_100, 500, quiet)
        assertTrue("no resume for a blip", short.none { it.second == Event.RESUME })
        assertEquals(State.PAUSED, d.state)
        val e = run(d, 6_000, 1_000, loud)
        assertEquals(listOf(Event.RESUME), events(e))
        assertEquals(6_200L, e[0].first)
        assertEquals(State.ACTIVE, d.state)
    }

    @Test
    fun theHoldEndsSixtySecondsAfterTheLastSound() {
        val d = ActivityDetector()
        run(d, 0, 1_000, loud) // the last sound chunk is at 980 ms
        val e = run(d, 1_000, 70_000, quiet)
        assertEquals(listOf(Event.PAUSE, Event.IDLE), events(e))
        assertEquals(980L + 60_000, e[1].first)
        assertEquals(State.IDLE, d.state)
        // new sound after that is a start again, not a resume
        val again = run(d, 80_000, 500, loud)
        assertEquals(listOf(Event.CANDIDATE, Event.START), events(again))
    }

    @Test
    fun aSkipInsideTheHoldNeverRestartsAnything() {
        val d = ActivityDetector()
        run(d, 0, 5_000, loud)
        // 9 s of silence between two tracks: paused, then sound again
        val gap = run(d, 5_000, 9_000, quiet)
        assertEquals(listOf(Event.PAUSE), events(gap))
        val back = run(d, 14_000, 1_000, loud)
        assertEquals(listOf(Event.RESUME), events(back))
        assertTrue(back.none { it.second == Event.START || it.second == Event.IDLE })
    }

    @Test
    fun theTimeComesFromTheTimestampsNotFromCountingChunks() {
        val d = ActivityDetector()
        // chunks of 100 ms each
        val events = ArrayList<Event>()
        var t = 0L
        repeat(3) {
            events.addAll(d.onChunk(t * ms, loud))
            t += 100
        }
        assertEquals(listOf(Event.CANDIDATE, Event.START), events)
        assertEquals(State.ACTIVE, d.state)
    }

    @Test
    fun aPeakAtTheThresholdCountsAsSilence() {
        val d = ActivityDetector(silenceDb = -70.0)
        assertTrue(run(d, 0, 1_000, -70.0).isEmpty())
        assertEquals(listOf(Event.CANDIDATE, Event.START), events(run(d, 1_000, 400, -69.9)))
    }

    @Test
    fun aPlayingHintPreparesAtOnceAndIsWithdrawnIfNothingFollows() {
        val d = ActivityDetector()
        assertEquals(listOf(Event.CANDIDATE), d.hint(true, 0))
        assertTrue("one hint is enough", d.hint(true, 100 * ms).isEmpty())
        val e = run(d, 100, 5_000, quiet)
        assertEquals(listOf(Event.CANDIDATE_CANCELLED), events(e))
        assertEquals(3_000L, e[0].first)
    }

    @Test
    fun soundAfterAHintIsAStartAsUsual() {
        val d = ActivityDetector()
        d.hint(true, 0)
        val e = run(d, 500, 500, loud)
        assertEquals(State.ACTIVE, d.state)
        assertTrue(e.any { it.second == Event.START })
    }

    @Test
    fun aPausedHintWhileActiveIsAPauseAtOnce() {
        val d = ActivityDetector()
        run(d, 0, 1_000, loud)
        assertEquals(listOf(Event.PAUSE), d.hint(false, 1_000 * ms))
        assertEquals(State.PAUSED, d.state)
        // a hint that says playing while active changes nothing
        val d2 = ActivityDetector()
        run(d2, 0, 1_000, loud)
        assertTrue(d2.hint(true, 1_000 * ms).isEmpty())
        assertEquals(State.ACTIVE, d2.state)
        // and "not playing" while idle changes nothing
        assertTrue(ActivityDetector().hint(false, 0).isEmpty())
    }

    @Test
    fun resetForgetsEverything() {
        val d = ActivityDetector()
        run(d, 0, 1_000, loud)
        d.reset()
        assertEquals(State.IDLE, d.state)
        assertEquals(-1L, d.silentForNanos(5_000 * ms))
        assertEquals(listOf(Event.CANDIDATE), run(d, 2_000, 20, loud).map { it.second })
    }
}
