package io.github.matijasalej.wholeflat.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LooksTest {
    /** A cover of [parts] (hue, saturation, value, share of the pixels). */
    private fun cover(vararg parts: DoubleArray, n: Int = 1024): IntArray {
        val px = IntArray(n)
        var i = 0
        for (p in parts) {
            val count = (p[3] * n).toInt()
            repeat(count) { if (i < n) px[i++] = Colors.hsv(p[0], p[1], p[2]) }
        }
        while (i < n) px[i++] = Colors.rgb(128, 128, 128)
        return px
    }

    @Test
    fun aCoverOfOneHueHasOneClusterAndIsNotColourful() {
        val h = CoverHues.of(cover(doubleArrayOf(210.0, 0.8, 0.7, 0.7)))
        assertEquals(1, h.clusters.size)
        assertEquals(210.0, h.dominant!!.hue, 6.0)
        assertFalse(h.colourful)
        assertNull(h.second())
    }

    @Test
    fun aCoverOfThreeStrongHuesIsColourful() {
        val h = CoverHues.of(cover(doubleArrayOf(0.0, 0.9, 0.9, 0.3), doubleArrayOf(120.0, 0.9, 0.8, 0.3), doubleArrayOf(240.0, 0.9, 0.8, 0.3)))
        assertTrue(h.colourful)
        assertNotNull(h.second())
    }

    @Test
    fun aGreyCoverHasNoHueAndTakesTheColourOfItsMood() {
        val h = CoverHues.of(cover())
        assertTrue(h.grey)
        val calmSad = Looks.choose("k", energy = 0.2, clarity = 0.6, mood = 0.2, hues = h, override = null)
        assertEquals(235.0, Colors.toHsv(calmSad.home)[0], 3.0)
        val wildHappy = Looks.choose("k", energy = 0.9, clarity = 0.8, mood = 0.9, hues = h, override = null)
        assertEquals(40.0, Colors.toHsv(wildHappy.home)[0], 3.0)
    }

    @Test
    fun theClassFollowsEnergyAndClarity() {
        assertEquals(LightClass.MONO, Looks.classify(0.2, 0.9, colourful = false, override = null))
        assertEquals(LightClass.MONO, Looks.classify(0.8, 0.2, colourful = false, override = null))
        assertEquals(LightClass.DUO, Looks.classify(0.5, 0.6, colourful = false, override = null))
        assertEquals(LightClass.MULTI, Looks.classify(0.75, 0.6, colourful = false, override = null))
        assertEquals(LightClass.DUO, Looks.classify(0.75, 0.4, colourful = false, override = null))
        assertEquals(LightClass.MULTI, Looks.classify(0.6, 0.4, colourful = true, override = null))
        assertEquals(LightClass.MONO, Looks.classify(0.9, 0.9, colourful = true, override = LightClass.MONO))
    }

    @Test
    fun aHappySongTurnsTowardsYellowAndASadOneTowardsBlue() {
        val h = CoverHues.of(cover(doubleArrayOf(150.0, 0.8, 0.8, 0.8)))
        val happy = Colors.toHsv(Looks.choose("k", 0.5, 0.6, 1.0, h, null).home)[0]
        val sad = Colors.toHsv(Looks.choose("k", 0.5, 0.6, 0.0, h, null).home)[0]
        assertEquals(125.0, happy, 3.0) // 25° from 150 towards 40
        assertEquals(175.0, sad, 3.0) // 25° from 150 towards 225
    }

    @Test
    fun moreEnergyIsMoreSaturatedAndBrighter() {
        val h = CoverHues.of(cover(doubleArrayOf(200.0, 0.8, 0.8, 0.8)))
        val calm = Colors.toHsv(Looks.choose("k", 0.1, 0.6, 0.5, h, null).home)
        val wild = Colors.toHsv(Looks.choose("k", 0.9, 0.6, 0.5, h, null).home)
        assertTrue(wild[1] > calm[1])
        assertTrue(wild[2] > calm[2])
    }

    @Test
    fun theAccentIsTheCoversSecondHueWhenItHasOne() {
        val h = CoverHues.of(cover(doubleArrayOf(20.0, 0.9, 0.9, 0.5), doubleArrayOf(200.0, 0.9, 0.8, 0.3)))
        val l = Looks.choose("k", 0.5, 0.6, 0.5, h, null)
        assertEquals(200.0, Colors.toHsv(l.accent)[0], 8.0)
    }

    @Test
    fun anEnergeticSongWithoutASecondHueGetsASplitComplement() {
        val h = CoverHues.of(cover(doubleArrayOf(100.0, 0.9, 0.9, 0.8)))
        val l = Looks.choose("k", 0.8, 0.6, 0.5, h, null)
        assertEquals(250.0, Colors.toHsv(l.accent)[0], 4.0)
        assertEquals(4, l.multi.size)
    }

    @Test
    fun colorsGoRoundTripsThroughHsvAndMeasureNoDistanceToThemselves() {
        val c = Colors.hsv(33.0, 0.7, 0.8)
        val hsv = Colors.toHsv(c)
        assertEquals(33.0, hsv[0], 1.0)
        assertEquals(0.7, hsv[1], 0.01)
        assertEquals(0.8, hsv[2], 0.01)
        assertEquals(0.0, Colors.deltaE(c, c), 1e-9)
        assertTrue(Colors.deltaE(Colors.rgb(255, 0, 0), Colors.rgb(0, 0, 255)) > 100)
        assertEquals(10.0, Colors.turnToward(350.0, 40.0, 20.0), 1e-9)
    }
}
