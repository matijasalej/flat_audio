package io.github.matijasalej.wholeflat.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongFeaturesTest {
    private fun features(pcm: ShortArray): SongFeatures {
        val o = Onsets(Signals.RATE)
        val f = SongFeatures()
        Signals.feed(pcm) { buf, n -> o.push(buf, n) { f.add(it) } }
        return f
    }

    @Test
    fun aLoudBandHasMoreEnergyThanAQuietOne() {
        val loud = features(Signals.band(120.0, 10.0, level = 1.0)).energySong()
        val quiet = features(Signals.band(120.0, 10.0, level = 0.12)).energySong()
        assertTrue("loud $loud, quiet $quiet", loud > quiet + 0.2)
    }

    @Test
    fun aBusyBeatHasMoreOnsetsThanAHeldChord() {
        val busy = features(Signals.band(128.0, 8.0)).onsetRate()
        val held = features(Signals.chord(8.0, 261.63, major = true)).onsetRate()
        assertTrue("busy $busy, held $held", busy > 3.0 && held < 1.0)
    }

    @Test
    fun aMajorChordIsMajorAndAMinorOneMinor() {
        val major = features(Signals.chord(20.0, 261.63, major = true))
        val minor = features(Signals.chord(20.0, 261.63, major = false))
        assertTrue("major ${major.mode()}", major.mode().first > 0.2)
        assertTrue("minor ${minor.mode()}", minor.mode().first < -0.2)
        assertTrue(major.mood(Double.NaN) > minor.mood(Double.NaN))
    }

    @Test
    fun aJumpOfEightDecibelsIsASectionUpAtItsStart() {
        val f = features(Signals.concat(Signals.band(120.0, 12.0, level = 0.4), Signals.band(120.0, 8.0, level = 1.0, seed = 3)))
        val up = f.sections().filter { it.up }
        assertEquals(1, up.size)
        assertEquals(12.0, up[0].hop / 100.0, 1.0)
    }

    @Test
    fun aBreakdownIsASectionDown() {
        val f = features(Signals.concat(Signals.band(120.0, 12.0, level = 1.0), Signals.band(120.0, 8.0, level = 0.25, seed = 3)))
        val down = f.sections().filter { !it.up }
        assertEquals(1, down.size)
        assertEquals(12.0, down[0].hop / 100.0, 1.0)
    }

    @Test
    fun anEvenSongHasNoSections() {
        assertTrue(features(Signals.band(120.0, 20.0)).sections().isEmpty())
    }

    @Test
    fun silenceIsSilentAndMusicIsNot() {
        assertTrue(features(Signals.silence(2.0)).silent())
        assertFalse(features(Signals.band(120.0, 2.0)).silent())
    }
}
