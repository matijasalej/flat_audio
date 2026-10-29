package io.github.matijasalej.wholeflat.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnsetsTest {
    private fun hops(pcm: ShortArray): List<Onsets.Hop> {
        val o = Onsets(Signals.RATE)
        val out = ArrayList<Onsets.Hop>()
        Signals.feed(pcm) { buf, n -> o.push(buf, n) { out.add(it) } }
        return out
    }

    @Test
    fun theFftFindsASineInItsBin() {
        val fft = Fft(256)
        val re = DoubleArray(256) { Math.sin(2 * Math.PI * 10 * it / 256) }
        val im = DoubleArray(256)
        fft.transform(re, im)
        val mags = DoubleArray(128) { Math.hypot(re[it], im[it]) }
        assertEquals(10, mags.indices.maxByOrNull { mags[it] })
        assertEquals(128.0, mags[10], 1e-6)
    }

    @Test
    fun aClickMakesTheFluxPeakAFixedLagAfterIt() {
        val beats = Signals.beats(120.0, 10.0)
        val h = hops(Signals.clicks(beats, 10.0))
        val hop = 441
        // the hop with the most flux near each click, and how far its end is after the click
        val lags = ArrayList<Long>()
        for (b in beats.drop(1).dropLast(1)) {
            val around = h.filter { Math.abs((it.index + 1) * hop - b) < 4 * hop }
            val peak = around.maxByOrNull { it.flux }!!
            lags.add((peak.index + 1) * hop - b)
        }
        val mean = lags.average()
        // the constant the beat times are corrected with must be what the clicks show, to a hop
        assertEquals(Onsets.ONSET_LAG_FRAMES.toDouble(), mean, hop.toDouble())
        assertTrue("the lag is the same for every click: $lags", lags.maxOrNull()!! - lags.minOrNull()!! <= hop)
    }

    @Test
    fun silenceHasNoFluxAndTheFloorLevel() {
        val h = hops(Signals.silence(2.0))
        assertTrue(h.all { it.flux == 0.0 })
        assertTrue(h.all { it.rmsDb == Onsets.FLOOR_DB })
    }

    @Test
    fun theLevelOfAFullScaleSineIsMinusThreeDb() {
        val n = Signals.RATE
        val pcm = ShortArray(n * 2)
        for (i in 0 until n) {
            val v = (32767 * Math.sin(2 * Math.PI * 1000 * i / Signals.RATE)).toInt().toShort()
            pcm[2 * i] = v
            pcm[2 * i + 1] = v
        }
        val h = hops(pcm)
        assertEquals(-3.0, h.last().rmsDb, 0.1)
        assertEquals(1000.0, h.last().centroidHz, 120.0)
    }

    @Test
    fun theChromaOfAnAIsA() {
        // a plain A4 with its octave
        val n = Signals.RATE
        val pcm = ShortArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / Signals.RATE
            val v = (8000 * (Math.sin(2 * Math.PI * 440 * t) + 0.5 * Math.sin(2 * Math.PI * 880 * t))).toInt().toShort()
            pcm[2 * i] = v
            pcm[2 * i + 1] = v
        }
        val chroma = hops(pcm).mapNotNull { it.chroma }.last()
        assertNotNull(chroma)
        assertEquals(9, chroma.indices.maxByOrNull { chroma[it] })
        assertTrue(chroma[9] > 0.8)
    }

    @Test
    fun aKickShowsInTheBassFluxMoreThanAHat() {
        val beats = Signals.beats(100.0, 6.0)
        val h = hops(Signals.band(100.0, 6.0))
        val hop = 441
        val onBeats = beats.drop(1).map { b -> h.filter { Math.abs((it.index + 1) * hop - b - Onsets.ONSET_LAG_FRAMES) <= hop }.maxOf { it.bassFlux } }
        val between = h.filter { hh -> beats.all { Math.abs((hh.index + 1) * hop - it - Onsets.ONSET_LAG_FRAMES) > 6 * hop } }.map { it.bassFlux }
        assertTrue(onBeats.average() > 5 * between.average())
    }
}
