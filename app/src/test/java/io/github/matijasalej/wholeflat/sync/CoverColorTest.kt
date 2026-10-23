package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverColorTest {
    private fun solid(r: Int, g: Int, b: Int, n: Int = 400) = IntArray(n) { CoverColor.rgb(r, g, b) }

    private fun lum(c: Int) = CoverColor.luminance(CoverColor.red(c).toDouble(), CoverColor.green(c).toDouble(), CoverColor.blue(c).toDouble())

    @Test
    fun noCoverGivesTheColourOfTheMock() {
        assertEquals(CoverColor.rgb(64, 92, 140), CoverColor.DEFAULT)
        assertEquals(CoverColor.DEFAULT, CoverColor.of(IntArray(0)))
        // transparent pixels do not count
        assertEquals(CoverColor.DEFAULT, CoverColor.of(IntArray(50) { 0x00FF0000 }))
    }

    @Test
    fun aColourThatReadsWellStaysAsItIs() {
        val c = CoverColor.of(solid(64, 92, 140))
        assertEquals(64, CoverColor.red(c))
        assertEquals(92, CoverColor.green(c))
        assertEquals(140, CoverColor.blue(c))
    }

    @Test
    fun aBrightColourIsDarkenedUntilWhiteTextReadsOnIt() {
        val c = CoverColor.of(solid(255, 220, 40))
        assertTrue("luminance ${lum(c)}", lum(c) <= 0.17)
        // it is still the same colour family: yellow stays red-ish and green-ish, hardly blue
        assertTrue(CoverColor.red(c) > CoverColor.blue(c) * 2)
        assertTrue(CoverColor.green(c) > CoverColor.blue(c) * 2)
    }

    @Test
    fun aNearlyBlackCoverIsLiftedALittle() {
        val c = CoverColor.of(solid(1, 1, 1))
        assertTrue("luminance ${lum(c)}", lum(c) >= 0.02)
    }

    @Test
    fun theColourThatDominatesWinsOverTheOneThatIsSmaller() {
        val pixels = IntArray(400) { if (it < 300) CoverColor.rgb(200, 30, 30) else CoverColor.rgb(30, 30, 200) }
        val c = CoverColor.of(pixels)
        assertTrue(CoverColor.red(c) > CoverColor.blue(c))
    }

    @Test
    fun aGreyCoverGivesItsAverageDarkened() {
        val c = CoverColor.of(solid(200, 200, 200))
        assertEquals(CoverColor.red(c), CoverColor.green(c))
        assertEquals(CoverColor.green(c), CoverColor.blue(c))
        assertTrue(lum(c) <= 0.17)
    }

    @Test
    fun vividPixelsOutweighPaleOnesOfAnotherHue() {
        // lots of nearly white pixels with a hint of blue and some vivid red: the red is what the cover is about
        val pixels = IntArray(400) { if (it < 100) CoverColor.rgb(220, 20, 20) else CoverColor.rgb(235, 238, 255) }
        val c = CoverColor.of(pixels)
        assertTrue("r ${CoverColor.red(c)} b ${CoverColor.blue(c)}", CoverColor.red(c) > CoverColor.blue(c))
    }

    @Test
    fun mixingBlendsTowardsTheOtherColour() {
        val bg = CoverColor.rgb(100, 100, 100)
        assertEquals(CoverColor.rgb(100, 100, 100), CoverColor.mix(bg, CoverColor.rgb(255, 255, 255), 0.0))
        assertEquals(CoverColor.rgb(255, 255, 255), CoverColor.mix(bg, CoverColor.rgb(255, 255, 255), 1.0))
        assertEquals(CoverColor.rgb(45, 45, 45), CoverColor.mix(bg, CoverColor.rgb(0, 0, 0), 0.55))
    }
}
