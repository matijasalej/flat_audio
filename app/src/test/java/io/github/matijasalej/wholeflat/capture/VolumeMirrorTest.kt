package io.github.matijasalej.wholeflat.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeMirrorTest {
    @Test
    fun theIndexIsMappedToTheConnectScale() {
        assertEquals(0, VolumeMirror.raw(0, 15))
        assertEquals(65_535, VolumeMirror.raw(15, 15))
        assertEquals(32_767, VolumeMirror.raw(7, 14)) // half of 14 steps
        assertEquals(0, VolumeMirror.raw(5, 0))
        assertEquals(65_535, VolumeMirror.raw(99, 15)) // clamped
    }

    @Test
    fun theVolumeAtTheStartIsNotAChange() {
        val m = VolumeMirror()
        m.seed(10, 15)
        m.onIndex(10, 15, 0)
        assertNull(m.poll(1_000, false))
    }

    @Test
    fun aChangeIsActedOnAfterTheDebounceOnly() {
        val m = VolumeMirror(debounceMs = 250)
        m.seed(15, 15)
        m.onIndex(12, 15, 1_000)
        assertNull("too soon", m.poll(1_100, false))
        val a = m.poll(1_300, false)
        assertNotNull(a)
        assertEquals(VolumeMirror.raw(12, 15), a!!.sonosRaw)
        assertFalse(a.restoreMax)
        assertNull("only once", m.poll(2_000, false))
    }

    @Test
    fun aFastSeriesOfStepsGivesTheLastValueOnce() {
        val m = VolumeMirror(debounceMs = 250)
        m.seed(10, 15)
        m.onIndex(9, 15, 1_000)
        m.onIndex(8, 15, 1_100)
        m.onIndex(7, 15, 1_200)
        assertNull(m.poll(1_300, false))
        val a = m.poll(1_460, false)
        assertEquals(VolumeMirror.raw(7, 15), a!!.sonosRaw)
        assertNull(m.poll(2_000, false))
    }

    @Test
    fun withFixedVolumeTheBoxIsPutBackToMaxAndThatIsNotMirroredAgain() {
        val m = VolumeMirror(debounceMs = 250, ignoreRestoreMs = 2_000)
        m.seed(15, 15)
        m.onIndex(9, 15, 1_000)
        val a = m.poll(1_300, true)!!
        assertEquals(VolumeMirror.raw(9, 15), a.sonosRaw)
        assertTrue(a.restoreMax)
        // the box now reports the restored maximum: ours, ignored
        m.onIndex(15, 15, 1_400)
        assertNull(m.poll(2_000, true))
        // and reading the same value again later is no change either
        m.onIndex(15, 15, 5_000)
        assertNull(m.poll(6_000, true))
    }

    @Test
    fun aRealChangeAfterTheRestoreIsMirroredAgain() {
        val m = VolumeMirror(debounceMs = 250, ignoreRestoreMs = 2_000)
        m.seed(15, 15)
        m.onIndex(9, 15, 1_000)
        m.poll(1_300, true)
        m.onIndex(15, 15, 1_350) // the restore
        m.onIndex(6, 15, 10_000) // the user turns it down again
        val a = m.poll(10_300, true)
        assertNotNull(a)
        assertEquals(VolumeMirror.raw(6, 15), a!!.sonosRaw)
        assertTrue(a.restoreMax)
    }

    @Test
    fun thePhoneGoingToMaxWithFixedVolumeOnlySetsTheSonos() {
        val m = VolumeMirror(debounceMs = 250)
        m.seed(10, 15)
        m.onIndex(15, 15, 1_000)
        val a = m.poll(1_300, true)!!
        assertEquals(65_535, a.sonosRaw)
        assertFalse("already at the maximum, nothing to restore", a.restoreMax)
    }

    @Test
    fun theSonosKeepsItsVolumeAtTheStartWhenTheBoxIsParkedAtMaximumOnPurpose() {
        // fixed living room volume: the box's index says nothing about the kitchen
        assertEquals(null, VolumeMirror.initialRaw(15, 15, fixedVolume = true))
        assertEquals(null, VolumeMirror.initialRaw(6, 15, fixedVolume = true))
        // otherwise the Sonos follows from the first note
        assertEquals(VolumeMirror.raw(6, 15), VolumeMirror.initialRaw(6, 15, fixedVolume = false))
        assertEquals(65_535, VolumeMirror.initialRaw(15, 15, fixedVolume = false))
        assertEquals(null, VolumeMirror.initialRaw(3, 0, fixedVolume = false))
    }

    @Test
    fun clearForgetsAChangeThatWasNotActedOn() {
        val m = VolumeMirror(debounceMs = 250)
        m.seed(10, 15)
        m.onIndex(5, 15, 1_000)
        m.clear()
        assertNull(m.poll(2_000, false))
    }

    @Test
    fun aBoxWithoutAScaleIsIgnored() {
        val m = VolumeMirror()
        m.onIndex(3, 0, 0)
        assertNull(m.poll(1_000, false))
    }
}
