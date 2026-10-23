package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LevelsTest {
    private fun sine(frames: Int, amplitude: Double, rate: Int = 48_000, hz: Double = 1_000.0): ShortArray {
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / rate) * amplitude * 32767.0).toInt().toShort()
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    @Test
    fun silenceIsAtTheFloor() {
        val pcm = ShortArray(960 * 2)
        assertEquals(Levels.FLOOR_DB, Levels.peakDb(pcm, 960), 0.0)
        assertEquals(Levels.FLOOR_DB, Levels.rmsDb(Levels.sumSquares(pcm, 960), 960), 0.0)
    }

    @Test
    fun fullScaleIsZeroDb() {
        val pcm = ShortArray(10 * 2) { Short.MIN_VALUE }
        assertEquals(0.0, Levels.peakDb(pcm, 10), 0.01)
    }

    @Test
    fun aHalfScaleSineHasAPeakOfMinusSixAndAnRmsOfMinusNine() {
        val pcm = sine(4_800, 0.5)
        assertEquals(-6.02, Levels.peakDb(pcm, 4_800), 0.05)
        assertEquals(-9.03, Levels.rmsDb(Levels.sumSquares(pcm, 4_800), 4_800), 0.1)
    }

    @Test
    fun oneLeastSignificantBitIsNinetyDbDown() {
        val pcm = ShortArray(20) { 1 }
        assertEquals(-90.3, Levels.peakDb(pcm, 10), 0.1)
        assertTrue("below the silence threshold", Levels.peakDb(pcm, 10) < ActivityDetector.DEFAULT_SILENCE_DB)
    }

    @Test
    fun theRollingLevelForgetsOldChunks() {
        val r = RollingLevel(windowChunks = 3)
        val loud = sine(960, 0.5)
        val quiet = ShortArray(960 * 2)
        for (i in 1..3) r.add(Levels.sumSquares(loud, 960), 960)
        assertEquals(-9.03, r.rmsDb(), 0.1)
        for (i in 1..3) r.add(Levels.sumSquares(quiet, 960), 960)
        assertEquals(Levels.FLOOR_DB, r.rmsDb(), 0.0)
    }
}
