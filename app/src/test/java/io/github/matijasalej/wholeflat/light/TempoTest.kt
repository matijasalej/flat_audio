package io.github.matijasalej.wholeflat.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoTest {
    private val hop = 441

    private fun envelope(pcm: ShortArray): DoubleArray {
        val o = Onsets(Signals.RATE)
        val env = ArrayList<Double>()
        Signals.feed(pcm) { buf, n -> o.push(buf, n) { env.add(Analyzer.envelopeOf(it)) } }
        return env.toDoubleArray()
    }

    private fun tempoOf(pcm: ShortArray, seconds: Double = 8.0): Tempo.Estimate {
        val env = envelope(pcm)
        val e = Tempo().estimate(env, count = (seconds * 100).toInt())
        assertNotNull(e)
        return e!!
    }

    private fun near(expected: Double, actual: Double, share: Double = 0.02) =
        assertEquals("tempo $actual for $expected", expected, actual, expected * share)

    @Test
    fun clickTracksFrom60To140GiveTheirTempoWithinTwoPercent() {
        for (bpm in doubleArrayOf(60.0, 90.0, 120.0, 128.0, 140.0)) {
            near(bpm, tempoOf(Signals.clicks(Signals.beats(bpm, 12.0), 12.0)).bpm)
        }
    }

    @Test
    fun fastClickTracksGiveTheirTempoOrItsHalf() {
        for (bpm in doubleArrayOf(174.0, 190.0)) {
            val got = tempoOf(Signals.clicks(Signals.beats(bpm, 12.0), 12.0)).bpm
            val ok = Math.abs(got - bpm) <= 0.02 * bpm || Math.abs(got - bpm / 2) <= 0.02 * bpm / 2
            assertTrue("tempo $got for $bpm", ok)
        }
    }

    @Test
    fun aBandWithKickSnareAndHatsGivesItsTempo() {
        for (bpm in doubleArrayOf(84.0, 100.0, 128.0)) near(bpm, tempoOf(Signals.band(bpm, 12.0)).bpm)
    }

    @Test
    fun swungEighthsDoNotMoveTheTempo() {
        near(120.0, tempoOf(Signals.band(120.0, 12.0, swing = 0.33)).bpm)
    }

    @Test
    fun noiseHasNoClearBeat() {
        val e = Tempo().estimate(envelope(Signals.noise(12.0)), count = 800)
        assertTrue("clarity ${e?.clarity}", e == null || e.clarity < 0.3)
    }

    @Test
    fun aClickTrackHasAClearBeat() {
        assertTrue(tempoOf(Signals.clicks(Signals.beats(120.0, 12.0), 12.0)).clarity > 0.6)
    }

    @Test
    fun theLatestBeatLandsOnAClickWithinTwentyMilliseconds() {
        for (bpm in doubleArrayOf(90.0, 120.0, 128.0)) {
            val beats = Signals.beats(bpm, 12.0)
            val env = envelope(Signals.clicks(beats, 12.0))
            val e = Tempo().estimate(env, count = 800)!!
            val lastHop = env.size - 1
            val beatFrame = (lastHop - e.offsetHops + 1) * hop - Onsets.ONSET_LAG_FRAMES
            val nearest = beats.minByOrNull { Math.abs(it - beatFrame) }!!
            val errMs = (beatFrame - nearest) * 1000.0 / Signals.RATE
            assertEquals("beat error at $bpm BPM", 0.0, errMs, 20.0)
        }
    }
}
