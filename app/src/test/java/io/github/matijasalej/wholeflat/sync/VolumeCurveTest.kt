package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeCurveTest {
    @Test
    fun theLogCurveIsMuteAtZeroAndUnityAtFull() {
        assertEquals(0.0, VolumeCurve.logGain(0.0), 0.0)
        assertEquals(1.0, VolumeCurve.logGain(1.0), 0.0)
        assertEquals(0.0, VolumeCurve.logGain(-0.5), 0.0)
        assertEquals(1.0, VolumeCurve.logGain(1.5), 0.0)
    }

    @Test
    fun theLogCurveIs60DbOverTheRange() {
        // 1000^(x - 1): half way is -30 dB, three quarters -15 dB
        assertEquals(Math.pow(10.0, -30.0 / 20.0), VolumeCurve.logGain(0.5), 1e-12)
        assertEquals(Math.pow(10.0, -15.0 / 20.0), VolumeCurve.logGain(0.75), 1e-12)
        assertEquals(0.001, VolumeCurve.logGain(0.0 + 1e-12), 1e-6)
    }

    @Test
    fun theLogCurveRisesWithTheVolume() {
        var last = 0.0
        for (i in 1..100) {
            val g = VolumeCurve.logGain(i / 100.0)
            assertTrue(g > last)
            last = g
        }
    }

    @Test
    fun theSonosVolumeFollowsTheConnectVolumeAndTheBalance() {
        assertEquals(0, VolumeCurve.sonosVolume(0, 0))
        assertEquals(100, VolumeCurve.sonosVolume(65_535, 0))
        assertEquals(50, VolumeCurve.sonosVolume(32_768, 0))
        assertEquals(60, VolumeCurve.sonosVolume(32_768, 10))
        assertEquals(30, VolumeCurve.sonosVolume(32_768, -20))
        assertEquals("clamped", 100, VolumeCurve.sonosVolume(65_535, 20))
        assertEquals("clamped", 0, VolumeCurve.sonosVolume(3_000, -20))
    }
}
