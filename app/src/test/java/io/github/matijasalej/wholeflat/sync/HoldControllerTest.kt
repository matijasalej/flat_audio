package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldControllerTest {
    private val rate = 44_100
    private val c = HoldController(rate)

    @Test
    fun anErrorInsideTheDeadbandIsLeftAlone() {
        assertEquals(0, c.trim(0.0, 0))
        assertEquals(0, c.trim(3.9, 0))
        assertEquals(0, c.trim(-3.9, 0))
    }

    @Test
    fun aLateStreamHasFramesDroppedAndAnEarlyOneHasFramesRepeated() {
        // half of the error per look: 40 ms late = 20 ms = 882 frames
        assertEquals(882, c.trim(40.0, 0))
        assertEquals(-882, c.trim(-40.0, 0))
        assertTrue(c.trim(5.0, 0) > 0)
    }

    @Test
    fun trimsThatAreOnTheirWayAreCountedAsDone() {
        // 40 ms late with 40 ms of trims on their way: nothing is left to ask for
        val onTheirWay = 40 * rate / 1000
        assertEquals(0, c.trim(40.0, onTheirWay))
        // 40 ms late with 20 ms on their way: half of the 20 ms that are left
        assertEquals(441, c.trim(40.0, 20 * rate / 1000))
    }

    @Test
    fun neverMoreThanTheMostThatMayBeOnTheirWay() {
        assertEquals(HoldController.MAX_PENDING_FRAMES, c.trim(5_000.0, 0))
        assertEquals(-HoldController.MAX_PENDING_FRAMES, c.trim(-5_000.0, 0))
        // already at the most: no more is added
        assertEquals(0, c.trim(5_000.0, HoldController.MAX_PENDING_FRAMES))
    }

    @Test
    fun repeatedLooksConvergeOnTheTarget() {
        // each look removes half of what is left (the trims are applied between the looks)
        var error = 60.0
        var looks = 0
        while (Math.abs(error) > HoldController.DEADBAND_MS && looks < 20) {
            val frames = c.trim(error, 0)
            error -= frames * 1000.0 / rate
            looks++
        }
        assertTrue("$looks looks, error $error", looks <= 5 && Math.abs(error) <= HoldController.DEADBAND_MS)
    }
}
